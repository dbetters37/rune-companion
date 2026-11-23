package com.aicontrolcenter.providers.openai

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.providers.http.ProviderHttp
import com.aicontrolcenter.providers.http.ToolArguments
import com.aicontrolcenter.providers.http.array
import com.aicontrolcenter.providers.http.int
import com.aicontrolcenter.providers.http.long
import com.aicontrolcenter.providers.http.obj
import com.aicontrolcenter.providers.http.objects
import com.aicontrolcenter.providers.http.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Builds one reply from Chat Completions output. A streamed reply is fed chunk by chunk; a
 * plain reply is fed once, since a whole `message` has the same fields as a `delta`.
 *
 * Tolerances, each for behaviour seen in servers that implement this protocol:
 *  - Reasoning text arrives as `reasoning_content` (DeepSeek, llama.cpp, vLLM) or `reasoning`
 *    (OpenRouter, Groq, Ollama's /v1); content may be a string or a list of typed parts
 *    (Mistral).
 *  - Tool-call fragments are joined by their `index`. A fragment without one is placed by its
 *    position, and a new `id` at an index already in use starts a new call, because some
 *    servers number every parallel call 0.
 *  - Arguments may be a JSON string, a string holding a JSON string, or already an object.
 *    Any other JSON value (a number, a boolean, an array, or null and nothing else) is not
 *    arguments, and the call is reported with [ToolCallRequest.argumentsError].
 *  - Usage arrives in a final chunk without choices, on the last content chunk, or under
 *    `x_groq`.
 *
 * Not safe for concurrent use; one instance per reply.
 */
internal class OpenAiReplyAssembler(
    private val http: ProviderHttp,
    private val origin: String,
    private val requestedModel: String,
) {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val refusal = StringBuilder()
    private var reasoningAsContent = false
    private val calls = ArrayList<PendingCall>()
    private val callAtIndex = HashMap<Int, PendingCall>()
    private val reasoningDetails = ArrayList<MutableMap<String, JsonElement>>()
    private var finish: String? = null
    private var usage: TokenUsage? = null
    private var model: String? = null

    /** True once a choice has been seen at all. */
    var sawChoice = false
        private set

    /** True once the provider has given a finish reason. */
    val finished: Boolean get() = finish != null

    private class PendingCall {
        var id = ""
        var name = ""
        val arguments = StringBuilder()
        var sawText = false
        var argumentsObject: JsonObject? = null

        /** The first value given as arguments that is neither a string nor an object nor null. */
        var argumentsInvalid: JsonElement? = null
        var sawNull = false
        val extras = LinkedHashMap<String, JsonElement>()

        /**
         * A wrong kind of value spoils the call whatever else arrives. A null fragment adds
         * nothing, so a stream that sends one beside the name and the arguments in later
         * fragments still works; a call whose arguments were null and nothing else is reported
         * like any other value that is not an object.
         */
        fun parsedArguments(): ToolArguments {
            argumentsInvalid?.let { return ToolArguments.fromStrictElement(it) }
            argumentsObject?.let { return ToolArguments.fromElement(it) }
            return if (sawNull && !sawText) ToolArguments.fromStrictElement(JsonNull) else ToolArguments.fromText(arguments.toString())
        }
    }

    /**
     * Takes in one chunk and returns the text and reasoning increments it contained.
     *
     * @throws com.aicontrolcenter.core.llm.LlmException when the chunk is an error report
     */
    fun accept(chunk: JsonObject): List<ChatChunk> {
        val error = chunk["error"]
        if (error is JsonObject || (error is JsonPrimitive && error.isString && error.content.isNotBlank())) {
            throw http.embeddedError(chunk)
        }
        chunk.string("model")?.takeIf { it.isNotBlank() }?.let { model = it }
        (chunk.obj("usage") ?: chunk.obj("x_groq")?.obj("usage"))?.let { reported -> usageOf(reported)?.let { usage = it } }
        val choice = chunk.array("choices")?.objects()?.firstOrNull() ?: return emptyList()
        sawChoice = true
        choice.string("finish_reason")?.let { finish = it }
        val part = choice.obj("delta") ?: choice.obj("message") ?: return emptyList()

        val increments = ArrayList<ChatChunk>(2)
        fun addReasoning(piece: String) {
            if (piece.isEmpty()) return
            reasoning.append(piece)
            increments += ChatChunk.Reasoning(piece)
        }
        fun addText(piece: String) {
            if (piece.isEmpty()) return
            text.append(piece)
            increments += ChatChunk.Text(piece)
        }
        part.string("reasoning_content")?.let {
            reasoningAsContent = true
            addReasoning(it)
        } ?: part.string("reasoning")?.let(::addReasoning)
        when (val content = part["content"]) {
            is JsonPrimitive -> if (content.isString) addText(content.content)
            is JsonArray -> for (piece in content.objects()) {
                when (piece.string("type")) {
                    "text" -> addText(piece.string("text").orEmpty())
                    "thinking" -> piece.array("thinking")?.objects()?.forEach { addReasoning(it.string("text").orEmpty()) }
                }
            }
            else -> Unit
        }
        part.string("refusal")?.let(refusal::append)
        part.array("tool_calls")?.objects()?.forEachIndexed(::acceptToolCall)
        part.array("reasoning_details")?.objects()?.forEach(::acceptReasoningDetail)
        return increments
    }

    private fun acceptToolCall(position: Int, fragment: JsonObject) {
        val index = fragment.int("index") ?: position
        val id = fragment.string("id").orEmpty()
        var call = callAtIndex[index]
        if (call == null || (id.isNotEmpty() && call.id.isNotEmpty() && call.id != id)) {
            call = PendingCall()
            calls += call
            callAtIndex[index] = call
        }
        if (id.isNotEmpty()) call.id = id
        fragment.obj("function")?.let { function ->
            // The name comes whole in the first fragment; a server repeating it must not double it.
            function.string("name")?.let { if (it != call.name) call.name += it }
            when (val arguments = function["arguments"]) {
                null -> Unit
                is JsonObject -> call.argumentsObject = arguments
                is JsonNull -> call.sawNull = true
                is JsonPrimitive -> if (arguments.isString) {
                    call.sawText = true
                    call.arguments.append(arguments.content)
                } else if (call.argumentsInvalid == null) {
                    call.argumentsInvalid = arguments
                }
                is JsonArray -> if (call.argumentsInvalid == null) call.argumentsInvalid = arguments
            }
        }
        for ((key, value) in OpenAiEcho.extrasOf(fragment)) call.extras.putIfAbsent(key, value)
    }

    /**
     * OpenRouter streams its reasoning blocks in pieces that share an `index`: text-like fields
     * grow, the rest (type, format, signature) arrive once.
     */
    private fun acceptReasoningDetail(piece: JsonObject) {
        val index = piece["index"]
        val existing = reasoningDetails.firstOrNull { index != null && index !is JsonNull && it["index"] == index && it["type"] == piece["type"] }
        if (existing == null) {
            reasoningDetails += LinkedHashMap(piece)
            return
        }
        for ((key, value) in piece) {
            val known = existing[key]
            existing[key] = when {
                value is JsonNull -> known ?: value
                key in GROWING_FIELDS && known is JsonPrimitive && known.isString && value is JsonPrimitive && value.isString ->
                    JsonPrimitive(known.content + value.content)
                else -> value
            }
        }
    }

    /**
     * The finished reply. A call whose arguments cannot be read is still reported, with empty
     * arguments and [ToolCallRequest.argumentsError] set, so the model is told and can correct
     * itself instead of the whole turn failing.
     *
     * @throws com.aicontrolcenter.core.llm.LlmException.MalformedResponse when a tool call has no name
     */
    fun complete(): ChatResponse {
        val reported = finishReasonOf(finish)
        val parsed = parseCalls(reported)
        val toolCalls = parsed.map { it.first }
        val finishReason = when {
            refusal.isNotEmpty() && text.isEmpty() && toolCalls.isEmpty() -> FinishReason.REFUSED
            // Several servers say "stop" (or nothing) for a turn that calls tools.
            toolCalls.isNotEmpty() && (reported == FinishReason.STOP || reported == FinishReason.UNKNOWN) -> FinishReason.TOOL_CALLS
            toolCalls.isEmpty() && reported == FinishReason.TOOL_CALLS -> FinishReason.STOP
            else -> reported
        }
        val echo = OpenAiEcho.capture(
            calls = parsed,
            reasoningContent = reasoning.toString().takeIf { reasoningAsContent },
            reasoningDetails = JsonArray(reasoningDetails.map(::JsonObject)),
        )
        val message = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = text.toString().ifEmpty { refusal.toString() },
            toolCalls = toolCalls,
            reasoning = reasoning.toString().ifEmpty { null },
            providerData = echo.toProviderData(origin),
        )
        return ChatResponse(message, finishReason, usage, model ?: requestedModel)
    }

    private fun parseCalls(reported: FinishReason): List<Pair<ToolCallRequest, Map<String, JsonElement>>> {
        val result = ArrayList<Pair<ToolCallRequest, Map<String, JsonElement>>>(calls.size)
        for ((position, call) in calls.withIndex()) {
            val arguments = call.parsedArguments()
            if (call.name.isBlank() || arguments.error != null) {
                // Output that hit the token limit ends mid-call; the rest of the reply is still good.
                if (reported == FinishReason.LENGTH && position == calls.lastIndex) continue
                if (call.name.isBlank()) throw http.errors.malformed("a tool call had no name")
            }
            val id = call.id.ifEmpty { "call_" + UUID.randomUUID().toString().replace("-", "").take(24) }
            result += ToolCallRequest(id, call.name, arguments.arguments, arguments.error) to call.extras
        }
        return result
    }

    companion object {
        private val GROWING_FIELDS = setOf("text", "summary", "data")

        fun finishReasonOf(raw: String?): FinishReason = when (raw) {
            "stop", "eos", "end_turn", "stop_sequence" -> FinishReason.STOP
            "length", "max_tokens" -> FinishReason.LENGTH
            "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
            "content_filter" -> FinishReason.REFUSED
            else -> FinishReason.UNKNOWN
        }

        /**
         * Token counts. `completion_tokens` already includes reasoning tokens. Cache hits are
         * reported as `prompt_tokens_details.cached_tokens` (OpenAI, OpenRouter, xAI) or
         * `prompt_cache_hit_tokens` (DeepSeek), in both cases as part of the prompt count.
         */
        fun usageOf(usage: JsonObject): TokenUsage? {
            val input = usage.long("prompt_tokens") ?: usage.long("input_tokens")
            val output = usage.long("completion_tokens") ?: usage.long("output_tokens")
            if (input == null && output == null) return null
            val cached = usage.obj("prompt_tokens_details")?.long("cached_tokens") ?: usage.long("prompt_cache_hit_tokens") ?: 0
            return TokenUsage(input ?: 0, output ?: 0, cached)
        }
    }
}
