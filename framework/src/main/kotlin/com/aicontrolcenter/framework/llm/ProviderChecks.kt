package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.settings.SettingsView
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Checking whether a provider answers, shared by the owner's provider manager ("Check now", start-up) and the
 * agent side's re-checks (Phase 6, D28; security review of wave 1, F3). It holds no provider manager, no provider
 * store and no vault: it reaches a provider only through [ProviderConnections], which pairs each record with its own
 * key, and it changes nothing about a provider but the status a check learned ([ProviderConnections.storeStatus]).
 *
 *  - Never for a provider off this device while local-only mode is on: a check sends the key to the provider.
 *  - Every check that reaches a provider off this device is logged with where it went, however it ended.
 *  - A change of status is logged once, as before.
 */
internal class ProviderChecks(
    private val settings: SettingsView,
    private val connections: ProviderConnections,
    private val activity: ActivityRecorder,
) {
    /**
     * Checks [provider], stores the status it learned and returns it. When local-only mode keeps the provider away,
     * nothing is sent and its stored status is returned with the reason. [timeoutMs] bounds the network part.
     */
    suspend fun check(provider: ModelProvider, timeoutMs: Long): ProviderHealth {
        blockedByLocalOnly(provider)?.let { return ProviderHealth(provider.status, it) }
        val health = probe(provider, timeoutMs) ?: return ProviderHealth(provider.status, localOnlyRefusal(provider))
        val before = connections.storeStatus(provider.id, health.status, health.message) ?: return health
        if (before.status != health.status) {
            val detail = "${provider.name}: ${health.status.label}" + (health.message?.let { " ($it)" } ?: "")
            val severity = if (health.status == ConnectionStatus.ONLINE || health.status == ConnectionStatus.UNKNOWN) Severity.INFO else Severity.WARNING
            activity.record(ActivityType.REGISTRY, "Provider status changed", detail, severity = severity)
        }
        return health
    }

    /**
     * Asks the adapter for the provider's health. Never throws, and never lets the key into the message. Null when
     * the record the lease was built from (see [ProviderConnections.acquire]), which an edit may have moved off this
     * device, is one local-only mode keeps away from.
     */
    private suspend fun probe(read: ModelProvider, timeoutMs: Long): ProviderHealth? {
        val lease = try {
            connections.acquire(read)
        } catch (e: LlmException) {
            // Nothing was sent: the adapter was never asked.
            return ProviderHealth(statusOf(e), e.message)
        }
        // Where the check really goes, with the key stored for exactly that address.
        val provider = lease.provider
        if (blockedByLocalOnly(provider) != null) return null
        var outcome = "stopped before it finished"
        try {
            val health = try {
                withTimeoutOrNull(timeoutMs) { lease.adapter.checkHealth() }
                    ?: ProviderHealth(ConnectionStatus.OFFLINE, "${provider.name} did not answer in time.")
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                ProviderHealth(ConnectionStatus.ERROR, "The check failed unexpectedly (${e::class.simpleName}).")
            }
            return health.copy(message = health.message?.let(lease::redact)).also { checked ->
                outcome = checked.status.label + (checked.message?.let { " ($it)" } ?: "")
            }
        } finally {
            recordContact(provider, CHECK_PURPOSE, "Provider checked", outcome)
        }
    }

    /**
     * Records that a check or a listing reached [provider], when that left this device. Written however the request
     * ended, as a model call's event is: a request that failed may still have delivered the key. Best effort, like all
     * book-keeping that follows a network call.
     */
    suspend fun recordContact(provider: ModelProvider, purpose: String, title: String, outcome: String) {
        val locality = provider.effectiveLocality
        if (!locality.leavesDevice) return
        withContext(NonCancellable) {
            try {
                activity.record(
                    ActivityType.REGISTRY, title, "${provider.name}: $outcome",
                    data = buildJsonObject {
                        put("providerId", provider.id.value)
                        put("purpose", purpose)
                    },
                    egress = EgressInfo(provider.destinationHost(), locality, provider.id, purpose),
                )
            } catch (e: Exception) {
                // The request has already been made; failing to note it must not turn its result into a failure.
            }
        }
    }

    /** Why local-only mode keeps [provider] from being contacted now, or null when it does not. */
    suspend fun blockedByLocalOnly(provider: ModelProvider): String? =
        if (provider.effectiveLocality.leavesDevice && settings.get().localOnlyMode) localOnlyRefusal(provider) else null

    private fun localOnlyRefusal(provider: ModelProvider) = "Local-only mode is on, so ${provider.name} was not contacted."

    private fun statusOf(error: LlmException): ConnectionStatus = when (error) {
        is LlmException.Authentication -> ConnectionStatus.AUTH_ERROR
        is LlmException.Network, is LlmException.Timeout -> ConnectionStatus.OFFLINE
        else -> ConnectionStatus.ERROR
    }

    companion object {
        const val CHECK_PURPOSE = "API key and connection check"
        const val LIST_PURPOSE = "model list request"
    }
}
