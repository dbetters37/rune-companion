package com.aicontrolcenter.app.ui.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Agents that depend on a provider's models, for the removal warning. */
@Immutable
data class ProviderDependents(
    /** Every model they were given is on this provider: removing it leaves them with none. */
    val leftWithoutModel: List<String>,
    /** Their primary model is here, but a fallback elsewhere would take over. */
    val fallingBack: List<String>,
) {
    val isEmpty: Boolean get() = leftWithoutModel.isEmpty() && fallingBack.isEmpty()
}

@Immutable
data class ProviderDetailUi(
    val row: ProviderRowUi,
    val models: List<ModelRowUi>,
    val dependents: ProviderDependents,
    /** Local-only mode is on and this provider would send data off the device, so it is never contacted. */
    val blockedByLocalOnly: Boolean,
) {
    val id: ProviderId get() = row.id
    val name: String get() = row.name
    val status: StatusLabel get() = row.status
    val locality: Locality get() = row.locality
}

@Immutable
data class ProviderDetailUiState(
    val isLoading: Boolean = true,
    /** Null after loading means the provider is not in the registry. */
    val provider: ProviderDetailUi? = null,
    val isChecking: Boolean = false,
    val isRefreshing: Boolean = false,
    /** The sentence from the last check made on this screen. */
    val lastCheck: String? = null,
    val lastSync: SyncReportUi? = null,
    /** Set once after this screen removed the provider; the route leaves. */
    val removed: Boolean = false,
    val message: UserMessage? = null,
)

/** One provider: its connection, its models, and every action on it through [ProviderManager]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderDetailViewModel(
    private val providerId: ProviderId,
    directory: RegistryDirectory,
    private val providerManager: ProviderManager,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private data class Local(
        val isChecking: Boolean = false,
        val isRefreshing: Boolean = false,
        val lastCheck: String? = null,
        val lastSync: SyncReportUi? = null,
        val removed: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    private val registry = directory.observe().mapLatest { snapshot ->
        val keyStored = if (snapshot.providersById[providerId] == null) null else keyStored()
        snapshot to keyStored
    }

    val uiState: StateFlow<ProviderDetailUiState> = combine(registry, local) { (snapshot, keyStored), local ->
        ProviderDetailUiState(
            isLoading = false,
            provider = snapshot.detail(keyStored),
            isChecking = local.isChecking,
            isRefreshing = local.isRefreshing,
            lastCheck = local.lastCheck,
            lastSync = local.lastSync,
            removed = local.removed,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProviderDetailUiState())

    private fun RegistrySnapshot.detail(keyStored: Boolean?): ProviderDetailUi? {
        val provider = providersById[providerId] ?: return null
        val row = providerRow(provider, keyStored, clock.now(), zone)
        val here = models.filter { it.providerId == providerId }.map { it.id }.toSet()
        val dependent = agents.filter { it.profile.modelId in here || it.profile.fallbackModelIds.any { id -> id in here } }
        val (stranded, falling) = dependent
            .filter { it.profile.modelId in here }
            .partition { entry -> entry.profile.fallbackModelIds.all { it in here } }
        return ProviderDetailUi(
            row = row,
            models = models.filter { it.providerId == providerId }.map(::modelRow),
            dependents = ProviderDependents(stranded.map { it.profile.name }, falling.map { it.profile.name }),
            blockedByLocalOnly = settings.localOnlyMode && row.locality.leavesDevice,
        )
    }

    /** Checks connectivity and credentials now and says what was found. */
    fun checkNow() {
        if (local.value.isChecking) return
        local.update { it.copy(isChecking = true) }
        viewModelScope.launch {
            val name = uiState.value.provider?.name ?: "The provider"
            val text = try {
                providerManager.checkProvider(providerId).describe(name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure.providerFailureText("The check")
            }
            local.update { it.copy(isChecking = false, lastCheck = text) }
        }
    }

    /** Fetches the model list and shows what changed. */
    fun refreshModels() {
        if (local.value.isRefreshing) return
        local.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                val report = providerManager.refreshModels(providerId).toUi()
                local.update { it.copy(isRefreshing = false, lastSync = report) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                local.update { it.copy(isRefreshing = false, message = message(failure.providerFailureText("Refreshing the models"))) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) = act("Changing the provider") {
        providerManager.setProviderEnabled(providerId, enabled)
        null
    }

    fun setModelEnabled(id: ModelId, enabled: Boolean) = act("Changing the model") {
        providerManager.setModelEnabled(id, enabled)
        null
    }

    fun addModel(name: String) = act("Adding the model") {
        "Added ${providerManager.addModel(providerId, name).displayName}."
    }

    /** Removes the provider, its models and its stored key. */
    fun remove() = act("Removing the provider") {
        providerManager.removeProvider(providerId)
        local.update { it.copy(removed = true) }
        null
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private suspend fun keyStored(): Boolean? = try {
        providerManager.hasApiKey(providerId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun act(what: String, block: suspend () -> String?) {
        viewModelScope.launch {
            val text = try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure.providerFailureText(what)
            }
            if (text != null) local.update { it.copy(message = message(text)) }
        }
    }

    private fun message(text: String) = UserMessage(text, ++messageCounter)
}
