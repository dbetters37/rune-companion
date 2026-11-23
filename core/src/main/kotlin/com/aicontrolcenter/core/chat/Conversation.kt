package com.aicontrolcenter.core.chat

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.tools.ToolOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** A direct conversation between the owner and one agent. */
@Serializable
data class Conversation(
    val id: ConversationId,
    val agentId: AgentId,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One stored message of a conversation, with what it cost and which model produced it. */
@Serializable
data class ConversationEntry(
    val conversationId: ConversationId,
    /** Position in the conversation, starting at 0. */
    val seq: Int,
    val message: ChatMessage,
    val at: Long,
    /** For assistant messages: the model that answered. */
    val modelName: String? = null,
    val usage: TokenUsage? = null,
    /** Set on a synthetic entry recording that the agent's turn failed, so the failure stays visible. */
    val error: String? = null,
)

interface ConversationRepository {
    /** Most recently updated first. [agentId] null means all agents. */
    fun observeConversations(agentId: AgentId? = null): Flow<List<Conversation>>
    suspend fun getConversation(id: ConversationId): Conversation?
    suspend fun upsertConversation(conversation: Conversation)

    /** Removes the conversation and all its entries. */
    suspend fun deleteConversation(id: ConversationId)

    /** Removes every conversation of an agent. */
    suspend fun deleteByAgent(agentId: AgentId)

    /** Entries in order. */
    fun observeEntries(id: ConversationId): Flow<List<ConversationEntry>>
    suspend fun getEntries(id: ConversationId): List<ConversationEntry>

    /** Appends with the next sequence number and returns the stored entry. [entry]'s own seq is ignored. */
    suspend fun appendEntry(entry: ConversationEntry): ConversationEntry
}

/** What the agent is doing during a turn that is in progress. */
data class LiveTurn(
    val conversationId: ConversationId,
    val phase: TurnPhase,
    /** Text of the reply received so far. */
    val partialText: String = "",
    val partialReasoning: String = "",
    /** The tool being run, when [phase] is RUNNING_TOOL. */
    val activeTool: ToolCallRequest? = null,
    val modelName: String? = null,
    /** Where this turn's prompt is being sent. */
    val locality: Locality? = null,
    val modelCalls: Int = 0,
    val toolCalls: Int = 0,
    /**
     * The permission or approval request [activeTool] is waiting for the owner to answer, shown
     * in the chat while the turn is paused on it; null when nothing waits. The owner answers it
     * in the app ([com.aicontrolcenter.core.operator.OwnerRequests]); stopping the turn cancels it.
     */
    val waitingForOwner: OwnerRequest? = null,
)

enum class TurnPhase { PREPARING, WAITING_FOR_MODEL, RECEIVING, RUNNING_TOOL }

/** How a turn ended. */
sealed interface TurnResult {
    data class Completed(val text: String, val usage: TokenUsage, val modelCalls: Int, val toolCalls: Int) : TurnResult

    /** [message] is suitable for showing to the owner. */
    data class Failed(val message: String, val retryable: Boolean) : TurnResult

    data object Cancelled : TurnResult
}

/**
 * Direct chat with a single agent: the simplest way to run one. A turn sends the owner's
 * message, lets the agent think and use its tools, and stores everything that happened. Turns
 * run in the framework's own scope, so leaving the screen does not stop them.
 */
interface AgentChat {
    fun observeConversations(agentId: AgentId? = null): Flow<List<Conversation>>

    fun observeEntries(id: ConversationId): Flow<List<ConversationEntry>>

    /** The turn in progress for each conversation that has one. */
    val liveTurns: StateFlow<Map<ConversationId, LiveTurn>>

    suspend fun startConversation(agentId: AgentId, title: String? = null): Conversation

    suspend fun rename(id: ConversationId, title: String)

    suspend fun delete(id: ConversationId)

    /**
     * Runs one turn and suspends until it ends. Cancelling the caller does not cancel the turn;
     * use [cancel].
     * @throws IllegalStateException if a turn is already running in this conversation.
     */
    suspend fun send(id: ConversationId, text: String): TurnResult

    /** Stops the turn in progress, if any. What was produced so far is kept. */
    suspend fun cancel(id: ConversationId)
}

/** A tool call and its result as shown in a transcript. */
data class ToolExchange(val call: ToolCallRequest, val outcome: ToolOutcome?)
