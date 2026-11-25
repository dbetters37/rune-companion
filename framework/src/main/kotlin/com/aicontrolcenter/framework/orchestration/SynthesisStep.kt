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
 *
 * Phase 6: today's limit reached pauses the objective (rule 6). While the objective has time
 * left, a Director whose provider is not answering, or asks the app to slow down, waits and is
 * tried again without using an attempt (R4; the driver waits until [TaskRecord.retryAt]); once
 * its time is up, such a failure counts as before, so the owner always gets a result.
 */
internal class SynthesisStep(private val ctx: OrchestrationContext) {

    suspend fun run(rt: ObjectiveRuntime, synthesisId: TaskId) {
        val synthesis = ctx.tasks.get(synthesisId) ?: return
        val objective = ctx.tasks.get(synthesis.rootId) ?: return
        val limits = ctx.limits(objective, ctx.settings.get())
        val directorId = synthesis.assignee ?: objective.assignee ?: return fail(rt, synthesis, "No Director was set for this objective.", limits.maxTaskAttempts, retry = false)
        val ready = when (val readiness = ctx.prepareChecked(directorId)) {
            is Readiness.Ready -> readiness
            is Readiness.NotReady -> {
                if (readiness.transient && hasTime(objective)) {
                    ctx.waitForProvider(rt, synthesis, ProviderWaits.named(readiness.provider, "The provider"), null, setOf(TaskStatus.QUEUED))
                    return
                }
                return fail(rt, synthesis, "The Director cannot write the final result: ${readiness.reason}.", limits.maxTaskAttempts, retry = false)
            }
        }
        val conversationId = synthesis.conversationId ?: ctx.transcripts.open(directorId, "Final result · ${objective.title}")
        val now = ctx.clock.now()
        val started = ctx.change(rt, synthesisId, setOf(TaskStatus.QUEUED)) {
            it.copy(
                status = TaskStatus.RUNNING, conversationId = conversationId, startedAt = it.startedAt ?: now, finishedAt = null,
                deadlineAt = now + limits.taskTimeoutMs, retryAt = null, statusDetail = "${ready.agent.name} is writing the final result, ${ready.destination}.",
            )
        } ?: return
        ctx.journal.taskChanged(started, "Synthesis started")
        val planned = plannedWork(ctx.tasks.getTree(objective.id))
        val names = ctx.agents.getAll().associate { it.id to it.name }
        val completed = planned.filter { it.status == TaskStatus.COMPLETED }.map {
            // A limit made the objective use it without the changes its latest review asked for: the brief shows that review with it.
            val unaddressed = it.reviews.lastOrNull()?.takeIf { _ -> it.statusDetail.orEmpty().startsWith(ObjectiveDriver.UNREVISED) }
            SourceResult(it, it.assignee?.let(names::get) ?: "a former team member", unaddressed)
        }
        val missing = planned.filter { it.status != TaskStatus.COMPLETED }.map { it.title to FinalDocument.whyNot(it) }
        // Every note the owner wrote: the Director writes the final result for all of them.
        val notes = ctx.ownerNotes(objective.id).map { it.id.value to it.body }
        val brief = ctx.briefs.synthesis(SynthesisBrief(objective, started, completed, missing, notes), ctx.steps.briefBudget(ready))
        ctx.runtimes.started(directorId, started.id)
        val result = try {
            ctx.steps.run(
                ready, started, conversationId, brief, limits = RunLimits(timeoutMs = limits.taskTimeoutMs),
                label = "Task: Final result · ${objective.title}", purpose = "the team's results for the final document",
                screens = objective.options?.screenActionsAllowed ?: true,
            )
        } finally {
            ctx.runtimes.ended(directorId, started.id)
        }
        ctx.account(rt, started.id) { it.copy(usage = it.usage + result.usage) }
        when (val trouble = Trouble.of(result)) {
            is Trouble.DailyLimit -> return run { ctx.pauseForDailyLimit(rt, trouble.sentence) }
            is Trouble.Passing -> if (hasTime(objective)) {
                ctx.waitForProvider(rt, started, ProviderWaits.named(ready.provider, ready.providerName), trouble.error, setOf(TaskStatus.RUNNING))
                return
            }
            null -> Unit
        }
        rt.waitsSoFar.remove(started.id)
        if (!result.ok) return fail(rt, started, result.failure ?: "The final result could not be written.", limits.maxTaskAttempts, retry = true)
        val done = ctx.change(rt, synthesisId, setOf(TaskStatus.RUNNING)) {
            it.copy(status = TaskStatus.COMPLETED, result = result.text, error = null, finishedAt = ctx.clock.now(), statusDetail = "Written by ${ready.agent.name}.")
        } ?: return
        ctx.journal.taskChanged(done, "Synthesis completed")
    }

    /** The objective's time is not up: its synthesis may still wait for a provider. */
    private suspend fun hasTime(objective: TaskRecord): Boolean =
        (ctx.tasks.get(objective.id)?.deadlineAt ?: objective.deadlineAt)?.let { ctx.clock.now() < it } ?: true

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
    /**
     * At most [maxBytes] of UTF-8 (by default [StoredText.MAX_DOCUMENT_BYTES], well below what
     * the task store holds): every result gets a share, and one cut says where its whole text is.
     */
    fun assemble(
        objective: TaskRecord,
        planned: List<TaskRecord>,
        names: Map<com.aicontrolcenter.core.AgentId, String>,
        why: String,
        maxBytes: Int = StoredText.MAX_DOCUMENT_BYTES,
    ): String {
        val completed = planned.filter { it.status == TaskStatus.COMPLETED }
        val missing = planned.filter { it.status != TaskStatus.COMPLETED }
        fun render(results: List<String>) = buildString {
            append("# ").append(objective.title).append("\n\n")
            append("> The Director could not write the final document ($why). The results are shown as the team produced them.\n")
            completed.forEachIndexed { index, task ->
                append("\n## ").append(task.title).append('\n')
                task.assignee?.let { names[it] }?.let { append("*By ").append(it).append("*\n\n") }
                append(results[index]).append('\n')
            }
            if (missing.isNotEmpty()) {
                append("\n## Not completed\n")
                missing.forEach { append("- ").append(it.title).append(": ").append(whyNot(it)).append('\n') }
            }
        }.trimEnd()
        val results = completed.map { it.result.orEmpty().trim() }
        val whole = render(results)
        if (StoredText.utf8Bytes(whole) <= maxBytes) return whole
        // Too large to store: each result gets a share of what the rest of the document leaves.
        val fixed = StoredText.utf8Bytes(render(results.map { "" })) + completed.size * CUT_NOTE_BYTES
        val shares = TextBudget.allocate(results.map { StoredText.utf8Bytes(it).toDouble() }, (maxBytes - fixed).toDouble().coerceAtLeast(0.0))
        val cut = results.mapIndexed { index, text ->
            StoredText.capBytes(text, shares[index].toInt().coerceAtLeast(0) + CUT_NOTE_BYTES) { n ->
                "[cut: $n characters omitted; the whole result is on the task \"${completed[index].title}\"]"
            }
        }
        return StoredText.capBytes(render(cut), maxBytes) { n -> "[cut: $n characters omitted; see the objective's tasks for every result]" }
    }

    private const val CUT_NOTE_BYTES = 200

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
