package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ProviderData
import com.aicontrolcenter.core.registry.ProviderKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * The provider's own form of an assistant turn, kept in `ChatMessage.providerData`.
 *
 * Claude validates that earlier assistant turns come back exactly as it produced them:
 * thinking blocks carry a signature bound to the conversation before them, and a turn rebuilt
 * from text and tool calls would drop or alter them and be rejected. So the complete content
 * array of every response is stored as `{"content":[...]}` and sent back as it is, including
 * thinking blocks whose text is empty and block types this adapter does not interpret.
 */
internal object StoredTurn {
    private const val CONTENT = "content"
    private const val FALLBACK = "fallback"

    private val thinkingTypes = setOf("thinking", "redacted_thinking")

    /** Block types that must not be echoed from before a refusal-fallback boundary. */
    private val droppedBeforeFallback = thinkingTypes + "tool_use"

    fun toProviderData(blocks: List<JsonObject>): ProviderData =
        ProviderData(ProviderKind.ANTHROPIC, buildJsonObject { put(CONTENT, JsonArray(blocks)) })

    /** The stored blocks, or null when the data belongs to another provider or is not in this shape. */
    fun read(data: ProviderData?): List<JsonObject>? {
        if (data == null || data.kind != ProviderKind.ANTHROPIC) return null
        val content = (data.payload as? JsonObject)?.get(CONTENT) as? JsonArray ?: return null
        return content.filterIsInstance<JsonObject>().takeIf { it.size == content.size }
    }

    /** Index of the block after which the answering model's own output starts, or -1. */
    fun lastFallbackIndex(blocks: List<JsonObject>): Int = blocks.indexOfLast { it.blockType == FALLBACK }

    /**
     * The blocks to send back for this turn. They are the stored blocks unchanged, with two
     * exceptions the API itself prescribes:
     *  - When a model declined part-way and a fallback model took over, the declined model's
     *    thinking and tool-use blocks before the final `fallback` block are left out, while the
     *    fallback block stays exactly where it was.
     *  - With [withThinking] off, every thinking block is left out. Removing all of them from
     *    all turns is the one removal that keeps a history valid.
     */
    fun replayable(blocks: List<JsonObject>, withThinking: Boolean = true): List<JsonObject> {
        val boundary = lastFallbackIndex(blocks)
        return blocks.filterIndexed { index, block ->
            val type = block.blockType
            (withThinking || type !in thinkingTypes) && (index >= boundary || type !in droppedBeforeFallback)
        }
    }

    /** Ids of the tool calls in these blocks, each of which needs a result in the next user turn. */
    fun toolUseIds(blocks: List<JsonObject>): List<String> =
        blocks.filter { it.blockType == "tool_use" }.mapNotNull { it.stringOrNull("id") }
}
