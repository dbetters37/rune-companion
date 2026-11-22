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
import com.aicontrolcenter.core.registry.RegistrySnapshot

/** Everything the dashboard shows. Produced by [DashboardViewModel]; the screen only renders it. */
@Immutable
data class DashboardUiState(
    val isLoading: Boolean = true,
    val summary: EcosystemSummary = EcosystemSummary(),
    val attention: List<AttentionItem> = emptyList(),
    val network: AgentNetwork = AgentNetwork(),
    val selectedNode: NetworkNode? = null,
    val recentActivity: List<ActivityRowUi> = emptyList(),
    val isScanning: Boolean = false,
    val message: UserMessage? = null,
)

/** The headline numbers of the registry. Agents, models and AI apps are counted separately. */
@Immutable
data class EcosystemSummary(
    val agentCount: Int = 0,
    val readyAgents: Int = 0,
    val unavailableAgents: Int = 0,
    val disabledAgents: Int = 0,
    val director: DirectorSummary? = null,
    val providerCount: Int = 0,
    val modelCount: Int = 0,
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
}

internal fun RegistrySnapshot.toSummary(): EcosystemSummary = EcosystemSummary(
    agentCount = agents.size,
    readyAgents = readyAgents,
    unavailableAgents = unavailableAgents,
    disabledAgents = disabledAgents,
    director = director?.let {
        DirectorSummary(it.profile.id, it.profile.name, it.profile.colorIndex, it.availability.toStatusLabel())
    },
    providerCount = providers.size,
    modelCount = models.size,
    registeredApps = apps.size,
    installedApps = installedApps,
)
