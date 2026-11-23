package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.settings.SettingsRepository
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
import com.aicontrolcenter.framework.orchestration.OrchestrationContext.Companion.WORKING
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
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
 *  - Agents run through [AgentRunner] with briefs written by [BriefWriter], so every protection
 *    of a run (per-task model and tool call limits, time limit, tool authorisation, content as
 *    data) applies to orchestrated work exactly as to a chat.
 *
 * Wiring: build it, then register the team tools with [TeamTools.registerAll], then call
 * [recoverInterrupted] once at start-up before objectives are started.
 */
class DefaultOrchestrator(
    tasks: TaskRepository,
    messages: MessageRepository,
    conversations: ConversationRepository,
    agents: AgentRepository,
    private val directory: RegistryDirectory,
    private val settings: SettingsRepository,
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
) : Orchestrator {
    private val runtimes = Runtimes()
    private val transcripts = TaskTranscripts(conversations, ids, clock)
    private val journal = ObjectiveJournal(messages, agents, activity, ids, clock)
    private val ctx = OrchestrationContext(
        tasks, messages, agents, directory, settings, toolRegistry, memory, monitor, journal, transcripts,
        StepRunner(runner, directory, transcripts, journal), ids, clock, runtimes,
    )
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

    override suspend fun startObjective(text: String, options: ObjectiveOptions): TaskRecord {
        val objectiveText = text.trim()
        require(objectiveText.isNotEmpty()) { "Describe the objective first." }
        require(options.maxTasks == null || options.maxTasks!! >= 1) { "Allow at least one task." }
        val current = settings.get()
        val directorId = current.directorAgentId
            ?: throw IllegalStateException("No Director is designated. Designate a Director agent before giving the team an objective.")
        val director = directory.snapshot().agentsById[directorId]
            ?: throw IllegalStateException("The designated Director no longer exists. Designate another Director agent first.")
        when (val availability = director.availability) {
            is AgentAvailability.Ready -> Unit
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
            val updated = objective.copy(status = TaskStatus.PAUSED, statusDetail = "Paused by the owner.", updatedAt = now)
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

    /** Resumes for the owner, or (after a crash, with auto-resume on) for the system, which is then what the record says. */
    private suspend fun resume(rootId: TaskId, byOwner: Boolean) {
        val rt = runtimes.of(rootId)
        if (rt.isDriven) return
        val outcome = rt.lock.withLockNonCancellable {
            val objective = ctx.tasks.get(rootId) ?: throw IllegalArgumentException(GONE)
            if (objective.status.isFinished) throw IllegalStateException("This objective has finished. Run one of its tasks again instead.")
            if (objective.status !in RESUMABLE) return@withLockNonCancellable null
            val now = clock.now()
            val limits = ctx.limits(objective, settings.get())
            val changes = ctx.tasks.getTree(rootId).filter { it.id != rootId && it.status in RESUMABLE + WORKING }.map {
                if (it.kind == TaskKind.WORK && it.delegationChain.isNotEmpty()) {
                    // Delegated work belongs to its delegating task's run, which restarts from its brief and may delegate again.
                    it.copy(status = TaskStatus.CANCELLED, error = "Its delegating task restarts from its brief.", finishedAt = now, updatedAt = now)
                } else {
                    it.copy(status = TaskStatus.QUEUED, statusDetail = "Resumed; restarts from its brief.", updatedAt = now)
                }
            }
            // Time spent paused does not count: the objective gets its whole time limit again.
            val updated = objective.copy(status = TaskStatus.RUNNING, statusDetail = "Resumed.", deadlineAt = now + limits.objectiveTimeoutMs, updatedAt = now)
            ctx.tasks.upsertAll(changes + updated)
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
        launch(rootId)
    }

    override suspend fun cancel(rootId: TaskId) {
        val rt = runtimes.of(rootId)
        val (cancelled, tasks) = rt.lock.withLockNonCancellable {
            val objective = ctx.tasks.get(rootId) ?: throw IllegalArgumentException(GONE)
            if (objective.status.isFinished) return@withLockNonCancellable null
            val now = clock.now()
            val tree = ctx.tasks.getTree(rootId)
            val changes = tree.filter { it.id != rootId && !it.status.isFinished }
                .map { it.copy(status = TaskStatus.CANCELLED, error = CANCELLED_BY_OWNER, statusDetail = CANCELLED_BY_OWNER, finishedAt = now, updatedAt = now) }
            // What was produced is kept; a final document exists only if the Director already wrote it.
            val written = tree.firstOrNull { it.kind == TaskKind.SYNTHESIS && it.status == TaskStatus.COMPLETED }?.result
            val updated = objective.copy(
                status = TaskStatus.CANCELLED, statusDetail = CANCELLED_BY_OWNER, error = CANCELLED_BY_OWNER,
                result = objective.result ?: written, finishedAt = now, updatedAt = now,
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
                    status = TaskStatus.QUEUED, attempt = current.attempt + 1, revisionRound = 0, finishedAt = null, updatedAt = now,
                    statusDetail = "Runs again at the owner's request.",
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
                // The final result must take the new result into account.
                tree.firstOrNull { it.kind == TaskKind.SYNTHESIS && it.id != current.id && it.status != TaskStatus.QUEUED }?.let {
                    changes += it.copy(status = TaskStatus.QUEUED, attempt = it.attempt + 1, finishedAt = null, statusDetail = "Waits for the task run again.", updatedAt = now)
                }
            }
            val objectiveNow = if (current.kind == TaskKind.OBJECTIVE || objective.status.isFinished) {
                objective.copy(
                    status = if (objective.status.isFinished) TaskStatus.RUNNING else objective.status, attempt = objective.attempt + if (current.kind == TaskKind.OBJECTIVE) 1 else 0,
                    error = null, finishedAt = null, deadlineAt = now + limits.objectiveTimeoutMs, statusDetail = "Reopened by the owner.", updatedAt = now,
                )
            } else objective
            ctx.tasks.upsertAll(changes + objectiveNow)
            changes.mapNotNull { rt.taskJobs[it.id] }.forEach { it.cancel() }
            objectiveNow
        } ?: return
        journal.ownerAction(task.rootId, task, "Task run again", task.title)
        journal.send(task.rootId, task, Participant.Owner, director(reopened), MessageType.STATUS, "Run \"${task.title}\" again.")
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

    override suspend fun recoverInterrupted(): List<TaskId> {
        val stuck = ctx.tasks.withStatus(WORKING)
        val roots = stuck.map { it.rootId }.distinct().filter { runtimes.existing(it)?.isDriven != true }
        val autoResume = settings.get().autoResumeInterruptedTasks
        val affected = ArrayList<TaskId>()
        for (rootId in roots) {
            val rt = runtimes.of(rootId)
            val objective = rt.lock.withLockNonCancellable {
                val objective = ctx.tasks.get(rootId) ?: return@withLockNonCancellable null
                val now = clock.now()
                val interrupted = ctx.tasks.getTree(rootId).filter { it.id != rootId && it.status in WORKING }
                    .map { it.copy(status = TaskStatus.INTERRUPTED, statusDetail = INTERRUPTED, updatedAt = now) }
                val updated = if (objective.status.isFinished) objective else objective.copy(
                    status = TaskStatus.INTERRUPTED, updatedAt = now,
                    statusDetail = if (autoResume) "$INTERRUPTED Resuming." else "$INTERRUPTED Resume it to continue.",
                )
                ctx.tasks.upsertAll(interrupted + updated)
                interrupted.forEach { journal.taskChanged(it, "Task interrupted", Severity.WARNING) }
                updated
            } ?: continue
            if (objective.status != TaskStatus.INTERRUPTED) continue
            affected += rootId
            journal.taskChanged(objective, "Objective interrupted", Severity.WARNING)
            journal.send(rootId, objective, Participant.System, Participant.Owner, MessageType.STATUS, objective.statusDetail.orEmpty())
            if (autoResume) resume(rootId, byOwner = false)
        }
        return affected
    }

    /** Starts the objective's job unless it is already running. */
    private fun launch(rootId: TaskId) {
        val rt = runtimes.of(rootId)
        synchronized(rt) {
            if (rt.isDriven) return
            val job = objectives.launch(start = CoroutineStart.LAZY) { driver.drive(rt) }
            rt.job = job
            runtimes.setRunning(rootId, true)
            job.invokeOnCompletion {
                synchronized(rt) {
                    if (rt.job === job) runtimes.setRunning(rootId, false)
                }
            }
            job.start()
        }
    }

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
        val RESUMABLE = setOf(TaskStatus.PAUSED, TaskStatus.INTERRUPTED)
        val RETRYABLE = setOf(TaskStatus.FAILED, TaskStatus.TIMED_OUT, TaskStatus.CANCELLED)
    }
}
