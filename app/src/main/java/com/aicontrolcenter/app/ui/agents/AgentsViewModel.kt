package com.aicontrolcenter.app.ui.agents

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.AgentManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.agents.label
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.registry.effectiveLocality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Narrows the agent list by whether an agent could run right now. */
enum class AgentStatusFilter(val label: String) {
    ALL("Any status"),
    READY("Ready"),

    /** Enabled but unable to run, whatever the reason (including "no model yet"). */
    UNAVAILABLE("Cannot run"),
    DISABLED("Disabled"),
    ;

    fun matches(availability: AgentAvailability): Boolean = when (this) {
        ALL -> true
        READY -> availability is AgentAvailability.Ready
        UNAVAILABLE -> availability is AgentAvailability.Unavailable
        DISABLED -> availability is AgentAvailability.Disabled
    }
}

/** The model an agent points at, resolved against the registry. */
@Immutable
data class ModelRefUi(
    val name: String,
    val providerName: String?,
    val locality: Locality?,
    /** False when the agent references a model id that is no longer in the registry. */
    val registered: Boolean = true,
)

@Immutable
data class AgentRowUi(
    val id: AgentId,
    val name: String,
    val kind: AgentKind,
    val description: String,
    val colorIndex: Int,
    /** Null means no model is assigned. */
    val model: ModelRefUi?,
    val status: StatusLabel,
    /** The framework's explanation, shown when the chip's label alone does not say why. */
    val statusDetail: String?,
    val isDesignatedDirector: Boolean,
)

@Immutable
data class AgentsUiState(
    val isLoading: Boolean = true,
    val rows: List<AgentRowUi> = emptyList(),
    /** Agents in the registry before filtering, to tell "no agents" from "no matches". */
    val totalCount: Int = 0,
    val query: String = "",
    val roleFilter: AgentKind? = null,
    val statusFilter: AgentStatusFilter = AgentStatusFilter.ALL,
    /** Names of agents that have no model at all, whatever the filters. */
    val withoutModel: List<String> = emptyList(),
    /** Models that could be given to them: enabled, on an enabled provider. */
    val assignableModels: List<ModelOptionUi> = emptyList(),
    val isAssigning: Boolean = false,
    val message: UserMessage? = null,
) {
    val hasActiveFilters: Boolean
        get() = query.isNotBlank() || roleFilter != null || statusFilter != AgentStatusFilter.ALL
}

/** The agent list with search and filters. Reads the live registry; changes nothing. */
class AgentsViewModel(
    private val directory: RegistryDirectory,
    initialStatusFilter: AgentStatusFilter = AgentStatusFilter.ALL,
    private val agentManager: AgentManager? = null,
) : ViewModel() {

    private data class Criteria(val query: String, val role: AgentKind?, val status: AgentStatusFilter)

    private data class Local(val isAssigning: Boolean = false, val message: UserMessage? = null)

    private val criteria = MutableStateFlow(Criteria("", null, initialStatusFilter))
    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    val uiState: StateFlow<AgentsUiState> = combine(directory.observe(), criteria, local) { snapshot, criteria, local ->
        val needle = criteria.query.trim()
        AgentsUiState(
            isLoading = false,
            rows = snapshot.agents
                .filter { entry ->
                    entry.matches(needle) &&
                        (criteria.role == null || entry.profile.kind == criteria.role) &&
                        criteria.status.matches(entry.availability)
                }
                .map { it.toRow(snapshot) },
            totalCount = snapshot.agents.size,
            query = criteria.query,
            roleFilter = criteria.role,
            statusFilter = criteria.status,
            withoutModel = snapshot.agents.filter { it.profile.modelId == null }.map { it.profile.name },
            assignableModels = snapshot.modelOptions().filter { it.note == null },
            isAssigning = local.isAssigning,
            message = local.message,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AgentsUiState(statusFilter = initialStatusFilter),
    )

    fun setQuery(query: String) = criteria.update { it.copy(query = query) }

    fun setRoleFilter(role: AgentKind?) = criteria.update { it.copy(role = role) }

    fun setStatusFilter(status: AgentStatusFilter) = criteria.update { it.copy(status = status) }

    fun clearFilters() = criteria.update { Criteria("", null, AgentStatusFilter.ALL) }

    /** Gives [modelId] to every agent that has none. */
    fun assignToAgentsWithoutModel(modelId: ModelId) {
        val manager = agentManager ?: return
        if (local.value.isAssigning) return
        local.update { it.copy(isAssigning = true) }
        viewModelScope.launch {
            val text = try {
                val names = assignModelToAgentsWithoutOne(manager, directory, modelId)
                val model = directory.snapshot().modelsById[modelId]?.displayName ?: "The model"
                if (names.isEmpty()) "Every agent already has a model." else "$model assigned to ${summariseNames(names)}."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "Could not assign the model: ${failure.message ?: "storage error"}"
            }
            local.update { it.copy(isAssigning = false, message = UserMessage(text, ++messageCounter)) }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }
}

/** Search looks at what the list shows about an agent: its name, role and description. */
private fun AgentEntry.matches(needle: String): Boolean =
    needle.isEmpty() || profile.name.contains(needle, ignoreCase = true) ||
        profile.description.contains(needle, ignoreCase = true) || profile.kind.label.contains(needle, ignoreCase = true)

internal fun AgentEntry.toRow(snapshot: RegistrySnapshot): AgentRowUi {
    val status = availability.toStatusLabel()
    val unavailable = availability as? AgentAvailability.Unavailable
    return AgentRowUi(
        id = profile.id,
        name = profile.name,
        kind = profile.kind,
        description = profile.description,
        colorIndex = profile.colorIndex,
        model = profile.modelId?.let { snapshot.modelRef(it) },
        status = status,
        statusDetail = unavailable?.takeIf { it.reason != UnavailableReason.NO_MODEL_ASSIGNED }?.detail,
        isDesignatedDirector = isDesignatedDirector,
    )
}

internal fun RegistrySnapshot.modelRef(id: com.aicontrolcenter.core.ModelId): ModelRefUi {
    val model = modelsById[id] ?: return ModelRefUi("Unregistered model", null, null, registered = false)
    val provider = provider(model)
    return ModelRefUi(model.displayName, provider?.name, provider?.effectiveLocality)
}
