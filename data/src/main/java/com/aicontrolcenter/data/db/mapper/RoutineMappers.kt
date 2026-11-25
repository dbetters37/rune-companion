package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.pickup.PickedUpFile
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineRun
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.data.db.entity.PickUpImportEntity
import com.aicontrolcenter.data.db.entity.PickUpRuleEntity
import com.aicontrolcenter.data.db.entity.RoutineEntity
import com.aicontrolcenter.data.db.entity.RoutineRunEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Polymorphic: `{"type":"daily",…}` or `{"type":"every_hours",…}` (schema 6, Appendix A). */
internal val routineScheduleSerializer: KSerializer<RoutineSchedule> = serializer<RoutineSchedule>()

internal fun Routine.toEntity(): RoutineEntity = RoutineEntity(
    id = id.value,
    title = title,
    objectiveText = objectiveText,
    optionsJson = encodeColumn(ObjectiveOptions.serializer(), options),
    scheduleJson = encodeColumn(routineScheduleSerializer, schedule),
    enabled = enabled,
    approvalTimeoutMinutes = approvalTimeoutMinutes,
    allowScreenActions = allowScreenActions,
    createdAt = createdAt,
    updatedAt = updatedAt,
    nextRunAt = nextRunAt,
    consecutiveFailures = consecutiveFailures,
    pausedNote = pausedNote,
)

/** @throws com.aicontrolcenter.data.StoredDataException naming the column, if the row cannot be read. */
internal fun RoutineEntity.toDomain(): Routine {
    val row = RowRef(RoutineEntity.TABLE, id)
    val options = row.json("options_json", ObjectiveOptions.serializer(), optionsJson)
    val schedule = row.json("schedule_json", routineScheduleSerializer, scheduleJson)
    return row.consistent {
        Routine(
            id = RoutineId(id),
            title = title,
            objectiveText = objectiveText,
            options = options,
            schedule = schedule,
            enabled = enabled,
            approvalTimeoutMinutes = approvalTimeoutMinutes,
            allowScreenActions = allowScreenActions,
            createdAt = createdAt,
            updatedAt = updatedAt,
            nextRunAt = nextRunAt,
            consecutiveFailures = consecutiveFailures,
            pausedNote = pausedNote,
        )
    }
}

internal fun RoutineRun.toEntity(): RoutineRunEntity = RoutineRunEntity(
    id = id.value,
    routineId = routineId.value,
    dueAt = dueAt,
    outcome = outcome.name,
    startedAt = startedAt,
    objectiveId = objectiveId?.value,
    note = note,
)

/** @throws com.aicontrolcenter.data.StoredDataException naming the column, if the row cannot be read. */
internal fun RoutineRunEntity.toDomain(): RoutineRun {
    val row = RowRef(RoutineRunEntity.TABLE, id)
    val outcome = row.enum<RoutineRunOutcome>("outcome", outcome)
    return row.consistent {
        RoutineRun(
            id = RoutineRunId(id),
            routineId = RoutineId(routineId),
            dueAt = dueAt,
            outcome = outcome,
            startedAt = startedAt,
            objectiveId = objectiveId?.let(::TaskId),
            note = note,
        )
    }
}

internal fun PickUpRule.toEntity(): PickUpRuleEntity = PickUpRuleEntity(
    id = id.value,
    folderId = folderId.value,
    spaceId = spaceId.value,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastRunAt = lastRunAt,
    lastResult = lastResult,
    folderReadOnlySince = folderReadOnlySince,
)

internal fun PickUpRuleEntity.toDomain(): PickUpRule = PickUpRule(
    id = PickUpRuleId(id),
    folderId = FolderId(folderId),
    spaceId = SpaceId(spaceId),
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastRunAt = lastRunAt,
    lastResult = lastResult,
    folderReadOnlySince = folderReadOnlySince,
)

internal fun PickedUpFile.toEntity(): PickUpImportEntity = PickUpImportEntity(
    ruleId = ruleId.value,
    contentSha256 = contentSha256,
    documentName = documentName,
    sizeBytes = sizeBytes,
    importedAt = importedAt,
    memoryId = memoryId?.value,
    seenOnly = seenOnly,
)

/** @throws com.aicontrolcenter.data.StoredDataException if the stored hash or size is not valid. */
internal fun PickUpImportEntity.toDomain(): PickedUpFile = RowRef(PickUpImportEntity.TABLE, "$ruleId/$contentSha256").consistent {
    PickedUpFile(
        ruleId = PickUpRuleId(ruleId),
        contentSha256 = contentSha256,
        documentName = documentName,
        sizeBytes = sizeBytes,
        importedAt = importedAt,
        memoryId = memoryId?.let(::MemoryId),
        seenOnly = seenOnly,
    )
}
