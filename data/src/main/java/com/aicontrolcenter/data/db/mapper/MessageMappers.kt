package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.data.db.entity.AgentMessageEntity

/** The stored names of the three kinds of [Participant], in `from_kind` and `to_kind`. */
internal enum class ParticipantKind { OWNER, AGENT, SYSTEM }

/** A participant spread over its two columns; [agentId] is null unless [kind] is [ParticipantKind.AGENT]. */
internal data class ParticipantColumns(val kind: ParticipantKind, val agentId: String?)

internal fun Participant.toColumns(): ParticipantColumns = when (this) {
    Participant.Owner -> ParticipantColumns(ParticipantKind.OWNER, null)
    Participant.System -> ParticipantColumns(ParticipantKind.SYSTEM, null)
    is Participant.Agent -> ParticipantColumns(ParticipantKind.AGENT, agentId.value)
}

internal fun AgentMessage.toEntity(): AgentMessageEntity {
    val sender = from.toColumns()
    val recipient = to.toColumns()
    return AgentMessageEntity(
        id = id.value,
        rootId = rootId.value,
        taskId = taskId?.value,
        fromKind = sender.kind.name,
        fromAgentId = sender.agentId,
        toKind = recipient.kind.name,
        toAgentId = recipient.agentId,
        type = type.name,
        body = body,
        replyTo = replyTo?.value,
        hop = hop,
        at = at,
    )
}

/** @throws com.aicontrolcenter.data.StoredDataException naming the column, if the row cannot be read. */
internal fun AgentMessageEntity.toDomain(): AgentMessage {
    val row = RowRef(AgentMessageEntity.TABLE, id)
    return AgentMessage(
        id = MessageId(id),
        rootId = TaskId(rootId),
        taskId = taskId?.let(::TaskId),
        from = row.participant("from", fromKind, fromAgentId),
        to = row.participant("to", toKind, toAgentId),
        type = row.enum<MessageType>("type", type),
        body = body,
        replyTo = replyTo?.let(::MessageId),
        hop = hop,
        at = at,
    )
}

/** An agent without its id is damage, not the owner or the system: such a row is unreadable. */
private fun RowRef.participant(prefix: String, kind: String, agentId: String?): Participant =
    when (enum<ParticipantKind>("${prefix}_kind", kind)) {
        ParticipantKind.OWNER -> Participant.Owner
        ParticipantKind.SYSTEM -> Participant.System
        ParticipantKind.AGENT -> Participant.Agent(AgentId(present("${prefix}_agent_id", agentId, "the participant is an agent")))
    }

/** UTF-8 bytes of the text a message row holds; see `RoomMessageRepository.MAX_TEXT_BYTES`. */
internal fun AgentMessageEntity.storedTextBytes(): Long =
    listOf(id, rootId, taskId, fromAgentId, toAgentId, body, replyTo).sumOf { it?.utf8Length() ?: 0L }
