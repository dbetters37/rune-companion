package com.aicontrolcenter.providers.ollama

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ReasoningEffort
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.llm.ToolChoice
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.TEST_KEY
import com.aicontrolcenter.providers.WEATHER_SCHEMA_JSON
import com.aicontrolcenter.providers.WEATHER_TOOL
import com.aicontrolcenter.providers.assertJsonEquals
import com.aicontrolcenter.providers.collectBlocking
import com.aicontrolcenter.providers.completed
import com.aicontrolcenter.providers.failsWith
import com.aicontrolcenter.providers.fullConversation
import com.aicontrolcenter.providers.json
import com.aicontrolcenter.providers.simpleRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ollama's native `POST /api/chat`: the request sent, and plain and NDJSON replies. */
class OllamaChatTest : MockServerTest() {
    private val factory = OllamaAdapterFactory()
    private val done = """{"model":"llama3.2","created_at":"2026-11-22T10:00:00Z","message":{"role":"assistant","content":"ok"},"done":true,"done_reason":"stop"}"""

    private fun client(apiKey: String? = null) = factory.create(connection(ProviderKind.OLLAMA, apiKey = apiKey)).client

    private fun chat(reply: String, request: ChatRequest = simpleRequest("llama3.2")) = runBlocking {
        enqueue(reply)
        client().chat(request)
    }

    private fun stream(vararg lines: String, request: ChatRequest = simpleRequest("llama3.2")): List<ChatChunk> {
        enqueueLines(*lines)
        return client().chatStream(request).collectBlocking()
    }

    private fun fullTurn(stream: Boolean) = """{
        "model": "llama3.2",
        "messages": [
          {"role": "system", "content": "You are terse.\n\nAnswer in English."},
          {"role": "user", "content": "What is the weather in Oslo?", "images": ["aGVsbG8="]},
          {"role": "assistant", "content": "", "tool_calls": [
            {"id": "call_1", "function": {"name": "get_weather", "arguments": {"city": "Oslo"}}}
          ]},
          {"role": "tool", "content": "{\"temp\":4}", "tool_name": "get_weather", "tool_call_id": "call_1"},
          {"role": "assistant", "content": "It is 4 degrees."},
          {"role": "system", "content": "Be brief."},
          {"role": "user", "content": "And tomorrow?"}
        ],
        "stream": $stream,
        "tools": [
          {"type": "function", "function": {"name": "get_weather", "description": "Looks up the weather.", "parameters": $WEATHER_SCHEMA_JSON}}
        ],
        "think": "low",
        "format": "json",
        "options": {"temperature": 0.2, "num_predict": 256}
    }"""

    @Test
    fun `a full turn is sent in ollama's own shape`() {
        chat(done, fullConversation("llama3.2"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/chat", request.url.encodedPath)
        assertNull(request.headers["Authorization"], "a local server gets no credential")
        assertJsonEquals(fullTurn(stream = false), json(request.body!!.utf8()))
    }

    @Test
    fun `a streamed turn asks for newline-delimited json`() {
        stream(done, request = fullConversation("llama3.2"))
        val request = server.takeRequest()
        assertEquals("application/x-ndjson", request.headers["Accept"])
        assertJsonEquals(fullTurn(stream = true), json(request.body!!.utf8()))
    }

    @Test
    fun `a stored key is sent as a bearer token`() {
        enqueue(done)
        runBlocking { client(apiKey = TEST_KEY).chat(simpleRequest("llama3.2")) }
        assertEquals("Bearer $TEST_KEY", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a minimal request has no options and forbidding tools withholds them`() {
        chat(done, simpleRequest("llama3.2").copy(tools = listOf(WEATHER_TOOL), toolChoice = ToolChoice.NONE))
        assertJsonEquals("""{"model":"llama3.2","messages":[{"role":"user","content":"Hello"}],"stream":false}""", takeBody())
    }

    @Test
    fun `the thinking of a turn that called tools is sent back with it`() {
        val turn = ChatMessage.assistant("", listOf(ToolCallRequest("call_1", "get_weather", json("""{"city":"Oslo"}""").jsonObject)))
            .copy(reasoning = "I should look it up.")
        chat(done, simpleRequest("llama3.2").let { it.copy(messages = it.messages + turn + ChatMessage.toolResult("call_1", "get_weather", "4")) })
        assertEquals(json("\"I should look it up.\""), takeBody().getValue("messages").jsonArray[1].jsonObject["thinking"])
    }

    @Test
    fun `a plain reply carries text thinking model and token counts`() {
        val response = chat(
            """{"model":"qwen3:8b","created_at":"2026-11-22T10:00:00Z",
                "message":{"role":"assistant","content":"It is 4 degrees.","thinking":"The tool said 4."},
                "done":true,"done_reason":"stop","total_duration":5191566416,"load_duration":2154458,
                "prompt_eval_count":26,"prompt_eval_duration":383809000,"eval_count":298,"eval_duration":4799921000}""",
        )
        assertEquals("It is 4 degrees.", response.message.text)
        assertEquals("The tool said 4.", response.message.reasoning)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(TokenUsage(inputTokens = 26, outputTokens = 298), response.usage)
        assertEquals("qwen3:8b", response.modelName)
    }

    @Test
    fun `tool calls arrive whole and a missing id is synthesised`() {
        val response = chat(
            """{"model":"llama3.2","message":{"role":"assistant","content":"","tool_calls":[
                 {"function":{"name":"get_weather","arguments":{"city":"Oslo"}}},
                 {"function":{"name":"get_weather","arguments":{"city":"Bergen"}}},
                 {"id":"call_given","function":{"index":2,"name":"get_time","arguments":{}}}]},
                "done":true,"done_reason":"stop","prompt_eval_count":30,"eval_count":12}""",
        )
        val calls = response.message.toolCalls
        assertEquals(FinishReason.TOOL_CALLS, response.finishReason, "ollama says stop for a turn that calls tools")
        assertEquals(listOf("get_weather", "get_weather", "get_time"), calls.map { it.name })
        assertEquals(json("""{"city":"Bergen"}"""), calls[1].arguments)
        assertEquals("call_given", calls[2].id)
        assertTrue(calls.take(2).all { it.id.startsWith("call_") && it.id.length > 10 })
        assertEquals(3, calls.map { it.id }.distinct().size, "every call can be paired with its result")
    }

    @Test
    fun `finish reasons and absent counts`() {
        val cut = chat("""{"model":"m","message":{"role":"assistant","content":"cut of"},"done":true,"done_reason":"length"}""")
        assertEquals(FinishReason.LENGTH, cut.finishReason)
        assertNull(cut.usage)
        val old = chat("""{"model":"m","message":{"role":"assistant","content":"hi"},"done":true}""")
        assertEquals(FinishReason.STOP, old.finishReason, "versions before done_reason")
        val odd = chat("""{"model":"m","message":{"role":"assistant","content":""},"done":true,"done_reason":"load"}""")
        assertEquals(FinishReason.UNKNOWN, odd.finishReason)
    }

    @Test
    fun `a stream delivers thinking text and tool calls as they arrive`() {
        val chunks = stream(
            """{"model":"qwen3:8b","message":{"role":"assistant","content":"","thinking":"Let me "},"done":false}""",
            """{"model":"qwen3:8b","message":{"role":"assistant","content":"","thinking":"check."},"done":false}""",
            """{"model":"qwen3:8b","message":{"role":"assistant","content":"One "},"done":false}""",
            """{"model":"qwen3:8b","message":{"role":"assistant","content":"moment."},"done":false}""",
            """{"model":"qwen3:8b","message":{"role":"assistant","content":"","tool_calls":[{"id":"call_x","function":{"name":"get_weather","arguments":{"city":"Oslo"}}}]},"done":false}""",
            """{"model":"qwen3:8b","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":40,"eval_count":21}""",
        )
        val call = ToolCallRequest("call_x", "get_weather", json("""{"city":"Oslo"}""").jsonObject)
        assertEquals(
            listOf(
                ChatChunk.Reasoning("Let me "), ChatChunk.Reasoning("check."),
                ChatChunk.Text("One "), ChatChunk.Text("moment."),
                ChatChunk.ToolCall(call),
            ),
            chunks.dropLast(1),
        )
        val response = chunks.completed().response
        assertEquals("One moment.", response.message.text)
        assertEquals("Let me check.", response.message.reasoning)
        assertEquals(listOf(call), response.message.toolCalls)
        assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
        assertEquals(TokenUsage(40, 21), response.usage)
    }

    @Test
    fun `a stream that stops before done or reports an error fails`() {
        val cutOff = failsWith<LlmException.Network> {
            stream("""{"model":"m","message":{"role":"assistant","content":"One "},"done":false}""")
        }
        assertTrue(cutOff.retryable)

        val crashed = failsWith<LlmException.Server> {
            stream(
                """{"model":"m","message":{"role":"assistant","content":"One "},"done":false}""",
                """{"error":"model runner has unexpectedly stopped"}""",
            )
        }
        assertTrue("unexpectedly stopped" in crashed.message!!)

        failsWith<LlmException.MalformedResponse> { stream("<html>proxy error</html>") }
    }

    @Test
    fun `ollama's error bodies are mapped`() {
        enqueue("""{"error":"model 'ghost' not found"}""", 404)
        failsWith<LlmException.ModelNotFound> { runBlocking { client().chat(simpleRequest("ghost")) } }

        enqueue("""{"error":"registry.ollama.ai/library/gemma3:4b does not support tools"}""", 400)
        val noTools = failsWith<LlmException.InvalidRequest> {
            runBlocking { client().chat(simpleRequest("gemma3:4b").copy(tools = listOf(WEATHER_TOOL))) }
        }
        assertTrue("does not support tools" in noTools.message!!)
        assertEquals(2, server.requestCount, "a refusal of tools is not something to retry without")

        enqueue("""{"model":"m","done":true}""")
        failsWith<LlmException.MalformedResponse> { runBlocking { client().chat(simpleRequest("m")) } }
    }

    @Test
    fun `a model without a thinking mode is asked again without think and remembered`() {
        val thinking = simpleRequest("llama3.2").copy(reasoningEffort = ReasoningEffort.HIGH)
        enqueue("""{"error":"registry.ollama.ai/library/llama3.2:latest does not support thinking"}""", 400)
        assertEquals("ok", chat(done, thinking).message.text)
        assertEquals(json("\"high\""), takeBody()["think"])
        assertNull(takeBody()["think"])

        chat(done, thinking)
        assertNull(takeBody()["think"], "remembered for the model")

        chat(done, thinking.copy(model = "qwen3:8b"))
        assertEquals(json("\"high\""), takeBody()["think"], "another model is still asked")
    }
}
