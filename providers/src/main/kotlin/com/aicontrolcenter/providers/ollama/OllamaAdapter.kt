package com.aicontrolcenter.providers.ollama

import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderAdapterFactory
import com.aicontrolcenter.core.llm.ProviderConnection
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.http.HttpTimeouts
import com.aicontrolcenter.providers.http.ParameterMemory
import com.aicontrolcenter.providers.http.ProviderHttp
import com.aicontrolcenter.providers.http.probeHealth
import com.aicontrolcenter.providers.http.providerHttpClient
import com.aicontrolcenter.providers.http.string
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * Adapter for Ollama's native API, on this phone, on the local network, or hosted.
 *
 * The base URL is the server root, without `/api`. A blank address means an Ollama server on
 * this device at its default port, [DEFAULT_BASE_URL]. As with Ollama's own tools, an address
 * that names a scheme but no port uses that scheme's port, not 11434. A local server needs no
 * key; when one is stored (a hosted instance, or a reverse proxy in front of the server) it is
 * sent as a bearer token.
 */
class OllamaAdapter internal constructor(
    private val connection: ProviderConnection,
    httpClient: OkHttpClient,
    memory: ParameterMemory,
) : ProviderAdapter {
    private val http = ProviderHttp(httpClient, connection, DEFAULT_BASE_URL) { key -> "Authorization" to "Bearer $key" }
    private val models = OllamaModels(http)

    override val client: LlmClient =
        OllamaChatClient(http, memory, memoryScope = "${connection.provider.id}|${connection.provider.baseUrl.orEmpty()}")

    /** Asks for the server's version: the cheapest request that only an Ollama server answers properly. */
    override suspend fun checkHealth(): ProviderHealth = probeHealth {
        val version = (http.getJson("api/version") as? JsonObject)?.string("version")
        if (version.isNullOrBlank()) throw http.errors.malformed("it does not look like an Ollama server")
    }

    override suspend fun listModels(): List<RemoteModel> = models.list()

    override fun toString(): String = "OllamaAdapter(${connection.provider.name})"

    companion object {
        const val DEFAULT_PORT = 11434
        const val DEFAULT_BASE_URL = "http://127.0.0.1:$DEFAULT_PORT"
    }
}

/**
 * Creates [OllamaAdapter]s. Keep one factory for the life of the app: it owns the memory of
 * which models refuse the thinking setting.
 */
class OllamaAdapterFactory(
    client: OkHttpClient? = null,
    timeouts: HttpTimeouts = HttpTimeouts(),
) : ProviderAdapterFactory {
    override val kind: ProviderKind = ProviderKind.OLLAMA
    private val httpClient = providerHttpClient(client, timeouts)
    private val memory = ParameterMemory()

    override fun create(connection: ProviderConnection): ProviderAdapter = OllamaAdapter(connection, httpClient, memory)
}
