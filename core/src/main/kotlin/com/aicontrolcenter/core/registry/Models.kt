package com.aicontrolcenter.core.registry

import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import kotlinx.serialization.Serializable

/**
 * A connection to something that serves models: a cloud API, a server on the local network,
 * a server on this phone, or an on-device runtime. Agents never reference a provider directly;
 * they reference a [ModelDescriptor], which points at its provider.
 */
@Serializable
data class ModelProvider(
    val id: ProviderId,
    val name: String,
    val kind: ProviderKind,
    /** Base URL for HTTP providers; null for in-process runtimes. */
    val baseUrl: String? = null,
    /** Alias of the API key inside the secret vault. The key itself is never stored here. */
    val apiKeyAlias: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Where prompts sent to this provider physically go. Drives every "leaves the device" indicator. */
    val locality: Locality,
    val enabled: Boolean = true,
    val status: ConnectionStatus = ConnectionStatus.UNKNOWN,
    val statusMessage: String? = null,
    val lastCheckedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The wire protocol / runtime a provider speaks. New kinds plug in through an adapter factory. */
enum class ProviderKind {
    /** Any server implementing the OpenAI Chat Completions protocol (cloud or local). */
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GEMINI,

    /** Ollama's native API (model listing with capabilities, local or LAN). */
    OLLAMA,

    /** An imported model file executed in-process on the phone. */
    ON_DEVICE,

    /** The built-in deterministic simulator. Produces scripted output, not real intelligence. */
    SIMULATOR,
}

enum class Locality {
    /** Stays inside this phone (in-process runtime or a loopback server). */
    ON_DEVICE,

    /** Goes to a machine on the user's own network. */
    LOCAL_NETWORK,

    /** Goes to a third-party service on the internet. */
    CLOUD,
    ;

    val leavesDevice: Boolean get() = this != ON_DEVICE

    companion object {
        /**
         * Classifies a base URL by its host. Unknown or unparsable hosts are treated as [CLOUD]
         * so that the app never under-reports data leaving the device.
         */
        fun infer(baseUrl: String?): Locality {
            if (baseUrl.isNullOrBlank()) return ON_DEVICE
            val host = hostOf(baseUrl)?.lowercase() ?: return CLOUD
            if (host == "localhost" || host == "::1") return ON_DEVICE
            if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return LOCAL_NETWORK
            val octets = host.split('.').map { it.toIntOrNull() }
            if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
                val a = octets[0]!!
                val b = octets[1]!!
                // 127.0.0.0/8 is loopback. 0.0.0.0 is what a server binds to, and reaches this device.
                if (a == 127 || octets.all { it == 0 }) return ON_DEVICE
                val private = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                    (a == 169 && b == 254) || (a == 100 && b in 64..127)
                return if (private) LOCAL_NETWORK else CLOUD
            }
            // IPv6 link-local (fe80::/10) and unique-local (fc00::/7) literals.
            if (':' in host && (host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd"))) {
                return LOCAL_NETWORK
            }
            return CLOUD
        }

        private fun hostOf(url: String): String? {
            var rest = url.trim()
            val scheme = rest.indexOf("://")
            if (scheme >= 0) rest = rest.substring(scheme + 3)
            rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
            rest = rest.substringAfterLast('@')
            if (rest.isEmpty()) return null
            if (rest.startsWith("[")) return rest.substringAfter('[').substringBefore(']').ifEmpty { null }
            // A bare IPv6 literal has more than one ':'; host:port has exactly one.
            return if (rest.count { it == ':' } == 1) rest.substringBefore(':') else rest
        }
    }
}

enum class ConnectionStatus { UNKNOWN, ONLINE, OFFLINE, AUTH_ERROR, ERROR }

/** A concrete LLM that can be assigned to agents. Belongs to exactly one [ModelProvider]. */
@Serializable
data class ModelDescriptor(
    val id: ModelId,
    val providerId: ProviderId,
    /** The identifier the provider expects on the wire, e.g. "gemma3:4b". */
    val modelName: String,
    val displayName: String,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val source: ModelSource,
    /** Path of an imported on-device model file, if any. */
    val localPath: String? = null,
    val sizeBytes: Long? = null,
    val enabled: Boolean = true,
    val status: ModelStatus = ModelStatus.UNKNOWN,
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

enum class ModelSource {
    /** Reported by the provider's model listing. */
    PROVIDER_LISTING,

    /** Typed in by the user. */
    MANUAL,

    /** A model file imported onto the device. */
    IMPORTED_FILE,

    /** Shipped with the app (the simulator). */
    BUILT_IN,
}

enum class ModelStatus { UNKNOWN, AVAILABLE, UNAVAILABLE }

@Serializable
data class ModelCapabilities(
    val chat: Boolean = true,
    /** Native function/tool calling. Models without it use the prompted tool protocol. */
    val toolCalling: Boolean = false,
    val vision: Boolean = false,
    val streaming: Boolean = true,
    val jsonOutput: Boolean = false,
    val embeddings: Boolean = false,
    val reasoning: CapabilityTier = CapabilityTier.UNKNOWN,
    val coding: CapabilityTier = CapabilityTier.UNKNOWN,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
)

enum class CapabilityTier { UNKNOWN, BASIC, GOOD, STRONG }
