package com.aicontrolcenter.providers.gemini

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.WEATHER_TOOL
import com.aicontrolcenter.providers.assertJsonEquals
import com.aicontrolcenter.providers.collectBlocking
import com.aicontrolcenter.providers.completed
import com.aicontrolcenter.providers.failsWith
import com.aicontrolcenter.providers.json
import com.aicontrolcenter.providers.simpleRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How Gemini replies become [ChatResponse]s and [ChatChunk]s, and how thought signatures get back to the model. */
class GeminiResponseTest : MockServerTest() {
    private val factory = GeminiAdapterFactory()

    private fun client() = factory.create(connection(ProviderKind.GEMINI, baseUrl("/v1beta"))).client

    private fun chat(reply: String, request: ChatRequest = simpleRequest("gemini-3-flash")): ChatResponse {
        enqueue(reply)
        return runBlocking { client().chat(request) }
    }

    private fun stream(vararg events: String): List<ChatChunk> {
        enqueueSse(*events)
        return client().chatStream(simpleRequest("gemini-3-flash")).collectBlocking()
    }

    /** One response object; [finish] null is a streamed piece that does not end the reply. */
    private fun candidate(parts: String, finish: String? = "STOP"): String {
        val finishField = if (finish == null) "" else ""","finishReason":"$finish""""
        return """{"candidates":[{"content":{"role":"model","parts":[$parts]}$finishField,"index":0}]}"""
    }

    @Test
    fun `a text reply carries text model version and usage`() {
        val response = chat(
            """{"candidates":[{"content":{"parts":[{"text":"It is 4 degrees."}],"role":"model"},"finishReason":"STOP","index":0}],
                "usageMetadata":{"promptTokenCount":25,"candidatesTokenCount":7,"totalTokenCount":92,"thoughtsTokenCount":60,"cachedContentTokenCount":10},
                "modelVersion":"gemini-3-flash-001","responseId":"r1"}""",
        )
        assertEquals("It is 4 degrees.", response.message.text)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(TokenUsage(inputTokens = 25, outputTokens = 67, cachedInputTokens = 10), response.usage, "thinking tokens are output")
        assertEquals("gemini-3-flash-001", response.modelName)
        assertNull(response.message.reasoning)
    }

    @Test
    fun `function calls and thought summaries are parsed`() {
        val response = chat(
            candidate(
                """{"text":"I need the weather.","thought":true},
                   {"functionCall":{"name":"get_weather","args":{"city":"Oslo"},"id":"fc_1"},"thoughtSignature":"SIG_A"},
                   {"functionCall":{"name":"get_time"}}""",
            ),
        )
        val (weather, time) = response.message.toolCalls
        assertEquals("fc_1", weather.id)
        assertEquals("get_weather", weather.name)
        assertEquals(json("""{"city":"Oslo"}"""), weather.arguments)
        assertEquals("get_time", time.name)
        assertEquals(json("{}"), time.arguments)
        assertTrue(time.id.startsWith("call_") && time.id.length > 10, "a call without an id is given one")
        assertEquals("I need the weather.", response.message.reasoning)
        assertEquals("", response.message.text)
        assertEquals(FinishReason.TOOL_CALLS, response.finishReason, "gemini says STOP for a turn that calls functions")
    }

    @Test
    fun `thought signatures are returned verbatim with the turn that carried them`() {
        val parts = """{"text":"I need the weather.","thought":true},
                       {"functionCall":{"name":"get_weather","args":{"city":"Oslo"},"id":"fc_1"},"thoughtSignature":"SIG_A"},
                       {"functionCall":{"name":"get_time","args":{}}}"""
        val first = chat(candidate(parts), simpleRequest("gemini-3-flash").copy(tools = listOf(WEATHER_TOOL)))
        val stored = assertNotNull(first.message.providerData)
        assertEquals(ProviderKind.GEMINI, stored.kind)
        server.takeRequest()

        val (weather, time) = first.message.toolCalls
        val followUp = simpleRequest("gemini-3-flash").let {
            it.copy(
                messages = it.messages + first.message +
                    ChatMessage.toolResult(weather.id, weather.name, "4 degrees") +
                    ChatMessage.toolResult(time.id, time.name, "clock unavailable", isError = true),
            )
        }
        chat(candidate("""{"text":"It is 4 degrees."}"""), followUp)
        assertJsonEquals(
            """[
              {"role":"user","parts":[{"text":"Hello"}]},
              {"role":"model","parts":[$parts]},
              {"role":"user","parts":[
                {"functionResponse":{"id":"fc_1","name":"get_weather","response":{"result":"4 degrees"}}},
                {"functionResponse":{"name":"get_time","response":{"error":"clock unavailable"}}}]}
            ]""",
            takeBody().getValue("contents"),
        )
    }

    @Test
    fun `a streamed reply keeps signatures intact while joining plain text`() {
        val chunks = stream(
            candidate("""{"text":"Thinking a","thought":true}""", finish = null),
            candidate("""{"text":"bout it.","thought":true}""", finish = null),
            candidate("""{"text":"It is "}""", finish = null),
            candidate("""{"text":"4."}""", finish = null),
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"","thoughtSignature":"SIG_T"}]},"finishReason":"STOP","index":0}],""" +
                """"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":5,"thoughtsTokenCount":30},"modelVersion":"gemini-3-flash-001"}""",
        )
        assertEquals(
            listOf(ChatChunk.Reasoning("Thinking a"), ChatChunk.Reasoning("bout it."), ChatChunk.Text("It is "), ChatChunk.Text("4.")),
            chunks.dropLast(1),
        )
        val response = chunks.completed().response
        assertEquals("It is 4.", response.message.text)
        assertEquals("Thinking about it.", response.message.reasoning)
        assertEquals(TokenUsage(12, 35), response.usage)
        assertEquals("gemini-3-flash-001", response.modelName)

        server.takeRequest()
        chat(candidate("""{"text":"ok"}"""), simpleRequest("gemini-3-flash").let { it.copy(messages = it.messages + response.message + ChatMessage.user("Sure?")) })
        assertJsonEquals(
            """{"role":"model","parts":[{"text":"Thinking about it.","thought":true},{"text":"It is 4."},{"text":"","thoughtSignature":"SIG_T"}]}""",
            takeBody().getValue("contents").jsonArray[1],
        )
    }

    @Test
    fun `a streamed function call is reported when it arrives`() {
        val chunks = stream(
            candidate("""{"text":"Let me check. "}""", finish = null),
            candidate("""{"functionCall":{"name":"get_weather","args":{"city":"Oslo"}},"thoughtSignature":"SIG_B"}""", finish = null),
            """{"candidates":[{"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":14}}""",
        )
        assertEquals(ChatChunk.Text("Let me check. "), chunks[0])
        val call = (chunks[1] as ChatChunk.ToolCall).call
        assertEquals("get_weather", call.name)
        val response = chunks.completed().response
        assertEquals(listOf(call), response.message.toolCalls)
        assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
        assertEquals(3, chunks.size)
    }

    @Test
    fun `finish reasons map onto the neutral ones`() {
        assertEquals(FinishReason.LENGTH, chat(candidate("""{"text":"cut of"}""", "MAX_TOKENS")).finishReason)
        for (filtered in listOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "LANGUAGE", "IMAGE_SAFETY")) {
            assertEquals(FinishReason.REFUSED, chat("""{"candidates":[{"finishReason":"$filtered","index":0}]}""").finishReason, filtered)
        }
        assertEquals(FinishReason.UNKNOWN, chat(candidate("""{"text":""}""", "MALFORMED_FUNCTION_CALL")).finishReason)
        assertEquals(FinishReason.UNKNOWN, chat(candidate("""{"text":"hm"}""", "OTHER")).finishReason)
    }

    @Test
    fun `a blocked prompt is a refusal without a candidate`() {
        val blocked = chat("""{"promptFeedback":{"blockReason":"SAFETY","safetyRatings":[{"category":"HARM_CATEGORY_DANGEROUS_CONTENT","probability":"HIGH"}]},"usageMetadata":{"promptTokenCount":8,"totalTokenCount":8}}""")
        assertEquals(FinishReason.REFUSED, blocked.finishReason)
        assertEquals("", blocked.message.text)
        assertNull(blocked.message.providerData)
        assertEquals(TokenUsage(8, 0), blocked.usage)

        val streamed = stream("""{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},"usageMetadata":{"promptTokenCount":8}}""")
        assertEquals(FinishReason.REFUSED, streamed.completed().response.finishReason)
    }

    @Test
    fun `an empty reply an unreadable call and a cut-off stream are failures`() {
        failsWith<LlmException.MalformedResponse> { chat("{}") }
        failsWith<LlmException.MalformedResponse> { chat(candidate("""{"functionCall":{"args":{}}}""")) }
        failsWith<LlmException.MalformedResponse> { chat(candidate("""{"functionCall":{"name":"get_weather","args":"Oslo"}}""")) }
        assertTrue(failsWith<LlmException.Network> { stream(candidate("""{"text":"It is "}""", finish = null)) }.retryable)
    }

    @Test
    fun `google's error bodies are mapped including one inside a stream`() {
        enqueue("""{"error":{"code":404,"message":"models/ghost is not found for API version v1beta, or is not supported for generateContent.","status":"NOT_FOUND"}}""", 404)
        failsWith<LlmException.ModelNotFound> { runBlocking { client().chat(simpleRequest("ghost")) } }

        enqueue(
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}""",
            400,
        )
        failsWith<LlmException.Authentication> { runBlocking { client().chat(simpleRequest("gemini-3-flash")) } }

        enqueue(
            """{"error":{"code":429,"message":"You exceeded your current quota.","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"21s"}]}}""",
            429,
        )
        assertEquals(21_000, failsWith<LlmException.RateLimited> { runBlocking { client().chat(simpleRequest("gemini-3-flash")) } }.retryAfterMs)

        val overloaded = failsWith<LlmException.Overloaded> {
            stream(
                candidate("""{"text":"It is "}""", finish = null),
                """{"error":{"code":503,"message":"The model is overloaded. Please try again later.","status":"UNAVAILABLE"}}""",
            )
        }
        assertTrue("HTTP" !in overloaded.message!!, "an error inside a stream has no HTTP status of its own")
    }
}
