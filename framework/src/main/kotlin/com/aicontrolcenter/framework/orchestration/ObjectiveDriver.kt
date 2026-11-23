package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import com.aicontrolcenter.framework.orchestration.OrchestrationContext.Companion.WORKING
import com.aicontrolcenter.framework.orchestration.OrchestrationContext.Companion.isScheduled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives one objective from its stored state to its end: plan (unless a plan is stored), run
 * the plan's tasks, synthesise, save knowledge, finish. Everything it decides is read from the
 * task store each time, so the same code continues an objective after a pause or a crash.
 *
 * The scheduler starts the tasks whose dependencies are COMPLETED, at most
 * [com.aicontrolcenter.core.settings.FrameworkSettings.maxConcurrentTasks] of the objective at
 * a time, and cancels tasks whose dependency finally failed. It wakes when a task ends or the
 * owner changes something, never by polling.
 *
 * The objective's deadline bounds the work: when it passes, unfinished work is cancelled and
 * the Director writes the final document from what exists (allowed one task time limit of its
 * own for that, so the owner always gets a result). A result that is already there is never
 * thrown away by a limit: it is used as it is, and its status line says it was not (further)
 * reviewed and why.
 *
 * While it runs, the driver writes a heartbeat onto the objective every [HEARTBEAT_MS], so that
 * after a crash the time the objective had left can be read from its last stored write.
 *
 * The objective is finished under its lock, against what is stored at that moment: when the
 * owner asked for a task to run again meanwhile, the driver goes on with it instead.
 *
 * Nothing that goes wrong inside escapes: an unexpected error fails this objective only.
 */
internal class ObjectiveDriver(
    private val ctx: OrchestrationContext,
    private val planning: PlanningStep,
    private val work: WorkSteps,
    private val reviews: ReviewSteps,
    private val synthesis: SynthesisStep,
    private val knowledge: KnowledgeKeeper,
) {
    suspend fun drive(rt: ObjectiveRuntime) {
        try {
            coroutineScope {
                val beat = launch { heartbeat(rt) }
                try {
                    body(rt)
                } finally {
                    beat.cancel()
                }
            }
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            internalFailure(rt, e)
        }
    }

    /** Stores the objective (its updatedAt) every [HEARTBEAT_MS] while it runs: the last moment it is known to have been running. */
    private suspend fun heartbeat(rt: ObjectiveRuntime) {
        while (true) {
            delay(HEARTBEAT_MS)
            try {
                ctx.change(rt, rt.rootId, setOf(TaskStatus.RUNNING)) { it } ?: return
            } catch (e: Exception) {
                rethrowIfCancelled(e) // a missed heartbeat only makes the time left after a crash a little longer
            }
        }
    }

    private suspend fun body(rt: ObjectiveRuntime) {
        if (!isRunning(rt.rootId)) return
        if (ctx.tasks.getTree(rt.rootId).none { it.kind == TaskKind.WORK || it.kind == TaskKind.SYNTHESIS }) {
            val objective = ctx.tasks.get(rt.rootId) ?: return
            if (objective.deadlineAt != null && ctx.clock.now() >= objective.deadlineAt!!) {
                planning.failObjective(rt, "The objective reached its time limit before the Director could make a plan.")
                return
            }
            if (!planning.plan(rt)) return
        }
        while (true) {
            val objective = ctx.tasks.get(rt.rootId)?.takeIf { it.status == TaskStatus.RUNNING } ?: return
            val remaining = ((objective.deadlineAt ?: Long.MAX_VALUE) - ctx.clock.now()).coerceAtLeast(1)
            val inTime = withTimeoutOrNull(remaining) { supervisorScope { schedule(rt, this) } }
            if (inTime == null) objectiveTimedOut(rt)
            if (!isRunning(rt.rootId)) return
            runSynthesis(rt)
            if (!isRunning(rt.rootId)) return
            // The owner may have asked for a task to run again while the synthesis ran: work on it first.
            if (!readyToFinish(ctx.tasks.getTree(rt.rootId))) continue
            if (finish(rt)) return
        }
    }

    /** Every planned task has finished, and the synthesis is not waiting to run (again, after a retry). */
    private fun readyToFinish(tree: List<TaskRecord>): Boolean =
        SynthesisStep.plannedWork(tree).all { it.status.isFinished } && tree.none { it.kind == TaskKind.SYNTHESIS && it.status == TaskStatus.QUEUED }

    /** Runs the plan until every scheduled task has finished. Returns true; the caller's time-out returns null. */
    private suspend fun schedule(rt: ObjectiveRuntime, scope: CoroutineScope): Boolean {
        while (true) {
            val keepGoing = rt.lock.withLockNonCancellable { step(rt, scope) }
            if (!keepGoing) return true
            rt.wake.receive()
        }
    }

    /** One look at the plan: cancels what can no longer run, starts what can. Returns false when nothing is left to wait for. */
    private suspend fun step(rt: ObjectiveRuntime, scope: CoroutineScope): Boolean {
        val tree = ctx.tasks.getTree(rt.rootId)
        val objective = tree.firstOrNull { it.id == rt.rootId }?.takeIf { it.status == TaskStatus.RUNNING } ?: return false
        val settings = ctx.settings.get()
        val limits = ctx.limits(objective, settings)
        val byId = tree.associateBy { it.id }.toMutableMap()
        val changed = ArrayList<TaskRecord>()
        fun put(task: TaskRecord) {
            byId[task.id] = task
            changed += task
        }
        val now = ctx.clock.now()

        // Tasks whose dependency finally did not complete can never start: cancel them, transitively.
        var cascading = true
        while (cascading) {
            cascading = false
            for (task in byId.values.filter { it.isScheduled() && it.status == TaskStatus.QUEUED }) {
                val blocker = task.dependsOn.mapNotNull { byId[it] }.firstOrNull { it.status.isFinished && it.status != TaskStatus.COMPLETED } ?: continue
                put(task.copy(status = TaskStatus.CANCELLED, error = "$DEPENDENCY_PREFIX\"${blocker.title}\", which did not complete.", statusDetail = "Not started.", finishedAt = now, updatedAt = now))
                cascading = true
            }
        }
        // A result waiting for a review that does not exist (the app stopped between the two): ask for the review again.
        for (task in byId.values.filter { it.kind == TaskKind.WORK && it.status == TaskStatus.IN_REVIEW }) {
            val pending = byId.values.any { it.kind == TaskKind.REVIEW && it.parentId == task.id && !it.status.isFinished }
            if (!pending) put(task.copy(status = TaskStatus.COMPLETED, finishedAt = now, updatedAt = now, statusDetail = "Used without review: its review was lost when the app stopped."))
        }

        val scheduled = byId.values.filter { it.isScheduled() }
        val active = scheduled.count { it.status in WORKING }
        val runnable = scheduled.filter { it.status == TaskStatus.QUEUED && it.dependsOn.all { dep -> byId[dep]?.status == TaskStatus.COMPLETED } }
            .sortedWith(compareBy({ it.order }, { it.createdAt }, { it.id.value }))
        var starting = emptyList<TaskRecord>()
        if (runnable.isNotEmpty() && ctx.messages.count(rt.rootId) >= limits.maxMessages) {
            val limit = "the objective reached its limit of ${limits.maxMessages} messages"
            stoppedByLimit(byId, scheduled.filter { it.status == TaskStatus.QUEUED }, limit, "Not started: $limit.", now).forEach(::put)
            ctx.tasks.upsertAll(changed)
            changed.filter { it.status.isFinished }.forEach { journalStopped(it) }
            ctx.journal.limitReached(rt.rootId, null, "$limit; tasks not yet started were cancelled, and results waiting for a review or a revision are used as they are.")
            return true.also { rt.poke() }
        }
        val slots = settings.maxConcurrentTasks.coerceAtLeast(1) - active
        if (slots > 0) {
            starting = runnable.take(slots).map { it.copy(status = TaskStatus.RUNNING, statusDetail = "Starting.", updatedAt = now) }
            starting.forEach(::put)
        }
        if (changed.isNotEmpty()) ctx.tasks.upsertAll(changed)
        changed.filter { it.status == TaskStatus.CANCELLED || it.status == TaskStatus.COMPLETED }.forEach {
            ctx.journal.taskChanged(it, if (it.status == TaskStatus.CANCELLED) "Task cancelled" else "Task completed", Severity.WARNING)
        }
        starting.forEach { launchTask(rt, scope, it) }

        val waiting = byId.values.filter { it.isScheduled() && !it.status.isFinished }
        if (waiting.isEmpty()) return rt.taskJobs.isNotEmpty()
        if (starting.isEmpty() && waiting.none { it.status in WORKING } && rt.taskJobs.isEmpty() && runnable.isEmpty()) {
            // Nothing runs and nothing can start: the remaining tasks would wait forever.
            val stuck = waiting.filter { it.status != TaskStatus.IN_REVIEW }.map {
                it.copy(status = TaskStatus.CANCELLED, error = "Could not be scheduled: ${FinalDocument.whyNot(it)}", finishedAt = now, updatedAt = now)
            }
            if (stuck.isEmpty()) return false
            ctx.tasks.upsertAll(stuck)
            rt.poke()
        }
        return true
    }

    private fun launchTask(rt: ObjectiveRuntime, scope: CoroutineScope, task: TaskRecord) {
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(rt, task) }
        rt.taskJobs[task.id] = job
        job.invokeOnCompletion {
            rt.taskJobs.remove(task.id, job)
            rt.poke()
        }
        job.start()
    }

    private suspend fun execute(rt: ObjectiveRuntime, task: TaskRecord) {
        try {
            when (task.kind) {
                TaskKind.WORK -> work.run(rt, task.id)
                TaskKind.REVIEW -> reviews.run(rt, task.id)
                else -> Unit
            }
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            val failed = ctx.change(rt, task.id, WORKING + TaskStatus.QUEUED) {
                it.copy(status = TaskStatus.FAILED, error = "An error inside the app stopped this task (${e::class.simpleName}).", finishedAt = ctx.clock.now())
            }
            failed?.let { ctx.journal.taskChanged(it, "Task failed", Severity.ERROR) }
        }
    }

    /** Runs the synthesis task while it is queued (attempts and retries), as a job the owner can cancel. */
    private suspend fun runSynthesis(rt: ObjectiveRuntime) {
        while (true) {
            val tree = ctx.tasks.getTree(rt.rootId)
            if (SynthesisStep.plannedWork(tree).any { !it.status.isFinished }) return
            val task = tree.firstOrNull { it.kind == TaskKind.SYNTHESIS }?.takeIf { it.status == TaskStatus.QUEUED } ?: return
            coroutineScope {
                val job = launch(start = CoroutineStart.LAZY) { synthesis.run(rt, task.id) }
                rt.taskJobs[task.id] = job
                job.invokeOnCompletion { rt.taskJobs.remove(task.id, job) }
                job.start()
                job.join()
            }
            if (!isRunning(rt.rootId)) return
        }
    }

    private suspend fun objectiveTimedOut(rt: ObjectiveRuntime) {
        val objective = ctx.tasks.get(rt.rootId) ?: return
        val minutes = ctx.limits(objective, ctx.settings.get()).objectiveTimeoutMs / OrchestrationContext.MINUTE_MS
        val limit = "the objective reached its time limit of $minutes minutes"
        val reason = "Stopped: $limit."
        val stopped = rt.lock.withLockNonCancellable {
            if (ctx.tasks.get(rt.rootId)?.status != TaskStatus.RUNNING) return@withLockNonCancellable emptyList()
            val tree = ctx.tasks.getTree(rt.rootId)
            val open = tree.filter { it.kind != TaskKind.OBJECTIVE && it.kind != TaskKind.SYNTHESIS && !it.status.isFinished }
            stoppedByLimit(tree.associateBy { it.id }, open, limit, reason, ctx.clock.now()).also { ctx.tasks.upsertAll(it) }
        }
        stopped.forEach { journalStopped(it) }
        ctx.journal.limitReached(rt.rootId, objective, "the objective's time limit of $minutes minutes was reached; unfinished work was stopped and the final result is written from what exists.")
    }

    /**
     * What a limit makes of [open] tasks: planned work that already holds a result (waiting for
     * its review, or queued for a revision or a new attempt) is used as it is; the rest is
     * cancelled with [cancelled] as the reason. The result a cancelled review was checking is
     * used as it is too. Returns the changed tasks, to be stored together.
     */
    private fun stoppedByLimit(byId: Map<TaskId, TaskRecord>, open: List<TaskRecord>, limit: String, cancelled: String, now: Long): List<TaskRecord> {
        val changes = LinkedHashMap<TaskId, TaskRecord>()
        fun used(task: TaskRecord) = task.copy(
            status = TaskStatus.COMPLETED, finishedAt = now, updatedAt = now, error = null,
            statusDetail = "${if (task.reviews.isEmpty()) "Used without review" else "Used without further review"}: $limit.",
        )
        for (task in open) {
            changes[task.id] = if (task.kind == TaskKind.WORK && task.delegationChain.isEmpty() && task.result != null) used(task)
            else task.copy(status = TaskStatus.CANCELLED, error = cancelled, statusDetail = cancelled, finishedAt = now, updatedAt = now)
        }
        for (review in open.filter { it.kind == TaskKind.REVIEW }) {
            val reviewed = review.parentId?.let { changes[it] ?: byId[it] } ?: continue
            if (reviewed.status == TaskStatus.IN_REVIEW && reviewed.result != null) changes[reviewed.id] = used(reviewed)
        }
        return changes.values.toList()
    }

    private suspend fun journalStopped(task: TaskRecord) {
        if (task.status == TaskStatus.COMPLETED) ctx.journal.taskChanged(task, "Task completed") else ctx.journal.taskChanged(task, "Task cancelled", Severity.WARNING)
        if (task.startedAt != null && task.assignee != null) ctx.journal.countFinished(task)
    }

    /**
     * Ends the objective: COMPLETED with the document, or FAILED when no task produced a usable
     * result. The document is made from what is stored, and the final status is written under
     * the lock only when that is still all there is: returns false, ending nothing, when a task
     * was queued again meanwhile (the driver then goes on), true otherwise.
     */
    private suspend fun finish(rt: ObjectiveRuntime): Boolean {
        val objective = ctx.tasks.get(rt.rootId)?.takeIf { it.status == TaskStatus.RUNNING } ?: return true
        val tree = ctx.tasks.getTree(rt.rootId)
        if (!readyToFinish(tree)) return false
        val planned = SynthesisStep.plannedWork(tree)
        val synthesisTask = tree.firstOrNull { it.kind == TaskKind.SYNTHESIS }
        val anyCompleted = planned.any { it.status == TaskStatus.COMPLETED }
        val names = ctx.agents.getAll().associate { it.id to it.name }
        val written = synthesisTask?.takeIf { it.status == TaskStatus.COMPLETED }?.result?.takeIf { it.isNotBlank() }
        val document = written ?: FinalDocument.assemble(objective, planned, names, synthesisTask?.let { FinalDocument.whyNot(it).trimEnd('.') } ?: "there was no synthesis task")
        if (written != null && anyCompleted && objective.options?.saveKnowledge == true) knowledge.save(objective, document)
        val snapshot = tree.associate { it.id to it.status }
        var again = false
        val done = rt.lock.withLockNonCancellable {
            val current = ctx.tasks.get(rt.rootId)?.takeIf { it.status == TaskStatus.RUNNING } ?: return@withLockNonCancellable null
            val fresh = ctx.tasks.getTree(rt.rootId)
            if (!readyToFinish(fresh) || fresh.any { snapshot[it.id] != it.status }) {
                again = true
                return@withLockNonCancellable null
            }
            val now = ctx.clock.now()
            val ended = if (anyCompleted) {
                current.copy(status = TaskStatus.COMPLETED, result = document, error = null, finishedAt = now, updatedAt = now, statusDetail = "Completed: ${planned.count { t -> t.status == TaskStatus.COMPLETED }} of ${planned.size} tasks produced results.")
            } else {
                current.copy(status = TaskStatus.FAILED, result = document, error = "No task produced a usable result.", finishedAt = now, updatedAt = now, statusDetail = "Failed: no task produced a usable result.")
            }
            ended.also { ctx.tasks.upsert(it) }
        }
        if (again) return false
        done ?: return true
        val director = done.assignee?.let { Participant.Agent(it) } ?: Participant.System
        if (done.status == TaskStatus.COMPLETED) {
            ctx.journal.taskChanged(done, "Objective completed")
            ctx.journal.send(done.rootId, done, director, Participant.Owner, MessageType.COMPLETION, "Final result ready: ${done.title}")
        } else {
            ctx.journal.taskChanged(done, "Objective failed", Severity.WARNING)
            ctx.journal.send(done.rootId, done, director, Participant.Owner, MessageType.FAILURE, "${done.title}: ${done.error}")
        }
        return true
    }

    private suspend fun internalFailure(rt: ObjectiveRuntime, e: Exception) {
        val reason = "An error inside the app stopped this objective (${e::class.simpleName})."
        val failed = rt.lock.withLockNonCancellable {
            val objective = ctx.tasks.get(rt.rootId)?.takeIf { !it.status.isFinished } ?: return@withLockNonCancellable null
            val now = ctx.clock.now()
            val open = ctx.tasks.getTree(rt.rootId).filter { it.id != rt.rootId && !it.status.isFinished }
                .map { it.copy(status = TaskStatus.CANCELLED, error = reason, finishedAt = now, updatedAt = now) }
            val updated = objective.copy(status = TaskStatus.FAILED, error = reason, statusDetail = reason, finishedAt = now, updatedAt = now)
            ctx.tasks.upsertAll(open + updated)
            updated
        } ?: return
        ctx.journal.taskChanged(failed, "Objective failed", Severity.ERROR)
    }

    private suspend fun isRunning(rootId: TaskId): Boolean = ctx.tasks.get(rootId)?.status == TaskStatus.RUNNING

    companion object {
        /** Opens the error of a task cancelled because a task it depends on did not complete; the owner's retry re-queues such tasks. */
        const val DEPENDENCY_PREFIX = "Not started: it depends on "

        /** How often a running objective stores that it is still running. */
        const val HEARTBEAT_MS = 60_000L
    }
}
