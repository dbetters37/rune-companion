package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderConnection
import com.anthropic.backends.AnthropicBackend
import com.anthropic.client.AnthropicClient
import com.anthropic.client.AnthropicClientImpl
import com.anthropic.client.okhttp.OkHttpClient
import com.anthropic.core.ClientOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Owns the SDK client of one provider connection and is the only way the adapter calls it.
 *
 * The client is built on first use, so creating an adapter costs nothing and a missing key
 * surfaces as an [LlmException.Authentication] from the call that needed it. The key is handed
 * to the SDK and to the [ErrorMapper], which scrubs it from anything it reports; it is never
 * logged and this class has no string form that includes it.
 */
internal class SdkClient(private val connection: ProviderConnection, private val options: AnthropicOptions) {
    val errors = ErrorMapper(connection.provider.name, connection.apiKey)

    private val lock = Any()
    private var client: AnthropicClient? = null
    private var closed = false

    /**
     * Runs a blocking SDK call off the caller's thread. Cancelling the coroutine interrupts the
     * call, which aborts the HTTP request (see [InterruptibleHttpClient]). Every failure comes
     * out as an [LlmException].
     */
    suspend fun <T> call(block: (AnthropicClient) -> T): T = try {
        runInterruptible(Dispatchers.IO) { block(client()) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw errors.map(e)
    }

    /**
     * Like [call] for a result that holds a connection. A result produced in the instant the
     * caller was cancelled is closed here, because nobody else will ever see it.
     */
    suspend fun <T : AutoCloseable> open(block: (AnthropicClient) -> T): T {
        var opened: T? = null
        try {
            return call { client -> block(client).also { opened = it } }
        } catch (e: CancellationException) {
            opened?.close()
            throw e
        }
    }

    fun close() {
        val toClose = synchronized(lock) {
            closed = true
            client.also { client = null }
        }
        toClose?.close()
    }

    private fun client(): AnthropicClient = synchronized(lock) {
        check(!closed) { "This provider adapter has been closed." }
        client ?: build().also { client = it }
    }

    /**
     * Assembled the way the SDK documents for a custom transport, mirroring what its OkHttp
     * builder does, with one difference: the transport is wrapped so that blocking calls can
     * be aborted. The backend is what turns the key into the request's credentials.
     */
    private fun build(): AnthropicClient {
        val apiKey = connection.apiKey?.takeIf { it.isNotBlank() }
            ?: throw LlmException.Authentication("No API key is stored for ${connection.provider.name}. Add one in the provider's settings.")
        val backend = AnthropicBackend.builder().apiKey(apiKey)
            .apply { serverRoot(connection.provider.baseUrl)?.let { baseUrl(it) } }
            .build()
        val transport = InterruptibleHttpClient(OkHttpClient.builder().backend(backend).build())
        val clientOptions = ClientOptions.builder()
            .httpClient(transport)
            .backend(backend)
            .baseUrl(backend.baseUrl())
            .apply { options.maxRetries?.let { maxRetries(it) } }
            .apply { connection.provider.extraHeaders.forEach { (name, value) -> putHeader(name, value) } }
        backend.applyCredentials(transport, clientOptions)
        return AnthropicClientImpl(clientOptions.build())
    }

    override fun toString(): String = "SdkClient(${connection.provider.name})"

    private companion object {
        /**
         * The SDK appends "/v1/..." itself, so an address entered with a trailing slash or
         * with the version segment is trimmed back to the server root. Blank means the SDK's
         * default endpoint.
         */
        fun serverRoot(configured: String?): String? =
            configured?.trim()?.trimEnd('/')?.removeSuffix("/v1")?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    }
}
