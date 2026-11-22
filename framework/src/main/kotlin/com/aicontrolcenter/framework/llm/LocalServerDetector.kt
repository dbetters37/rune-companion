package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.DetectedServer
import com.aicontrolcenter.core.llm.ProviderConnection
import com.aicontrolcenter.core.llm.ProviderPreset
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.registry.ProviderRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.URISyntaxException

/**
 * Looks for model servers already running on this phone by asking well-known loopback
 * addresses for their model list, through the same adapters that would later talk to them.
 *
 * Only addresses on this device are probed, so the search sends nothing off the phone and is
 * allowed in local-only mode. No key is sent either: a server that is not registered yet has
 * none. Finding a server registers nothing; the owner decides whether to add it.
 */
internal class LocalServerDetector(
    private val providers: ProviderRepository,
    private val connections: ProviderConnections,
    private val probes: List<ProviderPreset>,
) {
    /** The servers that answered and are not registered yet. All addresses are tried at the same time. */
    suspend fun detect(): List<DetectedServer> {
        val takenPorts = providers.getAll().mapNotNull { loopbackPort(it.kind, it.baseUrl) }.toSet()
        return coroutineScope {
            probes
                .filter { preset -> loopbackPort(preset.kind, preset.baseUrl)?.let { it !in takenPorts } == true }
                .map { preset -> async { probe(preset) } }
                .awaitAll()
                .filterNotNull()
        }
    }

    private suspend fun probe(preset: ProviderPreset): DetectedServer? {
        val baseUrl = preset.baseUrl ?: return null
        val candidate = ModelProvider(
            id = ProviderId("probe_${preset.id}"), name = preset.title, kind = preset.kind, baseUrl = baseUrl,
            locality = Locality.ON_DEVICE, createdAt = 0, updatedAt = 0,
        )
        val adapter = try {
            connections.openUncached(ProviderConnection(candidate, apiKey = null)) ?: return null
        } catch (e: Exception) {
            return null
        }
        return try {
            // Nothing listening is the normal case, so any failure simply means "not found".
            val listed = withTimeoutOrNull(PROBE_TIMEOUT_MS) { adapter.listModels() } ?: return null
            DetectedServer(preset.id, preset.title, baseUrl, preset.kind, listed.size)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            null
        } finally {
            try {
                adapter.close()
            } catch (e: Exception) {
                // A probe adapter that fails to close holds nothing worth reporting.
            }
        }
    }

    /** The port of an address on this phone, or null for anything else. Two servers cannot share one, so it identifies the server. */
    private fun loopbackPort(kind: ProviderKind, baseUrl: String?): Int? {
        if (baseUrl == null || Locality.infer(kind, baseUrl) != Locality.ON_DEVICE) return null
        val uri = try {
            URI(baseUrl)
        } catch (e: URISyntaxException) {
            return null
        }
        return if (uri.port > 0) uri.port else if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
    }

    private companion object {
        /** Loopback answers at once or not at all; a server that takes longer is treated as absent. */
        const val PROBE_TIMEOUT_MS = 1_500L
    }
}
