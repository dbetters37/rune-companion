package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
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
import com.aicontrolcenter.core.security.SecretVaultException
import com.aicontrolcenter.core.settings.SettingsView
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Connects, checks and removes providers and keeps their models. See [ProviderManager].
 *
 * Decisions worth knowing:
 *  - Methods taking an id throw [IllegalArgumentException] when nothing has that id, except
 *    the remove methods, which then do nothing.
 *  - The network is never touched while the registry lock is held, and never for a provider
 *    off this device while local-only mode is on: a health check or a model listing sends the
 *    key to the provider, which is exactly what that mode forbids.
 *  - Every check and listing that reaches a provider off this device is logged with where it
 *    went, whether it worked or not, like a model call: it sent the key there.
 *  - A stored key is never moved to another address: an edit that points the provider at
 *    another host, or makes it another kind, must come with the key again (or remove it).
 *  - A key is never written over the one a stored record points at. Every new key goes under a
 *    new alias (`provider/<id>/<n>`); then the record is changed to point at it; only then is
 *    the previous key deleted. A record write that fails deletes the new key again, so the old
 *    record keeps its old key, and a process that dies half-way leaves at worst a key that
 *    nothing points at, which [ensureBuiltIns] deletes at the next start ([deleteUnusedKeys]).
 *    Records saved before this, whose alias is `provider/<id>`, keep working unchanged.
 *  - [ModelDescriptor] has no field for "edited by the owner", so a listed model whose name or
 *    capabilities the owner changes becomes a [ModelSource.MANUAL] model. Refreshing never
 *    overwrites the description of anything but [ModelSource.PROVIDER_LISTING] models, and only
 *    those are marked unavailable when the provider stops listing them (see [ModelListingMerge]).
 */
class DefaultProviderManager(
    private val providers: ProviderRepository,
    private val models: ModelRepository,
    private val settings: SettingsView,
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

    /** Checking a provider, shared with the agent side's re-checks (Phase 6, D28), which hold no manager and no vault. */
    private val checks = ProviderChecks(settings, connections, activity)

    // ---- providers ----

    override suspend fun validate(draft: ProviderDraft, editing: ProviderId?): List<ProviderValidationIssue> {
        val all = providers.getAll()
        val current = all.firstOrNull { it.id == editing }
        return ProviderDraftValidator.validate(draft, all.filter { it.id != editing }, storedKey = hasStoredKey(current), current = current)
    }

    override suspend fun addProvider(draft: ProviderDraft): ModelProvider = connections.changeRecords {
        requireValid(draft, editing = null)
        val id = ids.providerId()
        val key = ProviderDraftValidator.normaliseKey(draft.apiKey)
        val baseUrl = ProviderDraftValidator.normaliseBaseUrl(draft.baseUrl)
        val now = nextCreationTime(providers.getAll().maxOfOrNull { it.createdAt })
        val alias = key?.let { nextKeyAlias(id, current = null) }
        val provider = ModelProvider(
            id = id, name = draft.name.trim(), kind = draft.kind, baseUrl = baseUrl,
            apiKeyAlias = alias, extraHeaders = normaliseHeaders(draft.extraHeaders),
            locality = Locality.infer(draft.kind, baseUrl), createdAt = now, updatedAt = now,
        )
        withContext(NonCancellable) {
            if (alias != null) vault.put(alias, key)
            try {
                providers.upsert(provider)
            } catch (e: Exception) {
                // No record means nothing would ever delete the key again.
                if (alias != null) discardKey(alias, e)
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
        // A new key never overwrites the one the stored record points at; see the class comment.
        val alias = when {
            newKey != null -> nextKeyAlias(id, current.apiKeyAlias)
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
            try {
                providers.upsert(updated)
            } catch (e: Exception) {
                // The stored record still points at its own key; the new one must not outlive the failed edit.
                if (newKey != null) discardKey(alias!!, e)
                throw e
            }
            // Only now does nothing point at the previous key any more.
            current.apiKeyAlias?.takeIf { it != alias }?.let { vault.delete(it) }
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
                // Every version of the provider's key goes, not only the current one: an earlier
                // failed clean-up may have left older versions, and once the record is gone the
                // start-up clean-up can no longer tell whose they were.
                val versions = try {
                    vault.aliases().filter { isKeyAliasOf(id, it) }
                } catch (e: SecretVaultException) {
                    emptyList()
                }
                (setOfNotNull(provider.apiKeyAlias, keyAlias(id)) + versions).forEach { vault.delete(it) }
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

    override suspend fun checkAllProviders() = checkAllProviders(includeCloud = true)

    /**
     * Checks every enabled provider, or with [includeCloud] false only those on this phone or the
     * owner's network. A check sends the provider's key to its address, so an app that should not
     * contact internet services without being asked, at start-up for instance, passes false.
     */
    suspend fun checkAllProviders(includeCloud: Boolean) {
        coroutineScope {
            providers.getAll()
                .filter { it.enabled && (includeCloud || it.effectiveLocality != Locality.CLOUD) }
                .map { async { check(it) } }
                .awaitAll()
        }
    }

    private suspend fun check(provider: ModelProvider): ProviderHealth = checks.check(provider, CHECK_TIMEOUT_MS)

    private suspend fun blockedByLocalOnly(provider: ModelProvider): String? = checks.blockedByLocalOnly(provider)

    override suspend fun detectLocalServers(): List<DetectedServer> = localServers.detect()

    // ---- models ----

    override suspend fun refreshModels(id: ProviderId): ModelSyncReport {
        val read = providers.get(id) ?: throw IllegalArgumentException(PROVIDER_GONE)
        blockedByLocalOnly(read)?.let { throw LlmException.BlockedByPolicy(it) }
        val lease = connections.acquire(read)
        // An edit may have landed since the read above: the listing goes where the lease's record says, with its key.
        val provider = lease.provider
        blockedByLocalOnly(provider)?.let { throw LlmException.BlockedByPolicy(it) }
        var outcome = "stopped before it finished"
        val listed = try {
            lease.adapter.listModels().also { outcome = if (it.size == 1) "1 model listed" else "${it.size} models listed" }
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            val failure = e.asLlmException(provider.name, lease)
            outcome = "failed (${failure.message})"
            if (failure is LlmException.Authentication) connections.storeStatus(id, ConnectionStatus.AUTH_ERROR, failure.message)
            throw failure
        } finally {
            checks.recordContact(provider, ProviderChecks.LIST_PURPOSE, "Model list requested", outcome)
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

    /**
     * Makes sure the built-in simulator exists, and deletes the provider keys left behind by an
     * edit the process did not live to finish ([deleteUnusedKeys]). Meant for every start.
     */
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
        try {
            deleteUnusedKeysLocked()
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            // Unused keys harm nobody while nothing points at them; the next start tries again.
        }
        Unit
    }

    /**
     * Deletes the provider keys in the vault that no provider record points at: the previous key
     * of an edit, or the new key of a failed one, when the process stopped before it could
     * delete it. Returns how many were deleted; the activity log says how many, never which.
     *
     * Only keys of a provider whose record can be read are considered. A key whose provider has
     * no readable record is kept: the record may be one this version of the app cannot read,
     * and deleting its key would lose it for good. Runs under the record lock, so it never sees
     * an edit half-done.
     */
    suspend fun deleteUnusedKeys(): Int = connections.changeRecords { deleteUnusedKeysLocked() }

    private suspend fun deleteUnusedKeysLocked(): Int {
        val records = providers.getAll()
        val used = records.mapNotNullTo(HashSet()) { it.apiKeyAlias }
        val unused = vault.aliases().filter { alias -> alias !in used && records.any { isKeyAliasOf(it.id, alias) } }
        if (unused.isEmpty()) return 0
        withContext(NonCancellable) {
            unused.forEach { vault.delete(it) }
            val count = if (unused.size == 1) "1 saved API key" else "${unused.size} saved API keys"
            activity.record(ActivityType.REGISTRY, "Unused API keys deleted", "$count that no provider used any more, left behind by a change that was interrupted.")
        }
        return unused.size
    }

    // ---- helpers ----

    private suspend fun requireValid(draft: ProviderDraft, editing: ProviderId?) {
        val issues = validate(draft, editing)
        if (issues.isNotEmpty()) throw ProviderValidationException(issues)
    }

    private suspend fun hasStoredKey(provider: ModelProvider?): Boolean = provider?.apiKeyAlias?.let { vault.contains(it) } ?: false

    /** The alias of [id]'s key as first saved, before aliases were versioned; records may still point at it. */
    private fun keyAlias(id: ProviderId): String = KeyAliases.base(id)

    /** A new alias for [id]'s key, never the one the record points at ([current]). */
    private fun nextKeyAlias(id: ProviderId, current: String?): String = KeyAliases.next(id, current)

    /** True when [alias] is one of the names [id]'s key is saved under: the original form or a versioned one. */
    private fun isKeyAliasOf(id: ProviderId, alias: String): Boolean = KeyAliases.belongsTo(id, alias)

    /** Deletes the key a failed record write left without a record; a failure to do so is kept with [cause]. */
    private suspend fun discardKey(alias: String, cause: Exception) {
        try {
            vault.delete(alias)
        } catch (e: Exception) {
            // The start-up clean-up deletes it if the record exists; the record write's failure is the one to report.
            cause.addSuppressed(e)
        }
    }

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
