package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaStopReason
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    /**
     * Why the arguments of a `tool_use` block could not be read, by block id. Only a streamed
     * turn has these: there the arguments arrive as text, and a block whose text is not a JSON
     * object is stored with empty input.
     */
    val argumentErrors: Map<String, String> = emptyMap(),
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
        val toolCalls = if (finish == FinishReason.TOOL_CALLS) {
            turn.content.drop(boundary + 1).mapNotNull { toolCall(it, turn.argumentErrors) }
        } else {
            emptyList()
        }
        val message = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = text,
            toolCalls = toolCalls,
            reasoning = reasoning.ifEmpty { null },
            providerData = StoredTurn.toProviderData(turn.content),
        )
        return ChatResponse(message, finish, turn.usage, model)
    }

    /**
     * The call a `tool_use` block asks for. Arguments that could not be read (see
     * [RawTurn.argumentErrors]) or are not an object leave the call with empty arguments and
     * [ToolCallRequest.argumentsError] set, so the model is told instead of the call being run
     * with arguments it did not give.
     */
    fun toolCall(block: JsonObject, argumentErrors: Map<String, String> = emptyMap()): ToolCallRequest? {
        if (block.blockType != "tool_use") return null
        val id = block.stringOrNull("id") ?: return null
        val name = block.stringOrNull("name") ?: return null
        val error = argumentErrors[id]
        return when (val input = block["input"]) {
            is JsonObject -> ToolCallRequest(id, name, input, error)
            null, is JsonNull -> ToolCallRequest(id, name, NO_ARGUMENTS, error)
            else -> ToolCallRequest(id, name, NO_ARGUMENTS, error ?: ToolArgumentErrors.describe(input.toString(), input))
        }
    }

    private val NO_ARGUMENTS = JsonObject(emptyMap())

    /** `to.model` of a fallback block: the model that produced what follows it. */
    private fun fallbackTarget(fallbackBlock: JsonObject): String? = (fallbackBlock["to"] as? JsonObject)?.stringOrNull("model")
}

/**
 * Explanations, meant for the model, of tool arguments that could not be used. Worded as in
 * the providers module's ToolArguments, so every adapter tells a model the same thing.
 */
internal object ToolArgumentErrors {
    /** Arguments longer than this are described, not repeated. */
    const val MAX_QUOTED_CHARS = 200
    private const val MAX_DETAIL_CHARS = 120

    /**
     * Why [raw] cannot be used: it did not parse at all ([parsed] null, [failure] set), it is
     * not an object, or it holds an unquoted value; null when it is a usable object.
     */
    fun problemWith(raw: String, parsed: JsonElement?, failure: SerializationException? = null): String? {
        val unquoted = (parsed as? JsonObject)?.let(::unquotedValue)
        if (parsed is JsonObject && unquoted == null) return null
        return describe(raw, parsed, failure, unquoted)
    }

    /** [raw] parsed to [parsed], which is not an object; or did not parse at all ([parsed] null, [failure] set). */
    fun describe(raw: String, parsed: JsonElement?, failure: SerializationException? = null, unquoted: String? = null): String {
        val reason = when {
            parsed == null -> "The arguments were not valid JSON${failure?.let(::detailOf).orEmpty()}."
            unquoted != null -> "The arguments were not valid JSON: the value ${unquoted.take(MAX_LITERAL_CHARS)} is not in quotes."
            else -> {
                val kind = when (parsed) {
                    is JsonArray -> "a JSON array"
                    is JsonPrimitive -> if (parsed.isString) "a JSON string" else "a single JSON value"
                    else -> "something else"
                }
                "The arguments must be one JSON object of named values, but were $kind."
            }
        }
        val quoted = if (raw.length <= MAX_QUOTED_CHARS) " Received: $raw" else " Received ${raw.length} characters."
        return reason + quoted
    }

    private const val MAX_LITERAL_CHARS = 40
    private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    /**
     * The first value that is neither quoted nor a JSON literal: kotlinx's tree parser accepts
     * `{"city": Oslo}`, which is not JSON. Walked without recursion.
     */
    private fun unquotedValue(root: JsonElement): String? {
        val pending = ArrayDeque<JsonElement>().apply { add(root) }
        while (pending.isNotEmpty()) {
            when (val element = pending.removeLast()) {
                is JsonObject -> pending.addAll(element.values)
                is JsonArray -> pending.addAll(element)
                is JsonPrimitive -> if (!element.isString && element !is JsonNull) {
                    val text = element.content
                    if (text != "true" && text != "false" && !JSON_NUMBER.matches(text)) return text
                }
            }
        }
        return null
    }

    /** The parser's explanation without the input it quotes after "JSON input:". */
    private fun detailOf(e: SerializationException): String {
        val text = e.message?.substringBefore("JSON input:")?.lineSequence()?.firstOrNull()?.trim()?.trimEnd('.', ',')
        return if (text.isNullOrEmpty()) "" else ": " + text.take(MAX_DETAIL_CHARS)
    }
}
