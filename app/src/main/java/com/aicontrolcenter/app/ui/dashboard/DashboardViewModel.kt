package com.aicontrolcenter.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.ActivityRowUi
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.dashboard.network.toAgentNetwork
import com.aicontrolcenter.app.ui.models.describe
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.app.ui.agents.assignModelToAgentsWithoutOne
import com.aicontrolcenter.app.ui.agents.summariseNames
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.framework.llm.Simulator
import kotlinx.coroutines.flow.flowOf
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.registry.RegistryDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Turns the live registry and activity log into the dashboard's state. All figures, the
 * attention list and the network are derived from one [com.aicontrolcenter.core.registry.RegistrySnapshot],
 * so they always describe the same moment.
 */
class DashboardViewModel(
    private val directory: RegistryDirectory,
    activityLog: ActivityLog,
    private val aiAppRegistry: AiAppRegistry,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    monitor: AgentActivityMonitor? = null,
    private val agentManager: AgentManager? = null,
) : ViewModel() {

    private data class Local(
        val selectedNodeId: String? = null,
        val isScanning: Boolean = false,
        val isAssigning: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    val uiState: StateFlow<DashboardUiState> = combine(
        directory.observe(),
        activityLog.observe(limit = RECENT_ACTIVITY_LIMIT),
        monitor?.states ?: flowOf(emptyMap()),
        local,
    ) { snapshot, events, working, local ->
        val network = snapshot.toAgentNetwork(working)
        val now = clock.now()
        DashboardUiState(
            isLoading = false,
            summary = snapshot.toSummary(),
            quickStart = snapshot.quickStart(),
            working = snapshot.agents.mapNotNull { entry ->
                working[entry.profile.id]?.let {
                    WorkingAgentUi(entry.profile.id, entry.profile.name, entry.profile.colorIndex, it.detail ?: "Working")
                }
            },
            attention = snapshot.attentionItems(),
            network = network,
            selectedNode = network.node(local.selectedNodeId),
            recentActivity = events.map { event ->
                ActivityRowUi(
                    event = event,
                    time = formatCompactTimestamp(event.at, now, zone),
                    agentName = event.agentId?.let { snapshot.agentsById[it]?.profile?.name },
                )
            },
            isScanning = local.isScanning,
            isAssigning = local.isAssigning,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DashboardUiState())

    /** Selects a node of the network, or clears the selection with null. Tapping the selected node clears it. */
    fun selectNode(id: String?) {
        local.update { it.copy(selectedNodeId = if (id == it.selectedNodeId) null else id) }
    }

    /** Scans the device for AI applications and reports the outcome as a message. */
    fun scanForApps() {
        if (local.value.isScanning) return
        local.update { it.copy(isScanning = true) }
        viewModelScope.launch {
            val text = try {
                aiAppRegistry.discover().describe()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "The scan failed: ${failure.message ?: "unknown error"}"
            }
            local.update { it.copy(isScanning = false, message = UserMessage(text, ++messageCounter)) }
        }
    }

    /**
     * Gives the built-in simulator to every agent that has no model, so the whole pipeline can be
     * tried before a real provider is connected. The screen confirms first that it is not a real model.
     */
    fun trySimulator() {
        val manager = agentManager ?: return
        if (local.value.isAssigning) return
        local.update { it.copy(isAssigning = true) }
        viewModelScope.launch {
            val text = try {
                val snapshot = directory.snapshot()
                val simulator = snapshot.modelsById[Simulator.MODEL_ID]
                when {
                    simulator == null -> "The simulator is not registered. Restart the app to bring it back."
                    !simulator.enabled || snapshot.provider(simulator)?.enabled != true ->
                        "The simulator is switched off. Switch it on under Models first."
                    else -> {
                        val names = assignModelToAgentsWithoutOne(manager, directory, Simulator.MODEL_ID)
                        if (names.isEmpty()) {
                            "Every agent already has a model."
                        } else {
                            "${summariseNames(names)} now use the simulator. Open an agent and tap Chat to try it."
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "Could not assign the simulator: ${failure.message ?: "storage error"}"
            }
            local.update { it.copy(isAssigning = false, message = UserMessage(text, ++messageCounter)) }
        }
    }

    fun messageShown() {
        local.update { it.copy(message = null) }
    }

    companion object {
        const val RECENT_ACTIVITY_LIMIT = 5
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
