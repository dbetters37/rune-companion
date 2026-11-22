package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.llm.DetectedServer
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ModelSyncReport
import com.aicontrolcenter.core.llm.ProviderDraft
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.llm.ProviderPreset
import com.aicontrolcenter.core.llm.ProviderValidationException
import com.aicontrolcenter.core.llm.ProviderValidationIssue
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.modelId
import com.aicontrolcenter.core.providerId
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connects, checks and removes providers and keeps their models. See [ProviderManager].
 *
 * Decisions worth knowing:
 *  - Methods taking an id throw [IllegalArgumentException] when nothing has that id, except
 *    the remove methods, which then do nothing.
 *  - The network is never touched while the registry lock is held, and never for a provider
 *    off this device while local-only mode is on: a health check or a model listing sends the
 *    key to the provider, which is exactly what that mode forbids.
 *  - [ModelDescriptor] has no field for "edited by the owner", so a listed model whose name or
 *    capabilities the owner changes becomes a [ModelSource.MANUAL] model. Refreshing never
 *    overwrites the description of anything but [ModelSource.PROVIDER_LISTING] models, and only
 *    those are marked unavailable when the provider stops listing them (see [ModelListingMerge]).
 */
class DefaultProviderManager(
    private val providers: ProviderRepository,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val vault: SecretVault,
    private val connections: ProviderConnections,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    override val presets: List<ProviderPreset> = ProviderPresets.all,
    localProbes: List<ProviderPreset> = ProviderPresets.localProbes,
) : ProviderManager {

    private val localServers = LocalServerDetector(providers, connections, localProbes)
    private val listingMerge = ModelListingMerge(ids)

    // ---- providers ----

    override suspend fun validate(draft: ProviderDraft, editing: ProviderId?): List<ProviderValidationIssue> {
        val all = providers.getAll()
        return ProviderDraftValidator.validate(draft, all.filter { it.id != editing }, storedKey = hasStoredKey(all.firstOrNull { it.id == editing }))
    }

    override suspend fun addProvider(draft: ProviderDraft): ModelProvider = connections.changeRecords {
        requireValid(draft, editing = null)
        val id = ids.providerId()
        val key = ProviderDraftValidator.normaliseKey(draft.apiKey)
        val baseUrl = ProviderDraftValidator.normaliseBaseUrl(draft.baseUrl)
        val now = nextCreationTime(providers.getAll().maxOfOrNull { it.createdAt })
        val provider = ModelProvider(
            id = id, name = draft.name.trim(), kind = draft.kind, baseUrl = baseUrl,
            apiKeyAlias = key?.let { keyAlias(id) }, extraHeaders = normaliseHeaders(draft.extraHeaders),
            locality = Locality.infer(draft.kind, baseUrl), createdAt = now, updatedAt = now,
        )
        withContext(NonCancellable) {
            if (key != null) vault.put(keyAlias(id), key)
            try {
                providers.upsert(provider)
            } catch (e: Exception) {
                // No record means nothing would ever delete the key again.
                if (key != null) vault.delete(keyAlias(id))
                throw e
            }
            val keyNote = if (key != null) ", API key saved" else ""
            activity.record(ActivityType.REGISTRY, "Provider added", "${provider.name} (${provider.kind.label}, ${provider.locality.label}$keyNote)")
        }
        provider
    }

    override suspend fun updateProvider(id: ProviderId, draft: ProviderDraft): ModelProvider = connections.changeRecords {
        val current = providers.get(id) ?: throw IllegalArgumentException(PROVIDER_GONE)
        requireValid(draft, editing = id)
        val newKey = ProviderDraftValidator.normaliseKey(draft.apiKey)
        val removeKey = draft.apiKey != null && newKey == null
        val baseUrl = ProviderDraftValidator.normaliseBaseUrl(draft.baseUrl)
        val alias = when {
            newKey != null -> current.apiKeyAlias ?: keyAlias(id)
            removeKey -> null
            else -> current.apiKeyAlias
        }
        val edited = current.copy(
            name = draft.name.trim(), kind = draft.kind, baseUrl = baseUrl, apiKeyAlias = alias,
            extraHeaders = normaliseHeaders(draft.extraHeaders), locality = Locality.infer(draft.kind, baseUrl),
        )
        val changes = buildList {
            if (edited.name != current.name) add("name")
            if (edited.kind != current.kind) add("kind")
            if (edited.baseUrl != current.baseUrl) add("address")
            if (edited.extraHeaders != current.extraHeaders) add("headers")
            if (newKey != null) add(if (current.apiKeyAlias == null) "API key saved" else "API key replaced")
            if (removeKey && current.apiKeyAlias != null) add("API key removed")
        }
        if (changes.isEmpty()) return@changeRecords current
        // What was learned about the old connection says nothing about the new one.
        val reconnects = changes.any { it != "name" }
        val updated = edited.copy(
            status = if (reconnects) ConnectionStatus.UNKNOWN else current.status,
            statusMessage = if (reconnects) null else current.statusMessage,
            lastCheckedAt = if (reconnects) null else current.lastCheckedAt,
            updatedAt = clock.now(),
        )
        withContext(NonCancellable) {
            if (newKey != null) vault.put(alias!!, newKey)
            providers.upsert(updated)
            if (removeKey) current.apiKeyAlias?.let { vault.delete(it) }
            activity.record(ActivityType.REGISTRY, "Provider updated", "${updated.name}: ${changes.joinToString(", ")}")
        }
        updated
    }

    override suspend fun setProviderEnabled(id: ProviderId, enabled: Boolean) = connections.changeRecords {
        val current = providers.get(id) ?: throw IllegalArgumentException(PROVIDER_GONE)
        if (current.enabled == enabled) return@changeRecords
        providers.upsert(current.copy(enabled = enabled, updatedAt = clock.now()))
        activity.record(ActivityType.REGISTRY, if (enabled) "Provider enabled" else "Provider disabled", current.name)
        Unit
    }

    override suspend fun removeProvider(id: ProviderId) {
        connections.changeRecords {
            val provider = providers.get(id) ?: return@changeRecords
            // Once started, the cascade must finish: a provider without its models, or a key without its provider, is worse than a slow cancel.
            withContext(NonCancellable) {
                val modelCount = models.getByProvider(id).size
                models.deleteByProvider(id)
                providers.delete(id)
                setOfNotNull(provider.apiKeyAlias, keyAlias(id)).forEach { vault.delete(it) }
                val modelNote = if (modelCount == 1) "1 model" else "$modelCount models"
                activity.record(ActivityType.REGISTRY, "Provider removed", "${provider.name} and its $modelNote. Agents that used them need another model.")
            }
            Unit
        }
        connections.evict(id)
    }

    override suspend fun hasApiKey(id: ProviderId): Boolean = hasStoredKey(providers.get(id))

    // ---- connectivity ----

    override suspend fun checkProvider(id: ProviderId): ProviderHealth =
        check(providers.get(id) ?: throw IllegalArgumentException(PROVIDER_GONE))

    override suspend fun checkAllProviders() {
        coroutineScope {
            providers.getAll().filter { it.enabled }.map { async { check(it) } }.awaitAll()
        }
    }

    private suspend fun check(provider: ModelProvider): ProviderHealth {
        blockedByLocalOnly(provider)?.let { return ProviderHealth(provider.status, it) }
        val health = probe(provider)
        val before = connections.storeStatus(provider.id, health.status, health.message) ?: return health
        if (before.status != health.status) {
            val detail = "${provider.name}: ${health.status.label}" + (health.message?.let { " ($it)" } ?: "")
            val severity = if (health.status == ConnectionStatus.ONLINE || health.status == ConnectionStatus.UNKNOWN) Severity.INFO else Severity.WARNING
            activity.record(ActivityType.REGISTRY, "Provider status changed", detail, severity = severity)
        }
        return health
    }

    /** Asks the adapter for the provider's health. Never throws, and never lets the key into the message. */
    private suspend fun probe(provider: ModelProvider): ProviderHealth {
        val lease = try {
            connections.acquire(provider)
        } catch (e: LlmException) {
            return ProviderHealth(statusOf(e), e.message)
        }
        val health = try {
            withTimeoutOrNull(CHECK_TIMEOUT_MS) { lease.adapter.checkHealth() }
                ?: ProviderHealth(ConnectionStatus.OFFLINE, "${provider.name} did not answer in time.")
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            ProviderHealth(ConnectionStatus.ERROR, "The check failed unexpectedly (${e::class.simpleName}).")
        }
        return health.copy(message = health.message?.let(lease::redact))
    }

    private fun statusOf(error: LlmException): ConnectionStatus = when (error) {
        is LlmException.Authentication -> ConnectionStatus.AUTH_ERROR
        is LlmException.Network, is LlmException.Timeout -> ConnectionStatus.OFFLINE
        else -> ConnectionStatus.ERROR
    }

    private suspend fun blockedByLocalOnly(provider: ModelProvider): String? =
        if (provider.effectiveLocality.leavesDevice && settings.get().localOnlyMode) {
            "Local-only mode is on, so ${provider.name} was not contacted."
        } else {
            null
        }

    override suspend fun detectLocalServers(): List<DetectedServer> = localServers.detect()

    // ---- models ----

    override suspend fun refreshModels(id: ProviderId): ModelSyncReport {
        val provider = providers.get(id) ?: throw IllegalArgumentException(PROVIDER_GONE)
        blockedByLocalOnly(provider)?.let { throw LlmException.BlockedByPolicy(it) }
        val lease = connections.acquire(provider)
        val listed = try {
            lease.adapter.listModels()
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            val failure = e.asLlmException(provider.name, lease)
            if (failure is LlmException.Authentication) connections.storeStatus(id, ConnectionStatus.AUTH_ERROR, failure.message)
            throw failure
        }
        val report = connections.changeRecords { merge(provider, listed) }
        // A listing that came back is as good as a passed health check.
        if (providers.get(id)?.status != ConnectionStatus.ONLINE) connections.storeStatus(id, ConnectionStatus.ONLINE, null)
        return report
    }

    private suspend fun merge(provider: ModelProvider, listed: List<RemoteModel>): ModelSyncReport {
        if (providers.get(provider.id) == null) throw IllegalArgumentException(PROVIDER_GONE)
        val changes = listingMerge.changes(
            provider, existing = models.getByProvider(provider.id), listed = listed,
            now = clock.now(), firstCreationTime = nextCreationTime(models.getAll().maxOfOrNull { it.createdAt }),
        )
        if (!changes.isEmpty) {
            withContext(NonCancellable) {
                models.upsertAll(changes.toStore)
                activity.record(
                    ActivityType.REGISTRY, "Models refreshed",
                    "${provider.name}: ${changes.added.size} added, ${changes.updated.size} updated, ${changes.newlyMissing.size} no longer listed",
                )
            }
        }
        return changes.report()
    }

    override suspend fun addModel(providerId: ProviderId, modelName: String, displayName: String?, capabilities: ModelCapabilities?): ModelDescriptor =
        connections.changeRecords {
            val provider = providers.get(providerId) ?: throw IllegalArgumentException(PROVIDER_GONE)
            val name = modelName.trim()
            require(name.isNotEmpty()) { "Enter the model's name as the provider expects it." }
            require(models.getByProvider(providerId).none { it.modelName == name }) { "${provider.name} already has a model called \"$name\"." }
            val now = nextCreationTime(models.getAll().maxOfOrNull { it.createdAt })
            val model = ModelDescriptor(
                id = ids.modelId(), providerId = providerId, modelName = name,
                displayName = displayName?.trim()?.ifEmpty { null } ?: name,
                capabilities = capabilities ?: ModelCapabilityEstimator.estimate(provider.kind, name),
                source = ModelSource.MANUAL, createdAt = now, updatedAt = now,
            )
            models.upsert(model)
            activity.record(ActivityType.REGISTRY, "Model added", "${model.displayName} on ${provider.name}")
            model
        }

    override suspend fun updateModel(id: ModelId, displayName: String, capabilities: ModelCapabilities, notes: String) = connections.changeRecords {
        val current = models.get(id) ?: throw IllegalArgumentException(MODEL_GONE)
        val name = displayName.trim()
        require(name.isNotEmpty()) { "Give the model a display name." }
        val described = name != current.displayName || capabilities != current.capabilities
        val updated = current.copy(
            displayName = name, capabilities = capabilities, notes = notes.trim(),
            // From here on the owner's description stands; see the class comment.
            source = if (described && current.source == ModelSource.PROVIDER_LISTING) ModelSource.MANUAL else current.source,
        )
        if (updated == current) return@changeRecords
        models.upsert(updated.copy(updatedAt = clock.now()))
        activity.record(ActivityType.REGISTRY, "Model updated", updated.displayName)
        Unit
    }

    override suspend fun setModelEnabled(id: ModelId, enabled: Boolean) = connections.changeRecords {
        val current = models.get(id) ?: throw IllegalArgumentException(MODEL_GONE)
        if (current.enabled == enabled) return@changeRecords
        models.upsert(current.copy(enabled = enabled, updatedAt = clock.now()))
        activity.record(ActivityType.REGISTRY, if (enabled) "Model enabled" else "Model disabled", current.displayName)
        Unit
    }

    override suspend fun removeModel(id: ModelId) = connections.changeRecords {
        val current = models.get(id) ?: return@changeRecords
        models.delete(id)
        activity.record(ActivityType.REGISTRY, "Model removed", "${current.displayName}. Agents that used it need another model.")
        Unit
    }

    // ---- built-ins ----

    override suspend fun ensureBuiltIns() = connections.changeRecords {
        val existing = providers.getAll()
        if (existing.none { it.id == Simulator.PROVIDER_ID }) {
            val now = nextCreationTime(existing.maxOfOrNull { it.createdAt })
            val taken = existing.any { ProviderDraftValidator.nameKey(it.name) == ProviderDraftValidator.nameKey(Simulator.PROVIDER_NAME) }
            providers.upsert(
                ModelProvider(
                    id = Simulator.PROVIDER_ID, name = if (taken) "${Simulator.PROVIDER_NAME} (built-in)" else Simulator.PROVIDER_NAME,
                    kind = ProviderKind.SIMULATOR, locality = Locality.ON_DEVICE, status = ConnectionStatus.ONLINE,
                    createdAt = now, updatedAt = now,
                ),
            )
            activity.record(ActivityType.REGISTRY, "Simulator registered", "A built-in stand-in for trying agents without a real model.")
        }
        if (models.getByProvider(Simulator.PROVIDER_ID).none { it.modelName == Simulator.MODEL_NAME }) {
            val now = nextCreationTime(models.getAll().maxOfOrNull { it.createdAt })
            models.upsert(
                ModelDescriptor(
                    id = Simulator.MODEL_ID, providerId = Simulator.PROVIDER_ID, modelName = Simulator.MODEL_NAME,
                    displayName = Simulator.MODEL_DISPLAY_NAME, capabilities = Simulator.capabilities, source = ModelSource.BUILT_IN,
                    status = ModelStatus.AVAILABLE, notes = "Produces labelled, scripted replies. Not a real model.",
                    createdAt = now, updatedAt = now,
                ),
            )
        }
        Unit
    }

    // ---- helpers ----

    private suspend fun requireValid(draft: ProviderDraft, editing: ProviderId?) {
        val issues = validate(draft, editing)
        if (issues.isNotEmpty()) throw ProviderValidationException(issues)
    }

    private suspend fun hasStoredKey(provider: ModelProvider?): Boolean = provider?.apiKeyAlias?.let { vault.contains(it) } ?: false

    private fun keyAlias(id: ProviderId): String = "provider/${id.value}"

    private fun normaliseHeaders(headers: Map<String, String>): Map<String, String> =
        headers.entries.associate { it.key.trim() to it.value.trim() }

    /** Strictly after everything that exists, so creation order is listing order even within one clock tick. */
    private fun nextCreationTime(latest: Long?): Long = maxOf(clock.now(), (latest ?: Long.MIN_VALUE) + 1)

    private companion object {
        const val PROVIDER_GONE = "That provider no longer exists."
        const val MODEL_GONE = "That model no longer exists."
        const val CHECK_TIMEOUT_MS = 20_000L
    }
}
