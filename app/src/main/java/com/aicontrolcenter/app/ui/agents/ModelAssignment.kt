package com.aicontrolcenter.app.ui.agents

import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.registry.RegistryDirectory

/**
 * Gives [modelId] to every agent that has no model at all, and returns their names. Agents
 * that already have one, even a broken one, are left alone: replacing a model the owner chose
 * is not a one-tap decision.
 */
internal suspend fun assignModelToAgentsWithoutOne(agentManager: AgentManager, directory: RegistryDirectory, modelId: ModelId): List<String> {
    val without = directory.snapshot().agents.filter { it.profile.modelId == null }
    without.forEach { agentManager.assignModel(it.profile.id, modelId) }
    return without.map { it.profile.name }
}

/** "Researcher, Analyst and Writer" / "Researcher, Analyst and 3 others". */
internal fun summariseNames(names: List<String>): String = when {
    names.size <= 1 -> names.joinToString()
    names.size <= 3 -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    else -> names.take(2).joinToString(", ") + " and ${names.size - 2} others"
}
