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
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.registry.DiscoveryReport
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.registry.controlChannels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The three registries shown under "Models". They are different things and never share a list. */
enum class ModelsTab(val title: String) {
    PROVIDERS("Providers"),
    MODELS("Models"),
    APPS("AI apps"),
}

@Immutable
data class ProviderRowUi(
    val id: ProviderId,
    val name: String,
    val kindLabel: String,
    val address: String?,
    val locality: Locality,
    val status: StatusLabel,
    val modelCount: Int,
)

@Immutable
data class ModelRowUi(
    val id: ModelId,
    val name: String,
    /** The identifier sent on the wire, e.g. "gemma3:4b". */
    val wireName: String,
    val providerName: String,
    val locality: Locality?,
    val status: StatusLabel,
    val traits: List<String>,
    val agentsUsing: Int,
)

@Immutable
data class AiAppRowUi(
    val id: AiAppId,
    val label: String,
    val packageName: String,
    /** "OpenAI · version 1.2026.300", or whichever parts are known. */
    val vendorAndVersion: String?,
    val status: StatusLabel,
    val sourceLabel: String,
    /** Found by name only; the owner should not assume it really is an AI app. */
    val unverified: Boolean,
    val capabilities: List<String>,
    /** What may be done with the app, in words. Empty when nothing may. */
    val permitted: List<String>,
    val enabledInRegistry: Boolean,
)

/** Outcome of the latest scan in this session. */
@Immutable
data class ScanSummaryUi(val added: List<String>, val changed: Int, val missing: List<String>) {
    val nothingChanged: Boolean get() = added.isEmpty() && changed == 0 && missing.isEmpty()
}

@Immutable
data class ModelsUiState(
    val isLoading: Boolean = true,
    val providers: List<ProviderRowUi> = emptyList(),
    val models: List<ModelRowUi> = emptyList(),
    val apps: List<AiAppRowUi> = emptyList(),
    val isScanning: Boolean = false,
    val lastScan: ScanSummaryUi? = null,
    val message: UserMessage? = null,
)

/**
 * The Models section: providers and models as the registry holds them, and the registry of AI
 * applications with device scanning.
 */
class ModelsViewModel(
    directory: RegistryDirectory,
    private val aiAppRegistry: AiAppRegistry,
) : ViewModel() {

    private data class Local(
        val isScanning: Boolean = false,
        val lastScan: ScanSummaryUi? = null,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    val uiState: StateFlow<ModelsUiState> = combine(directory.observe(), local) { snapshot, local ->
        ModelsUiState(
            isLoading = false,
            providers = snapshot.providerRows(),
            models = snapshot.modelRows(),
            apps = snapshot.apps.map { it.toRow() },
            isScanning = local.isScanning,
            lastScan = local.lastScan,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUiState())

    /** Scans the device and merges the result into the registry. */
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
                        message = UserMessage(report.describe(), ++messageCounter),
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

    fun messageShown() = local.update { it.copy(message = null) }
}

/** One sentence for a finished scan: how many apps are new, changed and gone. */
fun DiscoveryReport.describe(): String =
    if (added.isEmpty() && updated.isEmpty() && missing.isEmpty()) {
        "Scan complete. Nothing has changed."
    } else {
        "Scan complete: ${added.size} new, ${updated.size} changed, ${missing.size} no longer installed."
    }

private fun RegistrySnapshot.providerRows(): List<ProviderRowUi> = providers.map { provider ->
    ProviderRowUi(
        id = provider.id,
        name = provider.name,
        kindLabel = provider.kind.label,
        address = provider.baseUrl,
        locality = provider.locality,
        status = if (provider.enabled) provider.status.toStatusLabel() else StatusLabel("Disabled", StatusTone.DISABLED),
        modelCount = models.count { it.providerId == provider.id },
    )
}

private fun RegistrySnapshot.modelRows(): List<ModelRowUi> = models.map { model ->
    val provider = provider(model)
    val capabilities = model.capabilities
    ModelRowUi(
        id = model.id,
        name = model.displayName,
        wireName = model.modelName,
        providerName = provider?.name ?: "Unregistered provider",
        locality = provider?.locality,
        status = if (model.enabled) model.status.toStatusLabel() else StatusLabel("Disabled", StatusTone.DISABLED),
        traits = buildList {
            if (capabilities.toolCalling) add("Tool calling")
            if (capabilities.vision) add("Vision")
            if (capabilities.jsonOutput) add("JSON output")
            if (capabilities.embeddings) add("Embeddings")
            capabilities.contextWindow?.let { add("${it / 1000}k context") }
        },
        agentsUsing = agents.count { it.profile.modelId == model.id },
    )
}

internal fun AiApp.toRow(): AiAppRowUi = AiAppRowUi(
    id = id,
    label = label,
    packageName = packageName,
    vendorAndVersion = listOfNotNull(vendor, versionName?.let { "version $it" }).joinToString(" · ").ifEmpty { null },
    status = status.toStatusLabel(),
    sourceLabel = source.label,
    unverified = source == DiscoverySource.HEURISTIC,
    capabilities = capabilities.sortedBy { it.ordinal }.map { it.label },
    permitted = controlChannels().sortedBy { it.ordinal }.map { it.label },
    enabledInRegistry = enabled,
)
