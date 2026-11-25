package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderRechecks
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.registry.AgentStatsLog
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveLimits
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.framework.agents.DefaultAgentActivityMonitor
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import java.time.ZoneId
import kotlin.time.TimeSource

/** What the steps of an objective share. One per orchestrator. */
internal class OrchestrationContext(
    val tasks: TaskRepository,
    val messages: MessageRepository,
    val agents: AgentStatsLog,
    val directory: RegistryDirectory,
    val settings: SettingsView,
    val tools: ToolRegistry,
    val memory: MemoryService?,
    val monitor: DefaultAgentActivityMonitor,
    val journal: ObjectiveJournal,
    val transcripts: TaskTranscripts,
    val steps: StepRunner,
    val ids: IdGenerator,
    val clock: Clock,
    val runtimes: Runtimes,
    /** Measures the objective's pausable time limits; tests pass the test scheduler's. */
    val timeSource: TimeSource = TimeSource.Monotonic,
    /** Looks again at a provider that is not answering before work waits for it (D28); agent side only, never the provider manager. */
    val rechecks: ProviderRechecks = ProviderRechecks.None,
    /** The owner's time zone, for the clock times in status lines ("trying again at 10:45"). */
    val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    val briefs = BriefWriter()
    val selector = AgentSelector()

    /** Work waiting to be tried again, published for the background service ([com.aicontrolcenter.core.tasks.Orchestrator.timedWaits]). */
    val waits = TaskWaits(runtimes, clock)

    /** Providers that asked the app to slow down: their work waits together, and a used-up quota is not waited for (INT-1). */
    val slowDowns = ProviderSlowDowns()

    /**
     * Whether step [key] of [rt], whose model call to [provider] failed with [error] in a way that passes, may wait and
     * be tried again (INT-1). Not when it already waited [ProviderWaits.MAX_WAITS_AFTER_FAILURE] times in a row after
     * such failures, when the provider asked for a pause longer than [ProviderWaits.MAX_WAIT_MS], or when the provider's
     * quota looks used up ([ProviderSlowDowns]: it asked the app to slow down again after its wait): the failure then
     * counts as an ordinary failed attempt. When it may wait, the wait is counted; a "slow down" answer also holds the
     * provider's other work until then.
     */
    fun mayWait(rt: ObjectiveRuntime, key: TaskId, provider: Readiness.ProviderSide?, error: LlmException): Boolean {
        val earlier = rt.failedWaits[key] ?: 0
        var may = earlier < ProviderWaits.MAX_WAITS_AFTER_FAILURE
        if (error is LlmException.RateLimited) {
            may = may && (error.retryAfterMs ?: 0) <= ProviderWaits.MAX_WAIT_MS
            val now = clock.now()
            may = slowDowns.slowedDown(provider?.id, error, now, now + ProviderWaits.waitMs(rt.waitsSoFar[key] ?: 0, error), may)
        }
        if (may) rt.failedWaits[key] = earlier + 1
        return may
    }

    /** Step [key]'s provider answered (whatever the answer said): its waits after failures start afresh, and so does a "slow down". */
    fun providerAnswered(rt: ObjectiveRuntime, key: TaskId, provider: Readiness.ProviderSide?) {
        rt.failedWaits.remove(key)
        slowDowns.answered(provider?.id)
    }

    /**
     * When work on [provider] is held because it asked the app to slow down (INT-1): stores [task] as waiting until then
     * (as [waitForProvider], without counting a wait) and returns true; false when it is not held.
     */
    suspend fun waitIfHeld(rt: ObjectiveRuntime, task: TaskRecord, provider: Readiness.ProviderSide?, providerName: String, expected: Set<TaskStatus>): Boolean {
        val (until, error) = slowDowns.heldUntil(provider?.id, clock.now()) ?: return false
        waitForProvider(rt, task, ProviderWaits.named(provider, providerName), error, expected, at = until)
        return true
    }

    /**
     * [agentId]'s readiness. When it cannot run only because its provider is not answering, that provider is first
     * checked again if its status is older than [ProviderWaits.STALE_MS] (D28), and the answer is read afresh.
     */
    suspend fun prepareChecked(agentId: AgentId): Readiness {
        val first = steps.prepare(agentId)
        val provider = (first as? Readiness.NotReady)?.takeIf { it.transient }?.provider ?: return first
        try {
            rechecks.recheckIfStale(provider.id, ProviderWaits.STALE_MS)
        } catch (e: Exception) {
            rethrowIfCancelled(e) // a check that fails changes nothing: the work waits as it would have
        }
        return steps.prepare(agentId)
    }

    /**
     * Stores [task] as waiting for its provider (R4 part 1): QUEUED until [com.aicontrolcenter.core.tasks.TaskRecord.retryAt],
     * its attempt kept, its status line naming [provider] and the time ("Waiting: xAI is not answering; trying again at
     * 10:45."). [error] is the model call's failure, or null when its agent cannot run until the provider answers. Each
     * wait in a row is longer ([ProviderWaits.waitMs]). Only while the task's status is one of [expected]; returns the
     * stored task, or null when the owner changed it meanwhile.
     */
    suspend fun waitForProvider(
        rt: ObjectiveRuntime,
        task: TaskRecord,
        provider: String,
        error: LlmException?,
        expected: Set<TaskStatus>,
        /** Until when, when the provider's work is held ([waitIfHeld]): then the wait does not count as one in a row. */
        at: Long? = null,
    ): TaskRecord? {
        val earlier = rt.waitsSoFar[task.id] ?: 0
        val until = at ?: (clock.now() + ProviderWaits.waitMs(earlier, error))
        val text = ProviderWaits.text(provider, error, until, zone())
        val stored = change(rt, task.id, expected) { it.copy(status = TaskStatus.QUEUED, retryAt = until, statusDetail = text) } ?: return null
        if (at == null) rt.waitsSoFar[task.id] = earlier + 1
        journal.taskChanged(stored, "Task waits for its provider")
        rt.poke()
        return stored
    }

    /**
     * Pauses the objective of [rt] because today's limit of model answers, tokens or money is reached (rule 6, Q2): as the
     * owner's pause does (every working task PAUSED, the deadline as the running limit has it), never a failure, and with
     * no attempt used: resuming runs each task again from its brief. The status says [DailyLimits.PAUSED_TEXT]; [sentence]
     * (which limit) is logged. Then the objective's job is stopped, whose tasks write nothing on their way out. Returns
     * false when the objective was not running any more.
     */
    suspend fun pauseForDailyLimit(rt: ObjectiveRuntime, sentence: String): Boolean {
        val outcome = rt.lock.withLockNonCancellable {
            val objective = tasks.get(rt.rootId)?.takeIf { it.status == TaskStatus.RUNNING } ?: return@withLockNonCancellable null
            val now = clock.now()
            val tree = tasks.getTree(rt.rootId)
            val changes = tree.filter { it.id != rt.rootId && it.status in WORKING }
                .map { it.copy(status = TaskStatus.PAUSED, statusDetail = DailyLimits.PAUSED_TEXT, updatedAt = now) }
            val updated = objective.copy(
                status = TaskStatus.PAUSED, statusDetail = DailyLimits.PAUSED_TEXT, deadlineAt = rt.deadlineNow(objective.deadlineAt), retryAt = null, updatedAt = now,
            )
            // Work that waited for its provider no longer says it tries again at a time that will pass (INT-3).
            tasks.upsertAll(changes + ProviderWaits.stoppedIn(tree, rt.rootId, now) + updated)
            updated to changes
        } ?: return false
        val (paused, changes) = outcome
        changes.forEach { journal.taskChanged(it, "Task paused") }
        journal.taskChanged(paused, "Objective paused", Severity.WARNING)
        journal.warn(rt.rootId, paused, "Daily limit reached", sentence)
        journal.send(rt.rootId, paused, Participant.System, Participant.Owner, MessageType.STATUS, DailyLimits.PAUSED_TEXT)
        rt.job?.cancel()
        return true
    }

    /**
     * How many messages of objective [rootId] its message limit counts: all of them but the owner's notes (C2), which
     * never count. The notes are looked for only from [threshold] on, where they could make a difference.
     */
    suspend fun countedMessages(rootId: TaskId, threshold: Int): Int {
        val all = messages.count(rootId)
        if (all < threshold) return all
        return all - ownerNotes(rootId).size
    }

    /**
     * The owner's notes to objective [rootId] (C2, rule 24), in the order they were written. Only GUIDANCE messages are
     * read (INT-4): every brief reads them, and the objective's whole message list grows with its tasks.
     */
    suspend fun ownerNotes(rootId: TaskId): List<AgentMessage> =
        messages.listOfTypes(rootId, OwnerNotes.TYPES).filter { OwnerNotes.isNote(it) }.sortedWith(compareBy({ it.at }, { it.id.value }))

    /**
     * Changes one task under the objective's lock, only when its status is one of [expected];
     * returns the stored result, or null when the task is gone or has moved on (the owner
     * paused or cancelled it, for example): the caller then drops what it was about to record.
     */
    suspend fun change(rt: ObjectiveRuntime, id: TaskId, expected: Set<TaskStatus>, transform: (TaskRecord) -> TaskRecord): TaskRecord? =
        rt.lock.withLockNonCancellable {
            val current = tasks.get(id) ?: return@withLockNonCancellable null
            if (current.status !in expected) return@withLockNonCancellable null
            transform(current).copy(updatedAt = clock.now()).also { tasks.upsert(it) }
        }

    /** Adds [usage]-like counters to a task whatever its status: what was spent stays spent. */
    suspend fun account(rt: ObjectiveRuntime, id: TaskId, transform: (TaskRecord) -> TaskRecord) {
        rt.lock.withLockNonCancellable { tasks.update(id, transform) }
    }

    /** The limits of [objective] now: the framework's, lowered by the owner's options. */
    fun limits(objective: TaskRecord, settings: FrameworkSettings): ObjectiveLimits = ObjectiveLimits(
        maxTasks = minOf(settings.maxTasksPerObjective, objective.options?.maxTasks ?: Int.MAX_VALUE).coerceAtLeast(1),
        maxMessages = settings.maxMessagesPerObjective.coerceAtLeast(1),
        maxDelegationDepth = settings.maxDelegationDepth.coerceAtLeast(0),
        maxRevisionRounds = settings.maxRevisionRounds.coerceAtLeast(0),
        maxTaskAttempts = settings.maxTaskAttempts.coerceAtLeast(1),
        taskTimeoutMs = settings.taskTimeoutMinutes.coerceAtLeast(1) * MINUTE_MS,
        objectiveTimeoutMs = settings.objectiveTimeoutMinutes.coerceAtLeast(1) * MINUTE_MS,
    )

    /**
     * Task places used or kept under an objective: every WORK and REVIEW task, plus the place a
     * valid plan keeps for the first review of each planned task marked for review that has not
     * been reviewed yet (see [PlanParser.read]). The place kept for [except] is not counted: its
     * review is about to use it.
     */
    fun committedTasks(tree: List<TaskRecord>, except: TaskId? = null): Int {
        val reviewed = tree.filter { it.kind == TaskKind.REVIEW }.mapNotNull { it.parentId }.toSet()
        val kept = tree.count {
            it.kind == TaskKind.WORK && it.delegationChain.isEmpty() && it.needsReview && it.id != except && it.id !in reviewed && !it.status.isFinished
        }
        return tree.count { it.countsAgainstLimit() } + kept
    }

    /** Who gave [task]: the agent that requested it, or the owner. */
    fun assigner(task: TaskRecord): Participant = when (val by = task.requestedBy) {
        is TaskRequester.Agent -> Participant.Agent(by.agentId)
        TaskRequester.Owner -> Participant.Owner
    }

    companion object {
        const val MINUTE_MS = 60_000L

        /** Statuses in which a task is held by a job (or was, when the app stopped): running, or waiting for a teammate or the owner. */
        val WORKING = setOf(TaskStatus.RUNNING, TaskStatus.WAITING_FOR_AGENT, TaskStatus.WAITING_FOR_OWNER)

        /** The tasks of the plan the scheduler runs: planned work and reviews, not delegated work. */
        fun TaskRecord.isScheduled(): Boolean =
            (kind == TaskKind.WORK && delegationChain.isEmpty()) || kind == TaskKind.REVIEW

        /** Work and reviews: what the task limit of an objective counts. */
        fun TaskRecord.countsAgainstLimit(): Boolean = kind == TaskKind.WORK || kind == TaskKind.REVIEW
    }
}

/**
 * Takes the lock even when the caller is being cancelled, and keeps the change it makes whole:
 * a record must not be half-written because the work that produced it was stopped.
 */
internal suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockNonCancellable(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        lock()
        try {
            block()
        } finally {
            unlock()
        }
    }
