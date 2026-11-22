package com.aicontrolcenter.providers.openai

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderData
import com.aicontrolcenter.core.llm.ToolChoice
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.TEST_KEY
import com.aicontrolcenter.providers.WEATHER_SCHEMA_JSON
import com.aicontrolcenter.providers.WEATHER_TOOL
import com.aicontrolcenter.providers.assertJsonEquals
import com.aicontrolcenter.providers.collectBlocking
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

/** What the adapter puts on the wire for `POST {base}/chat/completions`. */
class OpenAiRequestTest : MockServerTest() {
    private val reply = """{"id":"chatcmpl-1","model":"gpt-test","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
    private val factory = OpenAiCompatibleAdapterFactory()

    private fun client() = factory.create(connection(ProviderKind.OPENAI_COMPATIBLE, baseUrl("/v1"))).client

    private val fullTurn = """
        "model": "gpt-test",
        "messages": [
          {"role": "system", "content": "You are terse.\n\nAnswer in English."},
          {"role": "user", "content": [
            {"type": "text", "text": "What is the weather in Oslo?"},
            {"type": "image_url", "image_url": {"url": "data:image/png;base64,aGVsbG8="}}
          ]},
          {"role": "assistant", "content": null, "tool_calls": [
            {"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\"city\":\"Oslo\"}"}}
          ]},
          {"role": "tool", "tool_call_id": "call_1", "content": "{\"temp\":4}"},
          {"role": "assistant", "content": "It is 4 degrees."},
          {"role": "system", "content": "Be brief."},
          {"role": "user", "content": "And tomorrow?"}
        ],
        "tools": [
          {"type": "function", "function": {"name": "get_weather", "description": "Looks up the weather.", "parameters": $WEATHER_SCHEMA_JSON}}
        ],
        "temperature": 0.2,
        "max_tokens": 256,
        "reasoning_effort": "low",
        "response_format": {"type": "json_object"}
    """

    @Test
    fun `a full turn is sent as chat completions json`() {
        enqueue(reply)
        runBlocking { client().chat(fullConversation("gpt-test")) }
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        assertEquals("Bearer $TEST_KEY", request.headers["Authorization"])
        assertJsonEquals("{$fullTurn}", json(request.body!!.utf8()))
    }

    @Test
    fun `a streamed turn asks for server-sent events with usage`() {
        enqueueSse("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]")
        client().chatStream(fullConversation("gpt-test")).collectBlocking()
        val request = server.takeRequest()
        assertEquals("text/event-stream", request.headers["Accept"])
        assertJsonEquals("""{$fullTurn, "stream": true, "stream_options": {"include_usage": true}}""", json(request.body!!.utf8()))
    }

    @Test
    fun `a minimal request carries nothing optional`() {
        enqueue(reply)
        runBlocking { client().chat(simpleRequest("gpt-test", "Hello")) }
        assertJsonEquals("""{"model":"gpt-test","messages":[{"role":"user","content":"Hello"}]}""", takeBody())
    }

    @Test
    fun `forbidding tool use keeps the tools and sets the choice`() {
        enqueue(reply)
        runBlocking { client().chat(simpleRequest("gpt-test").copy(tools = listOf(WEATHER_TOOL), toolChoice = ToolChoice.NONE)) }
        val body = takeBody()
        assertEquals(json("\"none\""), body["tool_choice"])
        assertEquals(1, body.getValue("tools").jsonArray.size)
    }

    @Test
    fun `without tools no tool choice is sent`() {
        enqueue(reply)
        runBlocking { client().chat(simpleRequest("gpt-test").copy(toolChoice = ToolChoice.NONE)) }
        val body = takeBody()
        assertNull(body["tool_choice"])
        assertNull(body["tools"])
    }

    @Test
    fun `a blank model name fails before anything is sent`() {
        failsWith<LlmException.InvalidRequest> { runBlocking { client().chat(ChatRequest(messages = listOf(ChatMessage.user("Hi")))) } }
        failsWith<LlmException.InvalidRequest> { client().chatStream(ChatRequest(messages = listOf(ChatMessage.user("Hi")))).collectBlocking() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `provider fields of a tool-calling turn are returned to the host that sent them`() {
        // Google's OpenAI-compatible endpoint attaches a thought signature to the call; DeepSeek wants the reasoning back.
        enqueue(
            """{"model":"gemini-3-flash","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
               "reasoning_content":"I should look it up.",
               "tool_calls":[{"id":"call_9","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Oslo\"}"},
                              "extra_content":{"google":{"thought_signature":"SIG=="}}}]}}]}""",
        )
        val first = runBlocking { client().chat(simpleRequest("gemini-3-flash").copy(tools = listOf(WEATHER_TOOL))) }
        server.takeRequest()

        enqueue(reply)
        val followUp = simpleRequest("gemini-3-flash").let {
            it.copy(messages = it.messages + first.message + ChatMessage.toolResult("call_9", "get_weather", "4 degrees"))
        }
        runBlocking { client().chat(followUp) }
        assertJsonEquals(
            """{"role":"assistant","content":null,"reasoning_content":"I should look it up.",
                "tool_calls":[{"id":"call_9","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Oslo\"}"},
                               "extra_content":{"google":{"thought_signature":"SIG=="}}}]}""",
            takeBody().getValue("messages").jsonArray[1],
        )

        // The same turn sent to a different service is rebuilt from the neutral fields alone.
        val elsewhere = OpenAiRequests.build(followUp, stream = false, omitted = emptySet(), origin = "api.other.example").body
        assertJsonEquals(
            """{"role":"assistant","content":null,
                "tool_calls":[{"id":"call_9","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Oslo\"}"}}]}""",
            elsewhere.getValue("messages").jsonArray[1],
        )
    }

    @Test
    fun `provider data of another provider kind is ignored`() {
        val foreign = ChatMessage.assistant("Hi there.").copy(
            providerData = ProviderData(ProviderKind.GEMINI, json("""{"parts":[{"text":"Hi there.","thoughtSignature":"SIG"}]}""")),
        )
        enqueue(reply)
        runBlocking { client().chat(simpleRequest("gpt-test").let { it.copy(messages = it.messages + foreign + ChatMessage.user("And?")) }) }
        assertJsonEquals("""{"role":"assistant","content":"Hi there."}""", takeBody().getValue("messages").jsonArray[1].jsonObject)
    }
}
