package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.components.ActivityRowUi
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.app.ui.dashboard.network.AgentNetwork
import com.aicontrolcenter.app.ui.dashboard.network.NetworkNode
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.framework.llm.Simulator

/** Everything the dashboard shows. Produced by [DashboardViewModel]; the screen only renders it. */
@Immutable
data class DashboardUiState(
    val isLoading: Boolean = true,
    val summary: EcosystemSummary = EcosystemSummary(),
    /** The one-tap help for agents that have no model yet. Null when every agent has one. */
    val quickStart: QuickStartUi? = null,
    /** Agents running a turn right now, with what they are doing. */
    val working: List<WorkingAgentUi> = emptyList(),
    val attention: List<AttentionItem> = emptyList(),
    val network: AgentNetwork = AgentNetwork(),
    val selectedNode: NetworkNode? = null,
    val recentActivity: List<ActivityRowUi> = emptyList(),
    val isScanning: Boolean = false,
    val isAssigning: Boolean = false,
    val message: UserMessage? = null,
    /** Null when the memory system is not part of this graph (some tests). */
    val memory: MemorySummaryUi? = null,
)

/** The headline numbers of memory: everything remembered, and the spaces agents can share. */
@Immutable
data class MemorySummaryUi(val memoryCount: Int = 0, val spaceCount: Int = 0)

/** Agents that have no model, and whether the simulator can stand in for one. */
@Immutable
data class QuickStartUi(
    val agentsWithoutModel: List<String>,
    /** The built-in simulator is registered and switched on. */
    val simulatorAvailable: Boolean,
    /** A real model (not the simulator) is registered. */
    val hasRealModels: Boolean,
)

@Immutable
data class WorkingAgentUi(
    val agentId: AgentId,
    val name: String,
    val colorIndex: Int,
    /** "Waiting for the model", "Running tool: Current time". */
    val detail: String,
    /** The objective's task it works on, when it is part of one. */
    val taskId: TaskId? = null,
    val taskTitle: String? = null,
    val objectiveTitle: String? = null,
    /** Waiting for a teammate rather than working itself. */
    val waiting: Boolean = false,
)

/** The headline numbers of the registry. Agents, models and AI apps are counted separately. */
@Immutable
data class EcosystemSummary(
    val agentCount: Int = 0,
    val readyAgents: Int = 0,
    /** Agents that only lack a model: an unfinished setup, counted apart from faults. */
    val agentsWithoutModel: Int = 0,
    /** Agents that cannot run because something they depend on is broken, disabled or blocked. */
    val unavailableAgents: Int = 0,
    val disabledAgents: Int = 0,
    val director: DirectorSummary? = null,
    val providerCount: Int = 0,
    val providersOnline: Int = 0,
    /** Enabled providers that are offline, rejected their key, or reported an error. */
    val providerProblems: Int = 0,
    val modelCount: Int = 0,
    /** Models assigned to at least one agent, as primary or fallback. */
    val modelsInUse: Int = 0,
    val registeredApps: Int = 0,
    val installedApps: Int = 0,
)

@Immutable
data class DirectorSummary(
    val agentId: AgentId,
    val name: String,
    val colorIndex: Int,
    val status: StatusLabel,
) {
    val canRun: Boolean get() = status.tone == StatusTone.READY

    /** Nothing is wrong with the Director; it only has no model yet. */
    val awaitingModel: Boolean get() = status.tone == StatusTone.INFO

    /** Whether the Director can run, and in a few words why not. */
    val runLabel: String
        get() = when {
            canRun -> "Can run"
            awaitingModel -> "Cannot run yet: no model"
            else -> "Cannot run: ${status.text.replaceFirstChar(Char::lowercase)}"
        }
}

internal fun RegistrySnapshot.toSummary(): EcosystemSummary {
    val withoutModel = agents.count {
        (it.availability as? AgentAvailability.Unavailable)?.reason == UnavailableReason.NO_MODEL_ASSIGNED
    }
    return toSummary(withoutModel)
}

private fun RegistrySnapshot.toSummary(withoutModel: Int): EcosystemSummary = EcosystemSummary(
    agentCount = agents.size,
    readyAgents = readyAgents,
    agentsWithoutModel = withoutModel,
    unavailableAgents = unavailableAgents - withoutModel,
    disabledAgents = disabledAgents,
    director = director?.let {
        DirectorSummary(it.profile.id, it.profile.name, it.profile.colorIndex, it.availability.toStatusLabel())
    },
    providerCount = providers.size,
    providersOnline = providers.count { it.enabled && it.status == ConnectionStatus.ONLINE },
    providerProblems = providers.count {
        it.enabled && (it.status == ConnectionStatus.OFFLINE || it.status == ConnectionStatus.AUTH_ERROR || it.status == ConnectionStatus.ERROR)
    },
    modelCount = models.size,
    modelsInUse = models.count { model -> agents.any { it.profile.modelId == model.id || model.id in it.profile.fallbackModelIds } },
    registeredApps = apps.size,
    installedApps = installedApps,
)

internal fun RegistrySnapshot.quickStart(): QuickStartUi? {
    val without = agents.filter { it.profile.modelId == null }.map { it.profile.name }
    if (without.isEmpty()) return null
    val simulator = modelsById[Simulator.MODEL_ID]
    return QuickStartUi(
        agentsWithoutModel = without,
        simulatorAvailable = simulator != null && simulator.enabled && provider(simulator)?.enabled == true,
        hasRealModels = !awaitingFirstModel,
    )
}
