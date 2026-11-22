package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaStopReason
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.optionals.getOrNull

/**
 * One finished assistant turn as the API delivered it, whether in a single response or
 * assembled from a stream. Content blocks stay raw JSON so they can be stored and replayed.
 */
internal data class RawTurn(
    val model: String,
    val stopReason: BetaStopReason?,
    /** `stop_details.explanation` of a refusal, when the API gave one. */
    val refusalExplanation: String?,
    val content: List<JsonObject>,
    val usage: TokenUsage?,
)

internal object ResponseMapper {

    fun rawTurn(message: BetaMessage): RawTurn = RawTurn(
        model = message.model().asString(),
        stopReason = message.stopReason().getOrNull(),
        refusalExplanation = message.stopDetails().getOrNull()?.explanation()?.getOrNull(),
        content = message.content().mapNotNull { sdkToJson(it) as? JsonObject },
        usage = message.usage().let {
            usage(it.inputTokens(), it.outputTokens(), it.cacheReadInputTokens().getOrNull(), it.cacheCreationInputTokens().getOrNull())
        },
    )

    /**
     * Claude reports uncached, cache-read and cache-written input separately; the neutral
     * [TokenUsage.inputTokens] is the whole prompt, with the cache-read part repeated in
     * [TokenUsage.cachedInputTokens].
     */
    fun usage(input: Long, output: Long, cacheRead: Long?, cacheCreation: Long?): TokenUsage =
        TokenUsage(inputTokens = input + (cacheRead ?: 0) + (cacheCreation ?: 0), outputTokens = output, cachedInputTokens = cacheRead ?: 0)

    fun finishReason(stopReason: BetaStopReason?): FinishReason = when (stopReason) {
        BetaStopReason.END_TURN, BetaStopReason.STOP_SEQUENCE -> FinishReason.STOP
        BetaStopReason.TOOL_USE -> FinishReason.TOOL_CALLS
        BetaStopReason.MAX_TOKENS, BetaStopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> FinishReason.LENGTH
        BetaStopReason.REFUSAL -> FinishReason.REFUSED
        // pause_turn and compaction belong to server features this adapter never requests.
        else -> FinishReason.UNKNOWN
    }

    /**
     * The stop reason is decided before any content is read, because it changes what the
     * content means:
     *  - A refusal carries no usable content (none, or a partial reply that must be discarded),
     *    so the message holds only the provider's explanation, if any, and nothing to replay.
     *  - Tool calls are reported only when the model stopped in order to have them run. After a
     *    cut-off the last call may be incomplete, so none is offered; the blocks are still
     *    stored, and the request builder answers them with a "not run" result on the next turn.
     *  - After a refusal fallback, only tool calls made by the model that took over count.
     */
    fun chatResponse(turn: RawTurn): ChatResponse {
        val finish = finishReason(turn.stopReason)
        val boundary = StoredTurn.lastFallbackIndex(turn.content)
        val model = turn.content.getOrNull(boundary)?.let(::fallbackTarget) ?: turn.model
        if (finish == FinishReason.REFUSED) {
            return ChatResponse(ChatMessage(ChatRole.ASSISTANT, text = turn.refusalExplanation.orEmpty()), finish, turn.usage, model)
        }
        val text = turn.content.filter { it.blockType == "text" }.joinToString("") { it.stringOrNull("text").orEmpty() }
        val reasoning = turn.content.filter { it.blockType == "thinking" }
            .mapNotNull { it.stringOrNull("thinking")?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
        val toolCalls = if (finish == FinishReason.TOOL_CALLS) turn.content.drop(boundary + 1).mapNotNull(::toolCall) else emptyList()
        val message = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = text,
            toolCalls = toolCalls,
            reasoning = reasoning.ifEmpty { null },
            providerData = StoredTurn.toProviderData(turn.content),
        )
        return ChatResponse(message, finish, turn.usage, model)
    }

    fun toolCall(block: JsonObject): ToolCallRequest? {
        if (block.blockType != "tool_use") return null
        val id = block.stringOrNull("id") ?: return null
        val name = block.stringOrNull("name") ?: return null
        return ToolCallRequest(id, name, block["input"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    /** `to.model` of a fallback block: the model that produced what follows it. */
    private fun fallbackTarget(fallbackBlock: JsonObject): String? = (fallbackBlock["to"] as? JsonObject)?.stringOrNull("model")
}
