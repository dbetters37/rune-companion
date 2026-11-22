package com.aicontrolcenter.providers.gemini

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
import okhttp3.OkHttpClient

/**
 * Adapter for Google's native Gemini API (Google AI Studio keys).
 *
 * The base URL is the versioned API root; blank means [DEFAULT_BASE_URL]. The key travels in
 * the `x-goog-api-key` header, never in the URL, so it cannot end up in a logged address.
 */
class GeminiAdapter internal constructor(
    private val connection: ProviderConnection,
    httpClient: OkHttpClient,
    memory: ParameterMemory,
) : ProviderAdapter {
    private val http = ProviderHttp(httpClient, connection, DEFAULT_BASE_URL) { key -> "x-goog-api-key" to key }
    private val models = GeminiModels(http)

    override val client: LlmClient =
        GeminiChatClient(http, memory, memoryScope = "${connection.provider.id}|${connection.provider.baseUrl.orEmpty()}")

    /** Fetches one entry of the model list, which exercises the address and the key without spending tokens. */
    override suspend fun checkHealth(): ProviderHealth = probeHealth { http.getJson("models", mapOf("pageSize" to "1")) }

    override suspend fun listModels(): List<RemoteModel> = models.list()

    override fun toString(): String = "GeminiAdapter(${connection.provider.name})"

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}

/**
 * Creates [GeminiAdapter]s. Keep one factory for the life of the app: it owns the memory of
 * which optional settings each model refuses.
 */
class GeminiAdapterFactory(
    client: OkHttpClient? = null,
    timeouts: HttpTimeouts = HttpTimeouts(),
) : ProviderAdapterFactory {
    override val kind: ProviderKind = ProviderKind.GEMINI
    private val httpClient = providerHttpClient(client, timeouts)
    private val memory = ParameterMemory()

    override fun create(connection: ProviderConnection): ProviderAdapter = GeminiAdapter(connection, httpClient, memory)
}
