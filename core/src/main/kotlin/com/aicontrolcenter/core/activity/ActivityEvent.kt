package com.aicontrolcenter.core.activity

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.registry.Locality
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One entry in the append-only execution log: every message, model call, tool call, permission
 * decision, state change and data transfer is recorded as an event the user can inspect.
 */
@Serializable
data class ActivityEvent(
    val id: EventId,
    val at: Long,
    val type: ActivityType,
    val severity: Severity = Severity.INFO,
    /** Agent that caused the event; null for the user or the system. */
    val agentId: AgentId? = null,
    val taskId: TaskId? = null,
    /** The objective the task belongs to, so one run can be viewed as a whole. */
    val rootTaskId: TaskId? = null,
    val title: String,
    val detail: String = "",
    /** Structured extras for the inspector. Must never contain secrets. */
    val data: JsonObject? = null,
    /** Present when this event moved information off the device. */
    val egress: EgressInfo? = null,
)

enum class ActivityType {
    SYSTEM,
    USER_ACTION,
    REGISTRY,
    MESSAGE,
    MODEL_CALL,
    TOOL_CALL,
    PERMISSION,
    APPROVAL,
    TASK,
    MEMORY,
    DATA_EGRESS,
    ERROR,
}

enum class Severity { INFO, WARNING, ERROR }

/** Describes information leaving the device: where it went, through what, and roughly how much. */
@Serializable
data class EgressInfo(
    val destinationHost: String,
    val locality: Locality,
    val providerId: ProviderId? = null,
    /** What was sent, in words: "prompt and conversation", "search query", "file contents"... */
    val purpose: String,
    val approxBytes: Long? = null,
)

data class ActivityFilter(
    val types: Set<ActivityType> = emptySet(),
    val agentId: AgentId? = null,
    val taskId: TaskId? = null,
    val rootTaskId: TaskId? = null,
    val minSeverity: Severity = Severity.INFO,
    val egressOnly: Boolean = false,
) {
    fun matches(event: ActivityEvent): Boolean =
        (types.isEmpty() || event.type in types) &&
            (agentId == null || event.agentId == agentId) &&
            (taskId == null || event.taskId == taskId) &&
            (rootTaskId == null || event.rootTaskId == rootTaskId) &&
            event.severity.ordinal >= minSeverity.ordinal &&
            (!egressOnly || event.egress != null)
}

interface ActivityLog {
    suspend fun record(event: ActivityEvent)

    /** Newest first, at most [limit] events matching [filter]; re-emits as events arrive. */
    fun observe(filter: ActivityFilter = ActivityFilter(), limit: Int = 200): Flow<List<ActivityEvent>>

    suspend fun recent(filter: ActivityFilter = ActivityFilter(), limit: Int = 200): List<ActivityEvent>

    /** Deletes events older than [olderThan] (epoch millis). Returns how many were removed. */
    suspend fun prune(olderThan: Long): Int

    suspend fun clear()
}
