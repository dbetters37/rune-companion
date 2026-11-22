package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.data.StoredDataException
import com.aicontrolcenter.data.db.entity.ActivityEventEntity
import kotlinx.serialization.json.JsonObject

/**
 * The number stored for a severity. Levels only have to grow with the severity; the gaps leave
 * room to add a level in between later without rewriting existing rows.
 */
internal val Severity.level: Int
    get() = when (this) {
        Severity.INFO -> 10
        Severity.WARNING -> 20
        Severity.ERROR -> 30
    }

internal fun ActivityEvent.toEntity(): ActivityEventEntity = ActivityEventEntity(
    id = id.value,
    at = at,
    type = type.name,
    severity = severity.level,
    agentId = agentId?.value,
    taskId = taskId?.value,
    rootTaskId = rootTaskId?.value,
    title = title,
    detail = detail,
    dataJson = data?.let { encodeColumn(JsonObject.serializer(), it) },
    egressJson = egress?.let { encodeColumn(EgressInfo.serializer(), it) },
    hasEgress = egress != null,
)

internal fun ActivityEventEntity.toDomain(): ActivityEvent {
    val row = RowRef(ActivityEventEntity.TABLE, id)
    return ActivityEvent(
        id = EventId(id),
        at = at,
        type = row.enum("type", type),
        severity = Severity.entries.firstOrNull { it.level == severity }
            ?: throw StoredDataException(
                "Column ${row.table}.severity of row '${row.rowId}' holds the unknown level $severity.",
            ),
        agentId = agentId?.let(::AgentId),
        taskId = taskId?.let(::TaskId),
        rootTaskId = rootTaskId?.let(::TaskId),
        title = title,
        detail = detail,
        data = dataJson?.let { row.json("data_json", JsonObject.serializer(), it) },
        egress = egressJson?.let { row.json("egress_json", EgressInfo.serializer(), it) },
    )
}
