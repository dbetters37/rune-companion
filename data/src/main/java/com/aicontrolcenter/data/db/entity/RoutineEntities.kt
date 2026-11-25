package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Routines and folder pick-up (schema 6; owner decision Q1, Appendix A). Only the owner creates
 * or changes these rows (`OwnerRoutines`, `OwnerPickUpRules`); nothing an agent reaches holds the
 * repositories. None of these tables has a foreign key: the repositories remove a routine's runs
 * and a rule's import records in the same transaction as the routine or rule, and an import
 * record does not point at the memory it became in a way that could delete it.
 */

/**
 * One routine (`Routine`). [scheduleJson] is the `RoutineSchedule` as JSON (`{"type":"daily",…}`
 * or `{"type":"every_hours",…}`) and [optionsJson] its `ObjectiveOptions`; [nextRunAt] is null
 * while the routine is off; [pausedNote] says, in plain words, why it switched itself off.
 * At most ten exist, so the listing (created time, then id) needs no index.
 */
@Entity(tableName = RoutineEntity.TABLE)
internal data class RoutineEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "objective_text") val objectiveText: String,
    @ColumnInfo(name = "options_json") val optionsJson: String,
    @ColumnInfo(name = "schedule_json") val scheduleJson: String,
    val enabled: Boolean,
    @ColumnInfo(name = "approval_timeout_minutes") val approvalTimeoutMinutes: Int?,
    @ColumnInfo(name = "allow_screen_actions") val allowScreenActions: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "next_run_at") val nextRunAt: Long?,
    @ColumnInfo(name = "consecutive_failures") val consecutiveFailures: Int,
    @ColumnInfo(name = "paused_note") val pausedNote: String?,
) {
    companion object {
        const val TABLE = "routines"
    }
}

/**
 * One due time of a routine (`RoutineRun`): [outcome] is a `RoutineRunOutcome` name; [startedAt]
 * and [objectiveId] are set together, for a started run only; [note] is app-written, never agent
 * text. Index `(routine_id, due_at, id)`, read backwards for a routine's runs newest first.
 */
@Entity(
    tableName = RoutineRunEntity.TABLE,
    indices = [Index(value = ["routine_id", "due_at", "id"])],
)
internal data class RoutineRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "routine_id") val routineId: String,
    @ColumnInfo(name = "due_at") val dueAt: Long,
    val outcome: String,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "objective_id") val objectiveId: String?,
    val note: String?,
) {
    companion object {
        const val TABLE = "routine_runs"
    }
}

/**
 * One folder pick-up rule (`PickUpRule`): new `text/…` files directly in shared folder
 * [folderId] go into memory space [spaceId]. One rule per folder: the unique index on
 * `folder_id` is the backstop of the check the repository makes in its transaction.
 * [lastResult] is an app-written sentence.
 */
@Entity(
    tableName = PickUpRuleEntity.TABLE,
    indices = [Index(value = ["folder_id"], unique = true)],
)
internal data class PickUpRuleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "folder_id") val folderId: String,
    @ColumnInfo(name = "space_id") val spaceId: String,
    val enabled: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_run_at") val lastRunAt: Long?,
    @ColumnInfo(name = "last_result") val lastResult: String?,
) {
    companion object {
        const val TABLE = "pickup_rules"
    }
}

/**
 * One file a pick-up rule imported (`PickedUpFile`). The primary key `(rule_id, content_sha256)`
 * is the de-duplication: the same content is recorded once per rule, and of two pick-ups racing
 * to record it exactly one succeeds (`INSERT OR IGNORE`). [memoryId] is the memory it became.
 *
 * Indexes: `(rule_id, imported_at DESC, content_sha256 ASC)`, a rule's imports newest first in
 * exactly the listing order; `(imported_at)`, the bytes of a day for the daily cap.
 */
@Entity(
    tableName = PickUpImportEntity.TABLE,
    primaryKeys = ["rule_id", "content_sha256"],
    indices = [
        Index(value = ["rule_id", "imported_at", "content_sha256"], orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.ASC]),
        Index(value = ["imported_at"]),
    ],
)
internal data class PickUpImportEntity(
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
    @ColumnInfo(name = "document_name") val documentName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "memory_id") val memoryId: String?,
) {
    companion object {
        const val TABLE = "pickup_imports"
    }
}
