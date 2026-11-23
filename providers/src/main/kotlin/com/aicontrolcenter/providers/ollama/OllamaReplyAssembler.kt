package com.aicontrolcenter.providers.ollama

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
import com.aicontrolcenter.providers.http.boolean
import com.aicontrolcenter.providers.http.long
import com.aicontrolcenter.providers.http.obj
import com.aicontrolcenter.providers.http.objects
import com.aicontrolcenter.providers.http.string
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Builds one reply from `/api/chat` output: a single object, or the NDJSON objects of a stream,
 * the last of which has `done: true` and the token counts.
 *
 * Ollama delivers each tool call whole. Recent versions give calls an `id`; older ones do not,
 * and the framework needs one to pair a result with its call, so a missing id is synthesised.
 *
 * `prompt_eval_cached_count` is read for the cached share of the prompt. Ollama's API document
 * lists that field for `/api/generate` only, so for chat it is an unconfirmed extra that simply
 * stays zero when absent.
 *
 * Not safe for concurrent use; one instance per reply.
 *
 * @param newCallId supplies ids for calls that arrive without one
 */
internal class OllamaReplyAssembler(
    private val http: ProviderHttp,
    private val requestedModel: String,
    private val newCallId: () -> String = { "call_" + UUID.randomUUID().toString().replace("-", "").take(24) },
) {
    private val text = StringBuilder()
    private val thinking = StringBuilder()
    private val calls = ArrayList<ToolCallRequest>()
    private var doneReason: String? = null
    private var usage: TokenUsage? = null
    private var model: String? = null

    /** True once the object marked `done` has been seen. */
    var done = false
        private set

    /**
     * Takes in one object and returns the increments it contained, tool calls included.
     *
     * @throws com.aicontrolcenter.core.llm.LlmException when the object reports an error
     */
    fun accept(chunk: JsonObject): List<ChatChunk> {
        if (chunk.string("error") != null) throw http.embeddedError(chunk)
        chunk.string("model")?.takeIf { it.isNotBlank() }?.let { model = it }
        val increments = ArrayList<ChatChunk>(2)
        chunk.obj("message")?.let { message ->
            message.string("thinking")?.takeIf { it.isNotEmpty() }?.let {
                thinking.append(it)
                increments += ChatChunk.Reasoning(it)
            }
            message.string("content")?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                increments += ChatChunk.Text(it)
            }
            for (raw in message.array("tool_calls")?.objects().orEmpty()) {
                val call = toolCall(raw)
                calls += call
                increments += ChatChunk.ToolCall(call)
            }
        }
        if (chunk.boolean("done") == true) {
            done = true
            doneReason = chunk.string("done_reason")
            val input = chunk.long("prompt_eval_count")
            val output = chunk.long("eval_count")
            if (input != null || output != null) {
                usage = TokenUsage(input ?: 0, output ?: 0, chunk.long("prompt_eval_cached_count") ?: 0)
            }
        }
        return increments
    }

    fun complete(): ChatResponse {
        val finishReason = when {
            doneReason == "length" -> FinishReason.LENGTH
            calls.isNotEmpty() -> FinishReason.TOOL_CALLS
            // Versions that predate done_reason end a normal reply without one.
            doneReason == "stop" || doneReason == null -> FinishReason.STOP
            else -> FinishReason.UNKNOWN
        }
        val message = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = text.toString(),
            toolCalls = calls.toList(),
            reasoning = thinking.toString().ifEmpty { null },
        )
        return ChatResponse(message, finishReason, usage, model ?: requestedModel)
    }

    /**
     * Arguments that are not an object are reported on the call ([ToolCallRequest.argumentsError])
     * rather than failing the turn, so the model hears what was wrong. Only a call without a
     * name cannot be answered and fails the reply.
     */
    private fun toolCall(raw: JsonObject): ToolCallRequest {
        val function = raw.obj("function") ?: throw http.errors.malformed("a tool call had no function")
        val name = function.string("name")?.takeIf { it.isNotBlank() } ?: throw http.errors.malformed("a tool call had no name")
        // The native API sends an object; a string is tolerated for servers that imitate it loosely.
        val arguments = ToolArguments.fromElement(function["arguments"])
        return ToolCallRequest(raw.string("id")?.takeIf { it.isNotBlank() } ?: newCallId(), name, arguments.arguments, arguments.error)
    }
}
