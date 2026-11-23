package com.aicontrolcenter.framework.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecution
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The stored form of a memory_search result: a small, versioned marker with what was searched
 * for and the ids of the memories the result showed, best first, and no memory text:
 *
 *     [[memory-ref v1]] {"tool":"memory_search","query":"colour","ids":["mem_1","mem_2"]}
 *
 * The query is the model's own argument, which the stored call carries anyway.
 */
internal object MemoryReference {
    const val VERSION = 1
    private const val TAG = "[[memory-ref v"
    private val HEADER = Regex("^\\[\\[memory-ref v(\\d{1,4})]] ")
    private const val TOOL = "memory_search"

    /** A search result: the query and the ids of the memories it showed. */
    data class Search(val query: String, val ids: List<MemoryId>)

    fun encode(search: Search): String = "${TAG}$VERSION]] " + buildJsonObject {
        put("tool", TOOL)
        put("query", search.query)
        putJsonArray("ids") { search.ids.forEach { add(it.value) } }
    }

    /** Whether [content] is a reference of any version, readable by this one or not. */
    fun isReference(content: String): Boolean = content.startsWith(TAG)

    /** The search [content] refers to, or null when it is not a reference this version can read. */
    fun decode(content: String): Search? {
        val match = HEADER.find(content) ?: return null
        if (match.groupValues[1].toIntOrNull() != VERSION) return null
        val json = runCatching { Json.parseToJsonElement(content.substring(match.range.last + 1)).jsonObject }.getOrNull() ?: return null
        if ((json["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content != TOOL) return null
        val query = (json["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val ids = (json["ids"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null } ?: return null
        return Search(query, ids.map(::MemoryId))
    }
}

/**
 * Keeps memory text out of stored conversations, so that forgetting a memory or taking away an
 * agent's access to it reaches conversations too.
 *
 * A memory_search result is stored as a [MemoryReference] and written out again from current
 * memory whenever it is read: as the agent, with its current profile, when history is rebuilt for
 * a model call ([forAgent]); as the owner when the chat shows it ([forOwner]). A memory that has
 * been forgotten, has expired or may no longer be read by the reader is shown as
 * [MemoryText.UNAVAILABLE], and so is a whole result when it cannot be written out at all (an
 * unknown marker version, memory tools that are no longer registered). Within the turn that made
 * the search the model reads the result as the tool returned it.
 *
 * Only results of the registered memory_search tool are stored this way and only results named
 * after it are rendered, so no other tool's output can make memory text appear.
 */
internal class MemoryReferences(private val tools: ToolRegistry) {
    private fun searchTool(): MemorySearchTool? = tools.byId(MemoryTools.SEARCH_ID) as? MemorySearchTool

    /** What to store for the result of [execution] in place of its text, or null to store the text as it is. */
    fun storedForm(execution: ToolExecution): String? {
        if (execution.end != ToolCallEnd.OK || execution.spec?.id != MemoryTools.SEARCH_ID) return null
        val tool = searchTool() ?: return null
        if (tool.spec != execution.spec) return null
        return tool.reference(execution.call.arguments, execution.outcome)?.let(MemoryReference::encode)
    }

    fun isReference(message: ChatMessage): Boolean {
        val result = message.toolResult ?: return false
        return message.role == ChatRole.TOOL && result.toolName == MemorySearchTool.FUNCTION && MemoryReference.isReference(result.content)
    }

    /** [messages] with every reference written out as [agent] (its current profile) may read it now. */
    suspend fun forAgent(messages: List<ChatMessage>, agent: AgentProfile): List<ChatMessage> {
        if (messages.none(::isReference)) return messages
        val reader = searchTool()?.Reader(MemoryActor.Agent(agent), agent.id)
        return messages.map { if (isReference(it)) render(it, reader) else it }
    }

    /** [entries] as the owner sees them, with scopes described as [viewer], the conversation's agent, saw them. */
    suspend fun forOwner(entries: List<ConversationEntry>, viewer: AgentId?): List<ConversationEntry> {
        if (entries.none { isReference(it.message) }) return entries
        val reader = searchTool()?.Reader(MemoryActor.Owner, viewer ?: AgentId(""))
        return entries.map { if (isReference(it.message)) it.copy(message = render(it.message, reader)) else it }
    }

    /** How many characters the reference in [message] can take once written out, at most; 0 for any other message. */
    fun renderedCharsAtMost(message: ChatMessage): Int {
        if (!isReference(message)) return 0
        val search = MemoryReference.decode(message.toolResult!!.content) ?: return MemoryText.UNAVAILABLE.length
        return MemorySearchTool.charsAtMost(search.ids.size)
    }

    private suspend fun render(message: ChatMessage, reader: MemorySearchTool.Reader?): ChatMessage {
        val result = message.toolResult ?: return message
        val search = MemoryReference.decode(result.content)
        val text = if (search == null || reader == null) MemoryText.UNAVAILABLE else reader.render(search)
        return message.copy(toolResult = result.copy(content = text))
    }
}
