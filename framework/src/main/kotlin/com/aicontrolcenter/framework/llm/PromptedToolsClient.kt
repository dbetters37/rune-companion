package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.ToolCallRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * Gives tools to a model that has no function calling. The tools and one call format are
 * described in the system prompt ([PromptedToolsFormat]), and the model's text reply is read
 * back into [ToolCallRequest]s ([PromptedToolCallParser]). To the rest of the framework the
 * wrapped model looks like any tool-calling model.
 *
 * A reply the parser does not understand is passed on unchanged as plain text: a model that
 * ignores the format still answers, it just cannot use tools.
 */
class PromptedToolsClient(
    private val delegate: LlmClient,
    private val newCallId: () -> String = { "call_" + UUID.randomUUID().toString().replace("-", "").take(16) },
) : LlmClient {

    override suspend fun chat(request: ChatRequest): ChatResponse =
        interpret(request, delegate.chat(PromptedToolsFormat.rewrite(request)))

    /**
     * Streams prose as it arrives and withholds everything from the first sign of a tool call,
     * so call markup never flickers through the conversation. Whatever was withheld and turned
     * out to be prose after all is delivered just before the reply completes.
     */
    override fun chatStream(request: ChatRequest): Flow<ChatChunk> = flow {
        val filter = if (PromptedToolsFormat.offersTools(request)) ToolCallStreamFilter() else null
        val released = StringBuilder()
        delegate.chatStream(PromptedToolsFormat.rewrite(request)).collect { chunk ->
            when (chunk) {
                is ChatChunk.Text -> {
                    val safe = filter?.accept(chunk.delta) ?: chunk.delta
                    if (safe.isNotEmpty()) {
                        released.append(safe)
                        emit(ChatChunk.Text(safe))
                    }
                }
                is ChatChunk.Reasoning -> emit(chunk)
                // Calls are announced from the interpreted reply, where native and parsed ones are together.
                is ChatChunk.ToolCall -> Unit
                is ChatChunk.Completed -> {
                    val response = interpret(request, chunk.response)
                    val text = response.message.text
                    if (text.length > released.length && text.startsWith(released)) {
                        emit(ChatChunk.Text(text.substring(released.length)))
                    }
                    response.message.toolCalls.forEach { emit(ChatChunk.ToolCall(it)) }
                    emit(ChatChunk.Completed(response))
                }
            }
        }
    }

    private fun interpret(request: ChatRequest, response: ChatResponse): ChatResponse {
        if (!PromptedToolsFormat.offersTools(request)) return response
        // A model that turned out to call tools natively after all needs no interpretation.
        if (response.message.toolCalls.isNotEmpty() || response.finishReason == FinishReason.REFUSED) return response
        val parsed = PromptedToolCallParser.parse(
            response.message.text,
            knownTools = request.tools.mapTo(HashSet()) { it.name },
            truncated = response.finishReason == FinishReason.LENGTH,
        )
        if (parsed.calls.isEmpty()) return response
        val calls = parsed.calls.map { ToolCallRequest(newCallId(), it.name, it.arguments) }
        // The provider's own form of the message no longer matches what the framework keeps.
        val message = response.message.copy(text = parsed.text, toolCalls = calls, providerData = null)
        return response.copy(message = message, finishReason = FinishReason.TOOL_CALLS)
    }
}

/**
 * Decides, delta by delta, how much streamed text is certainly not part of a tool call.
 * Once something that may open a call has been seen, nothing more is released: the complete
 * reply is interpreted at the end, and only then is it known what was prose.
 */
internal class ToolCallStreamFilter {
    private val pending = StringBuilder()
    private var withholding = false
    private var sawContent = false

    /** Returns the part of the text received so far that can be shown now. */
    fun accept(delta: String): String {
        if (withholding) return ""
        pending.append(delta)
        if (!sawContent) {
            val first = pending.indexOfFirst { !it.isWhitespace() }
            if (first < 0) return ""
            sawContent = true
            // A reply that opens with JSON is far more likely a bare call than prose.
            if (pending[first] == '{' || pending[first] == '[') {
                withholding = true
                return ""
            }
        }
        val opener = OPENERS.map { pending.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull()
        val releasable = if (opener != null) {
            withholding = true
            opener
        } else {
            pending.length - possibleOpenerTail()
        }
        val out = pending.substring(0, releasable)
        pending.delete(0, releasable)
        return out
    }

    /** Length of the longest tail of the pending text that could still grow into an opener. */
    private fun possibleOpenerTail(): Int {
        for (length in minOf(MAX_OPENER_LENGTH - 1, pending.length) downTo 1) {
            val tail = pending.substring(pending.length - length)
            if (OPENERS.any { it.startsWith(tail, ignoreCase = true) }) return length
        }
        return 0
    }

    private companion object {
        val OPENERS = listOf("<tool_call", "<toolcall", "<function", "<tool_use", "[tool_calls]", "```json", "```tool")
        val MAX_OPENER_LENGTH = OPENERS.maxOf { it.length }
    }
}
