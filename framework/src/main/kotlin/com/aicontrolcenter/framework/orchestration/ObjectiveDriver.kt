package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import com.aicontrolcenter.framework.orchestration.OrchestrationContext.Companion.WORKING
import com.aicontrolcenter.framework.orchestration.OrchestrationContext.Companion.isScheduled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
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
 * own for that, so the owner always gets a result). A result that is already there is not
 * thrown away by a limit: it is used as it is, and its status line says it was not (further)
 * reviewed, or not revised, and why; only a result its review rejected is not used.
 * Finishing respects the deadline too: with no time left, knowledge is not saved.
 *
 * While it runs, the driver writes a heartbeat onto the objective every [HEARTBEAT_MS], so that
 * after a crash the time the objective had left can be read from its last stored write.
 *
 * The objective is finished under its lock, against what is stored at that moment: when the
 * owner asked for a task to run again meanwhile, the driver goes on with it instead.
 *
 * Each time the owner runs one of its tasks again, the objective starts a new run (its
 * [TaskRecord.attempt] counts them, as it counts planning attempts before there is a plan).
 * Saving knowledge and announcing the ending happen once for each run, judged by the records of
 * that run, never by times or by the order of messages.
 *
 * Nothing that goes wrong inside escapes: an unexpected error fails this objective only, unless
 * its work and synthesis have finished: then a store error never costs it its result. An error
 * while saving knowledge ends that round with a note saying what is stored; one in storing the
 * final status is tried once more, and then the objective is left for the start-up recovery to
 * finish (only bookkeeping is left, which runs no agent).
 */
internal class ObjectiveDriver(
    private val ctx: OrchestrationContext,
    private val planning: PlanningStep,
    private val work: WorkSteps,
    private val reviews: ReviewSteps,
    private val synthesis: SynthesisStep,
    private val knowledge: KnowledgeKeeper,
) {
    /** [atStartUp]: the start-up recovery found only bookkeeping left ([DefaultOrchestrator.recoverInterrupted]). */
    suspend fun drive(rt: ObjectiveRuntime, atStartUp: Boolean = false) {
        try {
            coroutineScope {
                val beat = launch { heartbeat(rt) }
                try {
                    body(rt, atStartUp)
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

    private suspend fun body(rt: ObjectiveRuntime, atStartUp: Boolean) {
        if (!isRunning(rt.rootId)) return
        if (ctx.tasks.getTree(rt.rootId).none { it.kind == TaskKind.WORK || it.kind == TaskKind.SYNTHESIS }) {
            val objective = ctx.tasks.get(rt.rootId) ?: return
            if (objective.deadlineAt != null && ctx.clock.now() >= objective.deadlineAt!!) {
                planning.failObjective(rt, "The objective reached its time limit before the Director could make a plan.")
                return
            }
            if (!planning.plan(rt)) return
        }
        // Only bookkeeping was left at start-up: finish now, and say so. A task queued again meanwhile runs as usual.
        if (atStartUp && readyToFinish(ctx.tasks.getTree(rt.rootId)) && finish(rt, atStartUp = true)) return
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
            ctx.journal.limitReached(rt.rootId, null, "$limit; tasks not yet started were cancelled, results waiting for a review or a revision are used as they are, and results their review rejected are not used.")
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
     * What a limit makes of [open] tasks. Planned work that holds a result is settled by what is
     * known of that result:
     *  - its latest verdict judged it and REJECTED it: it is not used, it FAILED;
     *  - its latest verdict judged it and asked for changes not yet made: it is used without
     *    them, which its status line says ([UNREVISED]) and the synthesis brief shows;
     *  - otherwise (no verdict on it yet): it is used as it is.
     * The rest is cancelled with [cancelled] as the reason. The result a cancelled review was
     * checking is settled the same way. Returns the changed tasks, to be stored together.
     */
    private fun stoppedByLimit(byId: Map<TaskId, TaskRecord>, open: List<TaskRecord>, limit: String, cancelled: String, now: Long): List<TaskRecord> {
        val changes = LinkedHashMap<TaskId, TaskRecord>()
        fun settled(task: TaskRecord): TaskRecord {
            val verdict = task.reviews.lastOrNull()
            // A result waiting for its first review of this round has no verdict on it yet; one waiting for the Director's arbitration has.
            val judged = verdict != null && (task.status != TaskStatus.IN_REVIEW || byId.values.any {
                it.kind == TaskKind.REVIEW && it.parentId == task.id && !it.status.isFinished && ReviewKind.of(it) == ReviewKind.ARBITRATION
            })
            return when {
                judged && verdict!!.decision == ReviewDecision.REJECTED -> "Rejected by review; stopped by a limit: $limit.".let {
                    task.copy(status = TaskStatus.FAILED, finishedAt = now, updatedAt = now, error = it, statusDetail = it)
                }
                judged && verdict!!.decision == ReviewDecision.REVISE ->
                    task.copy(status = TaskStatus.COMPLETED, finishedAt = now, updatedAt = now, error = null, statusDetail = "$UNREVISED: $limit.")
                else -> task.copy(
                    status = TaskStatus.COMPLETED, finishedAt = now, updatedAt = now, error = null,
                    statusDetail = "${if (task.reviews.isEmpty()) "Used without review" else "Used without further review"}: $limit.",
                )
            }
        }
        for (task in open) {
            changes[task.id] = if (task.kind == TaskKind.WORK && task.delegationChain.isEmpty() && task.result != null) settled(task)
            else task.copy(status = TaskStatus.CANCELLED, error = cancelled, statusDetail = cancelled, finishedAt = now, updatedAt = now)
        }
        for (review in open.filter { it.kind == TaskKind.REVIEW }) {
            val reviewed = review.parentId?.let { changes[it] ?: byId[it] } ?: continue
            if (reviewed.status == TaskStatus.IN_REVIEW && reviewed.result != null) changes[reviewed.id] = settled(reviewed)
        }
        return changes.values.toList()
    }

    private suspend fun journalStopped(task: TaskRecord) {
        when (task.status) {
            TaskStatus.COMPLETED -> ctx.journal.taskChanged(task, "Task completed")
            TaskStatus.FAILED -> ctx.journal.taskChanged(task, "Task failed", Severity.WARNING)
            else -> ctx.journal.taskChanged(task, "Task cancelled", Severity.WARNING)
        }
        if (task.startedAt != null && task.assignee != null) ctx.journal.countFinished(task)
    }

    /**
     * Ends the objective: COMPLETED with the document, or FAILED when no task produced a usable
     * result. The document is made from what is stored, and the final status is written under
     * the lock only when that is still all there is: returns false, ending nothing, when a task
     * was queued again meanwhile (the driver then goes on), true otherwise. Saving knowledge is
     * a bounded step of its own ([saveKnowledge]); the ending is announced right before the final
     * status is written ([announce]). A store error on that write is tried once more, and then
     * the objective is left for the start-up recovery ([storeEnding]). [atStartUp]: the log says
     * it was finished at start-up.
     */
    private suspend fun finish(rt: ObjectiveRuntime, atStartUp: Boolean = false): Boolean {
        val objective = ctx.tasks.get(rt.rootId)?.takeIf { it.status == TaskStatus.RUNNING } ?: return true
        val tree = ctx.tasks.getTree(rt.rootId)
        if (!readyToFinish(tree)) return false
        val outcome = outcomeOf(objective, tree)
        val note = outcome.round?.takeIf { outcome.completed > 0 && objective.options?.saveKnowledge == true }?.let { saveKnowledge(objective, outcome, it) }
        val snapshot = tree.associate { it.id to it.status }
        var again = false
        val ended = rt.lock.withLockNonCancellable {
            val current = ctx.tasks.get(rt.rootId)?.takeIf { it.status == TaskStatus.RUNNING } ?: return@withLockNonCancellable null
            val fresh = ctx.tasks.getTree(rt.rootId)
            if (!readyToFinish(fresh) || fresh.any { snapshot[it.id] != it.status }) {
                again = true
                return@withLockNonCancellable null
            }
            ended(current, outcome, note).also { announce(it) }.takeIf { storeEnding(it) }
        }
        if (atStartUp && ended != null) ctx.journal.taskChanged(ended, FINISHED_AT_START_UP)
        return !again
    }

    /**
     * Stores the objective's final status ([ended]), trying once more after a store error. When
     * that fails too, the objective is left as it is stored, running with only bookkeeping left,
     * for the start-up recovery to finish ([leftForStartUp]); returns false then.
     */
    private suspend fun storeEnding(ended: TaskRecord): Boolean {
        var error: Exception? = null
        repeat(2) {
            try {
                ctx.tasks.upsert(ended)
                return true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e
            }
        }
        leftForStartUp(ended, error!!)
        return false
    }

    /** Logs that an error ([e]) kept [objective] from being finished now: the start-up recovery finishes it, its result kept. */
    private suspend fun leftForStartUp(objective: TaskRecord, e: Exception) = ctx.journal.warn(
        objective.rootId, objective, NOT_FINISHED,
        "An error inside the app (${e::class.simpleName}) kept \"${objective.title}\" from being finished. Its work is done and nothing of it is lost: only bookkeeping is left.",
    )

    /**
     * Saves what the objective taught the team from its final document, the one of run [round]:
     * each final result is a round of its own, so a task run again (on a finished objective, or
     * while it finishes) has what is new saved, and a finish with the result a round already saved
     * does nothing new. A document the Director could not write (assembled from the results) has
     * the team's task memories kept, and its note says the summary was not updated to it.
     * A round is a bounded step:
     *  - When it ends, that is recorded right after ([KNOWLEDGE_DONE], a STATUS message from the
     *    system, with the note it ended with); with that record there, the round is not saved
     *    again, and its note is the one used, however often the objective finishes with that result.
     *  - Each attempt is recorded before it starts ([KNOWLEDGE_SAVING]), with the revision the
     *    summary had when the round began, so an attempt the app stopped counts, and a summary an
     *    earlier attempt of the round updated is told from one an earlier round left; with the
     *    task attempt limit used up in the round, it is skipped. An attempt the owner stopped
     *    (pausing or cancelling the objective) is recorded as such ([KNOWLEDGE_STOPPED]) and
     *    does not count.
     *  - An attempt repeats nothing already stored ([KnowledgeKeeper]).
     *  - It runs within the objective's time left (and one task's time limit); with no time
     *    left, it is skipped.
     *  - A store error ends it: it is logged, and the note says what is stored and that an error
     *    stopped the rest ([failedWith]). The objective then finishes as it would have.
     * Returns the note for the objective's status when knowledge was not (fully) saved, or the
     * summary holds the owner's version, else null. The note says what is stored ([storedNote]).
     */
    private suspend fun saveKnowledge(objective: TaskRecord, outcome: Outcome, round: Int): String? {
        val document = outcome.document
        val written = outcome.written
        val note = try {
            val records = ctx.messages.list(objective.id).filter { it.from == Participant.System && it.type == MessageType.STATUS }
            records.lastOrNull { isRecord(it.body, KNOWLEDGE_DONE, round) }?.let { return noteOf(it.body, round) }
            val limits = ctx.limits(objective, ctx.settings.get())
            val attempts = records.filter { isRecord(it.body, KNOWLEDGE_SAVING, round) }
            val tried = attempts.size - records.count { isRecord(it.body, KNOWLEDGE_STOPPED, round) }
            val left = (objective.deadlineAt ?: Long.MAX_VALUE) - ctx.clock.now()
            val skipped = when {
                left <= 0 -> "the objective had no time left"
                tried >= limits.maxTaskAttempts -> "saving it was interrupted $tried time(s), and no attempts are left"
                else -> null
            }
            if (skipped != null) return storedNote(objective, document, written, skipped)
            // The summary's revision when the round began: read before its first attempt, then from that attempt's record.
            val startRevision = when {
                !written -> null
                attempts.isEmpty() -> summaryRevision(objective)
                else -> REVISION.find(attempts.first().body)?.groupValues?.get(1)?.toIntOrNull()
            }
            val revision = startRevision?.let { ", summary at revision $it" }.orEmpty()
            ctx.journal.send(objective.id, objective, Participant.System, Participant.Owner, MessageType.STATUS, "$KNOWLEDGE_SAVING ${resultOf(round)} (attempt ${tried + 1} of ${limits.maxTaskAttempts}$revision).")
            // What the attempt did; null: the round reached its time limit.
            val saved = try {
                withTimeoutOrNull(minOf(left, limits.taskTimeoutMs)) { knowledge.save(objective, document, written, finalResult(round), startRevision) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { recordIfStoppedByOwner(objective, round) }
                throw e
            }
            when {
                saved == null -> storedNote(objective, document, written, "saving it reached its time limit")
                saved.error != null -> storedNote(objective, document, written, failedWith(saved.error), errored = true)
                !written -> storedNote(objective, document, written, NOT_WRITTEN)
                saved.ownersVersion -> storedNote(objective, document, written, null)
                else -> null
            }
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            ctx.journal.warn(objective.id, objective, "Knowledge not saved", "${failedWith(e).replaceFirstChar { it.uppercase() }} stopped saving what the team learned ${resultOf(round)}.")
            storedNote(objective, document, written, failedWith(e), errored = true)
        }
        try {
            ctx.journal.send(objective.id, objective, Participant.System, Participant.Owner, MessageType.STATUS, "$KNOWLEDGE_DONE ${resultOf(round)}${note?.let { ": $it" } ?: "."}")
        } catch (e: Exception) {
            rethrowIfCancelled(e) // the round ended all the same; without its record, a later finish saves again, repeating nothing
            ctx.journal.warn(objective.id, objective, "Knowledge record not stored", "${failedWith(e).replaceFirstChar { it.uppercase() }} kept the end of saving what the team learned ${resultOf(round)} from being recorded. What was saved is kept.")
        }
        return note
    }

    /** Why a store error ([e]) stopped saving knowledge, for its note: "an error inside the app (IllegalStateException)". */
    private fun failedWith(e: Exception) = "an error inside the app (${e::class.simpleName})"

    /** The revision of the objective's summary, when one is stored; null when none is, or the store cannot tell. */
    private suspend fun summaryRevision(objective: TaskRecord): Int? = try {
        knowledge.savedSummary(objective)?.revision
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    /**
     * After a save was stopped: when the owner stopped it (the objective is stored as paused or
     * cancelled, which the owner's command writes before it stops the job), records that the
     * attempt does not count. The app dying or closing leaves the objective running: that counts.
     */
    private suspend fun recordIfStoppedByOwner(objective: TaskRecord, round: Int) {
        try {
            val status = ctx.tasks.get(objective.id)?.status
            if (status == TaskStatus.PAUSED || status == TaskStatus.CANCELLED) {
                ctx.journal.send(objective.id, objective, Participant.System, Participant.Owner, MessageType.STATUS, "$KNOWLEDGE_STOPPED ${resultOf(round)} by the owner; that attempt does not count.")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e // a store that fails here only leaves the attempt counted
        }
    }

    /**
     * The note on the knowledge stored for [document] ([written]: by the Director), after saving
     * stopped because of [why] (null: it ended, the summary holding the owner's version), from
     * what is stored; also logged as a warning when something was not saved. Null when all of it
     * is stored all the same ([errored]: an error stopped it, so the owner may not have been told
     * of the summary in this round: the note then names it).
     */
    private suspend fun storedNote(objective: TaskRecord, document: String, written: Boolean, why: String?, errored: Boolean = false): String? {
        val note = noteOn(knowledge.stored(objective, document), why, written, errored) ?: return null
        note.warning?.let { ctx.journal.warn(objective.id, objective, it, note.text) }
        return note.text
    }

    /** A note for the objective's status, and the title of the warning to log it under (null: nothing went wrong). */
    private class Note(val text: String, val warning: String?)

    /**
     * What [stored] says about the knowledge of a document ([written]: by the Director), after
     * saving stopped because of [why] (null: it ended, or the objective ended early, which its
     * status says): nothing when everything is stored and the owner was told where the summary
     * is. Otherwise the note names the summary memory and where it is, says when it holds the
     * owner's version (which is as it should be), and says the task memories may not all have
     * been kept unless the store shows they were. With no summary, it says the task memories
     * were kept when there were some and all were (or the document was assembled, when none is
     * left unkept). [errored]: an error stopped saving, which may have kept the owner from being
     * told of the summary (in this round): the note names it.
     */
    private fun noteOn(stored: KnowledgeKeeper.Stored, why: String?, written: Boolean = true, errored: Boolean = false): Note? {
        val tail = why?.let { ": $it." } ?: "."
        val summary = stored.summary ?: return if (stored.kept == true && (!written || stored.keptSome)) {
            Note("$PARTLY_SAVED: the team's task memories were kept, but no summary was saved$tail", PART_TITLE)
        } else {
            Note("$NOT_SAVED$tail", "Knowledge not saved")
        }
        val named = "${stored.place} (memory ${summary.id.value})"
        val behind = if (stored.ownersVersion) "the summary in $named holds the owner's version" else "the summary in $named was not updated to this result"
        return when {
            stored.current && stored.kept == true -> if (stored.told && !errored) null else Note("$SAVED: the summary is in $named.", null)
            stored.current -> Note("$PARTLY_SAVED: the summary was saved to $named, but the team's task memories may not all have been kept$tail", PART_TITLE)
            stored.ownersVersion && stored.kept == true -> Note("$SAVED: $behind.", null)
            stored.kept == true -> Note("$PARTLY_SAVED: the team's task memories were kept, but $behind$tail", PART_TITLE)
            else -> Note("$PARTLY_SAVED: $behind, and the team's task memories may not all have been kept$tail", PART_TITLE)
        }
    }

    /**
     * Ends the objective from what is stored, running nothing more: for an objective the app
     * stopped again and again with no progress in between (see [DefaultOrchestrator]'s recovery,
     * at start-up, which the log says). Open work is settled as a limit settles it, with [why] as
     * the reason; the document is the Director's when it was written, else assembled; knowledge
     * is not saved, and the status says what of it is stored ([knowledgeAtEnd], read before the
     * lock is taken).
     */
    suspend fun conclude(rt: ObjectiveRuntime, why: String) {
        val before = ctx.tasks.get(rt.rootId)?.takeIf { !it.status.isFinished } ?: return
        val beforeTree = ctx.tasks.getTree(rt.rootId)
        var note = knowledgeAtEnd(before, beforeTree)
        val (ended, stopped) = rt.lock.withLockNonCancellable {
            val objective = ctx.tasks.get(rt.rootId)?.takeIf { !it.status.isFinished } ?: return@withLockNonCancellable null
            val now = ctx.clock.now()
            val tree = ctx.tasks.getTree(rt.rootId)
            // A command of the owner's changed the result meanwhile (it rarely can): read what is stored for it.
            if (resultKey(objective, tree) != resultKey(before, beforeTree)) note = knowledgeAtEnd(objective, tree)
            val open = tree.filter { it.id != rt.rootId && !it.status.isFinished }
            val stopped = stoppedByLimit(tree.associateBy { it.id }, open.filter { it.kind != TaskKind.SYNTHESIS }, why, "Stopped: $why.", now) +
                open.filter { it.kind == TaskKind.SYNTHESIS }.map { it.copy(status = TaskStatus.CANCELLED, error = "Stopped: $why.", statusDetail = "Stopped: $why.", finishedAt = now, updatedAt = now) }
            val byId = stopped.associateBy { it.id }
            val after = tree.map { byId[it.id] ?: it }
            val ended = ended(objective, outcomeOf(objective, after), "Ended early: $why.${note?.let { " ${it.text}" }.orEmpty()}")
            announce(ended)
            ctx.tasks.upsertAll(stopped + ended)
            ended to stopped
        } ?: return
        stopped.forEach { journalStopped(it) }
        note?.let { n -> n.warning?.let { ctx.journal.warn(rt.rootId, before, it, n.text) } }
        ctx.journal.taskChanged(ended, FINISHED_AT_START_UP)
    }

    /**
     * The note on knowledge for an objective ended without saving it ([conclude]): none unless
     * the owner allowed saving and its synthesis has ended; else the note the round for that
     * document ended with, when it ended; else what is stored.
     */
    private suspend fun knowledgeAtEnd(objective: TaskRecord, tree: List<TaskRecord>): Note? {
        if (objective.options?.saveKnowledge != true) return null
        val outcome = outcomeOf(objective, tree)
        val round = outcome.round?.takeIf { outcome.completed > 0 } ?: return null
        val done = ctx.messages.list(objective.id).lastOrNull { it.from == Participant.System && it.type == MessageType.STATUS && isRecord(it.body, KNOWLEDGE_DONE, round) }
        // The warning was logged when that round ended.
        if (done != null) return noteOf(done.body, round)?.let { Note(it, null) }
        return noteOn(knowledge.stored(objective, outcome.document), null, outcome.written)
    }

    /** What decides the knowledge note: the run, and how its synthesis stands. */
    private fun resultKey(objective: TaskRecord, tree: List<TaskRecord>) = objective.attempt to tree.firstOrNull { it.kind == TaskKind.SYNTHESIS }?.status

    /**
     * The planned work, the final document made from what is stored, whether the Director wrote
     * it, and the knowledge round: the objective's run, once its synthesis has ended (it ends at
     * most once in a run: only a task run again, which starts a new run, queues it again).
     */
    private class Outcome(val planned: List<TaskRecord>, val document: String, val written: Boolean, val round: Int?) {
        val completed = planned.count { it.status == TaskStatus.COMPLETED }
    }

    private suspend fun outcomeOf(objective: TaskRecord, tree: List<TaskRecord>): Outcome {
        val planned = SynthesisStep.plannedWork(tree)
        val synthesisTask = tree.firstOrNull { it.kind == TaskKind.SYNTHESIS }
        val written = synthesisTask?.takeIf { it.status == TaskStatus.COMPLETED && !it.result.isNullOrBlank() }
        val names = ctx.agents.getAll().associate { it.id to it.name }
        val document = written?.result ?: FinalDocument.assemble(objective, planned, names, synthesisTask?.let { FinalDocument.whyNot(it).trimEnd('.') } ?: "there was no synthesis task")
        return Outcome(planned, document, written != null, synthesisTask?.takeIf { it.status.isFinished }?.let { objective.attempt })
    }

    /** [current] ended with [outcome]; [note] (on knowledge, or why it ended early) follows its status sentence. */
    private fun ended(current: TaskRecord, outcome: Outcome, note: String?): TaskRecord {
        val now = ctx.clock.now()
        val tail = note?.let { " $it" }.orEmpty()
        return if (outcome.completed > 0) {
            current.copy(
                status = TaskStatus.COMPLETED, result = outcome.document, error = null, finishedAt = now, updatedAt = now,
                statusDetail = "Completed: ${outcome.completed} of ${outcome.planned.size} tasks produced results.$tail",
            )
        } else {
            current.copy(
                status = TaskStatus.FAILED, result = outcome.document, error = "No task produced a usable result.", finishedAt = now, updatedAt = now,
                statusDetail = "Failed: no task produced a usable result.$tail",
            )
        }
    }

    /**
     * Tells the owner the objective ended ("Final result ready", or why it failed) and logs it,
     * once for each run ([done]'s attempt): the message's id names the run ([announcementId]),
     * so whether it was sent is read exactly. It runs under the objective's lock right before the
     * final status is stored: the app dying before that store leaves the objective unfinished,
     * so the restart finishes it again and finds the ending announced; dying after it leaves
     * nothing to announce. The log entry is written first, so a death between the two repeats
     * it rather than losing it. A store that fails here is logged, and the objective ends all the
     * same: a message that could not be stored never costs the owner the result.
     */
    private suspend fun announce(done: TaskRecord) {
        try {
            val id = announcementId(done.rootId, done.attempt)
            if (ctx.messages.list(done.rootId).any { it.id == id }) return
            val director = done.assignee?.let { Participant.Agent(it) } ?: Participant.System
            if (done.status == TaskStatus.COMPLETED) {
                ctx.journal.taskChanged(done, "Objective completed")
                ctx.journal.send(done.rootId, done, director, Participant.Owner, MessageType.COMPLETION, "Final result ready: ${done.title}", id = id)
            } else {
                ctx.journal.taskChanged(done, "Objective failed", Severity.WARNING)
                ctx.journal.send(done.rootId, done, director, Participant.Owner, MessageType.FAILURE, "${done.title}: ${done.error}", id = id)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ctx.journal.warn(done.rootId, done, "Ending not announced", "The message store failed (${e::class.simpleName}): the owner was not told that the objective ended. Its result is kept.")
        }
    }

    /**
     * Fails the objective after an unexpected error ([e]), unless its work and synthesis have
     * finished ([onlyBookkeeping]): such an objective keeps its result, left for the start-up
     * recovery to finish ([leftForStartUp]).
     */
    private suspend fun internalFailure(rt: ObjectiveRuntime, e: Exception) {
        val reason = "An error inside the app stopped this objective (${e::class.simpleName})."
        var left: TaskRecord? = null
        val failed = rt.lock.withLockNonCancellable {
            val objective = ctx.tasks.get(rt.rootId)?.takeIf { !it.status.isFinished } ?: return@withLockNonCancellable null
            val now = ctx.clock.now()
            val tree = ctx.tasks.getTree(rt.rootId)
            if (onlyBookkeeping(tree, rt.rootId)) {
                left = objective
                return@withLockNonCancellable null
            }
            val open = tree.filter { it.id != rt.rootId && !it.status.isFinished }
                .map { it.copy(status = TaskStatus.CANCELLED, error = reason, finishedAt = now, updatedAt = now) }
            val updated = objective.copy(status = TaskStatus.FAILED, error = reason, statusDetail = reason, finishedAt = now, updatedAt = now)
            ctx.tasks.upsertAll(open + updated)
            updated
        }
        left?.let { leftForStartUp(it, e) }
        failed?.let { ctx.journal.taskChanged(it, "Objective failed", Severity.ERROR) }
    }

    private suspend fun isRunning(rootId: TaskId): Boolean = ctx.tasks.get(rootId)?.status == TaskStatus.RUNNING

    companion object {
        /** Opens the error of a task cancelled because a task it depends on did not complete; the owner's retry re-queues such tasks. */
        const val DEPENDENCY_PREFIX = "Not started: it depends on "

        /** How often a running objective stores that it is still running. */
        const val HEARTBEAT_MS = 60_000L

        /** Opens the status line of a result a limit made the objective use without the changes its review asked for. */
        const val UNREVISED = "Used without the changes its review asked for"

        /** Opens the record (a STATUS message from the system) written before each attempt to save knowledge from a final result. */
        const val KNOWLEDGE_SAVING = "Saving what the team learned"

        /** Opens the record written when saving knowledge from a final result has ended: it is not saved again from that result. */
        const val KNOWLEDGE_DONE = "Done saving what the team learned"

        /** Opens the record written when the owner stopped an attempt to save knowledge: that attempt does not count. */
        const val KNOWLEDGE_STOPPED = "Stopped saving what the team learned"

        /** Opens the objective's status note when part of its knowledge is stored: the note names the summary memory. */
        const val PARTLY_SAVED = "Knowledge was saved in part"

        /** Opens the objective's status note when no summary is stored. */
        const val NOT_SAVED = "Knowledge was not saved"

        /** Opens the objective's status note when all of its knowledge is stored but the owner was not told where the summary is. */
        const val SAVED = "Knowledge was saved"

        private const val PART_TITLE = "Knowledge saved in part"

        /** Opens the log entry of an objective the start-up recovery finished, only bookkeeping being left. */
        const val FINISHED_AT_START_UP = "Objective finished at start-up"

        /** The warning logged when an error kept a finished objective's end from being stored: the start-up recovery finishes it. */
        const val NOT_FINISHED = "Could not finish the objective; it will be finished at the next start"

        /**
         * What is left of an objective needs no agent and no model: it has a plan, and every task of
         * it has finished, so its final document is written or can be assembled, and only saving
         * knowledge, announcing the ending and storing it remain.
         */
        fun onlyBookkeeping(tree: List<TaskRecord>, rootId: TaskId): Boolean =
            tree.any { it.kind == TaskKind.WORK || it.kind == TaskKind.SYNTHESIS } && tree.all { it.id == rootId || it.status.isFinished }

        /** Why a document assembled from the results has no summary saved from it. */
        const val NOT_WRITTEN = "the Director could not write the final document"

        /** The summary's revision when a round began, in its [KNOWLEDGE_SAVING] records. */
        private val REVISION = Regex(", summary at revision (\\d+)\\)")

        /** Names the final result of a run (the knowledge round), counted from 1. */
        fun finalResult(round: Int) = "final result ${round + 1}"

        /** Names the final result a knowledge record is about. */
        fun resultOf(round: Int) = "from ${finalResult(round)}"

        /** [body] is a knowledge record opened by [kind] (one of the openers above) about the final result of [round]. */
        fun isRecord(body: String, kind: String, round: Int): Boolean {
            val head = "$kind ${resultOf(round)}"
            return body.startsWith(head) && body.getOrNull(head.length).let { it == ' ' || it == '.' || it == ':' }
        }

        /** The note a [KNOWLEDGE_DONE] record of [round] ended with; null when there is none (all was saved). */
        private fun noteOf(body: String, round: Int): String? =
            body.removePrefix("$KNOWLEDGE_DONE ${resultOf(round)}").takeIf { it.startsWith(": ") }?.substring(2)?.trim()?.takeIf { it.isNotEmpty() }

        /** The owner's message asking for a task to run again, stored right after the new run is. */
        fun runAgain(title: String) = "Run \"$title\" again."

        fun isRunAgain(message: AgentMessage): Boolean =
            message.from == Participant.Owner && message.type == MessageType.STATUS && message.body.startsWith("Run \"") && message.body.endsWith("\" again.")

        /** The id of the message announcing how run [run] of objective [rootId] ended: one for each run, which the message store keeps once. */
        fun announcementId(rootId: TaskId, run: Int) = MessageId("${rootId.value}_end_$run")

        /**
         * [message] announces how the objective ended ([announce]): like the Director's notice about
         * the summary ([KnowledgeKeeper.isNotice]), not progress for the cap on restarts.
         */
        fun isAnnouncement(message: AgentMessage): Boolean =
            message.taskId == message.rootId && message.from != Participant.Owner && (message.type == MessageType.COMPLETION || message.type == MessageType.FAILURE)
    }
}
