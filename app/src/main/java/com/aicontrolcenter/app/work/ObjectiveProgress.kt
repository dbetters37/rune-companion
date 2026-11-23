package com.aicontrolcenter.app.work

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskStatus

/**
 * How far an objective has got: [completed] of [planned] steps of its plan.
 *
 * The plan is what the Director wrote: its work tasks and the final result. Reviews and work an
 * agent delegated are part of the task they belong to, so they are not counted again; before the
 * Director has planned, [planned] is 0 and the objective is "planning".
 */
data class ObjectiveProgress(val completed: Int, val planned: Int) {
    val isPlanning: Boolean get() = planned == 0

    /** 0 to 1, for a progress bar; 0 while planning. */
    val fraction: Float get() = if (planned == 0) 0f else completed.toFloat() / planned

    companion object {
        fun of(tree: List<TaskRecord>): ObjectiveProgress {
            val steps = tree.filter { it.isPlanStep() }
            return ObjectiveProgress(steps.count { it.status == TaskStatus.COMPLETED }, steps.size)
        }
    }
}

/**
 * A step of the Director's plan: a work task directly under the objective (work an agent
 * delegated hangs under the delegating task instead), or the final result.
 */
fun TaskRecord.isPlanStep(): Boolean =
    parentId == rootId && id != rootId && (kind == TaskKind.WORK || kind == TaskKind.SYNTHESIS)

/**
 * What the ongoing notification says while objectives run. Pure, so its wording is tested
 * without a notification manager.
 *
 * @property objectiveId the objective the notification is about (the oldest running one); tapping opens it.
 */
data class WorkNotice(
    val objectiveId: TaskId,
    val title: String,
    val text: String,
    val progress: ObjectiveProgress,
) {
    companion object {
        /**
         * The notice for the [running] objectives, given their task trees (in any order), or null
         * when nothing runs. With several objectives, the oldest one is named and the rest counted.
         */
        fun of(running: Set<TaskId>, trees: List<List<TaskRecord>>): WorkNotice? {
            val objectives = trees.mapNotNull { tree -> tree.firstOrNull { it.kind == TaskKind.OBJECTIVE && it.id in running }?.let { it to tree } }
                .sortedBy { (objective, _) -> objective.createdAt }
            val (first, tree) = objectives.firstOrNull() ?: return null
            val progress = ObjectiveProgress.of(tree)
            val text = buildString {
                append(if (progress.isPlanning) "Planning the work" else "${progress.completed} of ${progress.planned} tasks done")
                val others = objectives.size - 1
                if (others > 0) append(" · ").append(others).append(if (others == 1) " more objective running" else " more objectives running")
            }
            return WorkNotice(first.id, "Director is working on: ${first.title}", text, progress)
        }
    }
}
