package com.aicontrolcenter.providers.http

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.failsWith
import com.aicontrolcenter.providers.openai.OpenAiCompatibleAdapterFactory
import com.aicontrolcenter.providers.simpleRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OkHttp sends every provider request once. Trying again is the framework's decision alone,
 * because only the framework re-checks policy and records each transfer.
 */
class SingleSendTest : MockServerTest() {
    private val failure = """{"error":{"message":"upstream failure","type":"server_error"}}"""

    /** Every request gets [response], so a request OkHttp repeats on its own is counted too. */
    private fun always(response: () -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = response()
        }
    }

    private fun status(code: Int, vararg headers: Pair<String, String>): MockResponse {
        val builder = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(failure)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return builder.build()
    }

    private fun adapter(shared: OkHttpClient? = null): ProviderAdapter =
        OpenAiCompatibleAdapterFactory(shared).create(connection(ProviderKind.OPENAI_COMPATIBLE, baseUrl("/v1")))

    /** A plain call and a stream (both with a body) and a model listing (a GET without one). */
    private val calls: List<Pair<String, suspend (ProviderAdapter) -> Unit>> = listOf(
        "chat" to { it.client.chat(simpleRequest()) },
        "stream" to { it.client.chatStream(simpleRequest()).toList() },
        "models" to { it.listModels() },
    )

    private inline fun <reified T : LlmException> eachCallIsSentOnceAndFailsWith(): List<T> = calls.map { (name, call) ->
        val before = server.requestCount
        val error = failsWith<T> { runBlocking { call(adapter()) } }
        assertEquals(1, server.requestCount - before, "$name was sent ${server.requestCount - before} times")
        error
    }

    @Test
    fun `a 503 that asks for an immediate retry is sent once`() {
        always { status(503, "Retry-After" to "0") }
        eachCallIsSentOnceAndFailsWith<LlmException.Server>().forEach { assertEquals(503, it.status) }
    }

    @Test
    fun `a 408 is sent once`() {
        always { status(408) }
        eachCallIsSentOnceAndFailsWith<LlmException.Timeout>().forEach { assertTrue(it.retryable) }
    }

    @Test
    fun `a connection dropped before the response is sent once`() {
        always { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }
        eachCallIsSentOnceAndFailsWith<LlmException.Network>().forEach { assertTrue(it.retryable) }
    }

    /** What each of [calls] accepts as a good answer. */
    private fun success(call: String): MockResponse = when (call) {
        "stream" -> MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
            .body("data: " + """{"choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n").build()
        "models" -> MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"object":"list","data":[{"id":"m","object":"model"}]}""").build()
        else -> MockResponse.Builder().addHeader("Content-Type", "application/json")
            .body("""{"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}""").build()
    }

    @Test
    fun `a pooled connection that fails is not used to send the request again`() {
        // OkHttp repeats a request on a new connection when one that already served a reply fails.
        val script = LinkedBlockingQueue<MockResponse>()
        always { script.poll() ?: status(500) }
        for ((name, call) in calls) {
            val adapter = adapter()
            script += success(name)
            runBlocking { call(adapter) }
            script += MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
            script += success(name)
            val before = server.requestCount

            failsWith<LlmException.Network> { runBlocking { call(adapter) } }

            assertEquals(1, server.requestCount - before, "$name was sent ${server.requestCount - before} times")
            script.clear()
        }
    }

    @Test
    fun `a Retry-After too large for OkHttp to read is a server error, not a crash`() {
        // OkHttp reads a 503's Retry-After with Integer.valueOf; this one would throw out of the call.
        always { status(503, "Retry-After" to "99999999999") }
        eachCallIsSentOnceAndFailsWith<LlmException.Server>()
    }

    @Test
    fun `bodies leave as one-shot and the app still sees the Retry-After the server sent`() {
        val oneShot = CopyOnWriteArrayList<Boolean>()
        val seen = CopyOnWriteArrayList<List<String>>()
        val shared = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request()).also { seen += it.headers.values("Retry-After") + it.headers.values("X-AICC-Held-Retry-After") }
            }
            .addNetworkInterceptor { chain -> chain.request().body?.let { oneShot += it.isOneShot() }; chain.proceed(chain.request()) }
            .build()
        // A header under the name used internally is the server's, and must not turn into a Retry-After.
        always { status(503, "Retry-After" to "0", "X-AICC-Held-Retry-After" to "120") }

        failsWith<LlmException.Server> { runBlocking { adapter(shared).client.chat(simpleRequest()) } }

        assertEquals(1, server.requestCount)
        assertEquals(listOf(true), oneShot)
        assertEquals(listOf(listOf("0")), seen)
    }

    @Test
    fun `a client built here and passed back in does not get the guards twice`() {
        val once = providerHttpClient(null, HttpTimeouts())
        val twice = providerHttpClient(once, HttpTimeouts())

        assertEquals(once.interceptors.size, twice.interceptors.size)
        assertEquals(once.networkInterceptors.size, twice.networkInterceptors.size)
        assertEquals(false, twice.retryOnConnectionFailure)
        assertEquals(false, providerHttpClient(OkHttpClient.Builder().retryOnConnectionFailure(true).build(), HttpTimeouts()).retryOnConnectionFailure)
    }
}
