package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import com.anthropic.errors.AnthropicIoException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ErrorMappingTest : MockApiTest() {

    private val request = ChatRequest("claude-opus-4-8", listOf(ChatMessage.user("Hi")))

    private inline fun <reified E : LlmException> failure(options: AnthropicOptions = AnthropicOptions(maxRetries = 0)): E =
        assertFailsWith<E> { runBlocking { adapter(options).client.chat(request) } }

    @Test
    fun `a rejected key and a forbidden action are authentication errors`() {
        enqueueError(401, "authentication_error", "invalid x-api-key")
        val unauthorized = failure<LlmException.Authentication>()
        assertFalse(unauthorized.retryable)
        assertTrue(unauthorized.message!!.contains("invalid x-api-key"))
        assertTrue(unauthorized.message!!.contains("Claude"), "the message names the provider")

        enqueueError(403, "permission_error", "Your API key does not have permission to use the specified resource.")
        failure<LlmException.Authentication>()

        enqueueError(402, "billing_error", "Your credit balance is too low.")
        assertTrue(failure<LlmException.Authentication>().message!!.contains("billing"))
    }

    @Test
    fun `a rate limit carries the wait the provider asked for`() {
        enqueueError(429, "rate_limit_error", "Rate limit exceeded.", mapOf("retry-after" to "7"))
        val seconds = failure<LlmException.RateLimited>()
        assertEquals(7_000L, seconds.retryAfterMs)
        assertTrue(seconds.retryable)

        enqueueError(429, "rate_limit_error", "Rate limit exceeded.", mapOf("retry-after-ms" to "250", "retry-after" to "1"))
        assertEquals(250L, failure<LlmException.RateLimited>().retryAfterMs)

        enqueueError(429, "rate_limit_error", "Monthly spend limit reached.")
        assertNull(failure<LlmException.RateLimited>().retryAfterMs)
    }

    @Test
    fun `an unknown model is model-not-found and an unknown path is not`() {
        enqueueError(404, "not_found_error", "model: claude-opus-9")
        assertTrue(failure<LlmException.ModelNotFound>().message!!.contains("claude-opus-9"))

        server.enqueue(MockResponse.Builder().code(404).body("<html>Not Found</html>").build())
        assertEquals(404, failure<LlmException.InvalidRequest>().status)
    }

    @Test
    fun `an oversized request and an overlong prompt are context errors`() {
        enqueueError(413, "request_too_large", "Request exceeds the maximum allowed number of bytes.")
        failure<LlmException.ContextTooLong>()

        enqueueError(400, "invalid_request_error", "prompt is too long: 1203441 tokens > 1000000 maximum")
        assertTrue(failure<LlmException.ContextTooLong>().message!!.contains("1203441"))
    }

    @Test
    fun `other rejected requests are invalid-request errors with their status`() {
        enqueueError(400, "invalid_request_error", "messages: roles must alternate")
        val bad = failure<LlmException.InvalidRequest>()
        assertEquals(400, bad.status)
        assertTrue(bad.message!!.contains("roles must alternate"))
        assertFalse(bad.retryable)

        enqueueError(422, "invalid_request_error", "unprocessable")
        assertEquals(422, failure<LlmException.InvalidRequest>().status)
    }

    @Test
    fun `overload and server errors are told apart and both retryable`() {
        enqueueError(529, "overloaded_error", "Overloaded")
        assertTrue(failure<LlmException.Overloaded>().retryable)

        enqueueError(500, "api_error", "Internal server error")
        assertEquals(500, failure<LlmException.Server>().status)

        server.enqueue(MockResponse.Builder().code(503).body("upstream unavailable").build())
        assertEquals(503, failure<LlmException.Server>().status)
    }

    @Test
    fun `timeouts reported by the provider are timeouts`() {
        enqueueError(408, "timeout_error", "Request timed out")
        failure<LlmException.Timeout>()

        enqueueError(504, "timeout_error", "Gateway timeout")
        assertTrue(failure<LlmException.Timeout>().retryable)
    }

    @Test
    fun `a dropped connection and an unreachable server are network errors`() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        assertTrue(failure<LlmException.Network>().retryable)

        val unreachable = adapter(baseUrl = "http://127.0.0.1:1")
        assertFailsWith<LlmException.Network> { runBlocking { unreachable.client.chat(request) } }
    }

    @Test
    fun `a reply that is not a message is a malformed response`() {
        enqueueJson("""{"unexpected":true}""")
        assertFalse(failure<LlmException.MalformedResponse>().retryable)

        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("<html>not json</html>").build())
        failure<LlmException.MalformedResponse>()
    }

    @Test
    fun `the SDK's own retries still run before an error is reported`() {
        val quick = mapOf("retry-after-ms" to "1")
        enqueueError(529, "overloaded_error", "Overloaded", quick)
        enqueueError(500, "api_error", "Internal server error", quick)
        enqueueJson(messageJson(TEXT_REPLY))

        val response = runBlocking { adapter(AnthropicOptions()).client.chat(request) }

        assertEquals("Hello there.", response.message.text)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `retries are bounded and not spent on errors that cannot improve`() {
        repeat(3) { enqueueError(529, "overloaded_error", "Overloaded", mapOf("retry-after-ms" to "1")) }
        failure<LlmException.Overloaded>(AnthropicOptions())
        assertEquals(3, server.requestCount, "the first attempt and the SDK's two retries")

        enqueueError(400, "invalid_request_error", "bad")
        failure<LlmException.InvalidRequest>(AnthropicOptions())
        assertEquals(4, server.requestCount, "a rejected request is not repeated")
    }

    @Test
    fun `a missing key fails as an authentication error without touching the network`() {
        val error = assertFailsWith<LlmException.Authentication> { runBlocking { adapter(apiKey = null).client.chat(request) } }

        assertTrue(error.message!!.contains("No API key"))
        assertFailsWith<LlmException.Authentication> { runBlocking { adapter(apiKey = "  ").client.chat(request) } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a timed out connection is a timeout and other connection failures are network errors`() {
        val mapper = ErrorMapper("Claude", TEST_KEY)

        val readTimeout = mapper.map(AnthropicIoException("Request failed", SocketTimeoutException("timeout")))
        assertIs<LlmException.Timeout>(readTimeout)
        assertTrue(readTimeout.retryable)
        assertIs<LlmException.Timeout>(mapper.map(AnthropicIoException("Request failed", InterruptedIOException("timeout"))))
        assertIs<LlmException.Network>(mapper.map(AnthropicIoException("Request failed", ConnectException("Connection refused"))))
        assertIs<LlmException.Network>(mapper.map(UnknownHostException("api.anthropic.com")))
        assertIs<LlmException.InvalidRequest>(mapper.map(IllegalArgumentException("Streaming is required for long operations")))
    }
}
