package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind
import java.net.URI
import java.net.URISyntaxException

/** Display name of a provider kind, for messages and the activity log. */
val ProviderKind.label: String
    get() = when (this) {
        ProviderKind.OPENAI_COMPATIBLE -> "OpenAI-compatible"
        ProviderKind.ANTHROPIC -> "Anthropic"
        ProviderKind.GEMINI -> "Google Gemini"
        ProviderKind.OLLAMA -> "Ollama"
        ProviderKind.ON_DEVICE -> "On-device"
        ProviderKind.SIMULATOR -> "Simulator"
    }

val Locality.label: String
    get() = when (this) {
        Locality.ON_DEVICE -> "on this device"
        Locality.LOCAL_NETWORK -> "local network"
        Locality.CLOUD -> "cloud"
    }

val ConnectionStatus.label: String
    get() = when (this) {
        ConnectionStatus.UNKNOWN -> "not checked"
        ConnectionStatus.ONLINE -> "online"
        ConnectionStatus.OFFLINE -> "offline"
        ConnectionStatus.AUTH_ERROR -> "key rejected"
        ConnectionStatus.ERROR -> "error"
    }

/** True for kinds that run inside the app: they have no address and no API key. */
internal val ProviderKind.runsInProcess: Boolean
    get() = this == ProviderKind.ON_DEVICE || this == ProviderKind.SIMULATOR

/** The endpoint a vendor-specific kind uses when the record has no address of its own. */
private val ProviderKind.defaultHost: String?
    get() = when (this) {
        ProviderKind.ANTHROPIC -> "api.anthropic.com"
        ProviderKind.GEMINI -> "generativelanguage.googleapis.com"
        ProviderKind.OPENAI_COMPATIBLE -> "api.openai.com"
        ProviderKind.OLLAMA, ProviderKind.ON_DEVICE, ProviderKind.SIMULATOR -> null
    }

/** Host of a strict http(s) URL, or null. Parsed as unforgivingly as [Locality.infer] does. */
internal fun hostOfUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val uri = try {
        URI(url.trim())
    } catch (e: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    return uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()?.ifEmpty { null }
}

/**
 * Where requests to this provider go, for the "information left the device" record. An address
 * that cannot be read is reported as such rather than guessed.
 */
internal fun ModelProvider.destinationHost(): String = endpointHost(kind, baseUrl) ?: "unknown host ($name)"

/** The host requests of a provider of [kind] at [baseUrl] go to, or null when the address cannot be read. */
internal fun endpointHost(kind: ProviderKind, baseUrl: String?): String? =
    hostOfUrl(baseUrl) ?: kind.defaultHost?.takeIf { baseUrl.isNullOrBlank() }

/** Where requests go, as far as a key is concerned: a different scheme, host or port is a different server. */
internal data class Endpoint(val scheme: String, val host: String, val port: Int)

/**
 * The endpoint requests of a provider of [kind] at [baseUrl] go to, or null when the address
 * cannot be read. The port is explicit (80 or 443 when the address names none), and a blank
 * address of a vendor kind is that vendor's default endpoint.
 */
internal fun endpointOf(kind: ProviderKind, baseUrl: String?): Endpoint? {
    if (baseUrl.isNullOrBlank()) return kind.defaultHost?.let { Endpoint("https", it, 443) }
    val host = hostOfUrl(baseUrl) ?: return null
    val uri = URI(baseUrl.trim())
    val scheme = uri.scheme.lowercase()
    val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
    return Endpoint(scheme, host, port)
}
