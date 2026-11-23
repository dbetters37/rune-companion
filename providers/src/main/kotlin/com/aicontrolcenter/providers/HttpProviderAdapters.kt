package com.aicontrolcenter.providers

import com.aicontrolcenter.core.llm.ProviderAdapterFactory
import com.aicontrolcenter.providers.gemini.GeminiAdapterFactory
import com.aicontrolcenter.providers.http.HttpTimeouts
import com.aicontrolcenter.providers.ollama.OllamaAdapterFactory
import com.aicontrolcenter.providers.openai.OpenAiCompatibleAdapterFactory
import okhttp3.OkHttpClient

/**
 * The provider adapters of this module, ready to register with the framework: OpenAI-compatible
 * servers, Ollama's native API and Google's Gemini API.
 *
 * Call [factories] once at start-up and keep the result. The factories share one connection
 * pool and remember, per model, which optional request settings a provider refuses.
 */
object HttpProviderAdapters {
    /**
     * @param client the app's OkHttpClient, to share its connection pool, dispatcher, resolver
     *   and interceptors, and its proxy for cloud hosts. Timeouts, the redirect policy, the
     *   locality checks and the rule that OkHttp never repeats a request on its own (its
     *   retryOnConnectionFailure is turned off) are set by this module regardless (see
     *   `providerHttpClient`). When null, a client is created for the three factories to share.
     */
    fun factories(client: OkHttpClient? = null, timeouts: HttpTimeouts = HttpTimeouts()): List<ProviderAdapterFactory> {
        val shared = client ?: OkHttpClient()
        return listOf(
            OpenAiCompatibleAdapterFactory(shared, timeouts),
            OllamaAdapterFactory(shared, timeouts),
            GeminiAdapterFactory(shared, timeouts),
        )
    }
}
