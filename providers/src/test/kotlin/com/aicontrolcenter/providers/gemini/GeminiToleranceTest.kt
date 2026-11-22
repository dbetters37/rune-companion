package com.aicontrolcenter.providers.gemini

import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ReasoningEffort
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.WEATHER_TOOL
import com.aicontrolcenter.providers.assertJsonEquals
import com.aicontrolcenter.providers.collectBlocking
import com.aicontrolcenter.providers.completed
import com.aicontrolcenter.providers.failsWith
import com.aicontrolcenter.providers.simpleRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Optional settings Gemini refuses for a model are dropped, the request repeated, and the refusal remembered. */
class GeminiToleranceTest : MockServerTest() {
    private val factory = GeminiAdapterFactory()
    private val ok = """{"candidates":[{"content":{"role":"model","parts":[{"text":"hi"}]},"finishReason":"STOP"}]}"""

    private fun client() = factory.create(connection(ProviderKind.GEMINI, baseUrl("/v1beta"))).client

    private fun chat(request: ChatRequest) = runBlocking { client().chat(request) }

    private fun invalidArgument(message: String) = """{"error":{"code":400,"message":"$message","status":"INVALID_ARGUMENT"}}"""

    private fun generationConfig(): JsonObject? = takeBody()["generationConfig"]?.jsonObject

    @Test
    fun `a refused thinking level falls back to a budget and is remembered`() {
        val request = simpleRequest("gemini-next").copy(reasoningEffort = ReasoningEffort.MEDIUM, temperature = 0.5)
        enqueue(invalidArgument("Thinking level is not supported for this model."), 400)
        enqueue(ok)
        assertEquals("hi", chat(request).message.text)
        assertJsonEquals("""{"temperature":0.5,"thinkingConfig":{"includeThoughts":true,"thinkingLevel":"MEDIUM"}}""", generationConfig()!!)
        assertJsonEquals("""{"temperature":0.5,"thinkingConfig":{"includeThoughts":true,"thinkingBudget":8192}}""", generationConfig()!!)

        enqueue(ok)
        chat(request)
        assertJsonEquals("""{"temperature":0.5,"thinkingConfig":{"includeThoughts":true,"thinkingBudget":8192}}""", generationConfig()!!)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a model that cannot think is asked without any thinking setting`() {
        val request = simpleRequest("gemini-plain").copy(reasoningEffort = ReasoningEffort.HIGH)
        enqueue(invalidArgument("Unable to submit request because thinking is not supported by this model."), 400)
        enqueue(ok)
        chat(request)
        server.takeRequest()
        assertNull(generationConfig(), "neither spelling of the setting is tried once thinking itself is refused")

        enqueue(ok)
        chat(request)
        assertNull(generationConfig(), "remembered for the model")
    }

    @Test
    fun `json output refused together with tools is dropped for that request only`() {
        val withTools = simpleRequest("gemini-2.5-flash").copy(jsonOutput = true, tools = listOf(WEATHER_TOOL))
        enqueue(invalidArgument("Function calling with a response mime type: 'application/json' is unsupported"), 400)
        enqueue(ok)
        chat(withTools)
        assertJsonEquals("""{"responseMimeType":"application/json"}""", generationConfig()!!)
        assertNull(generationConfig())

        enqueue(ok)
        chat(withTools.copy(tools = emptyList()))
        // JSON output on its own is still asked for.
        assertJsonEquals("""{"responseMimeType":"application/json"}""", generationConfig()!!)
    }

    @Test
    fun `a streamed request recovers the same way`() {
        enqueue(invalidArgument("Thinking level is not supported for this model."), 400)
        enqueueSse(ok)
        val chunks = client().chatStream(simpleRequest("gemini-next").copy(reasoningEffort = ReasoningEffort.LOW)).collectBlocking()
        assertEquals("hi", chunks.completed().response.message.text)
        server.takeRequest()
        assertJsonEquals("""{"thinkingConfig":{"includeThoughts":true,"thinkingBudget":1024}}""", generationConfig()!!)
    }

    @Test
    fun `a rejection about anything else stands`() {
        enqueue(invalidArgument("Please ensure that function response turn comes immediately after a function call turn."), 400)
        val error = failsWith<LlmException.InvalidRequest> { chat(simpleRequest("gemini-next").copy(temperature = 0.5, maxOutputTokens = 64)) }
        assertEquals(400, error.status)
        assertEquals(1, server.requestCount)
    }
}
