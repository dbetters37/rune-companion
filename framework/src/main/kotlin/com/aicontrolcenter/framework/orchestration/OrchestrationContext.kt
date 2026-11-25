package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.registry.AgentStatsLog
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.ObjectiveLimits
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.framework.agents.DefaultAgentActivityMonitor
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
) {
    val briefs = BriefWriter()
    val selector = AgentSelector()

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
