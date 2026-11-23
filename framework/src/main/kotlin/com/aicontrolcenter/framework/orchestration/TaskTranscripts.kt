package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.conversationId
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.framework.agents.AgentRunListener
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Stores an agent's work on a task as an ordinary conversation, so the app shows it with the
 * same renderer as a chat: the brief as the user message, then every assistant message and
 * tool result as the runner hands them over. Memory search results arrive as references and
 * are shown from current memory, exactly as in a chat.
 *
 * Notes from the framework (a new attempt starting, a run that stopped) are stored as error
 * entries: they are shown to the owner and never sent to a model. The orchestrator never sends
 * a stored transcript back to a model; every run starts from a brief written afresh from the
 * stored task, which is what makes a task restartable after a pause or a crash.
 */
internal class TaskTranscripts(
    private val conversations: ConversationRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun open(agentId: AgentId, title: String): ConversationId = withContext(NonCancellable) {
        val now = clock.now()
        val conversation = Conversation(ids.conversationId(), agentId, title.take(MAX_TITLE), createdAt = now, updatedAt = now)
        conversations.upsertConversation(conversation)
        conversation.id
    }

    suspend fun user(id: ConversationId, text: String) = append(id, ChatMessage.user(text))

    suspend fun note(id: ConversationId, text: String) = withContext(NonCancellable) {
        conversations.appendEntry(ConversationEntry(id, seq = -1, message = ChatMessage.system(text), at = clock.now(), error = text))
        touch(id)
    }

    /** Stores what a run produces, as it is produced. */
    fun recorder(id: ConversationId): AgentRunListener = object : AgentRunListener {
        override suspend fun onMessage(message: ChatMessage, modelName: String?, usage: TokenUsage?) =
            append(id, message, modelName.takeIf { message.role == ChatRole.ASSISTANT }, usage)
    }

    suspend fun delete(ids: Collection<ConversationId>) = withContext(NonCancellable) {
        ids.forEach { conversations.deleteConversation(it) }
    }

    private suspend fun append(id: ConversationId, message: ChatMessage, modelName: String? = null, usage: TokenUsage? = null) =
        withContext(NonCancellable) {
            conversations.appendEntry(ConversationEntry(id, seq = -1, message = message, at = clock.now(), modelName = modelName, usage = usage))
            touch(id)
        }

    private suspend fun touch(id: ConversationId) {
        val current = conversations.getConversation(id) ?: return
        conversations.upsertConversation(current.copy(updatedAt = clock.now()))
    }

    private companion object {
        const val MAX_TITLE = 80
    }
}
