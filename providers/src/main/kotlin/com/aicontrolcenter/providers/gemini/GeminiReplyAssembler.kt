package com.aicontrolcenter.providers.gemini

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.ProviderData
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.http.ProviderHttp
import com.aicontrolcenter.providers.http.array
import com.aicontrolcenter.providers.http.boolean
import com.aicontrolcenter.providers.http.long
import com.aicontrolcenter.providers.http.obj
import com.aicontrolcenter.providers.http.objects
import com.aicontrolcenter.providers.http.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Builds one reply from `GenerateContentResponse` objects: the single one of a plain call, or
 * the sequence of a stream, where text arrives in pieces and each function call arrives whole.
 *
 * Besides the neutral message, the reply keeps the model's parts as Gemini sent them, in
 * [ChatMessage.providerData]. They carry the thought signatures (`thoughtSignature`, on a
 * function call or on a text part) that must be returned unchanged on the next request.
 * Streamed text pieces are joined to keep that record small, but never across a signature: a
 * part with a signature stays exactly as it arrived.
 *
 * Not safe for concurrent use; one instance per reply.
 */
internal class GeminiReplyAssembler(private val http: ProviderHttp, private val requestedModel: String) {
    private val text = StringBuilder()
    private val thoughts = StringBuilder()
    private val calls = ArrayList<ToolCallRequest>()
    private val parts = ArrayList<JsonObject>()
    private var finish: String? = null
    private var blockReason: String? = null
    private var usage: TokenUsage? = null
    private var model: String? = null

    /** True once the reply is known to be over: a finish reason arrived, or the prompt was blocked. */
    val finished: Boolean get() = finish != null || blockReason != null

    /**
     * Takes in one response object and returns the increments it contained, tool calls included.
     *
     * @throws com.aicontrolcenter.core.llm.LlmException when the object reports an error
     */
    fun accept(chunk: JsonObject): List<ChatChunk> {
        if (chunk["error"] is JsonObject) throw http.embeddedError(chunk)
        chunk.string("modelVersion")?.takeIf { it.isNotBlank() }?.let { model = it }
        chunk.obj("usageMetadata")?.let { usage = usageOf(it) }
        chunk.obj("promptFeedback")?.string("blockReason")?.takeIf { it != "BLOCK_REASON_UNSPECIFIED" }?.let { blockReason = it }
        val candidate = chunk.array("candidates")?.objects()?.firstOrNull() ?: return emptyList()
        candidate.string("finishReason")?.takeIf { it != "FINISH_REASON_UNSPECIFIED" }?.let { finish = it }

        val increments = ArrayList<ChatChunk>(2)
        for (part in candidate.obj("content")?.array("parts")?.objects().orEmpty()) {
            remember(part)
            val call = part.obj("functionCall")
            val piece = part.string("text")
            when {
                call != null -> {
                    val request = toolCall(call)
                    calls += request
                    increments += ChatChunk.ToolCall(request)
                }
                piece.isNullOrEmpty() -> Unit
                part.boolean("thought") == true -> {
                    thoughts.append(piece)
                    increments += ChatChunk.Reasoning(piece)
                }
                else -> {
                    text.append(piece)
                    increments += ChatChunk.Text(piece)
                }
            }
        }
        return increments
    }

    /** @throws com.aicontrolcenter.core.llm.LlmException.MalformedResponse when nothing usable was received */
    fun complete(): ChatResponse {
        if (finish == null && blockReason == null && parts.isEmpty()) {
            throw http.errors.malformed("the reply had neither a candidate nor a block reason")
        }
        val reported = finishReasonOf(finish)
        val finishReason = when {
            // A blocked prompt produces no candidate at all.
            finish == null && blockReason != null -> FinishReason.REFUSED
            // Gemini reports STOP for a turn that calls functions.
            calls.isNotEmpty() && (reported == FinishReason.STOP || reported == FinishReason.UNKNOWN) -> FinishReason.TOOL_CALLS
            else -> reported
        }
        val message = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = text.toString(),
            toolCalls = calls.toList(),
            reasoning = thoughts.toString().ifEmpty { null },
            providerData = parts.takeIf { it.isNotEmpty() }?.let {
                ProviderData(ProviderKind.GEMINI, buildJsonObject { put(PARTS, JsonArray(it.toList())) })
            },
        )
        return ChatResponse(message, finishReason, usage, model ?: requestedModel)
    }

    private fun remember(part: JsonObject) {
        val previous = parts.lastOrNull()
        if (previous != null && isPlainText(previous) && isPlainText(part) && previous["thought"] == part["thought"]) {
            parts[parts.lastIndex] = JsonObject(previous + ("text" to JsonPrimitive(previous.string("text") + part.string("text"))))
        } else {
            parts += part
        }
    }

    /** A text part with nothing attached that joining could damage. */
    private fun isPlainText(part: JsonObject): Boolean = part.string("text") != null && part.keys.all { it == "text" || it == "thought" }

    private fun toolCall(call: JsonObject): ToolCallRequest {
        val name = call.string("name")?.takeIf { it.isNotBlank() } ?: throw http.errors.malformed("a function call had no name")
        val arguments = when (val args = call["args"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> args
            else -> throw http.errors.malformed("the arguments of a call to ${name.take(64)} were not a JSON object")
        }
        // Gemini does not always number its calls; the framework needs an id to pair results with calls.
        val id = call.string("id")?.takeIf { it.isNotBlank() } ?: ("call_" + UUID.randomUUID().toString().replace("-", "").take(24))
        return ToolCallRequest(id, name, arguments)
    }

    companion object {
        /** Key of the raw parts inside the provider data payload. */
        const val PARTS = "parts"

        private val REFUSALS = setOf(
            "SAFETY", "RECITATION", "LANGUAGE", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
            "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION", "PUP_LIMITED_DISABLED",
        )

        /**
         * STOP and MAX_TOKENS map directly; CONTINUATION (the per-request token limit was hit
         * with more to say) is also a length cut. Every reason that means the content was
         * filtered is a refusal. The rest (a malformed function call, OTHER, values added
         * later) are reported as unknown rather than guessed at. CONTINUATION and
         * PUP_LIMITED_DISABLED could not be re-confirmed against Google's reference; they are
         * kept because mapping a value the API never sends costs nothing.
         */
        fun finishReasonOf(raw: String?): FinishReason = when (raw) {
            "STOP" -> FinishReason.STOP
            "MAX_TOKENS", "CONTINUATION" -> FinishReason.LENGTH
            in REFUSALS -> FinishReason.REFUSED
            else -> FinishReason.UNKNOWN
        }

        /**
         * Thinking tokens are billed as output but counted apart from `candidatesTokenCount`,
         * so they are added to it. Cached tokens are part of `promptTokenCount`.
         */
        fun usageOf(metadata: JsonObject): TokenUsage = TokenUsage(
            inputTokens = metadata.long("promptTokenCount") ?: 0,
            outputTokens = (metadata.long("candidatesTokenCount") ?: 0) + (metadata.long("thoughtsTokenCount") ?: 0),
            cachedInputTokens = metadata.long("cachedContentTokenCount") ?: 0,
        )
    }
}
