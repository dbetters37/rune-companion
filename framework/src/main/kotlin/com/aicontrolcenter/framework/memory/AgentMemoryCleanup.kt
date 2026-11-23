package com.aicontrolcenter.framework.memory

import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.framework.agents.AgentDeletionListener

/**
 * Deletes an agent's private and task memory, with their links, once the agent itself has been
 * deleted. What it shared into knowledge spaces stays there with its provenance: the space now
 * owns that knowledge, and other agents may rely on it.
 *
 * Add it to [com.aicontrolcenter.framework.agents.DefaultAgentManager]'s deletion listeners. A
 * failure here is caught and logged by the manager, and does not stop the other listeners.
 */
class AgentMemoryCleanup(private val memory: MemoryService) : AgentDeletionListener {
    override suspend fun onAgentDeleted(agent: AgentProfile) {
        memory.forgetAgent(agent.id)
    }
}
