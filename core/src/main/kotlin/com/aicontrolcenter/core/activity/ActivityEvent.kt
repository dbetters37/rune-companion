package com.aicontrolcenter.core.activity

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.registry.Locality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    /**
     * Phase 6 (K10, Tier B "Activity text search"): only events whose title or detail contains
     * this text, ignoring case. Null or blank: no text filter. Matched as plain text: `%`, `_`
     * and `\` mean themselves (Room escapes them in its `LIKE`). Case is ignored for ASCII
     * letters everywhere and for other letters where the store can (SQLite's LIKE folds ASCII only).
     */
    val text: String? = null,
) {
    fun matches(event: ActivityEvent): Boolean =
        (types.isEmpty() || event.type in types) &&
            (agentId == null || event.agentId == agentId) &&
            (taskId == null || event.taskId == taskId) &&
            (rootTaskId == null || event.rootTaskId == rootTaskId) &&
            event.severity.ordinal >= minSeverity.ordinal &&
            (!egressOnly || event.egress != null) &&
            (text.isNullOrBlank() || event.title.contains(text, ignoreCase = true) || event.detail.contains(text, ignoreCase = true))
}

/**
 * Where a page of the log ended: the last event shown (Phase 6, K10, D41 "Show older"). The next
 * page holds the events after it in the log's order (newest first; at the same time, latest
 * recorded first): older events, and events at the same time recorded before it. If that event
 * is gone (pruned), the next page starts with the events older than [at].
 */
data class ActivityCursor(val at: Long, val id: EventId) {
    /** Building cursors. */
    companion object {
        fun after(event: ActivityEvent): ActivityCursor = ActivityCursor(event.at, event.id)
    }
}

interface ActivityLog {
    /** Appends an event. An event whose id was already recorded is ignored: the log is append-only. */
    suspend fun record(event: ActivityEvent)

    /**
     * Newest first (events with the same time: latest recorded first), at most [limit] events
     * matching [filter]; re-emits as events arrive.
     */
    fun observe(filter: ActivityFilter = ActivityFilter(), limit: Int = 200): Flow<List<ActivityEvent>>

    suspend fun recent(filter: ActivityFilter = ActivityFilter(), limit: Int = 200): List<ActivityEvent>

    /**
     * One page of the log (Phase 6, K10): at most [limit] events matching [filter], in the order
     * of [observe], starting after [before] ([ActivityCursor]) or at the newest when it is null;
     * re-emits as events arrive. The default reads every matching event and cuts the page; Room
     * overrides it with a keyset query. Both pass `ActivityPageContract`.
     */
    fun observePage(filter: ActivityFilter = ActivityFilter(), before: ActivityCursor? = null, limit: Int = 200): Flow<List<ActivityEvent>> =
        observe(filter, Int.MAX_VALUE).map { all -> activityPageOf(all, before, limit) }

    /** Deletes events older than [olderThan] (epoch millis). Returns how many were removed. */
    suspend fun prune(olderThan: Long): Int

    suspend fun clear()
}

/** The page of [newestFirst] (in the log's order) that follows [before], at most [limit] long. */
fun activityPageOf(newestFirst: List<ActivityEvent>, before: ActivityCursor?, limit: Int): List<ActivityEvent> {
    val size = limit.coerceAtLeast(0)
    if (before == null) return newestFirst.take(size)
    val index = newestFirst.indexOfFirst { it.id == before.id }
    val rest = if (index >= 0) newestFirst.drop(index + 1) else newestFirst.filter { it.at < before.at }
    return rest.take(size)
}
