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
 * Where a page of the log starts: after the stored row at [at] with insertion sequence [seq]
 * (the cursor's event, `ActivityCursor`), or after every row at [at] and later when the event
 * is gone ([seq] null).
 */
internal data class ActivityKeyset(val at: Long, val seq: Long?)

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
 *
 * Schema 6 (Phase 6, K10) adds two conditions that never drive, checked on each row the driving
 * index yields: the text filter ([ActivityFilter.text], title or detail contains it, ASCII case
 * ignored, `%`, `_` and `\` literal through `LIKE … ESCAPE`), and the page start ([ActivityKeyset]):
 * only rows after the cursor's row in display order, bounded from above on `at` so the index
 * range starts there.
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

    /**
     * Longer text filters are matched with `instr` on lower-cased text instead of `LIKE`, whose
     * patterns SQLite refuses beyond 50,000 bytes. The text never drives a query, so no index
     * is given up either way.
     */
    const val MAX_LIKE_PATTERN_BYTES = MemoryQueryBuilder.MAX_LIKE_PATTERN_BYTES

    /**
     * @param after the page start: only rows after it in display order (null: from the newest).
     * @throws IllegalArgumentException if [limit] is negative.
     */
    fun build(filter: ActivityFilter, limit: Int, after: ActivityKeyset? = null): ActivityQuery {
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
        val residual = active.drop(1).map { it.check } + listOfNotNull(textCondition(filter.text), after?.let(::keysetCondition))
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

    /** Title or detail contains [text], ASCII case ignored (what SQLite's `LIKE` folds); null for no filter. */
    private fun textCondition(text: String?): Condition? {
        if (text.isNullOrBlank()) return null
        val pattern = MemoryQueryBuilder.likeContaining(text)
        if (pattern.encodeToByteArray().size <= MAX_LIKE_PATTERN_BYTES) {
            val escape = MemoryQueryBuilder.LIKE_ESCAPE
            return Condition("(title LIKE ? ESCAPE '$escape' OR detail LIKE ? ESCAPE '$escape')", listOf(SqlArg.Text(pattern), SqlArg.Text(pattern)))
        }
        val lower = asciiLowercase(text)
        return Condition("(instr(lower(title), ?) > 0 OR instr(lower(detail), ?) > 0)", listOf(SqlArg.Text(lower), SqlArg.Text(lower)))
    }

    private fun asciiLowercase(text: String): String =
        buildString(text.length) { text.forEach { append(if (it in 'A'..'Z') it + ('a' - 'A') else it) } }

    /**
     * Rows after the cursor's row in display order (`at DESC, seq DESC`): older, or as old and
     * recorded before it; only older when the cursor's row is gone ([ActivityKeyset.seq] null).
     */
    private fun keysetCondition(after: ActivityKeyset): Condition {
        val at = SqlArg.Integer(after.at)
        return if (after.seq == null) {
            Condition("at < ?", listOf(at))
        } else {
            Condition("at <= ? AND (at < ? OR seq < ?)", listOf(at, at, SqlArg.Integer(after.seq)))
        }
    }

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
