package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per activity event.
 *
 * - [seq] is the insertion sequence. It is the primary key so that "newest first" has a stable
 *   tie-break for events with the same timestamp (the later insert wins), and because SQLite
 *   appends the primary key to every index, each `(column, at)` index below returns rows
 *   already in display order.
 * - [severity] is a numeric level (see `Severity.level`) so "at least WARNING" is a range.
 * - [hasEgress] duplicates "egress is not null" as an indexable flag.
 *
 * Every filter of `ActivityFilter` has a matching `(column, at)` index; `ActivityQueryBuilder`
 * picks one per query.
 */
@Entity(
    tableName = ActivityEventEntity.TABLE,
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["at"], name = ActivityEventEntity.INDEX_AT),
        Index(value = ["type", "at"], name = ActivityEventEntity.INDEX_TYPE),
        Index(value = ["agent_id", "at"], name = ActivityEventEntity.INDEX_AGENT),
        Index(value = ["task_id", "at"], name = ActivityEventEntity.INDEX_TASK),
        Index(value = ["root_task_id", "at"], name = ActivityEventEntity.INDEX_ROOT_TASK),
        Index(value = ["severity", "at"], name = ActivityEventEntity.INDEX_SEVERITY),
        Index(value = ["has_egress", "at"], name = ActivityEventEntity.INDEX_EGRESS),
    ],
)
internal data class ActivityEventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val id: String,
    val at: Long,
    val type: String,
    val severity: Int,
    @ColumnInfo(name = "agent_id") val agentId: String?,
    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "root_task_id") val rootTaskId: String?,
    val title: String,
    val detail: String,
    @ColumnInfo(name = "data_json") val dataJson: String?,
    @ColumnInfo(name = "egress_json") val egressJson: String?,
    @ColumnInfo(name = "has_egress") val hasEgress: Boolean,
) {
    companion object {
        const val TABLE = "activity_events"
        const val INDEX_AT = "index_activity_events_at"
        const val INDEX_TYPE = "index_activity_events_type_at"
        const val INDEX_AGENT = "index_activity_events_agent_id_at"
        const val INDEX_TASK = "index_activity_events_task_id_at"
        const val INDEX_ROOT_TASK = "index_activity_events_root_task_id_at"
        const val INDEX_SEVERITY = "index_activity_events_severity_at"
        const val INDEX_EGRESS = "index_activity_events_has_egress_at"
    }
}
