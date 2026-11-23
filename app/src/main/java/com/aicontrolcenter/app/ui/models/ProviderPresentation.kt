package com.aicontrolcenter.app.ui.models

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.components.SIMULATOR_LABEL
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ModelSyncReport
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.framework.llm.Simulator
import java.time.ZoneId

/*
 * How providers and models are put into words, shared by the Models tabs, the provider and
 * model screens, the agent editor and the dashboard, so a provider is "Offline" everywhere.
 */

val ModelProvider.isSimulator: Boolean get() = kind == ProviderKind.SIMULATOR

val ModelDescriptor.isSimulator: Boolean get() = id == Simulator.MODEL_ID || source == ModelSource.BUILT_IN

/** Kinds that run inside the app have no address and no key. */
val ProviderKind.hasAddress: Boolean get() = this != ProviderKind.SIMULATOR && this != ProviderKind.ON_DEVICE

/** The provider's status, with "Disabled" winning over whatever was last observed. */
fun ModelProvider.statusLabel(): StatusLabel =
    if (enabled) status.toStatusLabel() else StatusLabel("Disabled", StatusTone.DISABLED)

/** "Checked 14:05", "Checked yesterday 09:12", or "Never checked". */
fun ModelProvider.lastCheckedText(now: Long, zone: ZoneId): String =
    lastCheckedAt?.let { "Checked ${formatCompactTimestamp(it, now, zone)}" } ?: "Never checked"

/** Whether a stored key matters for this provider, and what to say about it. Null for kinds without keys. */
fun ModelProvider.keyNote(keyStored: Boolean?): String? = when {
    !kind.hasAddress || keyStored == null -> null
    keyStored -> "API key stored"
    effectiveLocality == Locality.CLOUD -> "No API key stored"
    else -> "No API key (local servers rarely need one)"
}

/** True when the provider very likely cannot work: a cloud service without a key. */
fun ModelProvider.missingKey(keyStored: Boolean?): Boolean =
    keyStored == false && kind.hasAddress && effectiveLocality == Locality.CLOUD

@Immutable
data class ProviderRowUi(
    val id: ProviderId,
    val name: String,
    val kindLabel: String,
    val address: String?,
    /** Where prompts really go: the more remote of the stored value and what the address implies. */
    val locality: Locality,
    val status: StatusLabel,
    /** The provider's own sentence about its last check, when it was not simply online. */
    val statusMessage: String?,
    val lastChecked: String,
    val keyNote: String?,
    val keyMissing: Boolean,
    val modelCount: Int,
    val enabled: Boolean,
    val isSimulator: Boolean,
)

fun RegistrySnapshot.providerRow(provider: ModelProvider, keyStored: Boolean?, now: Long, zone: ZoneId) = ProviderRowUi(
    id = provider.id,
    name = provider.name,
    kindLabel = if (provider.isSimulator) SIMULATOR_LABEL else provider.kind.label,
    address = provider.baseUrl,
    locality = provider.effectiveLocality,
    status = provider.statusLabel(),
    statusMessage = provider.statusMessage?.takeIf { provider.enabled && provider.status != ConnectionStatus.ONLINE },
    lastChecked = provider.lastCheckedText(now, zone),
    keyNote = provider.keyNote(keyStored),
    keyMissing = provider.missingKey(keyStored),
    modelCount = models.count { it.providerId == provider.id },
    enabled = provider.enabled,
    isSimulator = provider.isSimulator,
)

/** One capability as a chip. [estimated] chips are guesses from the model's name, not facts from the provider. */
@Immutable
data class CapabilityChipUi(val label: String, val estimated: Boolean) {
    /** "≈" marks a guess; the legend under the chips says so in words. */
    val text: String get() = if (estimated) "≈ $label" else label
}

private val CapabilityTier.word: String
    get() = when (this) {
        CapabilityTier.UNKNOWN -> "unknown"
        CapabilityTier.BASIC -> "basic"
        CapabilityTier.GOOD -> "good"
        CapabilityTier.STRONG -> "strong"
    }

/**
 * Capability chips. What a listing reports (tools, vision, context window) counts as reported
 * for listed and built-in models; reasoning and coding tiers are never reported by providers,
 * so they are always estimates. A model typed in by hand, or whose description the owner
 * edited, has nothing reported at all.
 */
fun ModelDescriptor.capabilityChips(): List<CapabilityChipUi> {
    val reported = source == ModelSource.PROVIDER_LISTING || source == ModelSource.BUILT_IN
    val caps = capabilities
    return buildList {
        if (caps.toolCalling) add(CapabilityChipUi("Tool calling", !reported))
        if (caps.vision) add(CapabilityChipUi("Vision", !reported))
        if (caps.reasoning != CapabilityTier.UNKNOWN) add(CapabilityChipUi("Reasoning: ${caps.reasoning.word}", source != ModelSource.BUILT_IN))
        if (caps.coding != CapabilityTier.UNKNOWN) add(CapabilityChipUi("Coding: ${caps.coding.word}", source != ModelSource.BUILT_IN))
        caps.contextWindow?.let { add(CapabilityChipUi(formatContextWindow(it), !reported)) }
    }
}

/** The sentence under the chips that says where they come from. */
fun ModelDescriptor.capabilityNote(): String = when (source) {
    ModelSource.BUILT_IN -> "Declared by the app."
    ModelSource.PROVIDER_LISTING -> "≈ estimated from the model's name. The rest is reported by the provider."
    ModelSource.MANUAL, ModelSource.IMPORTED_FILE -> "≈ estimated from the model's name or set by you; nothing here was reported by the provider."
}

val ModelSource.label: String
    get() = when (this) {
        ModelSource.PROVIDER_LISTING -> "Listed by the provider"
        ModelSource.MANUAL -> "Added or edited by you"
        ModelSource.IMPORTED_FILE -> "Imported file"
        ModelSource.BUILT_IN -> "Built into the app"
    }

/** "8k context" for 8192, "1M context" for a million, "512 context" below a thousand tokens. */
internal fun formatContextWindow(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M context"
    tokens >= 1000 -> "${tokens / 1000}k context"
    else -> "$tokens context"
}

@Immutable
data class ModelRowUi(
    val id: ModelId,
    val name: String,
    /** The identifier sent on the wire, e.g. "gemma3:4b". */
    val wireName: String,
    val providerName: String,
    val locality: Locality?,
    val status: StatusLabel,
    val enabled: Boolean,
    val chips: List<CapabilityChipUi>,
    val capabilityNote: String,
    val agentsUsing: Int,
    val isSimulator: Boolean,
)

fun RegistrySnapshot.modelRow(model: ModelDescriptor): ModelRowUi {
    val provider = provider(model)
    return ModelRowUi(
        id = model.id,
        name = model.displayName,
        wireName = model.modelName,
        providerName = provider?.name ?: "Unregistered provider",
        locality = provider?.effectiveLocality,
        status = if (model.enabled) model.status.toStatusLabel() else StatusLabel("Disabled", StatusTone.DISABLED),
        enabled = model.enabled,
        chips = model.capabilityChips(),
        capabilityNote = model.capabilityNote(),
        agentsUsing = agents.count { it.profile.modelId == model.id || model.id in it.profile.fallbackModelIds },
        isSimulator = model.isSimulator,
    )
}

/** What a model refresh changed, by display name. */
@Immutable
data class SyncReportUi(val added: List<String>, val updated: List<String>, val missing: List<String>) {
    val nothingChanged: Boolean get() = added.isEmpty() && updated.isEmpty() && missing.isEmpty()

    val summary: String
        get() = if (nothingChanged) {
            "The model list is up to date."
        } else {
            "${added.size} added, ${updated.size} updated, ${missing.size} no longer listed."
        }
}

fun ModelSyncReport.toUi() = SyncReportUi(
    added = added.map { it.displayName },
    updated = updated.map { it.displayName },
    missing = missing.map { it.displayName },
)

/** One sentence for the result of a connection check. */
fun ProviderHealth.describe(providerName: String): String = when (status) {
    ConnectionStatus.ONLINE -> "$providerName is online" + (latencyMs?.let { " (answered in $it ms)." } ?: ".")
    ConnectionStatus.UNKNOWN -> message ?: "$providerName was not checked."
    ConnectionStatus.OFFLINE -> "$providerName is offline. " + (message ?: "It could not be reached.")
    ConnectionStatus.AUTH_ERROR -> "$providerName rejected the key. " + (message ?: "Check the API key.")
    ConnectionStatus.ERROR -> "$providerName answered with an error. " + (message ?: "")
}.trim()

/** A human sentence for a failed provider operation, never an exception class name alone. */
fun Throwable.providerFailureText(what: String): String = when (this) {
    is LlmException.BlockedByPolicy -> message ?: "Local-only mode does not allow this."
    is LlmException.Authentication -> "The provider rejected the key: ${message ?: "check the API key."}"
    is LlmException.Network, is LlmException.Timeout -> "Could not reach the provider. ${message.orEmpty()}".trim()
    is LlmException -> "$what failed: ${message ?: "the provider reported an error."}"
    is IllegalArgumentException -> message ?: "That is no longer possible."
    else -> "$what failed: ${message ?: "unexpected error."}"
}
