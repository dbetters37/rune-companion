package com.aicontrolcenter.core.activity

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.eventId
import kotlinx.serialization.json.JsonObject

/** Convenience for writing [ActivityEvent]s with ids and timestamps filled in. */
class ActivityRecorder(
    private val log: ActivityLog,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun record(
        type: ActivityType,
        title: String,
        detail: String = "",
        severity: Severity = Severity.INFO,
        agentId: AgentId? = null,
        taskId: TaskId? = null,
        rootTaskId: TaskId? = null,
        data: JsonObject? = null,
        egress: EgressInfo? = null,
    ): ActivityEvent {
        val event = ActivityEvent(
            id = ids.eventId(), at = clock.now(), type = type, severity = severity, agentId = agentId,
            taskId = taskId, rootTaskId = rootTaskId, title = title, detail = detail, data = data, egress = egress,
        )
        log.record(event)
        return event
    }
}
