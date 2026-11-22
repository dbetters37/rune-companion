package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryConversationRepository : ConversationRepository {
    private data class State(
        val conversations: Map<ConversationId, Conversation> = emptyMap(),
        val entries: Map<ConversationId, List<ConversationEntry>> = emptyMap(),
    )

    private val state = MutableStateFlow(State())

    private fun State.ordered(agentId: AgentId?) = conversations.values
        .filter { agentId == null || it.agentId == agentId }
        .sortedWith(compareByDescending<Conversation> { it.updatedAt }.thenBy { it.id.value })

    override fun observeConversations(agentId: AgentId?): Flow<List<Conversation>> =
        state.map { it.ordered(agentId) }.distinctUntilChanged()

    override suspend fun getConversation(id: ConversationId): Conversation? = state.value.conversations[id]

    override suspend fun upsertConversation(conversation: Conversation) =
        state.update { it.copy(conversations = it.conversations + (conversation.id to conversation)) }

    override suspend fun deleteConversation(id: ConversationId) =
        state.update { State(it.conversations - id, it.entries - id) }

    override suspend fun deleteByAgent(agentId: AgentId) = state.update { current ->
        val gone = current.conversations.values.filter { it.agentId == agentId }.map { it.id }.toSet()
        State(current.conversations - gone, current.entries - gone)
    }

    override fun observeEntries(id: ConversationId): Flow<List<ConversationEntry>> =
        state.map { it.entries[id].orEmpty() }.distinctUntilChanged()

    override suspend fun getEntries(id: ConversationId): List<ConversationEntry> = state.value.entries[id].orEmpty()

    override suspend fun appendEntry(entry: ConversationEntry): ConversationEntry {
        lateinit var stored: ConversationEntry
        state.update { current ->
            require(entry.conversationId in current.conversations) { "No such conversation: ${entry.conversationId}" }
            val existing = current.entries[entry.conversationId].orEmpty()
            stored = entry.copy(seq = existing.size)
            current.copy(entries = current.entries + (entry.conversationId to existing + stored))
        }
        return stored
    }
}
