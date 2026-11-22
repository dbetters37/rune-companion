package com.aicontrolcenter.providers.http

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.registry.ConnectionStatus
import kotlinx.coroutines.CancellationException

/**
 * Runs a cheap request against a provider and reports the outcome as a status, never as an
 * exception (cancellation excepted). A rejected key is [ConnectionStatus.AUTH_ERROR], an
 * unreachable or silent server is [ConnectionStatus.OFFLINE], and anything else that went wrong
 * (a refused address, an unexpected answer) is [ConnectionStatus.ERROR] with the reason.
 */
internal suspend fun probeHealth(probe: suspend () -> Unit): ProviderHealth {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    return try {
        probe()
        ProviderHealth(ConnectionStatus.ONLINE, latencyMs = elapsed())
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmException) {
        val status = when (e) {
            is LlmException.Authentication -> ConnectionStatus.AUTH_ERROR
            is LlmException.Network, is LlmException.Timeout -> ConnectionStatus.OFFLINE
            else -> ConnectionStatus.ERROR
        }
        ProviderHealth(status, e.message, elapsed())
    } catch (e: Exception) {
        ProviderHealth(ConnectionStatus.ERROR, "The check failed unexpectedly (${e.javaClass.simpleName}).", elapsed())
    }
}
