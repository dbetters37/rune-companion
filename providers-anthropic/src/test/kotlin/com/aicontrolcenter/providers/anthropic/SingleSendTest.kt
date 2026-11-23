package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderAdapter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The adapter's transport sends every request once under the default options. Trying again is
 * the framework's decision, because only the framework re-checks policy and records each
 * transfer.
 */
internal class SingleSendTest : MockApiTest() {
    @get:Rule
    val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

    private val request = ChatRequest("claude-opus-5-5", listOf(ChatMessage.user("PRIVATE PROMPT TEXT")))

    /** Every request gets [response], so a request repeated on its own is counted too. */
    private fun always(response: () -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = response()
        }
    }

    private fun error(code: Int, type: String, vararg headers: Pair<String, String>): MockResponse {
        val body = """{"type":"error","error":{"type":"$type","message":${JsonPrimitive("upstream $code")}},"request_id":"req_1"}"""
        val builder = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return builder.build()
    }

    /** A plain call and a stream (both with a body) and a model listing (a GET without one), all with default options. */
    private val calls: List<Pair<String, suspend (ProviderAdapter) -> Unit>> = listOf(
        "chat" to { it.client.chat(request) },
        "stream" to { it.client.chatStream(request).toList() },
        "models" to { it.listModels() },
    )

    private inline fun <reified T : LlmException> eachCallIsSentOnceAndFailsWith(): List<T> = calls.map { (name, call) ->
        val before = server.requestCount
        val error = try {
            runBlocking { call(adapter(options = AnthropicOptions())) }
            fail("$name succeeded")
        } catch (e: LlmException) {
            e as? T ?: fail("$name: expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}")
        }
        assertEquals(1, server.requestCount - before, "$name was sent ${server.requestCount - before} times")
        error
    }

    @Test
    fun `a 503 that asks for an immediate retry is sent once`() {
        always { error(503, "api_error", "Retry-After" to "0") }
        eachCallIsSentOnceAndFailsWith<LlmException.Server>().forEach { assertEquals(503, it.status) }
    }

    @Test
    fun `a 408 is sent once`() {
        always { error(408, "timeout_error") }
        eachCallIsSentOnceAndFailsWith<LlmException.Timeout>()
    }

    @Test
    fun `a connection dropped before the response is sent once`() {
        always { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }
        eachCallIsSentOnceAndFailsWith<LlmException.Network>().forEach { assertTrue(it.retryable) }
    }

    @Test
    fun `a pooled connection that fails is not used to send the request again`() {
        val script = LinkedBlockingQueue<MockResponse>()
        always { script.poll() ?: error(500, "api_error") }
        val replies = mapOf(
            "chat" to { MockResponse.Builder().addHeader("Content-Type", "application/json").body(messageJson(TEXT_REPLY)).build() },
            "stream" to {
                val events = listOf(
                    "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}}""",
                    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                )
                MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }).build()
            },
            "models" to {
                MockResponse.Builder().addHeader("Content-Type", "application/json")
                    .body("""{"data":[{"type":"model","id":"claude-opus-5-5","display_name":"Opus","created_at":"2026-01-01T00:00:00Z"}],"has_more":false,"first_id":"a","last_id":"a"}""").build()
            },
        )
        for ((name, call) in calls) {
            val adapter = adapter(options = AnthropicOptions())
            script += replies.getValue(name)()
            runBlocking { call(adapter) }
            script += MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
            script += replies.getValue(name)()
            val before = server.requestCount

            val failure = runCatching { runBlocking { call(adapter) } }.exceptionOrNull()

            assertTrue(failure is LlmException.Network, "$name: got $failure")
            assertEquals(1, server.requestCount - before, "$name was sent ${server.requestCount - before} times")
            script.clear()
        }
    }

    @Test
    fun `a Retry-After too large for OkHttp to read is a server error, not a crash`() {
        // OkHttp reads a 503's Retry-After with Integer.valueOf; this one would throw out of the call.
        always { error(503, "api_error", "Retry-After" to "99999999999") }
        eachCallIsSentOnceAndFailsWith<LlmException.Server>()
    }

    @Test
    fun `bodies leave as one-shot and the response keeps the Retry-After the server sent`() {
        // A header under the name used internally is the server's, and must not turn into a Retry-After.
        always { error(503, "api_error", "Retry-After" to "0", "X-AICC-Held-Retry-After" to "120") }
        val oneShot = CopyOnWriteArrayList<Boolean>()
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain -> chain.request().body?.let { oneShot += it.isOneShot() }; chain.proceed(chain.request()) }
            .singleSend()
            .build()

        val response = client.newCall(Request.Builder().url(server.url("/v1/messages")).post("{}".toRequestBody()).build()).execute()

        response.use {
            assertEquals(503, it.code)
            assertEquals(listOf("0"), it.headers.values("Retry-After"))
            assertEquals(emptyList(), it.headers.values("X-AICC-Held-Retry-After"))
        }
        assertEquals(listOf(true), oneShot)
        assertEquals(1, server.requestCount)
    }
}
