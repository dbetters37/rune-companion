package com.aicontrolcenter.app.ui.agents

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.tools.ToolSpec
import kotlinx.coroutines.flow.flowOf
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.agents.AgentOperationException
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.registry.RegistryDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One agent with everything its detail screen shows, already resolved against the registry. */
@Immutable
data class AgentDetailUi(
    val profile: AgentProfile,
    val status: StatusLabel,
    val stats: AgentStats,
    val isDesignatedDirector: Boolean,
    /** Null when no model is assigned. */
    val model: ModelRefUi?,
    val fallbackModels: List<ModelRefUi>,
    /** True when the registry has at least one model that could be assigned. */
    val modelsAvailable: Boolean,
    /** What the agent is doing right now ("Waiting for the model"), or null when it is idle. */
    val liveActivity: String? = null,
    /** Titles of the tools switched on for it that exist in the registry. */
    val tools: List<String> = emptyList(),
) {
    /** Only an agent with the Director role can be designated, and only if it is not already. */
    val canDesignateAsDirector: Boolean get() = profile.kind == AgentKind.DIRECTOR && !isDesignatedDirector

    /** Why this agent cannot be deleted, or null when it can. */
    val deleteBlockedReason: String?
        get() = if (isDesignatedDirector) {
            "${profile.name} is the designated Director, the agent that receives your objectives. " +
                "Designate another Director first, then this one can be deleted."
        } else {
            null
        }

    val hasHistory: Boolean get() = stats != AgentStats()
}

@Immutable
data class AgentDetailUiState(
    val isLoading: Boolean = true,
    /** Null after loading means the agent is not in the registry (deleted here or elsewhere). */
    val agent: AgentDetailUi? = null,
    /** Set once after this screen deleted the agent; the route leaves the screen. */
    val deleted: Boolean = false,
    /** Set once after a duplicate was created; the route opens the copy. */
    val duplicateId: AgentId? = null,
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
)

/** Shows one agent and performs the actions available on it through [AgentManager]. */
class AgentDetailViewModel(
    private val agentId: AgentId,
    directory: RegistryDirectory,
    private val agentManager: AgentManager,
    monitor: AgentActivityMonitor? = null,
    tools: List<ToolSpec> = emptyList(),
) : ViewModel() {
    private val toolTitles: Map<ToolId, String> = tools.associate { it.id to it.title }

    private data class Local(
        val deleted: Boolean = false,
        val duplicateId: AgentId? = null,
        val isBusy: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    val uiState: StateFlow<AgentDetailUiState> = combine(
        directory.observe(),
        monitor?.states ?: flowOf(emptyMap()),
        local,
    ) { snapshot, working, local ->
        val entry = snapshot.agentsById[agentId]
        AgentDetailUiState(
            isLoading = false,
            agent = entry?.let {
                AgentDetailUi(
                    profile = it.profile,
                    status = it.availability.toStatusLabel(),
                    stats = it.stats,
                    isDesignatedDirector = it.isDesignatedDirector,
                    model = it.profile.modelId?.let(snapshot::modelRef),
                    fallbackModels = it.profile.fallbackModelIds.map(snapshot::modelRef),
                    modelsAvailable = snapshot.models.isNotEmpty(),
                    liveActivity = working[agentId]?.let { state -> state.detail ?: "Working" },
                    tools = it.profile.toolIds.mapNotNull(toolTitles::get).sorted(),
                )
            },
            deleted = local.deleted,
            duplicateId = local.duplicateId,
            isBusy = local.isBusy,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentDetailUiState())

    fun setEnabled(enabled: Boolean) = run { agentManager.setEnabled(agentId, enabled) }

    fun designateAsDirector() = run(successMessage = "This agent is now the designated Director.") {
        agentManager.designateDirector(agentId)
    }

    fun duplicate() = run {
        val copy = agentManager.duplicate(agentId)
        local.update { it.copy(duplicateId = copy.id, message = message("Created \"${copy.name}\".")) }
    }

    fun delete() = run {
        agentManager.delete(agentId)
        local.update { it.copy(deleted = true) }
    }

    fun duplicateHandled() = local.update { it.copy(duplicateId = null) }

    fun messageShown() = local.update { it.copy(message = null) }

    /**
     * Runs one action at a time. A refusal from the manager (for example deleting the designated
     * Director) and a storage failure are both reported to the owner instead of being thrown.
     */
    private fun run(successMessage: String? = null, action: suspend () -> Unit) {
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val failure: String? = try {
                action()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: AgentOperationException) {
                refused.message ?: "That is not possible right now."
            } catch (failure: Exception) {
                "Something went wrong: ${failure.message ?: "storage error"}"
            }
            local.update {
                val text = failure ?: successMessage
                it.copy(isBusy = false, message = if (text != null) message(text) else it.message)
            }
        }
    }

    private fun message(text: String) = UserMessage(text, ++messageCounter)
}
