package com.aicontrolcenter.core.llm

import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.health.TimedWaits
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind

/** What the owner enters to connect a provider. */
data class ProviderDraft(
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String? = null,
    /** Null leaves the stored key unchanged when editing; empty removes it. */
    val apiKey: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
)

/** A pre-filled starting point for a well-known service or local server. */
data class ProviderPreset(
    val id: String,
    val title: String,
    val description: String,
    val kind: ProviderKind,
    val baseUrl: String?,
    val needsApiKey: Boolean,
    /** Where to get a key or how to start the server, in one sentence. */
    val setupHint: String,
)

data class ProviderValidationIssue(val field: ProviderField, val message: String)

enum class ProviderField { NAME, BASE_URL, API_KEY, HEADERS }

class ProviderValidationException(val issues: List<ProviderValidationIssue>) :
    IllegalArgumentException(issues.joinToString("; ") { it.message })

/** What changed when a provider's model list was refreshed. */
data class ModelSyncReport(
    val added: List<ModelDescriptor>,
    val updated: List<ModelDescriptor>,
    /** Previously listed models the provider no longer reports. They stay registered, marked unavailable. */
    val missing: List<ModelDescriptor>,
)

/** A model server found by probing well-known local addresses. */
data class DetectedServer(val presetId: String, val title: String, val baseUrl: String, val kind: ProviderKind, val modelCount: Int)

/**
 * Connects, checks and removes model providers and maintains their models. API keys go
 * straight into the secret vault; a provider record only ever holds the alias.
 */
interface ProviderManager {
    val presets: List<ProviderPreset>

    suspend fun validate(draft: ProviderDraft, editing: ProviderId? = null): List<ProviderValidationIssue>

    /** @throws ProviderValidationException */
    suspend fun addProvider(draft: ProviderDraft): ModelProvider

    /** @throws ProviderValidationException */
    suspend fun updateProvider(id: ProviderId, draft: ProviderDraft): ModelProvider

    suspend fun setProviderEnabled(id: ProviderId, enabled: Boolean)

    /** Removes the provider, its models and its stored key. Agents using those models become unavailable. */
    suspend fun removeProvider(id: ProviderId)

    /** Checks connectivity and credentials, stores the resulting status and returns it. */
    suspend fun checkProvider(id: ProviderId): ProviderHealth

    /** Checks every enabled provider. */
    suspend fun checkAllProviders()

    /** Fetches the provider's model list and merges it into the registry. @throws LlmException */
    suspend fun refreshModels(id: ProviderId): ModelSyncReport

    /** Registers a model by name for providers that cannot list models, or to add one early. */
    suspend fun addModel(providerId: ProviderId, modelName: String, displayName: String? = null, capabilities: ModelCapabilities? = null): ModelDescriptor

    suspend fun updateModel(id: ModelId, displayName: String, capabilities: ModelCapabilities, notes: String)

    suspend fun setModelEnabled(id: ModelId, enabled: Boolean)

    suspend fun removeModel(id: ModelId)

    /** Whether a key is stored for this provider. Never returns the key. */
    suspend fun hasApiKey(id: ProviderId): Boolean

    /** Probes this phone's loopback addresses for known local model servers that are not registered yet. */
    suspend fun detectLocalServers(): List<DetectedServer>

    /** Makes sure the built-in simulator provider and model exist. Safe to call on every start. */
    suspend fun ensureBuiltIns()

    /**
     * Checks provider [providerId] again if its stored status is older than [olderThanMs] (or it
     * was never checked), within a bounded time, and returns the status it has now; null when the
     * provider is unknown or nothing was checked (Phase 6, K15, D28). Never checks a provider off
     * the phone in local-only mode, and never a provider whose key was rejected. Work about to
     * wait for a provider that is not answering asks this first. The default checks nothing.
     */
    suspend fun recheckIfStale(providerId: ProviderId, olderThanMs: Long): ConnectionStatus? = null

    /** When the next scheduled re-check of a provider that is not answering is due (rule 5). The default: none. */
    val timedWaits: TimedWaits get() = TimedWaits.None

    /**
     * True when [text] contains, or is, one of the provider keys stored in the vault, compared in
     * constant time (Phase 6, rule 32: an answer to an agent's question that holds a stored key is
     * never sent). For the owner's side only; never returns or logs a key. The default: false.
     */
    suspend fun matchesStoredKey(text: String): Boolean = false
}
