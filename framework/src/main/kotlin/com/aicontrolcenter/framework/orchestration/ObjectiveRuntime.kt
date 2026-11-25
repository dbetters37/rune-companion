package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.WaitClock
import com.aicontrolcenter.framework.agents.TaskCallBudget
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * What the orchestrator holds in memory for one objective while it runs. Everything that
 * matters is in the task store; this is only the machinery around it.
 *
 *  - [lock] serialises every change to the objective's tasks made by this process: the
 *    scheduler, the task jobs and the owner's commands. A change is read, checked against the
 *    status it expects, and written (several tasks in one transaction where they belong
 *    together) under it, so a command and a finishing task never overwrite each other. Never
 *    held while an agent runs.
 *  - [wake] tells the scheduler to look again: a task finished, the owner changed something.
 *  - [job] runs the objective; [taskJobs] are the jobs of its running tasks, by task id, so the
 *    owner can stop one. [driving] is true from the moment a job is started until it has decided,
 *    with nothing left to do, to end; a command that needs the objective driven while it is true
 *    sets [anotherPass], and the job looks again before it ends instead of starting a second one.
 *  - [callBudgets] are the call budgets of the WORK runs going on, by task id, which the answers
 *    to their questions are charged to.
 *  - [limit] is the objective's time limit while its plan runs: the deadline it started from and
 *    its [WaitClock], whose paused time (waiting for the owner) moves the deadline later
 *    ([deadlineNow]).
 *  - [scheduledDue] and [dueAt] are when work that waits to be tried again (Phase 6, R4: a provider
 *    not answering, "slow down" answers) is due: the earliest planned task the scheduler skips for
 *    now, and the planning, synthesis or delegated work waiting in a step of its own, by task id.
 *    [waitsSoFar] counts each task's waits in a row, which make the next one longer.
 */
internal class ObjectiveRuntime(val rootId: TaskId) {
    val lock = Mutex()
    val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    var job: Job? = null

    val taskJobs = ConcurrentHashMap<TaskId, Job>()

    val callBudgets = ConcurrentHashMap<TaskId, TaskCallBudget>()

    /** Guarded by this runtime's monitor (synchronized), as is [anotherPass]. */
    @Volatile
    var driving: Boolean = false

    @Volatile
    var anotherPass: Boolean = false

    fun poke() {
        wake.trySend(Unit)
    }

    /** The objective's time limit while it runs: started from [deadline], paused while anything under it waits for the owner. */
    class RunningLimit(val deadline: Long, val clock: WaitClock)

    @Volatile
    var limit: RunningLimit? = null

    /** The objective's deadline now: [stored], or while its limit runs, the deadline it started from plus the time it was paused. */
    fun deadlineNow(stored: Long?): Long? = limit?.let { it.deadline + it.clock.pausedMs() } ?: stored

    val isDriven: Boolean get() = driving && job?.isActive == true

    @Volatile
    var scheduledDue: Long? = null

    val dueAt = ConcurrentHashMap<TaskId, Long>()

    val waitsSoFar = ConcurrentHashMap<TaskId, Int>()

    /** When the earliest wait of this objective is due; null when nothing of it waits. */
    fun nextDueAt(): Long? = listOfNotNull(scheduledDue, dueAt.values.minOrNull()).minOrNull()

    /** Nothing of this objective waits any more: its job ended. */
    fun clearWaits() {
        scheduledDue = null
        dueAt.clear()
    }
}

/** The runtimes of all objectives, and the live views derived from them. */
internal class Runtimes {
    private val byRoot = ConcurrentHashMap<TaskId, ObjectiveRuntime>()
    private val runningState = MutableStateFlow<Set<TaskId>>(emptySet())
    private val assignmentState = MutableStateFlow<Map<AgentId, TaskId>>(emptyMap())

    /** Tasks each agent is working on now, most recent last; [assignments] shows the most recent. */
    private val working = HashMap<AgentId, ArrayList<TaskId>>()

    val running: StateFlow<Set<TaskId>> = runningState.asStateFlow()
    val assignments: StateFlow<Map<AgentId, TaskId>> = assignmentState.asStateFlow()

    fun of(rootId: TaskId): ObjectiveRuntime = byRoot.getOrPut(rootId) { ObjectiveRuntime(rootId) }

    fun existing(rootId: TaskId): ObjectiveRuntime? = byRoot[rootId]

    fun remove(rootId: TaskId) {
        byRoot.remove(rootId)
    }

    /** Tells every objective's scheduler to look again (a timed wait may be due, [TaskWaits.wake]). */
    fun pokeAll() = byRoot.values.forEach { it.poke() }

    /** When the earliest wait of any objective is due, or null. */
    fun nextDueAt(): Long? = byRoot.values.mapNotNull { it.nextDueAt() }.minOrNull()

    fun setRunning(rootId: TaskId, running: Boolean) =
        runningState.update { if (running) it + rootId else it - rootId }

    fun started(agentId: AgentId, taskId: TaskId) = synchronized(working) {
        working.getOrPut(agentId) { ArrayList() }.add(taskId)
        publish()
    }

    fun ended(agentId: AgentId, taskId: TaskId) = synchronized(working) {
        val tasks = working[agentId] ?: return@synchronized
        tasks.remove(taskId)
        if (tasks.isEmpty()) working.remove(agentId)
        publish()
    }

    /** How many tasks each agent is working on now, for the selector's load. */
    fun loads(): Map<AgentId, Int> = synchronized(working) { working.mapValues { it.value.size } }

    private fun publish() {
        assignmentState.value = working.mapValues { it.value.last() }
    }
}
