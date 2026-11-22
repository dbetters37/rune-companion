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
 * One agent can have several runs at once (two conversations, later several tasks). The state
 * shown is that of whichever run reported last, and the agent turns idle only when all of its
 * runs have ended.
 */
class DefaultAgentActivityMonitor(private val clock: Clock) : AgentActivityMonitor {
    private val lock = Any()
    private val activeRuns = HashMap<AgentId, Int>()
    private val current = MutableStateFlow<Map<AgentId, AgentRuntimeState>>(emptyMap())

    override val states: StateFlow<Map<AgentId, AgentRuntimeState>> = current.asStateFlow()

    /** Marks the start of a run. Every call must be matched by one [runEnded]. */
    fun runStarted(agentId: AgentId, detail: String, taskId: TaskId? = null) {
        synchronized(lock) {
            activeRuns[agentId] = (activeRuns[agentId] ?: 0) + 1
            publish(agentId, AgentActivity.WORKING, detail, taskId)
        }
    }

    /** Reports what a running agent is doing now. Ignored for an agent without a run, so a late report cannot resurrect it. */
    fun report(agentId: AgentId, activity: AgentActivity, detail: String?, taskId: TaskId? = null) {
        synchronized(lock) {
            if (agentId in activeRuns) publish(agentId, activity, detail, taskId)
        }
    }

    fun runEnded(agentId: AgentId) {
        synchronized(lock) {
            val remaining = (activeRuns[agentId] ?: return) - 1
            if (remaining > 0) {
                activeRuns[agentId] = remaining
            } else {
                activeRuns.remove(agentId)
                current.value = current.value - agentId
            }
        }
    }

    private fun publish(agentId: AgentId, activity: AgentActivity, detail: String?, taskId: TaskId?) {
        val before = current.value[agentId]
        if (before != null && before.activity == activity && before.detail == detail && before.currentTaskId == taskId) return
        current.value = current.value + (agentId to AgentRuntimeState(agentId, activity, detail, taskId, since = clock.now()))
    }
}
