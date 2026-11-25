package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentActivity
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.agents.AgentRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What every agent is doing right now, held in memory. An agent is in the map from the moment
 * its first run starts until its last run ends; an agent that is not in the map is idle.
 *
 * One agent can have several runs at once (a task and a chat with the owner, two conversations).
 * Each run keeps its own last report, and the agent shows the most active of its live runs:
 * working before waiting for the owner, before needing attention, before waiting for another
 * agent; the latest report among equals. When a run ends, the agent shows what its other runs
 * last reported (AND-W2B-1): an agent whose only run left waits for the owner no longer reads as
 * working, so nothing keeps the phone awake for it (rule 5).
 */
class DefaultAgentActivityMonitor(private val clock: Clock) : AgentActivityMonitor {
    /** One run of an agent, from [runStarted] to its [runEnded]. */
    class Run internal constructor(val agentId: AgentId, internal val taskId: TaskId?)

    private class Last(val state: AgentRuntimeState, val order: Long)

    private val lock = Any()
    private var reports = 0L

    /** Per agent, its live runs in the order they started, each with its last report. */
    private val live = HashMap<AgentId, LinkedHashMap<Run, Last>>()
    private val current = MutableStateFlow<Map<AgentId, AgentRuntimeState>>(emptyMap())

    override val states: StateFlow<Map<AgentId, AgentRuntimeState>> = current.asStateFlow()

    /** Marks the start of a run, working; the run is ended by passing what this returns to [runEnded], exactly once. */
    fun runStarted(agentId: AgentId, detail: String, taskId: TaskId? = null): Run = synchronized(lock) {
        val run = Run(agentId, taskId)
        live.getOrPut(agentId) { LinkedHashMap() }[run] = Last(AgentRuntimeState(agentId, AgentActivity.WORKING, detail, taskId, since = clock.now()), ++reports)
        publish(agentId)
        run
    }

    /** Reports what [run] is doing now. Ignored once it has ended, so a late report cannot resurrect it. */
    fun report(run: Run, activity: AgentActivity, detail: String?, taskId: TaskId? = run.taskId) {
        synchronized(lock) {
            val runs = live[run.agentId] ?: return
            val before = runs[run] ?: return
            if (before.state.activity == activity && before.state.detail == detail && before.state.currentTaskId == taskId) return
            runs[run] = Last(AgentRuntimeState(run.agentId, activity, detail, taskId, since = clock.now()), ++reports)
            publish(run.agentId)
        }
    }

    /**
     * Reports for the run of [agentId] on [taskId] (another part of the framework speaking for a run it serves, such as
     * the team desk while a task waits for a helper); the latest started of them when there are several. Ignored when
     * the agent has no live run on [taskId].
     */
    fun report(agentId: AgentId, activity: AgentActivity, detail: String?, taskId: TaskId? = null) {
        val run = synchronized(lock) { live[agentId]?.keys?.lastOrNull { it.taskId == taskId } } ?: return
        report(run, activity, detail, taskId)
    }

    /** Ends [run]: the agent shows its other runs' last reports, or turns idle when it has none left. */
    fun runEnded(run: Run) {
        synchronized(lock) {
            val runs = live[run.agentId] ?: return
            if (runs.remove(run) == null) return
            if (runs.isEmpty()) {
                live.remove(run.agentId)
                current.value = current.value - run.agentId
            } else {
                publish(run.agentId)
            }
        }
    }

    /** Under [lock]: the agent's most active live run, the latest report among equals. */
    private fun publish(agentId: AgentId) {
        val runs = live[agentId] ?: return
        val shown = runs.values.maxWithOrNull(compareBy<Last>({ rank(it.state.activity) }, { it.order }))?.state ?: return
        if (current.value[agentId] == shown) return
        current.value = current.value + (agentId to shown)
    }

    private fun rank(activity: AgentActivity): Int = when (activity) {
        AgentActivity.WORKING -> 4
        AgentActivity.WAITING_FOR_APPROVAL -> 3
        AgentActivity.NEEDS_ATTENTION -> 2
        AgentActivity.WAITING_FOR_AGENT -> 1
        AgentActivity.IDLE -> 0
    }
}
