package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.TokenUsage
import com.anthropic.models.beta.messages.BetaMessageDeltaUsage
import com.anthropic.models.beta.messages.BetaRawContentBlockDelta
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaUsage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.jvm.optionals.getOrNull

/**
 * Folds the events of one streamed reply into incremental [ChatChunk]s and, at the end, the
 * same [RawTurn] a non-streaming call would have returned.
 *
 * The content blocks are rebuilt as JSON from each block's start event plus its deltas, so a
 * streamed turn is stored and replayed exactly like any other, signatures included. The SDK's
 * own accumulator is not used because it fails on a tool call whose JSON was cut off by the
 * token limit, which here is an ordinary reply that ends with the LENGTH finish reason.
 *
 * Not thread-safe; one instance per stream.
 */
internal class StreamAssembler {
    private var model: String? = null
    private var stopReason: BetaStopReason? = null
    private var refusalExplanation: String? = null
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var cacheRead: Long? = null
    private var cacheCreation: Long? = null
    private var usageSeen = false
    private var stopped = false
    private val blocks = sortedMapOf<Long, BlockBuilder>()

    /**
     * Returns the chunk this event produces, if any: a text or reasoning delta, or a tool call
     * once its block has closed with complete arguments. A tool call announced here can still
     * be void if the reply then ends in a refusal or a fallback takes over; the completed
     * response is the authority on which calls to run.
     */
    fun accept(event: BetaRawMessageStreamEvent): ChatChunk? {
        when {
            event.isMessageStart() -> event.asMessageStart().message().let { message ->
                model = message.model().asString()
                start(message.usage())
            }
            event.isContentBlockStart() -> event.asContentBlockStart().let { start ->
                (sdkToJson(start.contentBlock()) as? JsonObject)?.let { blocks[start.index()] = BlockBuilder(it) }
            }
            event.isContentBlockDelta() -> event.asContentBlockDelta().let { delta ->
                return blocks[delta.index()]?.add(delta.delta())
            }
            event.isContentBlockStop() -> return blocks[event.asContentBlockStop().index()]?.close()
            event.isMessageDelta() -> event.asMessageDelta().let { delta ->
                delta.delta().stopReason().getOrNull()?.let { stopReason = it }
                delta.delta().stopDetails().getOrNull()?.explanation()?.getOrNull()?.let { refusalExplanation = it }
                update(delta.usage())
            }
            event.isMessageStop() -> stopped = true
        }
        return null
    }

    /** @throws LlmException.Network when the stream ended before the provider finished the message. */
    fun finish(): RawTurn {
        val model = model
        if (model == null || !stopped) {
            throw LlmException.Network("The connection closed before the reply was complete.")
        }
        val usage: TokenUsage? = if (usageSeen) ResponseMapper.usage(inputTokens, outputTokens, cacheRead, cacheCreation) else null
        val argumentErrors = blocks.values.mapNotNull { block -> block.argumentsError?.let { error -> block.id?.let { it to error } } }.toMap()
        return RawTurn(model, stopReason, refusalExplanation, blocks.values.map { it.build() }, usage, argumentErrors)
    }

    private fun start(usage: BetaUsage) {
        usageSeen = true
        inputTokens = usage.inputTokens()
        outputTokens = usage.outputTokens()
        cacheRead = usage.cacheReadInputTokens().getOrNull()
        cacheCreation = usage.cacheCreationInputTokens().getOrNull()
    }

    /** The final counts are cumulative; input-side counts are repeated only when they changed. */
    private fun update(usage: BetaMessageDeltaUsage) {
        usageSeen = true
        outputTokens = usage.outputTokens()
        usage.inputTokens().getOrNull()?.let { inputTokens = it }
        usage.cacheReadInputTokens().getOrNull()?.let { cacheRead = it }
        usage.cacheCreationInputTokens().getOrNull()?.let { cacheCreation = it }
    }

    /** One content block under construction: the JSON of its start event plus what the deltas add. */
    private class BlockBuilder(private val start: JsonObject) {
        private val text = StringBuilder(start.stringOrNull("text").orEmpty())
        private val thinking = StringBuilder(start.stringOrNull("thinking").orEmpty())
        private val signature = StringBuilder(start.stringOrNull("signature").orEmpty())
        private val inputJson = StringBuilder()
        private val citations = ArrayList<JsonElement>()
        private var input: JsonObject? = null

        val id: String? get() = start.stringOrNull("id")

        /** Why a tool call's streamed arguments cannot be used: not JSON, not an object, or holding an unquoted value. */
        var argumentsError: String? = null
            private set

        fun add(delta: BetaRawContentBlockDelta): ChatChunk? = when {
            delta.isText() -> delta.asText().text().let { text.append(it); ChatChunk.Text(it) }
            delta.isThinking() -> delta.asThinking().thinking().let { thinking.append(it); ChatChunk.Reasoning(it) }
            delta.isSignature() -> null.also { signature.append(delta.asSignature().signature()) }
            delta.isInputJson() -> null.also { inputJson.append(delta.asInputJson().partialJson()) }
            delta.isCitations() -> null.also { citations += sdkToJson(delta.asCitations().citation()) }
            else -> null
        }

        /**
         * Parses the streamed arguments of a tool call. Arguments that do not parse to an
         * object produce no chunk: they may have been cut off by the token limit, which only
         * the stop reason that follows will tell. They are kept as [argumentsError], which the
         * completed response reports on the call when the model did stop to have it run.
         */
        fun close(): ChatChunk? {
            if (start.blockType != "tool_use") return null
            if (inputJson.isBlank()) {
                input = start["input"] as? JsonObject ?: JsonObject(emptyMap())
            } else {
                val raw = inputJson.toString()
                val parsed = try {
                    Json.parseToJsonElement(raw)
                } catch (e: SerializationException) {
                    argumentsError = ToolArgumentErrors.describe(raw, null, e)
                    return null
                }
                argumentsError = ToolArgumentErrors.problemWith(raw, parsed)
                if (argumentsError == null) input = parsed as JsonObject
            }
            return input?.let { ResponseMapper.toolCall(build()) }?.let(ChatChunk::ToolCall)
        }

        fun build(): JsonObject {
            val fields = LinkedHashMap<String, JsonElement>(start)
            when (start.blockType) {
                "text" -> {
                    fields["text"] = JsonPrimitive(text.toString())
                    if (citations.isNotEmpty()) fields["citations"] = JsonArray((start["citations"] as? JsonArray).orEmpty() + citations)
                }
                "thinking" -> {
                    fields["thinking"] = JsonPrimitive(thinking.toString())
                    fields["signature"] = JsonPrimitive(signature.toString())
                }
                "tool_use" -> fields["input"] = input ?: start["input"] as? JsonObject ?: JsonObject(emptyMap())
            }
            return JsonObject(fields)
        }
    }
}
