package com.aicontrolcenter.providers.http

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.TEST_KEY
import com.aicontrolcenter.providers.failsWith
import com.aicontrolcenter.providers.openai.OpenAiCompatibleAdapterFactory
import com.aicontrolcenter.providers.simpleRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderHttpTest : MockServerTest() {
    private val ok = """{"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}"""

    private fun client(
        factory: OpenAiCompatibleAdapterFactory = OpenAiCompatibleAdapterFactory(),
        apiKey: String? = TEST_KEY,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = factory.create(connection(ProviderKind.OPENAI_COMPATIBLE, baseUrl("/v1"), apiKey, extraHeaders)).client

    @Test
    fun `requests carry the credential the extra headers and json content`() {
        enqueue(ok)
        runBlocking { client(extraHeaders = mapOf("X-Title" to "AI Control Center", "X-Note" to "a,b; \"c\"")).chat(simpleRequest()) }
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        assertEquals("Bearer $TEST_KEY", request.headers["Authorization"])
        assertEquals("AI Control Center", request.headers["X-Title"])
        assertEquals("a,b; \"c\"", request.headers["X-Note"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("application/json", request.headers["Accept"])
    }

    @Test
    fun `the stored key wins over an extra header of the same name`() {
        enqueue(ok)
        runBlocking { client(extraHeaders = mapOf("authorization" to "Bearer something-else")).chat(simpleRequest()) }
        assertEquals(listOf("Bearer $TEST_KEY"), server.takeRequest().headers.values("Authorization"))
    }

    @Test
    fun `without a key no credential header is sent`() {
        for (key in listOf(null, "", "   ")) {
            enqueue(ok)
            runBlocking { client(apiKey = key).chat(simpleRequest()) }
            assertNull(server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `a key that cannot be a header value fails without being sent or quoted`() {
        for (bad in listOf("sk-broken\nSECRETPART-123456", "sk broken SECRETPART-123456", "sk-brökenSECRETPART-123456")) {
            val error = failsWith<LlmException.Authentication> { runBlocking { client(apiKey = bad).chat(simpleRequest()) } }
            assertFalse("SECRETPART" in error.stackTraceToString(), error.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `surrounding whitespace of a pasted key is ignored`() {
        enqueue(ok)
        runBlocking { client(apiKey = "  $TEST_KEY\n").chat(simpleRequest()) }
        assertEquals("Bearer $TEST_KEY", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `an extra header with illegal characters is an invalid request`() {
        val error = failsWith<LlmException.InvalidRequest> {
            runBlocking { client(extraHeaders = mapOf("X-Bad" to "line\nbreak")).chat(simpleRequest()) }
        }
        assertTrue("X-Bad" in error.message!!)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a key echoed back by the server is removed from the message`() {
        enqueue("""{"error":{"message":"Bad credentials: Authorization: Bearer $TEST_KEY (key $TEST_KEY)"}}""", 401)
        val error = failsWith<LlmException.Authentication> { runBlocking { client().chat(simpleRequest()) } }
        assertFalse(TEST_KEY in error.stackTraceToString())
        assertTrue("[redacted]" in error.message!!)
    }

    @Test
    fun `cancelling a waiting call cancels the http call`() {
        val cancelled = CountDownLatch(1)
        val shared = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) = cancelled.countDown()
        }).build()
        server.enqueue(MockResponse.Builder().body(ok).headersDelay(30, TimeUnit.SECONDS).build())
        runBlocking {
            val outcome = CompletableDeferred<Throwable?>()
            val job = launch(Dispatchers.Default) {
                try {
                    client(OpenAiCompatibleAdapterFactory(shared)).chat(simpleRequest())
                    outcome.complete(null)
                } catch (e: Throwable) {
                    outcome.complete(e)
                    throw e
                }
            }
            server.takeRequest()
            withTimeout(5_000) { job.cancelAndJoin() }
            assertIs<CancellationException>(outcome.await(), "cancellation must not turn into an LlmException")
        }
        assertTrue(cancelled.await(5, TimeUnit.SECONDS), "the OkHttp call was not cancelled")
    }

    @Test
    fun `cancelling a stream that has gone quiet cancels the http call`() {
        val cancelled = CountDownLatch(1)
        val shared = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) = cancelled.countDown()
        }).build()
        // One event arrives at once; the rest of the body trickles in far too slowly to finish.
        // MockWebServer throttles the reading of the request by the same amount, so the first
        // period has to be large enough to take in the whole request body.
        val first = """data: {"choices":[{"index":0,"delta":{"content":"partial"},"finish_reason":null}]}""" + "\n\n"
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                .body(first + ": keep-alive\n".repeat(2_000))
                .throttleBody(2_048, 30, TimeUnit.SECONDS)
                .build(),
        )
        runBlocking {
            val received = CompletableDeferred<ChatChunk>()
            val outcome = CompletableDeferred<Throwable?>()
            val job = launch(Dispatchers.Default) {
                try {
                    client(OpenAiCompatibleAdapterFactory(shared)).chatStream(simpleRequest()).collect { received.complete(it) }
                    outcome.complete(null)
                } catch (e: Throwable) {
                    outcome.complete(e)
                    throw e
                }
            }
            assertEquals(ChatChunk.Text("partial"), withTimeout(10_000) { received.await() })
            withTimeout(5_000) { job.cancelAndJoin() }
            assertIs<CancellationException>(outcome.await())
        }
        assertTrue(cancelled.await(5, TimeUnit.SECONDS), "the OkHttp call was not cancelled")
    }

    @Test
    fun `a stream that completes normally does not cancel its call`() {
        val cancelled = CountDownLatch(1)
        val shared = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) = cancelled.countDown()
        }).build()
        enqueueSse("""{"choices":[{"index":0,"delta":{"content":"done"},"finish_reason":"stop"}]}""")
        runBlocking { client(OpenAiCompatibleAdapterFactory(shared)).chatStream(simpleRequest()).collect { } }
        assertFalse(cancelled.await(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a rejected stream fails the flow with the mapped error`() {
        enqueue("""{"error":{"message":"Incorrect API key provided."}}""", 401)
        failsWith<LlmException.Authentication> { runBlocking { client().chatStream(simpleRequest()).collect { } } }
        assertEquals("text/event-stream", server.takeRequest().headers["Accept"])
    }

    @Test
    fun `the timeouts and redirect policy are applied on top of a shared client`() {
        val shared = OkHttpClient.Builder().followRedirects(true).callTimeout(1, TimeUnit.SECONDS).build()
        val derived = providerHttpClient(shared, HttpTimeouts())
        assertEquals(15_000, derived.connectTimeoutMillis)
        assertEquals(600_000, derived.readTimeoutMillis)
        assertEquals(0, derived.callTimeoutMillis)
        assertFalse(derived.followRedirects)
        assertFalse(derived.followSslRedirects)
        assertTrue(derived.connectionPool === shared.connectionPool, "the connection pool must be shared")
    }
}
