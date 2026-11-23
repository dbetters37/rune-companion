package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskUsage
import com.aicontrolcenter.data.db.entity.TaskEntity

internal fun TaskRecord.toEntity(): TaskEntity = TaskEntity(
    id = id.value,
    rootId = rootId.value,
    parentId = parentId?.value,
    kind = kind.name,
    status = status.name,
    assigneeId = assignee?.value,
    sortOrder = order,
    attempt = attempt,
    revisionRound = revisionRound,
    needsReview = needsReview,
    conversationId = conversationId?.value,
    title = title,
    instructions = instructions,
    statusDetail = statusDetail,
    result = result,
    error = error,
    criteriaJson = encodeColumn(ColumnSerializers.stringList, completionCriteria),
    dependsOnJson = encodeColumn(ColumnSerializers.taskIdList, dependsOn),
    delegationChainJson = encodeColumn(ColumnSerializers.agentIdList, delegationChain),
    reviewsJson = encodeColumn(ColumnSerializers.reviewList, reviews),
    usageJson = encodeColumn(TaskUsage.serializer(), usage),
    optionsJson = options?.let { encodeColumn(ObjectiveOptions.serializer(), it) },
    requestedByJson = encodeColumn(ColumnSerializers.taskRequester, requestedBy),
    createdAt = createdAt,
    updatedAt = updatedAt,
    startedAt = startedAt,
    finishedAt = finishedAt,
    deadlineAt = deadlineAt,
)

/** @throws com.aicontrolcenter.data.StoredDataException naming the column, if the row cannot be read. */
internal fun TaskEntity.toDomain(): TaskRecord {
    val row = RowRef(TaskEntity.TABLE, id)
    return TaskRecord(
        id = TaskId(id),
        rootId = TaskId(rootId),
        parentId = parentId?.let(::TaskId),
        kind = row.enum<TaskKind>("kind", kind),
        title = title,
        instructions = instructions,
        completionCriteria = row.json("criteria_json", ColumnSerializers.stringList, criteriaJson),
        assignee = assigneeId?.let(::AgentId),
        requestedBy = row.json("requested_by_json", ColumnSerializers.taskRequester, requestedByJson),
        status = row.enum<TaskStatus>("status", status),
        statusDetail = statusDetail,
        dependsOn = row.json("depends_on_json", ColumnSerializers.taskIdList, dependsOnJson),
        delegationChain = row.json("delegation_chain_json", ColumnSerializers.agentIdList, delegationChainJson),
        order = sortOrder,
        attempt = attempt,
        revisionRound = revisionRound,
        needsReview = needsReview,
        result = result,
        error = error,
        reviews = row.json("reviews_json", ColumnSerializers.reviewList, reviewsJson),
        usage = row.json("usage_json", TaskUsage.serializer(), usageJson),
        conversationId = conversationId?.let(::ConversationId),
        options = optionsJson?.let { row.json("options_json", ObjectiveOptions.serializer(), it) },
        createdAt = createdAt,
        updatedAt = updatedAt,
        startedAt = startedAt,
        finishedAt = finishedAt,
        deadlineAt = deadlineAt,
    )
}

/**
 * UTF-8 bytes of the text a task row holds, the measure for `RoomTaskRepository.MAX_TEXT_BYTES`.
 * The fixed-size columns are left out; they are a few hundred bytes at most.
 */
internal fun TaskEntity.storedTextBytes(): Long =
    listOf(
        id, rootId, parentId, title, instructions, statusDetail, result, error, criteriaJson, dependsOnJson,
        delegationChainJson, reviewsJson, usageJson, optionsJson, requestedByJson, conversationId, assigneeId,
    ).sumOf { it?.utf8Length() ?: 0L }
