package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.health.TimedWaits
import com.aicontrolcenter.core.llm.ProviderRechecks
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.registry.AgentStatsLog
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.taskId
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.framework.agents.AgentRunner
import com.aicontrolcenter.framework.agents.DefaultAgentActivityMonitor
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import com.aicontrolcenter.framework.operator.OperatorTaskHooks
import com.aicontrolcenter.framework.orchestration.OrchestrationContext.Companion.WORKING
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The Director at work: see [Orchestrator] for the contract. How it is built:
 *
 *  - The task store is the truth. An objective's tasks, their statuses, results, reviews and
 *    usage are stored before anything acts on them; [ObjectiveDriver] decides every next step
 *    from what is stored, so pausing, resuming and recovering after a crash all run the same code.
 *  - Each objective runs in its own job under [scope] (the framework's application scope), as a
 *    child of a supervisor: one objective failing, or being cancelled, never touches another.
 *  - The owner's commands write the new statuses first, under the objective's lock, and only
 *    then stop the jobs involved, which therefore never write a status on their way out.
 *  - The objective's time limit counts only the time it runs. A stopped objective (PAUSED or
 *    INTERRUPTED) keeps the time it had left as the difference between its deadline and the
 *    moment it was stored as stopped (its updatedAt); resuming gives it exactly that again. After
 *    a crash, that moment is the objective's last stored write (the driver stores a heartbeat),
 *    and every task that was running counts the interrupted run as one of its attempts. A crash
 *    while no task runs (while knowledge is saved, say) counts too: the objective is restarted
 *    at most [MAX_RESTARTS_WITHOUT_PROGRESS] times in a row without progress, then ended from
 *    what exists. An objective with only bookkeeping left (no agent or model work: its final
 *    document is written or can be assembled) is finished at start-up, whatever the setting for
 *    resuming: it runs no agent and sends nothing off the device.
 *  - Running a task again starts a new run of the objective, counted by its attempt: its
 *    ending is announced again, once ([ObjectiveDriver]).
 *  - Agents run through [AgentRunner] with briefs written by [BriefWriter], so every protection
 *    of a run (per-task model and tool call limits, time limit, tool authorisation, content as
 *    data) applies to orchestrated work exactly as to a chat.
 *  - Waiting for the owner does not use up time: the objective's and the tasks' limits are
 *    pausable ([com.aicontrolcenter.core.tasks.withPausableTimeout]), a task waiting for an
 *    answer is WAITING_FOR_OWNER (working, as WAITING_FOR_AGENT is: pausing, cancelling and the
 *    crash recovery treat both alike), and the objective's stored deadline moves with the pauses.
 *  - With [operatorHooks], a task that ends takes its permissions with it (its pending asks are
 *    withdrawn, its TASK and ONCE grants revoked), and the first recovery at start-up withdraws
 *    every pending request that no live call waits for, since no waiter survived the stop; a
 *    request raised by work restarted before the recovery ran keeps its waiter and stays open.
 *  - Phase 6 (WP7): today's limit of model answers, tokens or money pauses the objective, never
 *    fails it, and uses no attempt (rule 6); a provider that is only not answering, or asks the
 *    app to slow down, makes the work wait and go on by itself ([timedWaits], R4 part 1), never
 *    moving it to an agent farther from the phone (rule 25); the objective's options are copied
 *    wherever a record is rebuilt, never made anew (F12); starting an objective is refused while a
 *    backup is restored ([MaintenanceGate.RESTORING]).
 *
 * Wiring: build it, then register the team tools with [TeamTools.registerAll], then call
 * [recoverInterrupted] once at start-up before objectives are started.
 */
class DefaultOrchestrator(
    tasks: TaskRepository,
    messages: MessageRepository,
    conversations: ConversationRepository,
    agents: AgentStatsLog,
    private val directory: RegistryDirectory,
    private val settings: SettingsView,
    runner: AgentRunner,
    toolRegistry: ToolRegistry,
    monitor: DefaultAgentActivityMonitor,
    activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    /** The framework's application scope: objectives outlive the screens that started them. */
    scope: CoroutineScope,
    /** Where the Director saves knowledge when the owner allows it; null: nothing is saved. */
    memory: MemoryService? = null,
    /** Measures the objectives' pausable time limits; tests pass the test scheduler's (`testTimeSource`). */
    timeSource: TimeSource = TimeSource.Monotonic,
    /** Ends a task's permissions with it and withdraws stale requests at start-up; null without the phone operator. */
    private val operatorHooks: OperatorTaskHooks? = null,
    /**
     * Looks again at a provider that is not answering before work waits for it (D28). The agent side only: the
     * orchestrator never holds the owner's provider manager (security review of wave 1, F3).
     */
    rechecks: ProviderRechecks = ProviderRechecks.None,
    /** The activity log, for the few entries written at most once however often the app dies around them (D20). */
    activityLog: ActivityLog? = null,
    /** The owner's time zone, for the clock times in status lines ("trying again at 10:45"). */
    zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** True while a backup is restored (the maintenance gate's `busy`): no objective starts meanwhile. */
    private val restoring: StateFlow<Boolean> = MutableStateFlow(false),
) : Orchestrator {
    private val runtimes = Runtimes()
    private val transcripts = TaskTranscripts(conversations, ids, clock)
    private val journal = ObjectiveJournal(
        messages, agents, activity, ids, clock,
        spaceName = { id -> memory?.observeSpaces()?.first()?.firstOrNull { it.id == id }?.name },
        log = activityLog,
    )
    private val ctx = OrchestrationContext(
        CappedTaskStore(operatorHooks?.let { TaskEndingStore(tasks, it) } ?: tasks), messages, agents, directory, settings, toolRegistry, memory,
        monitor, journal, transcripts, StepRunner(runner, directory, transcripts, journal), ids, clock, runtimes, timeSource, rechecks, zone,
    )

    /** Whether the start-up withdrawal of pending requests has run: once per process. */
    private val startedUp = AtomicBoolean(false)
    private val work = WorkSteps(ctx)
    private val driver = ObjectiveDriver(ctx, PlanningStep(ctx), work, ReviewSteps(ctx, work), SynthesisStep(ctx), KnowledgeKeeper(ctx))

    /** What the team tools call; see [TeamTools.registerAll]. */
    internal val desk = TeamDesk(ctx, work)

    /**
     * Where objectives run: the application scope under a supervisor of their own, so a failing
     * objective cancels neither the others nor the application scope, and closing the
     * application scope still stops them all.
     */
    private val objectives = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    override val running: StateFlow<Set<TaskId>> = runtimes.running
    override val assignments: StateFlow<Map<AgentId, TaskId>> = runtimes.assignments

    /** Work waiting to be tried again after provider trouble (R4): when the next is due, and a way to make it look again. */
    override val timedWaits: TimedWaits get() = ctx.waits

    override suspend fun startObjective(text: String, options: ObjectiveOptions): TaskRecord {
        val objectiveText = text.trim()
        require(objectiveText.isNotEmpty()) { "Describe the objective first." }
        require(options.maxTasks == null || options.maxTasks!! >= 1) { "Allow at least one task." }
        check(!restoring.value) { MaintenanceGate.RESTORING }
        val current = settings.get()
        val directorId = current.directorAgentId
            ?: throw IllegalStateException("No Director is designated. Designate a Director agent before giving the team an objective.")
        directory.snapshot().agentsById[directorId]
            ?: throw IllegalStateException("The designated Director no longer exists. Designate another Director agent first.")
        // A Director whose provider is only not answering is looked at again first (D28).
        val director = when (ctx.prepareChecked(directorId)) {
            is Readiness.Ready -> null
            is Readiness.NotReady -> directory.snapshot().agentsById[directorId]
        }
        when (val availability = director?.availability) {
            null, is AgentAvailability.Ready -> Unit
            is AgentAvailability.Disabled -> throw IllegalStateException("The Director, ${director.profile.name}, is switched off. Switch it on to give it an objective.")
            is AgentAvailability.Unavailable -> throw IllegalStateException("The Director, ${director.profile.name}, cannot run right now. ${availability.detail}")
        }
        val now = clock.now()
        val id = ids.taskId()
        val objective = TaskRecord(
            id = id, rootId = id, kind = TaskKind.OBJECTIVE, title = titleOf(objectiveText), instructions = objectiveText,
            assignee = directorId, requestedBy = TaskRequester.Owner, status = TaskStatus.RUNNING, statusDetail = "Waiting for the Director to plan.",
            options = options, createdAt = now, updatedAt = now, startedAt = now,
            deadlineAt = now + current.objectiveTimeoutMinutes.coerceAtLeast(1) * OrchestrationContext.MINUTE_MS,
        )
        ctx.tasks.upsert(objective)
        journal.taskChanged(objective, "Objective started")
        journal.send(id, objective, Participant.Owner, Participant.Agent(directorId), MessageType.ASSIGNMENT, objectiveText)
        launch(id)
        return objective
    }

    override suspend fun pause(rootId: TaskId) {
        val rt = runtimes.of(rootId)
        val outcome = rt.lock.withLockNonCancellable {
            val objective = ctx.tasks.get(rootId) ?: throw IllegalArgumentException(GONE)
            if (objective.status != TaskStatus.RUNNING) return@withLockNonCancellable null
            val now = clock.now()
            val changes = ctx.tasks.getTree(rootId).filter { it.id != rootId && it.status in WORKING }
                .map { it.copy(status = TaskStatus.PAUSED, statusDetail = "Paused by the owner.", updatedAt = now) }
            // The deadline as its running limit has it: time spent waiting for the owner moved it later.
            val updated = objective.copy(status = TaskStatus.PAUSED, statusDetail = "Paused by the owner.", deadlineAt = rt.deadlineNow(objective.deadlineAt), updatedAt = now)
            ctx.tasks.upsertAll(changes + updated)
            updated to changes
        } ?: return
        stop(rt)
        val (paused, tasks) = outcome
        tasks.forEach { journal.taskChanged(it, "Task paused") }
        journal.ownerAction(rootId, paused, "Objective paused", paused.title)
        journal.taskChanged(paused, "Objective paused")
        journal.send(rootId, paused, Participant.Owner, director(paused), MessageType.STATUS, "Paused by the owner.")
    }

    override suspend fun resume(rootId: TaskId) = resume(rootId, byOwner = true)

    /**
     * Resumes for the owner, or (after a crash, with auto-resume on or only bookkeeping left) for
     * the system, which is then what the record says. [atStartUp]: only bookkeeping is left.
     */
    private suspend fun resume(rootId: TaskId, byOwner: Boolean, atStartUp: Boolean = false) {
        val rt = runtimes.of(rootId)
        if (rt.isDriven) return
        val outcome = rt.lock.withLockNonCancellable {
            val objective = ctx.tasks.get(rootId) ?: throw IllegalArgumentException(GONE)
            if (objective.status.isFinished) throw IllegalStateException("This objective has finished. Run one of its tasks again instead.")
            if (objective.status !in RESUMABLE) return@withLockNonCancellable null
            val now = clock.now()
            val limits = ctx.limits(objective, settings.get())
            val tree = ctx.tasks.getTree(rootId)
            val changes = tree.filter { it.id != rootId && (it.status in RESUMABLE + WORKING || it.isDelegated() && it.status == TaskStatus.QUEUED) }.map {
                if (it.isDelegated()) {
                    // Delegated work belongs to its delegating task's run, which restarts from its brief and may delegate again.
                    it.copy(status = TaskStatus.CANCELLED, error = "Its delegating task restarts from its brief.", statusDetail = "Its delegating task restarts from its brief.", finishedAt = now, updatedAt = now)
                } else {
                    it.copy(status = TaskStatus.QUEUED, statusDetail = "Resumed; restarts from its brief.", retryAt = null, updatedAt = now)
                }
            } + tree.filter { it.id != rootId && it.status == TaskStatus.QUEUED && it.retryAt != null && !it.isDelegated() }
                // The owner resumed: work that waited for its provider is tried again at once.
                .map { it.copy(retryAt = null, statusDetail = "Resumed; tries again now.", updatedAt = now) }
            // Time spent stopped does not count, and time already used stays used: the objective gets what it had left.
            val remaining = remainingWhenStopped(objective, limits.objectiveTimeoutMs)
            val detail = if (remaining > 0) "Resumed." else "Resumed with no time left: the final result is written from what exists."
            val updated = objective.copy(status = TaskStatus.RUNNING, statusDetail = detail, deadlineAt = now + remaining, retryAt = null, updatedAt = now)
            ctx.tasks.upsertAll(changes + updated)
            rt.waitsSoFar.clear()
            updated to changes
        } ?: return
        val (resumed, tasks) = outcome
        tasks.forEach { journal.taskChanged(it, if (it.status == TaskStatus.CANCELLED) "Task cancelled" else "Task queued again") }
        if (byOwner) {
            journal.ownerAction(rootId, resumed, "Objective resumed", resumed.title)
            journal.send(rootId, resumed, Participant.Owner, director(resumed), MessageType.STATUS, "Resumed.")
        } else {
            journal.send(rootId, resumed, Participant.System, director(resumed), MessageType.STATUS, "Resumed automatically after the interruption.")
        }
        journal.taskChanged(resumed, "Objective resumed")
        launch(rootId, atStartUp)
    }

    override suspend fun cancel(rootId: TaskId) {
        val rt = runtimes.of(rootId)
        val (cancelled, tasks) = rt.lock.withLockNonCancellable {
            val objective = ctx.tasks.get(rootId) ?: throw IllegalArgumentException(GONE)
            if (objective.status.isFinished) return@withLockNonCancellable null
            val now = clock.now()
            val tree = ctx.tasks.getTree(rootId)
            val changes = tree.filter { it.id != rootId && !it.status.isFinished }
                .map { it.copy(status = TaskStatus.CANCELLED, error = CANCELLED_BY_OWNER, statusDetail = CANCELLED_BY_OWNER, finishedAt = now, updatedAt = now, retryAt = null) }
            // What was produced is kept; a final document exists only if the Director already wrote it.
            val written = tree.firstOrNull { it.kind == TaskKind.SYNTHESIS && it.status == TaskStatus.COMPLETED }?.result
            val updated = objective.copy(
                status = TaskStatus.CANCELLED, statusDetail = CANCELLED_BY_OWNER, error = CANCELLED_BY_OWNER,
                result = objective.result ?: written, finishedAt = now, updatedAt = now, retryAt = null,
            )
            ctx.tasks.upsertAll(changes + updated)
            updated to changes
        } ?: return
        stop(rt)
        tasks.forEach { journal.taskChanged(it, "Task cancelled", Severity.WARNING) }
        tasks.filter { it.startedAt != null && it.assignee != null }.forEach { journal.countFinished(it) }
        journal.ownerAction(rootId, cancelled, "Objective cancelled", cancelled.title)
        journal.taskChanged(cancelled, "Objective cancelled", Severity.WARNING)
        journal.send(rootId, cancelled, Participant.Owner, director(cancelled), MessageType.CANCEL, CANCELLED_BY_OWNER)
    }

    override suspend fun cancelTask(taskId: TaskId) {
        val task = ctx.tasks.get(taskId) ?: throw IllegalArgumentException(TASK_GONE)
        if (task.kind == TaskKind.OBJECTIVE) return cancel(taskId)
        val rt = runtimes.of(task.rootId)
        val changes = rt.lock.withLockNonCancellable {
            val current = ctx.tasks.get(taskId)?.takeIf { !it.status.isFinished } ?: return@withLockNonCancellable emptyList()
            val now = clock.now()
            val tree = ctx.tasks.getTree(task.rootId)
            val below = descendants(tree, taskId).filter { !it.status.isFinished }
                .map { it.copy(status = TaskStatus.CANCELLED, error = "Cancelled with \"${current.title}\".", finishedAt = now, updatedAt = now) }
            val cancelled = current.copy(status = TaskStatus.CANCELLED, error = CANCELLED_BY_OWNER, statusDetail = CANCELLED_BY_OWNER, finishedAt = now, updatedAt = now)
            // A cancelled review: the result it was checking is used as it is, and says so.
            val reviewed = if (current.kind == TaskKind.REVIEW) {
                current.parentId?.let { id -> tree.firstOrNull { it.id == id } }?.takeIf { it.status == TaskStatus.IN_REVIEW }
                    ?.copy(status = TaskStatus.COMPLETED, finishedAt = now, updatedAt = now, statusDetail = "Used without review: the owner cancelled the review.")
            } else null
            val all = listOfNotNull(cancelled, reviewed) + below
            ctx.tasks.upsertAll(all)
            all
        }
        if (changes.isEmpty()) return
        changes.mapNotNull { rt.taskJobs[it.id] }.forEach { it.cancelAndJoin() }
        rt.poke()
        val cancelled = changes.first()
        journal.ownerAction(cancelled.rootId, cancelled, "Task cancelled", cancelled.title)
        changes.forEach { journal.taskChanged(it, if (it.status == TaskStatus.COMPLETED) "Task completed" else "Task cancelled", Severity.WARNING) }
        if (cancelled.startedAt != null) journal.countFinished(cancelled)
        journal.send(cancelled.rootId, cancelled, Participant.Owner, cancelled.assignee?.let { Participant.Agent(it) } ?: Participant.System, MessageType.CANCEL, CANCELLED_BY_OWNER)
    }

    override suspend fun retryTask(taskId: TaskId) {
        val task = ctx.tasks.get(taskId) ?: throw IllegalArgumentException(TASK_GONE)
        when {
            task.kind == TaskKind.REVIEW -> throw IllegalStateException("A review cannot be run again on its own. Run the task it reviewed again instead.")
            task.kind == TaskKind.WORK && task.delegationChain.isNotEmpty() ->
                throw IllegalStateException("Delegated work is run by the task that delegated it. Run that task again instead.")
            task.status !in RETRYABLE -> throw IllegalStateException("Only a failed, timed-out or cancelled task can be run again.")
        }
        val rt = runtimes.of(task.rootId)
        val reopened = rt.lock.withLockNonCancellable {
            val current = ctx.tasks.get(taskId)?.takeIf { it.status in RETRYABLE } ?: return@withLockNonCancellable null
            val tree = ctx.tasks.getTree(task.rootId)
            val objective = tree.first { it.id == task.rootId }
            if (current.kind == TaskKind.OBJECTIVE && tree.any { it.kind == TaskKind.WORK }) {
                throw IllegalStateException("This objective has a plan. Run one of its tasks again instead.")
            }
            val now = clock.now()
            val limits = ctx.limits(objective, settings.get())
            val changes = ArrayList<TaskRecord>()
            if (current.kind != TaskKind.OBJECTIVE) {
                changes += current.copy(
                    status = TaskStatus.QUEUED, attempt = if (current.kind == TaskKind.SYNTHESIS) 0 else current.attempt + 1, revisionRound = 0, finishedAt = null, updatedAt = now,
                    statusDetail = "Runs again at the owner's request.", retryAt = null,
                )
                // Tasks that never started because this one did not complete get their chance again.
                val requeued = HashSet<TaskId>().apply { add(current.id) }
                var found = true
                while (found) {
                    found = false
                    for (t in tree) {
                        if (t.id in requeued || t.status != TaskStatus.CANCELLED || t.error?.startsWith(ObjectiveDriver.DEPENDENCY_PREFIX) != true) continue
                        if (t.dependsOn.any { it in requeued }) {
                            requeued += t.id
                            changes += t.copy(status = TaskStatus.QUEUED, error = null, finishedAt = null, statusDetail = "Queued again.", updatedAt = now)
                            found = true
                        }
                    }
                }
                // The final result must take the new result into account: the new run's synthesis has the attempts of a run.
                tree.firstOrNull { it.kind == TaskKind.SYNTHESIS && it.id != current.id && it.status != TaskStatus.QUEUED }?.let {
                    changes += it.copy(status = TaskStatus.QUEUED, attempt = 0, finishedAt = null, statusDetail = "Waits for the task run again.", updatedAt = now)
                }
            }
            // A new run (the objective's attempt counts them), whose ending is announced again. A stopped objective keeps its time left.
            val objectiveNow = if (objective.status.isFinished) {
                objective.copy(
                    status = TaskStatus.RUNNING, attempt = objective.attempt + 1,
                    error = null, finishedAt = null, deadlineAt = now + limits.objectiveTimeoutMs, statusDetail = "Reopened by the owner.", updatedAt = now,
                )
            } else objective.copy(attempt = objective.attempt + 1)
            ctx.tasks.upsertAll(changes + objectiveNow)
            // Stored once the new run is: a request that is in the conversation has been acted on.
            journal.send(task.rootId, current, Participant.Owner, director(objectiveNow), MessageType.STATUS, ObjectiveDriver.runAgain(current.title))
            changes.mapNotNull { rt.taskJobs[it.id] }.forEach { it.cancel() }
            objectiveNow
        } ?: return
        journal.ownerAction(task.rootId, task, "Task run again", task.title)
        if (reopened.status == TaskStatus.RUNNING) {
            launch(task.rootId)
            rt.poke()
        }
    }

    override suspend fun delete(rootId: TaskId) {
        val objective = ctx.tasks.get(rootId) ?: return
        if (!objective.status.isFinished) throw IllegalStateException("Cancel the objective before deleting it.")
        val rt = runtimes.existing(rootId)
        rt?.let { stop(it) }
        val tree = ctx.tasks.getTree(rootId)
        transcripts.delete(tree.mapNotNull { it.conversationId })
        ctx.messages.deleteFor(rootId)
        ctx.tasks.deleteTree(rootId)
        runtimes.remove(rootId)
        journal.ownerAction(rootId, null, "Objective deleted", "${objective.title}: ${tree.size} task(s), their messages and transcripts.")
    }

    override suspend fun recoverInterrupted(): List<TaskId> = recoverInterrupted(null, true)

    /**
     * R1 (Phase 6, K14): [cause] is the app-written phrase for why the app stopped ("Android closed the app to free
     * memory"), which the interrupted texts say instead of the general one, or null when it is not known.
     * [resumeAllowed] false (safe start, rule 10) leaves every objective interrupted until the owner resumes it, whatever
     * the setting for resuming says: nothing is resumed, finished or ended by itself.
     */
    override suspend fun recoverInterrupted(cause: String?, resumeAllowed: Boolean): List<TaskId> {
        // No waiter outlives the app: every question left open by an earlier run is withdrawn, before any task is recovered.
        if (operatorHooks != null && startedUp.compareAndSet(false, true)) {
            try {
                operatorHooks.startUp()
            } catch (e: Exception) {
                rethrowIfCancelled(e) // the requests then expire on their own; recovery goes on
            }
        }
        // What was running, and objectives an earlier recovery left interrupted: those with only bookkeeping left, or being ended, are finished now.
        val stuck = ctx.tasks.withStatus(WORKING + TaskStatus.INTERRUPTED)
        val roots = stuck.map { it.rootId }.distinct().filter { runtimes.existing(it)?.isDriven != true }
        val current = settings.get()
        val autoResume = current.autoResumeInterruptedTasks && resumeAllowed
        val stopped = interruptedText(cause)
        val affected = ArrayList<TaskId>()
        for (rootId in roots) {
            val rt = runtimes.of(rootId)
            var planningFailed = false
            var endEarly: String? = null
            var bookkeeping = false
            val objective = rt.lock.withLockNonCancellable {
                val objective = ctx.tasks.get(rootId) ?: return@withLockNonCancellable null
                // The owner stopped it: it stays as the owner left it, whatever its tasks say.
                if (objective.status == TaskStatus.PAUSED || objective.status == TaskStatus.CANCELLED) return@withLockNonCancellable null
                val now = clock.now()
                val limits = ctx.limits(objective, current)
                val tree = ctx.tasks.getTree(rootId)
                // Each task that was running used up the attempt the app stopped.
                val interrupted = tree.filter { it.id != rootId && it.status in WORKING }.map { interrupted(it, limits.maxTaskAttempts, now, cause) }
                val byId = interrupted.associateBy { it.id }
                bookkeeping = ObjectiveDriver.onlyBookkeeping(tree.map { byId[it.id] ?: it }, rootId)
                val waiting = objective.status == TaskStatus.INTERRUPTED
                if (waiting && !bookkeeping && objective.statusDetail?.endsWith(ENDING) != true) return@withLockNonCancellable null
                val updated = if (objective.status.isFinished) objective else {
                    // The time left is what it had at its last stored write: the time the app was not running does not count.
                    val stoppedAt = if (objective.status in WORKING) tree.maxOf { it.updatedAt } else objective.updatedAt
                    val remaining = objective.deadlineAt?.let { (it - stoppedAt).coerceIn(0, limits.objectiveTimeoutMs) } ?: limits.objectiveTimeoutMs
                    val planning = objective.status in WORKING && tree.none { it.kind == TaskKind.WORK || it.kind == TaskKind.SYNTHESIS }
                    val attempt = objective.attempt + if (planning) 1 else 0
                    if (planning && attempt >= limits.maxTaskAttempts) {
                        planningFailed = true
                        val reason = "Planning was interrupted $attempt time(s): the app stopped while the Director was planning, and no attempts are left."
                        objective.copy(status = TaskStatus.FAILED, attempt = attempt, error = reason, statusDetail = reason, finishedAt = now, updatedAt = now)
                    } else {
                        // Nothing but the objective itself was running (finishing, say, or between two steps): no task's attempt
                        // counts this one, so the interruptions in a row with no progress between them are counted, and capped.
                        // A recovery that died ending it (or finishing it) left it interrupted: that counts as another interruption.
                        val idle = (objective.status in WORKING || waiting) && !planning && interrupted.isEmpty()
                        val inARow = if (idle) idleInterruptions(rootId) + 1 else 0
                        if (inARow > MAX_RESTARTS_WITHOUT_PROGRESS) endEarly = "the objective was interrupted $inARow times in a row without progress"
                        val then = when {
                            !resumeAllowed -> "Resume it to continue."
                            endEarly != null -> ENDING
                            bookkeeping -> "Only bookkeeping is left: finishing it now."
                            autoResume -> "Resuming."
                            else -> "Resume it to continue."
                        }
                        objective.copy(
                            status = TaskStatus.INTERRUPTED, attempt = attempt, deadlineAt = now + remaining, updatedAt = now, retryAt = null,
                            statusDetail = if (idle) "$IDLE_INTERRUPTION ($inARow in a row without progress). $then" else "$stopped $then",
                        )
                    }
                }
                ctx.tasks.upsertAll(interrupted + updated)
                interrupted.forEach {
                    if (it.status == TaskStatus.FAILED) {
                        journal.taskChanged(it, "Task failed", Severity.WARNING)
                        journal.countFinished(it)
                    } else {
                        journal.taskChanged(it, "Task interrupted", Severity.WARNING)
                    }
                }
                updated
            } ?: continue
            if (planningFailed) {
                journal.taskChanged(objective, "Objective failed", Severity.WARNING)
                journal.send(rootId, objective, Participant.System, Participant.Owner, MessageType.FAILURE, objective.error.orEmpty())
            }
            if (objective.status != TaskStatus.INTERRUPTED) continue
            affected += rootId
            journal.taskChanged(objective, "Objective interrupted", Severity.WARNING)
            journal.send(rootId, objective, Participant.System, Participant.Owner, MessageType.STATUS, objective.statusDetail.orEmpty())
            val why = endEarly
            when {
                // Safe start: the owner decides (rule 10).
                !resumeAllowed -> Unit
                why != null -> driver.conclude(rt, why)
                bookkeeping -> resume(rootId, byOwner = false, atStartUp = true)
                autoResume -> resume(rootId, byOwner = false)
            }
        }
        return affected
    }

    /**
     * How often the objective was interrupted while nothing but itself was running
     * ([IDLE_INTERRUPTION], recorded as a STATUS message from the system) since the last thing
     * an agent did: an agent's message is progress, except those the objective's finishing
     * step writes (the Director's notice about the summary, [KnowledgeKeeper.isNotice], and the
     * announcement of the ending, [ObjectiveDriver.isAnnouncement]), so that dying again and
     * again while finishing is capped here too. A recovery always comes after the last message of the run it follows,
     * so "since" compares their times strictly.
     */
    private suspend fun idleInterruptions(rootId: TaskId): Int {
        val messages = ctx.messages.list(rootId)
        val progressAt = messages.filter { it.from is Participant.Agent && !KnowledgeKeeper.isNotice(it) && !ObjectiveDriver.isAnnouncement(it) }.maxOfOrNull { it.at } ?: Long.MIN_VALUE
        return messages.count { it.from == Participant.System && it.type == MessageType.STATUS && it.body.startsWith(IDLE_INTERRUPTION) && it.at > progressAt }
    }

    /**
     * Starts the objective's job unless one is driving it. When one is, it is told to look again
     * before it ends ([ObjectiveRuntime.anotherPass]), so a command that arrives while the job is
     * winding down (writing its last records, say) is never lost. [atStartUp]: only bookkeeping
     * is left, which the job's first pass finishes ([ObjectiveDriver.drive]).
     */
    private fun launch(rootId: TaskId, atStartUp: Boolean = false) {
        val rt = runtimes.of(rootId)
        synchronized(rt) {
            if (rt.isDriven) {
                rt.anotherPass = true
                return
            }
            rt.driving = true
            rt.anotherPass = false
            val job = objectives.launch(start = CoroutineStart.LAZY) {
                val self = coroutineContext.job
                var first = true
                do {
                    driver.drive(rt, atStartUp && first)
                    first = false
                    val again = synchronized(rt) {
                        val more = rt.job === self && rt.anotherPass
                        rt.anotherPass = false
                        if (!more && rt.job === self) rt.driving = false
                        more
                    }
                } while (again)
            }
            rt.job = job
            runtimes.setRunning(rootId, true)
            job.invokeOnCompletion {
                synchronized(rt) {
                    if (rt.job === job) {
                        rt.driving = false
                        runtimes.setRunning(rootId, false)
                    }
                }
            }
            job.start()
        }
    }

    /** The time a stopped objective had left: its deadline less the moment it was stored as stopped, at most [limitMs]. */
    private fun remainingWhenStopped(objective: TaskRecord, limitMs: Long): Long =
        objective.deadlineAt?.let { (it - objective.updatedAt).coerceIn(0, limitMs) } ?: limitMs

    /**
     * [task] after the app stopped while it ran ([cause]: why, when known): the run counts as an attempt; with none
     * left, the task has failed.
     */
    private fun interrupted(task: TaskRecord, maxAttempts: Int, now: Long, cause: String?): TaskRecord {
        val times = (INTERRUPTIONS.find(task.error.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0) + 1
        val what = "Interrupted $times time(s): ${cause?.let { "$it while this was running" } ?: "the app stopped while this was running"}"
        return if (task.attempt + 1 >= maxAttempts) {
            task.copy(status = TaskStatus.FAILED, error = "$what, and no attempts are left.", statusDetail = "$what, and no attempts are left.", finishedAt = now, updatedAt = now)
        } else {
            task.copy(status = TaskStatus.INTERRUPTED, attempt = task.attempt + 1, error = "$what.", statusDetail = interruptedText(cause), updatedAt = now)
        }
    }

    /** "Interrupted: Android closed the app to free memory." ([cause]), or the general sentence when it is not known. */
    private fun interruptedText(cause: String?): String =
        cause?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }?.let { "Interrupted: $it." } ?: INTERRUPTED

    private fun TaskRecord.isDelegated() = kind == TaskKind.WORK && delegationChain.isNotEmpty()

    /** Stops the objective's job and every task job under it, and waits until they have wound down. */
    private suspend fun stop(rt: ObjectiveRuntime) {
        rt.job?.cancelAndJoin()
        rt.taskJobs.values.toList().forEach { it.cancelAndJoin() }
        runtimes.setRunning(rt.rootId, false)
    }

    private fun director(objective: TaskRecord): Participant = objective.assignee?.let { Participant.Agent(it) } ?: Participant.System

    private fun descendants(tree: List<TaskRecord>, id: TaskId): List<TaskRecord> {
        val children = tree.filter { it.parentId == id && it.id != id }
        return children + children.flatMap { descendants(tree, it.id) }
    }

    private fun titleOf(text: String): String {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return if (first.length <= TITLE_LENGTH) first else first.take(TITLE_LENGTH - 1).trimEnd() + "…"
    }

    private companion object {
        const val TITLE_LENGTH = 72
        const val GONE = "That objective no longer exists."
        const val TASK_GONE = "That task no longer exists."
        const val CANCELLED_BY_OWNER = "Cancelled by the owner."
        const val INTERRUPTED = "Interrupted: the app stopped while this was running."
        const val IDLE_INTERRUPTION = "Interrupted while no task was running"

        /** Ends the status of an objective the recovery ends from what exists. */
        const val ENDING = "Ending it from what exists."

        /** An objective interrupted again and again while no task ran, with no progress between, is restarted at most this many times in a row. */
        const val MAX_RESTARTS_WITHOUT_PROGRESS = 3
        val INTERRUPTIONS = Regex("^Interrupted (\\d+) time")
        val RESUMABLE = setOf(TaskStatus.PAUSED, TaskStatus.INTERRUPTED)
        val RETRYABLE = setOf(TaskStatus.FAILED, TaskStatus.TIMED_OUT, TaskStatus.CANCELLED)
    }
}
