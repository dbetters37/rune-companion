package com.aicontrolcenter.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.ActivityRowUi
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.dashboard.network.toAgentNetwork
import com.aicontrolcenter.app.ui.models.describe
import com.aicontrolcenter.core.Clock
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
    directory: RegistryDirectory,
    activityLog: ActivityLog,
    private val aiAppRegistry: AiAppRegistry,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private data class Local(
        val selectedNodeId: String? = null,
        val isScanning: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    val uiState: StateFlow<DashboardUiState> = combine(
        directory.observe(),
        activityLog.observe(limit = RECENT_ACTIVITY_LIMIT),
        local,
    ) { snapshot, events, local ->
        val network = snapshot.toAgentNetwork()
        val now = clock.now()
        DashboardUiState(
            isLoading = false,
            summary = snapshot.toSummary(),
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

    fun messageShown() {
        local.update { it.copy(message = null) }
    }

    companion object {
        const val RECENT_ACTIVITY_LIMIT = 5
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
