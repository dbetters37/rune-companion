package com.aicontrolcenter.providers.openai

import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ReasoningEffort
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.collectBlocking
import com.aicontrolcenter.providers.completed
import com.aicontrolcenter.providers.failsWith
import com.aicontrolcenter.providers.json
import com.aicontrolcenter.providers.simpleRequest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Optional settings are hints: a provider that refuses one gets the request again without it,
 * and a refusal of the setting itself is remembered for that model.
 */
class OpenAiToleranceTest : MockServerTest() {
    private val ok = """{"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}"""
    private val factory = OpenAiCompatibleAdapterFactory()

    /** A new adapter per call, as the framework creates them; only the factory is long-lived. */
    private fun client(factory: OpenAiCompatibleAdapterFactory = this.factory) =
        factory.create(connection(ProviderKind.OPENAI_COMPATIBLE, baseUrl("/v1"))).client

    private fun chat(request: ChatRequest) = runBlocking { client().chat(request) }

    private fun request(model: String = "o-test") = simpleRequest(model).copy(temperature = 0.2, maxOutputTokens = 256)

    private val maxTokensUnsupported =
        """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.","type":"invalid_request_error","param":"max_tokens","code":"unsupported_parameter"}}"""
    private val temperatureUnsupported =
        """{"error":{"message":"Unsupported value: 'temperature' does not support 0.2 with this model. Only the default (1) value is supported.","type":"invalid_request_error","param":"temperature","code":"unsupported_value"}}"""

    @Test
    fun `a refused max_tokens is resent as max_completion_tokens and remembered for the model`() {
        enqueue(maxTokensUnsupported, 400)
        enqueue(ok)
        assertEquals("hi", chat(request()).message.text)

        assertEquals(json("256"), takeBody()["max_tokens"])
        val retried = takeBody()
        assertNull(retried["max_tokens"])
        assertEquals(json("256"), retried["max_completion_tokens"])
        assertEquals(json("0.2"), retried["temperature"], "settings that were not refused stay")

        enqueue(ok)
        chat(request())
        val later = takeBody()
        assertNull(later["max_tokens"], "the refusal is remembered across adapters of the same factory")
        assertEquals(json("256"), later["max_completion_tokens"])
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `the memory is kept per model and per factory`() {
        enqueue(maxTokensUnsupported, 400)
        enqueue(ok)
        chat(request("o-test"))
        repeat(2) { server.takeRequest() }

        enqueue(ok)
        chat(request("another-model"))
        assertEquals(json("256"), takeBody()["max_tokens"], "another model is asked the usual way")

        enqueue(ok)
        runBlocking { client(OpenAiCompatibleAdapterFactory()).chat(request("o-test")) }
        assertEquals(json("256"), takeBody()["max_tokens"], "a new factory starts without memory")
    }

    @Test
    fun `several refused settings are dropped one after another`() {
        enqueue(maxTokensUnsupported, 400)
        enqueue(temperatureUnsupported, 400)
        enqueue(ok)
        assertEquals("hi", chat(request()).message.text)
        repeat(2) { server.takeRequest() }
        val accepted = takeBody()
        assertNull(accepted["temperature"])
        assertNull(accepted["max_tokens"])
        assertEquals(json("256"), accepted["max_completion_tokens"])

        enqueue(ok)
        chat(request())
        val later = takeBody()
        assertNull(later["temperature"])
        assertNull(later["max_tokens"])
    }

    @Test
    fun `a refusal worded without a param field is recognised from the message`() {
        // vLLM and other FastAPI servers answer 422 with a validation list; older servers name the argument in prose.
        enqueue("""{"detail":[{"type":"extra_forbidden","loc":["body","reasoning_effort"],"msg":"Extra inputs are not permitted","input":"low"}]}""", 422)
        enqueue("""{"error":{"message":"Unrecognized request argument supplied: response_format","type":"invalid_request_error"}}""", 400)
        enqueue(ok)
        val request = simpleRequest("local-model").copy(reasoningEffort = ReasoningEffort.HIGH, jsonOutput = true)
        chat(request)
        assertEquals(json("\"high\""), takeBody()["reasoning_effort"])
        val second = takeBody()
        assertNull(second["reasoning_effort"])
        assertEquals(json("""{"type":"json_object"}"""), second["response_format"])
        val third = takeBody()
        assertNull(third["reasoning_effort"])
        assertNull(third["response_format"])
    }

    @Test
    fun `a stream whose usage option is refused is restarted without it`() {
        enqueue("""{"error":{"message":"Unrecognized request argument supplied: stream_options","type":"invalid_request_error","param":null,"code":null}}""", 400)
        enqueueSse("""{"choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}""", "[DONE]")
        val chunks = client().chatStream(simpleRequest("old-server-model")).collectBlocking()
        assertEquals("hi", chunks.completed().response.message.text)

        assertEquals(json("""{"include_usage":true}"""), takeBody()["stream_options"])
        val retried = takeBody()
        assertNull(retried["stream_options"])
        assertEquals(json("true"), retried["stream"])

        enqueueSse("""{"choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}""", "[DONE]")
        client().chatStream(simpleRequest("old-server-model")).collectBlocking()
        assertNull(takeBody()["stream_options"], "remembered for the model")
    }

    @Test
    fun `an objection to a value is retried without the limit but not remembered`() {
        enqueue(
            """{"error":{"message":"max_tokens is too large: 256. This model supports at most 128 completion tokens.","type":"invalid_request_error","param":"max_tokens","code":null}}""",
            400,
        )
        enqueue(ok)
        chat(request())
        server.takeRequest()
        val retried = takeBody()
        assertNull(retried["max_tokens"])
        assertNull(retried["max_completion_tokens"], "the same value under another name would be refused again")

        enqueue(ok)
        chat(request())
        assertEquals(json("256"), takeBody()["max_tokens"], "a later request with another value may be fine")
    }

    @Test
    fun `a provider that keeps refusing gets a bounded number of attempts`() {
        repeat(6) { enqueue(maxTokensUnsupported, 400) }
        val error = failsWith<LlmException.InvalidRequest> { chat(request()) }
        assertEquals(400, error.status)
        assertTrue(server.requestCount <= 3, "each setting is given up once; then the rejection stands (${server.requestCount} requests)")
    }

    @Test
    fun `a rejection about something that was not sent is not retried`() {
        enqueue(temperatureUnsupported, 400)
        failsWith<LlmException.InvalidRequest> { chat(simpleRequest("o-test")) }
        assertEquals(1, server.requestCount)
    }
}
