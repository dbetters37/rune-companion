package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageHeader
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.data.db.entity.AgentMessageEntity
import com.aicontrolcenter.data.db.entity.MessageHeaderRow

/**
 * The stored names of the kinds of [Participant], in `from_kind` and `to_kind`. Appended only:
 * [SPACE] (schema 6) is a memory space as the recipient of a memory shared into it.
 */
internal enum class ParticipantKind { OWNER, AGENT, SYSTEM, SPACE }

/**
 * A participant spread over its columns; [agentId] is null unless [kind] is
 * [ParticipantKind.AGENT], [spaceId] null unless it is [ParticipantKind.SPACE].
 */
internal data class ParticipantColumns(val kind: ParticipantKind, val agentId: String?, val spaceId: String? = null)

internal fun Participant.toColumns(): ParticipantColumns = when (this) {
    Participant.Owner -> ParticipantColumns(ParticipantKind.OWNER, null)
    Participant.System -> ParticipantColumns(ParticipantKind.SYSTEM, null)
    is Participant.Agent -> ParticipantColumns(ParticipantKind.AGENT, agentId.value)
    is Participant.Space -> ParticipantColumns(ParticipantKind.SPACE, null, spaceId.value)
}

/**
 * @throws IllegalArgumentException, storing nothing, if the sender is a memory space: a space
 *   only receives (there is no column for a sending space).
 */
internal fun AgentMessage.toEntity(): AgentMessageEntity {
    val sender = from.toColumns()
    val recipient = to.toColumns()
    require(sender.kind != ParticipantKind.SPACE) { "Message $id: a memory space cannot send a message." }
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
        toSpaceId = recipient.spaceId,
    )
}

/** @throws com.aicontrolcenter.data.StoredDataException naming the column, if the row cannot be read. */
internal fun AgentMessageEntity.toDomain(): AgentMessage {
    val row = RowRef(AgentMessageEntity.TABLE, id)
    return AgentMessage(
        id = MessageId(id),
        rootId = TaskId(rootId),
        taskId = taskId?.let(::TaskId),
        from = row.participant("from", fromKind, fromAgentId, spaceId = null),
        to = row.participant("to", toKind, toAgentId, toSpaceId),
        type = row.enum<MessageType>("type", type),
        body = body,
        replyTo = replyTo?.let(::MessageId),
        hop = hop,
        at = at,
    )
}

/**
 * An agent without its id, or a space without its id, is damage, not the owner or the system:
 * such a row is unreadable. So is a sending space ([spaceId] is null for the sender: there is no
 * column for it).
 */
private fun RowRef.participant(prefix: String, kind: String, agentId: String?, spaceId: String?): Participant =
    when (enum<ParticipantKind>("${prefix}_kind", kind)) {
        ParticipantKind.OWNER -> Participant.Owner
        ParticipantKind.SYSTEM -> Participant.System
        ParticipantKind.AGENT -> Participant.Agent(AgentId(present("${prefix}_agent_id", agentId, "the participant is an agent")))
        ParticipantKind.SPACE -> Participant.Space(SpaceId(present("${prefix}_space_id", spaceId, "the participant is a memory space")))
    }

/** UTF-8 bytes of the text a message row holds; see `RoomMessageRepository.MAX_TEXT_BYTES`. */
internal fun AgentMessageEntity.storedTextBytes(): Long =
    listOf(id, rootId, taskId, fromAgentId, toAgentId, toSpaceId, body, replyTo).sumOf { it?.utf8Length() ?: 0L }

/** @throws com.aicontrolcenter.data.StoredDataException naming the column, if the row cannot be read. */
internal fun MessageHeaderRow.toDomain(): MessageHeader {
    val row = RowRef(AgentMessageEntity.TABLE, id)
    return MessageHeader(
        id = MessageId(id),
        rootId = TaskId(rootId),
        taskId = taskId?.let(::TaskId),
        from = row.participant("from", fromKind, fromAgentId, spaceId = null),
        to = row.participant("to", toKind, toAgentId, toSpaceId),
        type = row.enum<MessageType>("type", type),
        at = at,
        hop = hop,
        replyTo = replyTo?.let(::MessageId),
        preview = preview,
    )
}
