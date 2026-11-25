package com.aicontrolcenter.core.tasks

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.health.TimedWaits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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

    // --- Summaries for lists (Phase 6, K1, rule 16). The defaults build them from whole records;
    // Room overrides each with a projection or an aggregate query that never reads the result,
    // the instructions, the reviews or the transcript. Both pass `TaskSummaryContract`. Every
    // flow emits at once and again after a change that alters what it emits (no repeats of an
    // equal list).

    /** The summaries of one objective's tasks, in the order of [observeTree] (created time, then id). Empty for an unknown objective. */
    fun observeSummaries(rootId: TaskId): Flow<List<TaskSummary>> =
        observeTree(rootId).map { tree -> tree.map(TaskSummary::of) }.distinctUntilChanged()

    /**
     * At most [limit] objectives as rows ([ObjectiveRow]), newest first as [observeObjectives]
     * (created time descending, then id ascending), starting after [olderThan] when it is given
     * ([ObjectiveCursor.precedes]): the next page of a list whose last row was [olderThan].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeObjectiveRows(limit: Int, olderThan: ObjectiveCursor? = null): Flow<List<ObjectiveRow>> =
        observeObjectives()
            .map { objectives -> objectives.filter { olderThan == null || olderThan.precedes(it.createdAt, it.id) }.take(limit.coerceAtLeast(0)).map { it.id } }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) flowOf(emptyList()) else combine(ids.map(::observeTree)) { trees -> trees.mapNotNull { ObjectiveRow.of(it) } }
            }
            .distinctUntilChanged()

    /**
     * The newest [limit] tasks of any kind assigned to [agentId], across every objective, most
     * recently updated first (updated time descending, then id ascending).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRecentByAgent(agentId: AgentId, limit: Int): Flow<List<TaskSummary>> =
        observeObjectives()
            .map { objectives -> objectives.map { it.id } }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(ids.map(::observeTree)) { trees ->
                        trees.asList().flatten().filter { it.assignee == agentId }
                            .sortedWith(compareByDescending<TaskRecord> { it.updatedAt }.thenBy { it.id.value })
                            .take(limit.coerceAtLeast(0)).map(TaskSummary::of)
                    }
                }
            }
            .distinctUntilChanged()

    /**
     * How far each objective of [rootIds] has got ([PlanProgress]), keyed by objective. An id
     * with no stored objective is left out; an objective without a plan yet has total 0.
     */
    fun observeProgress(rootIds: Set<TaskId>): Flow<Map<TaskId, PlanProgress>> {
        if (rootIds.isEmpty()) return flowOf(emptyMap())
        val ids = rootIds.toList()
        return combine(ids.map(::observeTree)) { trees ->
            ids.zip(trees.asList())
                .filter { (id, tree) -> tree.any { it.id == id && it.kind == TaskKind.OBJECTIVE } }
                .associate { (id, tree) -> id to PlanProgress.of(id, tree) }
        }.distinctUntilChanged()
    }
}

/** Storage of agent messages, in the order they were sent (time, then id). */
interface MessageRepository {
    suspend fun append(message: AgentMessage)
    fun observe(rootId: TaskId): Flow<List<AgentMessage>>

    /** The newest [limit] messages across all objectives, newest first (for the dashboard and the network view). */
    fun observeRecent(limit: Int): Flow<List<AgentMessage>>
    suspend fun list(rootId: TaskId): List<AgentMessage>

    /**
     * The messages of objective [rootId] whose type is one of [types], only those about [taskId] when it is set, in
     * sending order (time, then id). The orchestrator reads the owner's notes and a task's answers this way instead of
     * every message of the objective for every brief (review of wave 2a, INT-4). The default filters [list]; Room
     * reads only the matching rows.
     */
    suspend fun listOfTypes(rootId: TaskId, types: Set<MessageType>, taskId: TaskId? = null): List<AgentMessage> =
        list(rootId).filter { it.type in types && (taskId == null || it.taskId == taskId) }

    suspend fun count(rootId: TaskId): Int
    suspend fun deleteFor(rootId: TaskId)

    // --- Headers for lists (Phase 6, K2, rule 16). The defaults build them from whole messages;
    // Room overrides them with projections that read only the first characters of the body.
    // Both pass `MessageHeaderContract`.

    /** The newest [limit] messages of one objective as headers, in sending order (time, then id). */
    fun observeHeaders(rootId: TaskId, limit: Int): Flow<List<MessageHeader>> =
        observe(rootId).map { all -> all.takeLast(limit.coerceAtLeast(0)).map(MessageHeader::of) }.distinctUntilChanged()

    /** The newest [limit] messages across all objectives as headers, newest first, as [observeRecent]. */
    fun observeRecentHeaders(limit: Int): Flow<List<MessageHeader>> =
        observeRecent(limit).map { all -> all.map(MessageHeader::of) }.distinctUntilChanged()
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
     * Phase 6: implementations override the two-argument form and make this one call it with
     * `(null, true)`.
     */
    suspend fun recoverInterrupted(): List<TaskId>

    /**
     * [recoverInterrupted] with what the app knows about the stop (Phase 6, K14, R1):
     * [cause] is an app-written phrase such as "Android closed the app to free memory" that
     * becomes part of the interrupted tasks' text, or null when unknown; [resumeAllowed] false
     * (safe start, rule 10) keeps every objective interrupted whatever the settings say, until
     * the owner resumes it. The default ignores both and calls [recoverInterrupted]; the
     * framework's orchestrator overrides it (WP7).
     */
    suspend fun recoverInterrupted(cause: String?, resumeAllowed: Boolean): List<TaskId> = recoverInterrupted()

    /**
     * When the next task that waits to be tried again ([TaskRecord.retryAt]) is due, so the
     * background service can keep the phone awake or set a wake alarm for it (rule 5).
     * [TimedWaits.wake] makes the driver look again at once. [TimedWaits.None] when nothing waits.
     */
    val timedWaits: TimedWaits get() = TimedWaits.None

    /** Agents that are part of a running objective right now, with the task each is on. */
    val assignments: StateFlow<Map<AgentId, TaskId>>
}
