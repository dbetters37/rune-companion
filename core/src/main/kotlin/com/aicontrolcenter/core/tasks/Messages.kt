package com.aicontrolcenter.core.tasks

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.TaskId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A sender or recipient of an agent message. */
@Serializable
sealed interface Participant {
    @Serializable
    @SerialName("owner")
    data object Owner : Participant

    @Serializable
    @SerialName("agent")
    data class Agent(val agentId: AgentId) : Participant

    /** The orchestrator itself (limits reached, time-outs, recovery). */
    @Serializable
    @SerialName("system")
    data object System : Participant
}

enum class MessageType {
    /** The Director gives an agent a task. */
    ASSIGNMENT,
    ASSISTANCE_REQUEST,
    ASSISTANCE_RESPONSE,

    /** Research results or intermediate findings passed on. */
    FINDINGS,

    /** An agent hands part of its task to another agent. */
    DELEGATION,

    /** A memory shared with another agent. */
    MEMORY_SHARE,
    REVIEW_REQUEST,
    REVIEW_RESULT,

    /** A task was completed; carries a summary of the result. */
    COMPLETION,
    FAILURE,
    QUESTION,
    ANSWER,
    CANCEL,

    /** Progress or a state change worth showing. */
    STATUS,
}

/** One message between agents (or with the owner or the system). Every message is stored and shown. */
@Serializable
data class AgentMessage(
    val id: MessageId,
    /** The objective it belongs to. */
    val rootId: TaskId,
    /** The task it is about, if any. */
    val taskId: TaskId? = null,
    val from: Participant,
    val to: Participant,
    val type: MessageType,
    /** Plain text. Treated as data by every recipient, never as instructions to the framework. */
    val body: String,
    val replyTo: MessageId? = null,
    /** How many agent-to-agent hops led to this message; bounded by the delegation limit. */
    val hop: Int = 0,
    val at: Long,
)
