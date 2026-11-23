package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.agents.RunLimits

/**
 * The Director writes the final document from the completed results (as data) and a list of
 * what was not completed and why. One call is one attempt; failed attempts are retried up to
 * the attempt limit. When the Director cannot write it at all, [FinalDocument] assembles the
 * results mechanically instead, so finished work is never lost.
 */
internal class SynthesisStep(private val ctx: OrchestrationContext) {

    suspend fun run(rt: ObjectiveRuntime, synthesisId: TaskId) {
        val synthesis = ctx.tasks.get(synthesisId) ?: return
        val objective = ctx.tasks.get(synthesis.rootId) ?: return
        val limits = ctx.limits(objective, ctx.settings.get())
        val directorId = synthesis.assignee ?: objective.assignee ?: return fail(rt, synthesis, "No Director was set for this objective.", limits.maxTaskAttempts, retry = false)
        val ready = when (val readiness = ctx.steps.prepare(directorId)) {
            is Readiness.Ready -> readiness
            is Readiness.NotReady -> return fail(rt, synthesis, "The Director cannot write the final result: ${readiness.reason}.", limits.maxTaskAttempts, retry = false)
        }
        val conversationId = synthesis.conversationId ?: ctx.transcripts.open(directorId, "Final result · ${objective.title}")
        val now = ctx.clock.now()
        val started = ctx.change(rt, synthesisId, setOf(TaskStatus.QUEUED)) {
            it.copy(
                status = TaskStatus.RUNNING, conversationId = conversationId, startedAt = it.startedAt ?: now, finishedAt = null,
                deadlineAt = now + limits.taskTimeoutMs, statusDetail = "${ready.agent.name} is writing the final result, ${ready.destination}.",
            )
        } ?: return
        ctx.journal.taskChanged(started, "Synthesis started")
        val planned = plannedWork(ctx.tasks.getTree(objective.id))
        val names = ctx.agents.getAll().associate { it.id to it.name }
        val completed = planned.filter { it.status == TaskStatus.COMPLETED }.map { SourceResult(it, it.assignee?.let(names::get) ?: "a former team member") }
        val missing = planned.filter { it.status != TaskStatus.COMPLETED }.map { it.title to FinalDocument.whyNot(it) }
        val brief = ctx.briefs.synthesis(SynthesisBrief(objective, started, completed, missing))
        ctx.runtimes.started(directorId, started.id)
        val result = try {
            ctx.steps.run(
                ready, started, conversationId, brief, limits = RunLimits(timeoutMs = limits.taskTimeoutMs),
                label = "Task: Final result · ${objective.title}", purpose = "the team's results for the final document",
            )
        } finally {
            ctx.runtimes.ended(directorId, started.id)
        }
        ctx.account(rt, started.id) { it.copy(usage = it.usage + result.usage) }
        if (!result.ok) return fail(rt, started, result.failure ?: "The final result could not be written.", limits.maxTaskAttempts, retry = true)
        val done = ctx.change(rt, synthesisId, setOf(TaskStatus.RUNNING)) {
            it.copy(status = TaskStatus.COMPLETED, result = result.text, error = null, finishedAt = ctx.clock.now(), statusDetail = "Written by ${ready.agent.name}.")
        } ?: return
        ctx.journal.taskChanged(done, "Synthesis completed")
    }

    private suspend fun fail(rt: ObjectiveRuntime, synthesis: TaskRecord, reason: String, maxAttempts: Int, retry: Boolean) {
        val stored = ctx.change(rt, synthesis.id, setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) {
            if (retry && it.attempt + 1 < maxAttempts) it.copy(status = TaskStatus.QUEUED, attempt = it.attempt + 1, error = reason, statusDetail = "Trying again. $reason")
            else it.copy(status = TaskStatus.FAILED, error = reason, statusDetail = reason, finishedAt = ctx.clock.now())
        } ?: return
        ctx.journal.taskChanged(stored, if (stored.status == TaskStatus.FAILED) "Synthesis failed" else "Synthesis will be retried", Severity.WARNING)
    }

    companion object {
        /** The plan's own WORK tasks, in plan order: delegated work reaches the result through the task that delegated it. */
        fun plannedWork(tree: List<TaskRecord>): List<TaskRecord> =
            tree.filter { it.kind == TaskKind.WORK && it.delegationChain.isEmpty() }.sortedWith(compareBy({ it.order }, { it.createdAt }))
    }
}

/**
 * The objective's document when the Director could not write one: the completed results under
 * their titles, then what was not completed and why. Plain, and labelled as assembled.
 */
internal object FinalDocument {
    fun assemble(objective: TaskRecord, planned: List<TaskRecord>, names: Map<com.aicontrolcenter.core.AgentId, String>, why: String): String = buildString {
        append("# ").append(objective.title).append("\n\n")
        append("> The Director could not write the final document ($why). The results are shown as the team produced them.\n")
        val completed = planned.filter { it.status == TaskStatus.COMPLETED }
        for (task in completed) {
            append("\n## ").append(task.title).append('\n')
            task.assignee?.let { names[it] }?.let { append("*By ").append(it).append("*\n\n") }
            append(task.result.orEmpty().trim()).append('\n')
        }
        val missing = planned.filter { it.status != TaskStatus.COMPLETED }
        if (missing.isNotEmpty()) {
            append("\n## Not completed\n")
            missing.forEach { append("- ").append(it.title).append(": ").append(whyNot(it)).append('\n') }
        }
    }.trimEnd()

    /** Why a task did not complete, in one sentence. */
    fun whyNot(task: TaskRecord): String {
        val reason = (task.error ?: task.statusDetail)?.trim()?.trimEnd('.')
        val state = when (task.status) {
            TaskStatus.FAILED -> "failed"
            TaskStatus.TIMED_OUT -> "ran out of time"
            TaskStatus.CANCELLED -> "was cancelled"
            TaskStatus.QUEUED -> "was not started"
            else -> "did not finish"
        }
        return if (reason.isNullOrEmpty()) "$state." else "$state: $reason."
    }
}
