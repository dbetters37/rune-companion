package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderAdapterFactory
import com.aicontrolcenter.core.llm.ProviderConnection
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.security.SecretVaultException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The live side of the provider registry, shared by the model gateway and the provider
 * manager: one open adapter per provider, and the lock under which provider and model records
 * are changed.
 *
 * An adapter is kept until the provider's connection details or its key change; then it is
 * closed and replaced. The key is read from the vault on every [acquire], so a key that was
 * replaced or removed takes effect on the very next call.
 */
class ProviderConnections(
    private val providers: ProviderRepository,
    private val vault: SecretVault,
    factories: List<ProviderAdapterFactory>,
    private val clock: Clock,
) {
    private val factories: Map<ProviderKind, ProviderAdapterFactory> = factories.associateBy { it.kind }
    private val cacheLock = Mutex()
    private val open = HashMap<ProviderId, OpenAdapter>()

    /**
     * Guards every read-modify-write of a provider or model record, so a status written after
     * a model call can never overwrite an edit the owner made in the meantime. Never held
     * across a network call.
     */
    private val recordLock = Mutex()

    /** The connection details an adapter was built from. A change in any of them replaces the adapter. */
    private data class Shape(
        val kind: ProviderKind,
        val name: String,
        val baseUrl: String?,
        val headers: Map<String, String>,
        val keyAlias: String?,
    )

    private class OpenAdapter(val shape: Shape, val key: String?, val lease: ProviderLease)

    fun supports(kind: ProviderKind): Boolean = kind in factories

    /**
     * The adapter for [provider], created or replaced as needed.
     * @throws LlmException when no adapter exists for the kind, the key cannot be read, or the
     * adapter cannot be built from the record.
     */
    suspend fun acquire(provider: ModelProvider): ProviderLease {
        val factory = factories[provider.kind] ?: throw LlmException.Unsupported(
            "${provider.name} is a ${provider.kind.label} provider, which this version of the app cannot connect to.",
        )
        val key = readKey(provider)
        val shape = Shape(provider.kind, provider.name, provider.baseUrl, provider.extraHeaders, provider.apiKeyAlias)
        return cacheLock.withLock {
            val current = open[provider.id]
            if (current != null && current.shape == shape && current.key == key) return@withLock current.lease
            val adapter = try {
                factory.create(ProviderConnection(provider, key))
            } catch (e: LlmException) {
                throw e
            } catch (e: Exception) {
                // The cause is left out on purpose: it was built from a connection that holds the key.
                throw LlmException.InvalidRequest("${provider.name} could not be set up (${e::class.simpleName}). Check its address and settings.")
            }
            current?.let { closeQuietly(it.lease.adapter) }
            ProviderLease(adapter, key).also { open[provider.id] = OpenAdapter(shape, key, it) }
        }
    }

    /** A throw-away adapter that is not cached, for probing an address that is not registered. Null when the kind has no factory. */
    fun openUncached(connection: ProviderConnection): ProviderAdapter? = factories[connection.provider.kind]?.create(connection)

    /** Closes and forgets the adapter of a provider that was removed. */
    suspend fun evict(id: ProviderId) {
        cacheLock.withLock { open.remove(id) }?.let { closeQuietly(it.lease.adapter) }
    }

    suspend fun closeAll() {
        val all = cacheLock.withLock { open.values.toList().also { open.clear() } }
        all.forEach { closeQuietly(it.lease.adapter) }
    }

    /** Runs [block] as one atomic change of the registry's provider and model records. Not reentrant. */
    suspend fun <T> changeRecords(block: suspend () -> T): T = recordLock.withLock { block() }

    /**
     * Stores what a call or a check just learned about a provider's connection and returns the
     * record as it was before, or null when the provider no longer exists. Leaves `updatedAt`
     * alone: a status is an observation, not an edit.
     */
    suspend fun storeStatus(id: ProviderId, status: ConnectionStatus, message: String?): ModelProvider? = recordLock.withLock {
        val before = providers.get(id) ?: return@withLock null
        providers.upsert(before.copy(status = status, statusMessage = message, lastCheckedAt = clock.now()))
        before
    }

    private suspend fun readKey(provider: ModelProvider): String? {
        val alias = provider.apiKeyAlias ?: return null
        return try {
            vault.get(alias)?.takeIf { it.isNotEmpty() }
        } catch (e: SecretVaultException) {
            throw LlmException.Authentication(
                "The saved key for ${provider.name} could not be read from secure storage. Enter the key again in the provider's settings.",
                e,
            )
        }
    }

    private fun closeQuietly(adapter: ProviderAdapter) {
        try {
            adapter.close()
        } catch (e: Exception) {
            // A failing close must not break the call that replaced the adapter; nothing else can be done with it.
        }
    }
}

/**
 * An adapter ready to use, together with the means to keep its secret out of anything that
 * is shown or stored. The key itself cannot be read back from a lease.
 */
class ProviderLease internal constructor(val adapter: ProviderAdapter, private val secret: String?) {
    private val guarded: String? = secret?.takeIf { it.length >= MIN_GUARDED_LENGTH }

    /** True when [text] contains the provider's key. */
    fun exposes(text: String?): Boolean = guarded != null && text != null && text.contains(guarded)

    /** [text] with the provider's key removed, should an adapter or a server have echoed it. */
    fun redact(text: String): String = if (exposes(text)) text.replace(guarded!!, "[key removed]") else text

    override fun toString(): String = "ProviderLease"

    private companion object {
        /** Shorter strings are not keys worth guarding and would mangle ordinary words. */
        const val MIN_GUARDED_LENGTH = 8
    }
}
