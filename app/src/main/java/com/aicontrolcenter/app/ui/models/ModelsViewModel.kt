package com.aicontrolcenter.app.ui.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.DetectedServer
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.registry.DiscoveryReport
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.controlChannels
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.settings.FrameworkSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** The three registries shown under "Models". They are different things and never share a list. */
enum class ModelsTab(val title: String) {
    PROVIDERS("Providers"),
    MODELS("Models"),
    APPS("AI apps"),
}

@Immutable
data class AiAppRowUi(
    val id: AiAppId,
    val label: String,
    val packageName: String,
    /** "OpenAI · version 1.2026.300", or whichever parts are known. */
    val vendorAndVersion: String?,
    val status: StatusLabel,
    val sourceLabel: String,
    /** Found by name only and not confirmed; the owner should not assume it really is an AI app. */
    val unverified: Boolean,
    val capabilities: List<String>,
    /** What may be done with the app right now, in words. Empty when nothing may. */
    val permitted: List<String>,
    val enabledInRegistry: Boolean,
)

/** Outcome of the latest scan in this session. */
@Immutable
data class ScanSummaryUi(val added: List<String>, val changed: Int, val missing: List<String>) {
    val nothingChanged: Boolean get() = added.isEmpty() && changed == 0 && missing.isEmpty()
}

/** The models of one provider, as the Models tab groups them. */
@Immutable
data class ProviderGroupUi(
    val providerId: ProviderId,
    val providerName: String,
    val locality: Locality,
    val isSimulator: Boolean,
    val providerEnabled: Boolean,
    val models: List<ModelRowUi>,
)

/** The search for model servers already running on this phone. */
@Immutable
sealed interface DetectionUi {
    data object Searching : DetectionUi

    data class Done(val servers: List<DetectedServer>) : DetectionUi
}

@Immutable
data class ModelsUiState(
    val isLoading: Boolean = true,
    val providers: List<ProviderRowUi> = emptyList(),
    val groups: List<ProviderGroupUi> = emptyList(),
    val apps: List<AiAppRowUi> = emptyList(),
    val isScanning: Boolean = false,
    val lastScan: ScanSummaryUi? = null,
    val detection: DetectionUi? = null,
    val message: UserMessage? = null,
) {
    val modelCount: Int get() = groups.sumOf { it.models.size }
}

/**
 * The Models section: the providers and the models they serve, with what can be done to them
 * from the lists (switching models on and off, adding a model by name, finding servers on this
 * phone), and the registry of AI applications with device scanning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModel(
    directory: RegistryDirectory,
    private val aiAppRegistry: AiAppRegistry,
    private val providerManager: ProviderManager,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private data class Local(
        val isScanning: Boolean = false,
        val lastScan: ScanSummaryUi? = null,
        val detection: DetectionUi? = null,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    /** The registry with whether each provider has a key in the vault (never the key itself). */
    private val registry = directory.observe().mapLatest { snapshot ->
        snapshot to snapshot.providers.associate { it.id to keyStored(it.id) }
    }

    val uiState: StateFlow<ModelsUiState> = combine(registry, local) { (snapshot, keys), local ->
        val now = clock.now()
        ModelsUiState(
            isLoading = false,
            providers = snapshot.providers.map { snapshot.providerRow(it, keys[it.id], now, zone) },
            groups = snapshot.providers.map { provider ->
                ProviderGroupUi(
                    providerId = provider.id,
                    providerName = provider.name,
                    locality = provider.effectiveLocality,
                    isSimulator = provider.isSimulator,
                    providerEnabled = provider.enabled,
                    models = snapshot.models.filter { it.providerId == provider.id }.map(snapshot::modelRow),
                )
            },
            apps = snapshot.apps.map { it.toRow(snapshot.settings) },
            isScanning = local.isScanning,
            lastScan = local.lastScan,
            detection = local.detection,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUiState())

    fun setModelEnabled(id: ModelId, enabled: Boolean) = act("Changing the model") {
        providerManager.setModelEnabled(id, enabled)
        null
    }

    /** Registers a model the provider does not list (or not yet), by the name the provider expects. */
    fun addModel(providerId: ProviderId, modelName: String) = act("Adding the model") {
        val model = providerManager.addModel(providerId, modelName)
        "Added ${model.displayName}."
    }

    /** Probes this phone's loopback addresses for model servers that are not registered yet. Sends nothing off the phone. */
    fun findLocalServers() {
        if (local.value.detection == DetectionUi.Searching) return
        local.update { it.copy(detection = DetectionUi.Searching) }
        viewModelScope.launch {
            try {
                val found = providerManager.detectLocalServers()
                local.update { it.copy(detection = DetectionUi.Done(found)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                local.update { it.copy(detection = null) }
                show(failure.providerFailureText("The search"))
            }
        }
    }

    fun dismissDetection() = local.update { it.copy(detection = null) }

    private suspend fun keyStored(id: ProviderId): Boolean? = try {
        providerManager.hasApiKey(id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The vault could not be read; say nothing rather than something wrong.
        null
    }

    /** Runs a registry change and reports its outcome; [block] returns the success sentence, if any. */
    private fun act(what: String, block: suspend () -> String?) {
        viewModelScope.launch {
            val text = try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure.providerFailureText(what)
            }
            if (text != null) show(text)
        }
    }

    /** Scans the device and merges the result into the registry. The outcome stays on screen as [ModelsUiState.lastScan]. */
    fun scanDevice() {
        if (local.value.isScanning) return
        local.update { it.copy(isScanning = true) }
        viewModelScope.launch {
            try {
                val report = aiAppRegistry.discover()
                local.update {
                    it.copy(
                        isScanning = false,
                        lastScan = ScanSummaryUi(
                            added = report.added.map(AiApp::label),
                            changed = report.updated.size,
                            missing = report.missing.map(AiApp::label),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                local.update {
                    it.copy(
                        isScanning = false,
                        message = UserMessage("The scan failed: ${failure.message ?: "unknown error"}", ++messageCounter),
                    )
                }
            }
        }
    }

    /** Records the owner's word that an app found only by its name really is an AI application. */
    fun confirm(id: AiAppId) {
        viewModelScope.launch {
            try {
                aiAppRegistry.confirm(id)
                show("Confirmed as an AI application.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: IllegalArgumentException) {
                show(refused.message ?: "That app is no longer registered.")
            } catch (failure: Exception) {
                show("Could not confirm the app: ${failure.message ?: "unknown error"}")
            }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun show(text: String) = local.update { it.copy(message = UserMessage(text, ++messageCounter)) }
}

/** One sentence for a finished scan: how many apps are new, changed and gone. */
fun DiscoveryReport.describe(): String =
    if (added.isEmpty() && updated.isEmpty() && missing.isEmpty()) {
        "Scan complete. Nothing has changed."
    } else {
        "Scan complete: ${added.size} new, ${updated.size} changed, ${missing.size} no longer installed."
    }

/** [settings] decides what is permitted right now: accessibility needs both operator switches. */
internal fun AiApp.toRow(settings: FrameworkSettings): AiAppRowUi = AiAppRowUi(
    id = id,
    label = label,
    packageName = packageName,
    vendorAndVersion = listOfNotNull(vendor, versionName?.let { "version $it" }).joinToString(" · ").ifEmpty { null },
    status = status.toStatusLabel(),
    sourceLabel = source.label,
    unverified = isUnverified,
    capabilities = capabilities.sortedBy { it.ordinal }.map { it.label },
    permitted = controlChannels(settings).sortedBy { it.ordinal }.map { it.label },
    enabledInRegistry = enabled,
)
