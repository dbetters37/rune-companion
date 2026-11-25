package com.aicontrolcenter.core.tasks

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.SpaceId
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

    /**
     * A shared knowledge space, as the recipient of a memory shared into it (Phase 6, K2). Stored
     * with the kind "space" and the space's id; shares stored before schema 6 stay [System].
     */
    @Serializable
    @SerialName("space")
    data class Space(val spaceId: SpaceId) : Participant
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

    /**
     * A note the owner wrote to the team while an objective runs (Phase 6, C2, Tier B). Only
     * `OwnerNotes` creates one, from [Participant.Owner]; briefs read it only in its own
     * `owner_note` frame (rule 24). Appended last: stored by name.
     */
    GUIDANCE,
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

/**
 * One message as a list shows it (Phase 6, K2, rule 16): everything but the body, and the first
 * [PREVIEW_MAX_CHARS] code points of the body as [preview] (cut like SQLite's `substr`,
 * [SummaryText.cut]). Room builds it with a projection; [of] builds it from a whole message, and
 * both must agree (`MessageHeaderContract`).
 */
data class MessageHeader(
    val id: MessageId,
    val rootId: TaskId,
    val taskId: TaskId?,
    val from: Participant,
    val to: Participant,
    val type: MessageType,
    val at: Long,
    val hop: Int,
    val replyTo: MessageId?,
    val preview: String,
) {
    /** Building headers from messages. */
    companion object {
        /** The longest [preview], in code points. */
        const val PREVIEW_MAX_CHARS: Int = 120

        fun of(message: AgentMessage): MessageHeader = MessageHeader(
            id = message.id, rootId = message.rootId, taskId = message.taskId, from = message.from, to = message.to,
            type = message.type, at = message.at, hop = message.hop, replyTo = message.replyTo,
            preview = SummaryText.cut(message.body, PREVIEW_MAX_CHARS)!!,
        )
    }
}
