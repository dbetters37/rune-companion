package com.aicontrolcenter.providers.openai

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
 * Adapter for any server that implements OpenAI's Chat Completions protocol: OpenAI itself,
 * OpenRouter, Groq, Mistral, DeepSeek, xAI, and local servers such as LM Studio, llama.cpp,
 * vLLM and Ollama's `/v1`.
 *
 * The base URL is the API root, the address under which `chat/completions` and `models` live
 * (for most services it ends in `/v1`). A blank address means OpenAI. The key, when there is
 * one, is sent as a bearer token; local servers usually need none.
 */
class OpenAiCompatibleAdapter internal constructor(
    private val connection: ProviderConnection,
    httpClient: OkHttpClient,
    memory: ParameterMemory,
) : ProviderAdapter {
    private val http = ProviderHttp(httpClient, connection, DEFAULT_BASE_URL) { key -> "Authorization" to "Bearer $key" }

    override val client: LlmClient =
        OpenAiChatClient(http, memory, memoryScope = "${connection.provider.id}|${connection.provider.baseUrl.orEmpty()}")

    /** Lists the models, which exercises the address and the key without spending tokens. */
    override suspend fun checkHealth(): ProviderHealth = probeHealth { listModels() }

    override suspend fun listModels(): List<RemoteModel> =
        OpenAiModels.parse(http.getJson("models")) ?: throw http.errors.malformed("the model list had an unknown shape")

    override fun toString(): String = "OpenAiCompatibleAdapter(${connection.provider.name})"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}

/**
 * Creates [OpenAiCompatibleAdapter]s. Keep one factory for the life of the app: it owns the
 * memory of which optional settings each model refuses.
 *
 * @param client a client to share connections with; see [providerHttpClient] for what is taken from it
 */
class OpenAiCompatibleAdapterFactory(
    client: OkHttpClient? = null,
    timeouts: HttpTimeouts = HttpTimeouts(),
) : ProviderAdapterFactory {
    override val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE
    private val httpClient = providerHttpClient(client, timeouts)
    private val memory = ParameterMemory()

    override fun create(connection: ProviderConnection): ProviderAdapter = OpenAiCompatibleAdapter(connection, httpClient, memory)
}
