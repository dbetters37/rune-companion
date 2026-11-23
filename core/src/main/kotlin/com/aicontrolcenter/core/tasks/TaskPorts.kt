package com.aicontrolcenter.core.tasks

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Storage of tasks. Lists of objectives are newest first; a tree is ordered by creation time,
 * then id. Observe flows re-emit after every change.
 */
interface TaskRepository {
    fun observeObjectives(): Flow<List<TaskRecord>>
    fun observeTree(rootId: TaskId): Flow<List<TaskRecord>>
    suspend fun get(id: TaskId): TaskRecord?
    suspend fun getTree(rootId: TaskId): List<TaskRecord>
    suspend fun upsert(task: TaskRecord)

    /** Stores several tasks in one transaction. */
    suspend fun upsertAll(tasks: List<TaskRecord>)

    /** Atomically applies [transform] to the stored task and returns the result, or null if it does not exist. */
    suspend fun update(id: TaskId, transform: (TaskRecord) -> TaskRecord): TaskRecord?

    /** Tasks in any of [statuses], across all objectives, oldest first. */
    suspend fun withStatus(statuses: Set<TaskStatus>): List<TaskRecord>

    /** Deletes the objective and every task under it. Messages are deleted separately. */
    suspend fun deleteTree(rootId: TaskId)
}

/** Storage of agent messages, in the order they were sent (time, then id). */
interface MessageRepository {
    suspend fun append(message: AgentMessage)
    fun observe(rootId: TaskId): Flow<List<AgentMessage>>

    /** The newest [limit] messages across all objectives, newest first (for the dashboard and the network view). */
    fun observeRecent(limit: Int): Flow<List<AgentMessage>>
    suspend fun list(rootId: TaskId): List<AgentMessage>
    suspend fun count(rootId: TaskId): Int
    suspend fun deleteFor(rootId: TaskId)
}

/** What the Director is allowed to do with one objective, and how far it has got. */
data class ObjectiveLimits(
    val maxTasks: Int,
    val maxMessages: Int,
    val maxDelegationDepth: Int,
    val maxRevisionRounds: Int,
    val maxTaskAttempts: Int,
    val taskTimeoutMs: Long,
    val objectiveTimeoutMs: Long,
)

/**
 * Runs objectives: the Director interprets an objective, plans tasks, assigns them to agents,
 * collects and reviews results, resolves disagreements and produces the final result. The
 * owner can watch, pause, resume and cancel at any point. Execution is bounded by
 * [ObjectiveLimits] and protected against agent-to-agent loops.
 */
interface Orchestrator {
    /** Objectives currently being worked on (not paused, not finished). */
    val running: StateFlow<Set<TaskId>>

    /**
     * Records the objective and starts working on it in the framework's own scope.
     * @throws IllegalStateException when no Director is designated or it cannot run.
     */
    suspend fun startObjective(text: String, options: ObjectiveOptions = ObjectiveOptions()): TaskRecord

    /** Stops dispatching new work and interrupts running tasks; they resume on [resume]. */
    suspend fun pause(rootId: TaskId)

    suspend fun resume(rootId: TaskId)

    /** Cancels the objective and every unfinished task. What was produced is kept. */
    suspend fun cancel(rootId: TaskId)

    /** Cancels one task; the Director decides how to continue without it. */
    suspend fun cancelTask(taskId: TaskId)

    /** Runs a failed, timed-out or cancelled task again (counts as an attempt). */
    suspend fun retryTask(taskId: TaskId)

    /** Deletes a finished or cancelled objective with its tasks, messages and transcripts. */
    suspend fun delete(rootId: TaskId)

    /**
     * Called once at start: marks tasks that were running when the app stopped as INTERRUPTED,
     * and resumes their objectives if the settings allow it. Returns the affected objectives.
     */
    suspend fun recoverInterrupted(): List<TaskId>

    /** Agents that are part of a running objective right now, with the task each is on. */
    val assignments: StateFlow<Map<AgentId, TaskId>>
}
