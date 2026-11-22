package com.aicontrolcenter.data.db

import androidx.room.RoomRawQuery
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.data.db.entity.ActivityEventEntity
import com.aicontrolcenter.data.db.mapper.level

/** A value bound to a `?` placeholder. */
internal sealed interface SqlArg {
    data class Text(val value: String) : SqlArg
    data class Integer(val value: Long) : SqlArg
}

/** A complete SELECT over `activity_events` with its bind arguments in placeholder order. */
internal data class ActivityQuery(val sql: String, val args: List<SqlArg>) {

    fun toRoomRawQuery(): RoomRawQuery = RoomRawQuery(sql) { statement ->
        args.forEachIndexed { position, arg ->
            when (arg) {
                is SqlArg.Text -> statement.bindText(position + 1, arg.value)
                is SqlArg.Integer -> statement.bindLong(position + 1, arg.value)
            }
        }
    }
}

/**
 * Turns an [ActivityFilter] into SQL that the database can answer from an index, for every
 * combination of filters.
 *
 * Only the conditions the filter actually sets appear in the statement. One of them is chosen
 * as the *driver*: its `(column, at)` index is pinned with `INDEXED BY`, which both narrows
 * the rows and delivers them newest-first, so SQLite stops as soon as `limit` rows passed the
 * remaining conditions. Nothing is sorted or filtered outside the database.
 *
 * SQLite keeps no statistics for this table, and left alone it would pick between equally
 * shaped indexes arbitrarily (a query for one task could walk every event that left the
 * device). The driver is therefore chosen by how few rows a condition is expected to leave:
 * one task, then one objective, then elevated severity, one agent, the event types, the
 * egress flag, and finally the plain time index.
 *
 * A driver with several values (two event types, or "warnings and errors") cannot be read
 * from one index range in time order. Each value gets its own newest-first range limited to
 * `limit` rows and the ranges are merged with `UNION ALL`, so the cost stays bounded by
 * `values x limit` however large the log grows.
 */
internal object ActivityQueryBuilder {
    private const val ORDER = "ORDER BY at DESC, seq DESC"

    private class Condition(val sql: String, val args: List<SqlArg> = emptyList())

    /**
     * One filter that is switched on: the index that serves it, the index ranges to read when
     * it drives the query, and the same test as a single condition for when it does not.
     */
    private class Dimension(val index: String, val ranges: List<Condition>, val check: Condition) {
        constructor(index: String, condition: Condition) : this(index, listOf(condition), condition)
    }

    /** @throws IllegalArgumentException if [limit] is negative. */
    fun build(filter: ActivityFilter, limit: Int): ActivityQuery {
        require(limit >= 0) { "limit must not be negative, was $limit" }
        // Most selective first: the first active dimension drives, the others are checked on
        // each row its index yields.
        val active = listOfNotNull(
            filter.taskId?.let { Dimension(ActivityEventEntity.INDEX_TASK, textEquals("task_id", it.value)) },
            filter.rootTaskId?.let { Dimension(ActivityEventEntity.INDEX_ROOT_TASK, textEquals("root_task_id", it.value)) },
            filter.selectiveSeverities()?.let { severities ->
                Dimension(ActivityEventEntity.INDEX_SEVERITY, severities.map(::severityEquals), severityAtLeast(severities))
            },
            filter.agentId?.let { Dimension(ActivityEventEntity.INDEX_AGENT, textEquals("agent_id", it.value)) },
            filter.selectiveTypes()?.let { types ->
                Dimension(ActivityEventEntity.INDEX_TYPE, types.map { textEquals("type", it.name) }, typeIn(types))
            },
            if (filter.egressOnly) Dimension(ActivityEventEntity.INDEX_EGRESS, Condition("has_egress = 1")) else null,
        )
        val driver = active.firstOrNull()
        val index = driver?.index ?: ActivityEventEntity.INDEX_AT
        val ranges: List<Condition?> = driver?.ranges ?: listOf(null)
        val residual = active.drop(1).map { it.check }
        val limitArg = SqlArg.Integer(limit.toLong())

        val sql = StringBuilder()
        val args = mutableListOf<SqlArg>()
        val merged = ranges.size > 1
        ranges.forEachIndexed { position, range ->
            val conditions = listOfNotNull(range) + residual
            if (position > 0) sql.append(" UNION ALL ")
            if (merged) sql.append("SELECT * FROM (")
            sql.append("SELECT * FROM ").append(ActivityEventEntity.TABLE).append(" INDEXED BY ").append(index)
            if (conditions.isNotEmpty()) sql.append(" WHERE ").append(conditions.joinToString(" AND ") { it.sql })
            sql.append(' ').append(ORDER).append(" LIMIT ?")
            if (merged) sql.append(')')
            conditions.forEach { args += it.args }
            args += limitArg
        }
        if (merged) {
            sql.append(' ').append(ORDER).append(" LIMIT ?")
            args += limitArg
        }
        return ActivityQuery(sql.toString(), args)
    }

    /** The requested types in a stable order, or null when the filter does not narrow by type. */
    private fun ActivityFilter.selectiveTypes(): List<ActivityType>? =
        types.sortedBy { it.ordinal }.takeUnless { it.isEmpty() || it.size == ActivityType.entries.size }

    /** The severities at or above the minimum, or null when that is all of them. */
    private fun ActivityFilter.selectiveSeverities(): List<Severity>? =
        Severity.entries.filter { it.ordinal >= minSeverity.ordinal }.takeUnless { it.size == Severity.entries.size }

    private fun textEquals(column: String, value: String) = Condition("$column = ?", listOf(SqlArg.Text(value)))

    private fun typeIn(types: List<ActivityType>): Condition =
        if (types.size == 1) {
            textEquals("type", types.single().name)
        } else {
            Condition("type IN (${types.joinToString(", ") { "?" }})", types.map { SqlArg.Text(it.name) })
        }

    private fun severityEquals(severity: Severity) =
        Condition("severity = ?", listOf(SqlArg.Integer(severity.level.toLong())))

    private fun severityAtLeast(severities: List<Severity>) =
        Condition("severity >= ?", listOf(SqlArg.Integer(severities.minOf { it.level }.toLong())))
}
