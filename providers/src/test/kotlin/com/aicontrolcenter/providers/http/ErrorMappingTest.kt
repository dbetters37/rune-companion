package com.aicontrolcenter.providers.http

import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.failsWith
import com.aicontrolcenter.providers.openai.OpenAiCompatibleAdapterFactory
import com.aicontrolcenter.providers.simpleRequest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Headers.Companion.headersOf
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every row of the status and failure mapping, driven through a real adapter so that the
 * transport, the body parsing and the mapper are exercised together.
 */
class ErrorMappingTest : MockServerTest() {
    private val factory = OpenAiCompatibleAdapterFactory()

    private fun chat(baseUrl: String = baseUrl("/v1")): ChatResponse = runBlocking {
        factory.create(connection(ProviderKind.OPENAI_COMPATIBLE, baseUrl)).client.chat(simpleRequest())
    }

    private fun openAiError(message: String, code: String? = null) =
        """{"error":{"message":"$message","type":"invalid_request_error","param":null,"code":${code?.let { "\"$it\"" }}}}"""

    @Test
    fun `401 and 403 are authentication failures`() {
        for (status in listOf(401, 403)) {
            enqueue(openAiError("Incorrect API key provided.", "invalid_api_key"), status)
            val error = failsWith<LlmException.Authentication> { chat() }
            assertFalse(error.retryable)
            assertEquals("Test provider rejected the API key or does not allow this request (HTTP $status): Incorrect API key provided.", error.message)
        }
    }

    @Test
    fun `404 about the model means the model was not found`() {
        enqueue(openAiError("The model `gpt-9` does not exist or you do not have access to it.", "model_not_found"), 404)
        failsWith<LlmException.ModelNotFound> { chat() }
        enqueue("""{"error":"model 'llama9' not found"}""", 404)
        val error = failsWith<LlmException.ModelNotFound> { chat() }
        assertTrue("model 'llama9' not found" in error.message!!)
    }

    @Test
    fun `404 about anything else points at the base url`() {
        for (body in listOf("""{"detail":"Not Found"}""", "<html><body>404 page not found</body></html>", "")) {
            enqueue(body, 404)
            val error = failsWith<LlmException.InvalidRequest> { chat() }
            assertEquals(404, error.status)
            assertTrue("base URL" in error.message!!, error.message)
            assertFalse("<html>" in error.message!!)
        }
    }

    @Test
    fun `408 and 504 are timeouts`() {
        // OkHttp would repeat a request once after a 408, as HTTP allows; here only the framework repeats anything.
        enqueue("""{"error":{"message":"the request was not received in time"}}""", 408)
        assertTrue(failsWith<LlmException.Timeout> { chat() }.retryable)
        assertEquals(1, server.requestCount)
        enqueue("""{"error":{"message":"upstream timed out"}}""", 504)
        assertTrue(failsWith<LlmException.Timeout> { chat() }.retryable)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `429 is a rate limit carrying the wait the provider asked for`() {
        enqueue(openAiError("Rate limit reached.", "rate_limit_exceeded"), 429, "Retry-After" to "7")
        assertEquals(7_000, failsWith<LlmException.RateLimited> { chat() }.retryAfterMs)

        enqueue(openAiError("Rate limit reached."), 429, "retry-after-ms" to "1500", "Retry-After" to "2")
        assertEquals(1_500, failsWith<LlmException.RateLimited> { chat() }.retryAfterMs)

        enqueue(openAiError("Rate limit reached."), 429, "Retry-After" to "0.5")
        assertEquals(500, failsWith<LlmException.RateLimited> { chat() }.retryAfterMs)

        enqueue(
            """{"error":{"code":429,"message":"Quota exceeded.","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"34s"}]}}""",
            429,
        )
        assertEquals(34_000, failsWith<LlmException.RateLimited> { chat() }.retryAfterMs)

        enqueue(openAiError("Rate limit reached."), 429)
        val bare = failsWith<LlmException.RateLimited> { chat() }
        assertNull(bare.retryAfterMs)
        assertTrue(bare.retryable)
    }

    @Test
    fun `a 429 says when its provider names a daily quota, in the message or in the body`() {
        fun google(quotaId: String) =
            """[{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details.","status":"RESOURCE_EXHAUSTED",""" +
                """"details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",""" +
                """"quotaId":"$quotaId","quotaValue":"50"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"35s"}]}}]"""

        // Gemini names the day only in the quota's id in the body, and asks for a short wait all the same.
        enqueue(google("GenerateRequestsPerDayPerProjectPerModel-FreeTier"), 429)
        val daily = failsWith<LlmException.RateLimited> { chat() }
        assertTrue(daily.dailyQuota, daily.message)
        assertEquals(35_000, daily.retryAfterMs)
        enqueue(google("GenerateRequestsPerMinutePerProjectPerModel-FreeTier"), 429)
        assertFalse(failsWith<LlmException.RateLimited> { chat() }.dailyQuota)

        // OpenRouter and Groq say it in the message.
        enqueue(openAiError("Rate limit exceeded: free-models-per-day. Add 10 credits to unlock 1000 free model requests per day"), 429)
        assertTrue(failsWith<LlmException.RateLimited> { chat() }.dailyQuota)
        enqueue(openAiError("Rate limit exceeded: free-models-per-min."), 429)
        assertFalse(failsWith<LlmException.RateLimited> { chat() }.dailyQuota)
        enqueue(openAiError("Rate limit reached in organization `o` on tokens per day (TPD): Limit 100000, Used 99649, Requested 1015. Please try again in 9m10s.", "rate_limit_exceeded"), 429, "Retry-After" to "550")
        assertTrue(failsWith<LlmException.RateLimited> { chat() }.dailyQuota)
        enqueue(openAiError("Rate limit reached in organization `o` on requests per minute (RPM): Limit 30, Used 30, Requested 1. Please try again in 2s.", "rate_limit_exceeded"), 429, "Retry-After" to "2")
        assertFalse(failsWith<LlmException.RateLimited> { chat() }.dailyQuota)
    }

    @Test
    fun `a per-minute 429 that mentions today elsewhere, as Groq's upgrade line does, is not a daily quota`() {
        // Wave-2a residual verification, R3: only words that name a daily limit count; "today" in an advert does not.
        val upgrade = "Need more tokens? Upgrade to Dev Tier today at https://console.groq.com/settings/billing"
        for (limit in listOf("tokens per minute (TPM): Limit 12000, Used 11210, Requested 2450", "requests per minute (RPM): Limit 30, Used 30, Requested 1")) {
            enqueue(
                openAiError("Rate limit reached for model `m-1` in organization `o` service tier `on_demand` on $limit. Please try again in 8.3s. $upgrade", "rate_limit_exceeded"),
                429, "Retry-After" to "9",
            )
            val error = failsWith<LlmException.RateLimited> { chat() }
            assertFalse(error.dailyQuota, error.message)
            assertEquals(9_000, error.retryAfterMs)
        }
        // The words that do name a daily limit still count, in any spelling.
        for (daily in listOf("You have reached your daily request limit.", "Daily quota exceeded.", "Rate limit reached on tokens per day (TPD).", "Rate limit exceeded: free-models-per-day.")) {
            enqueue(openAiError(daily, "rate_limit_exceeded"), 429)
            assertTrue(failsWith<LlmException.RateLimited> { chat() }.dailyQuota, daily)
        }
    }

    @Test
    fun `retry-after is read as seconds or as a date`() {
        val now = 1_800_000_000_000L
        assertEquals(12_000, ProviderHttp.retryAfterMillis(headersOf("Retry-After", "12"), now))
        // Sun, 15 Jan 2027 08:00:30 GMT is 30 seconds after `now`.
        assertEquals(30_000, ProviderHttp.retryAfterMillis(headersOf("Retry-After", "Fri, 15 Jan 2027 08:00:30 GMT"), now))
        assertEquals(0, ProviderHttp.retryAfterMillis(headersOf("Retry-After", "Fri, 15 Jan 2027 07:00:00 GMT"), now))
        assertNull(ProviderHttp.retryAfterMillis(headersOf("Retry-After", "soon"), now))
        assertNull(ProviderHttp.retryAfterMillis(headersOf("Content-Type", "text/plain"), now))
    }

    @Test
    fun `400 413 and 422 are invalid requests`() {
        for (status in listOf(400, 413, 422)) {
            enqueue(openAiError("messages must not be empty"), status)
            val error = failsWith<LlmException.InvalidRequest> { chat() }
            assertEquals(status, error.status)
            assertFalse(error.retryable)
        }
        assertEquals(3, server.requestCount, "a rejection that names no setting is not retried")
    }

    @Test
    fun `a rejection for exceeding the context window is reported as such`() {
        val bodies = listOf(
            400 to openAiError("This model's maximum context length is 8192 tokens. However, your messages resulted in 9001 tokens.", "context_length_exceeded"),
            400 to """{"error":{"code":400,"message":"The input token count (1200000) exceeds the maximum number of tokens allowed (1048576).","status":"INVALID_ARGUMENT"}}""",
            422 to """{"error":"prompt is too long: 250000 tokens > 200000 maximum"}""",
            413 to openAiError("Request too large for model `x`: Limit 6000, Requested 9000 tokens."),
            400 to """{"error":{"message":"the request exceeds the available context size, try increasing it"}}""",
        )
        for ((status, body) in bodies) {
            enqueue(body, status)
            assertFalse(failsWith<LlmException.ContextTooLong> { chat() }.retryable)
        }
    }

    @Test
    fun `a 400 that says the key is invalid is an authentication failure`() {
        enqueue(
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com"}]}}""",
            400,
        )
        failsWith<LlmException.Authentication> { chat() }
    }

    @Test
    fun `a 400 that says the model does not exist is model not found`() {
        enqueue(openAiError("model 'ghost' not found", "model_not_found"), 400)
        failsWith<LlmException.ModelNotFound> { chat() }
    }

    @Test
    fun `529 and an overloaded 503 mean the provider is at capacity`() {
        enqueue("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""", 529)
        assertTrue(failsWith<LlmException.Overloaded> { chat() }.retryable)
        enqueue("""{"error":{"code":503,"message":"The model is overloaded. Please try again later.","status":"UNAVAILABLE"}}""", 503)
        failsWith<LlmException.Overloaded> { chat() }
        enqueue("""{"error":{"code":503,"message":"Loading model","type":"unavailable_error"}}""", 503)
        failsWith<LlmException.Overloaded> { chat() }
    }

    @Test
    fun `other 5xx are server errors with their status`() {
        for ((status, body) in listOf(500 to """{"error":{"message":"boom"}}""", 502 to "<html>Bad Gateway</html>", 503 to "<html>Service Unavailable</html>")) {
            enqueue(body, status)
            val error = failsWith<LlmException.Server> { chat() }
            assertEquals(status, error.status)
            assertTrue(error.retryable)
        }
    }

    @Test
    fun `a redirect is refused rather than followed`() {
        enqueue("", 302, "Location" to baseUrl("/elsewhere"))
        val error = failsWith<LlmException.InvalidRequest> { chat() }
        assertEquals(302, error.status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `other 4xx are invalid requests`() {
        enqueue("""{"error":{"message":"Insufficient credits","code":402}}""", 402)
        assertEquals(402, failsWith<LlmException.InvalidRequest> { chat() }.status)
    }

    @Test
    fun `a success that cannot be decoded is a malformed response`() {
        for (body in listOf("this is not json", "", "[1,2,3]", """{"choices":[]}""", """{"id":"x"}""")) {
            enqueue(body)
            assertFalse(failsWith<LlmException.MalformedResponse> { chat() }.retryable, body)
        }
    }

    @Test
    fun `an error object inside a 200 reply is mapped by its embedded code`() {
        enqueue("""{"error":{"code":429,"message":"Rate limited upstream"}}""")
        failsWith<LlmException.RateLimited> { chat() }
        enqueue("""{"error":{"message":"Provider returned error"}}""")
        assertEquals(500, failsWith<LlmException.Server> { chat() }.status)
    }

    @Test
    fun `silence beyond the idle timeout is a timeout`() {
        val impatient = OpenAiCompatibleAdapterFactory(timeouts = HttpTimeouts(idleMillis = 250))
        server.enqueue(MockResponse.Builder().body("{}").headersDelay(5, TimeUnit.SECONDS).build())
        val error = failsWith<LlmException.Timeout> {
            runBlocking { impatient.create(connection(ProviderKind.OPENAI_COMPATIBLE, baseUrl("/v1"))).client.chat(simpleRequest()) }
        }
        assertIs<SocketTimeoutException>(error.cause)
        assertTrue(error.retryable)
    }

    @Test
    fun `a refused connection is a network failure`() {
        val closed = MockWebServer().apply { start() }
        val address = closed.url("/v1").toString()
        closed.close()
        assertTrue(failsWith<LlmException.Network> { chat(address) }.retryable)
    }

    @Test
    fun `a connection dropped during the body is a network failure`() {
        server.enqueue(MockResponse.Builder().body("{\"choices\":[" + " ".repeat(4096)).onResponseBody(SocketEffect.CloseSocket()).build())
        failsWith<LlmException.Network> { chat() }
    }

    @Test
    fun `io failures map by kind`() {
        val mapper = ErrorMapper("Home server", Redactor(emptyList()))
        assertIs<LlmException.Timeout>(mapper.fromIo(SocketTimeoutException("Read timed out")))
        assertIs<LlmException.Timeout>(mapper.fromIo(java.io.InterruptedIOException("timeout")))
        assertIs<LlmException.Network>(mapper.fromIo(UnknownHostException("nowhere.invalid")))
        assertIs<LlmException.Network>(mapper.fromIo(ConnectException("Connection refused")))
        assertIs<LlmException.Network>(mapper.fromIo(SSLHandshakeException("bad certificate")))
        assertIs<LlmException.Network>(mapper.fromIo(java.io.IOException("unexpected end of stream")))
        assertTrue(mapper.fromIo(ConnectException("Connection refused")).message!!.startsWith("Could not connect to Home server"))
    }
}
