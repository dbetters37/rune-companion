package com.aicontrolcenter.providers.openai

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.collectBlocking
import com.aicontrolcenter.providers.completed
import com.aicontrolcenter.providers.failsWith
import com.aicontrolcenter.providers.json
import com.aicontrolcenter.providers.simpleRequest
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a streamed Chat Completions reply becomes [ChatChunk]s. */
class OpenAiStreamTest : MockServerTest() {
    private val factory = OpenAiCompatibleAdapterFactory()

    private fun stream(): List<ChatChunk> =
        factory.create(connection(ProviderKind.OPENAI_COMPATIBLE, baseUrl("/v1"))).client.chatStream(simpleRequest("asked-model")).collectBlocking()

    private fun delta(fields: String, finish: String? = null) =
        """{"id":"c1","object":"chat.completion.chunk","model":"gpt-test","choices":[{"index":0,"delta":{$fields},"finish_reason":${finish?.let { "\"$it\"" }}}]}"""

    @Test
    fun `text arrives as deltas and the stream ends with the whole reply`() {
        enqueueSse(
            delta(""""role":"assistant","content":"""""),
            delta(""""content":"It is """"),
            delta(""""content":"4 degrees.""""),
            delta("", finish = "stop"),
            """{"id":"c1","model":"gpt-test","choices":[],"usage":{"prompt_tokens":20,"completion_tokens":6,"prompt_tokens_details":{"cached_tokens":16}}}""",
            "[DONE]",
        )
        val chunks = stream()
        assertEquals(listOf(ChatChunk.Text("It is "), ChatChunk.Text("4 degrees.")), chunks.dropLast(1))
        val response = chunks.completed().response
        assertIs<ChatChunk.Completed>(chunks.last())
        assertEquals("It is 4 degrees.", response.message.text)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(TokenUsage(20, 6, 16), response.usage)
        assertEquals("gpt-test", response.modelName)
    }

    @Test
    fun `reasoning deltas are reported apart from the text`() {
        enqueueSse(
            delta(""""reasoning_content":"Six times """"),
            delta(""""reasoning_content":"seven.""""),
            delta(""""content":"42""""),
            delta("", finish = "stop"),
            "[DONE]",
        )
        val chunks = stream()
        assertEquals(listOf(ChatChunk.Reasoning("Six times "), ChatChunk.Reasoning("seven."), ChatChunk.Text("42")), chunks.dropLast(1))
        assertEquals("Six times seven.", chunks.completed().response.message.reasoning)
        assertEquals("42", chunks.completed().response.message.text)
    }

    @Test
    fun `tool call arguments are assembled by index across interleaved fragments`() {
        enqueueSse(
            delta(""""role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"get_weather","arguments":""}}]"""),
            delta(""""tool_calls":[{"index":0,"function":{"arguments":"{\"ci"}}]"""),
            delta(""""tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"get_time","arguments":""}}]"""),
            delta(""""tool_calls":[{"index":0,"function":{"arguments":"ty\":\"Os"}},{"index":1,"function":{"arguments":"{\"zone\":"}}]"""),
            delta(""""tool_calls":[{"index":1,"function":{"arguments":"\"CET\"}"}},{"index":0,"function":{"arguments":"lo\"}"}}]"""),
            delta("", finish = "tool_calls"),
            """{"choices":[],"usage":{"prompt_tokens":30,"completion_tokens":18}}""",
            "[DONE]",
        )
        val chunks = stream()
        val weather = ToolCallRequest("call_a", "get_weather", json("""{"city":"Oslo"}""").jsonObject)
        val time = ToolCallRequest("call_b", "get_time", json("""{"zone":"CET"}""").jsonObject)
        assertEquals(listOf(ChatChunk.ToolCall(weather), ChatChunk.ToolCall(time)), chunks.dropLast(1), "calls are reported once complete, in order")
        val response = chunks.completed().response
        assertEquals(listOf(weather, time), response.message.toolCalls)
        assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
        assertEquals(TokenUsage(30, 18), response.usage)
    }

    @Test
    fun `parallel calls that all claim index zero are told apart by their ids`() {
        enqueueSse(
            delta(""""tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"one","arguments":"{\"n\":1}"}}]"""),
            delta(""""tool_calls":[{"index":0,"id":"call_b","type":"function","function":{"name":"two","arguments":"{\"n\":2}"}}]"""),
            delta("", finish = "tool_calls"),
            "[DONE]",
        )
        val calls = stream().completed().response.message.toolCalls
        assertEquals(listOf("call_a" to "one", "call_b" to "two"), calls.map { it.id to it.name })
        assertEquals(listOf("""{"n":1}""", """{"n":2}"""), calls.map { it.arguments.toString() })
    }

    @Test
    fun `usage on the last content chunk and a missing terminator are both accepted`() {
        enqueueSse(
            delta(""""content":"hi""""),
            """{"model":"m","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"x_groq":{"usage":{"prompt_tokens":3,"completion_tokens":1}}}""",
        )
        val response = stream().completed().response
        assertEquals("hi", response.message.text)
        assertEquals(TokenUsage(3, 1), response.usage)
    }

    @Test
    fun `a stream without usage reports none`() {
        enqueueSse(delta(""""content":"hi"""", finish = "stop"), "[DONE]")
        assertNull(stream().completed().response.usage)
    }

    @Test
    fun `keep-alive comments and event names are ignored`() {
        val body = ": OPENROUTER PROCESSING\n\n" +
            "event: message\ndata: ${delta(""""content":"hi"""")}\n\n" +
            ": OPENROUTER PROCESSING\n\n" +
            "data: ${delta("", finish = "stop")}\n\n" +
            "data: [DONE]\n\n"
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body).build())
        assertEquals("hi", stream().completed().response.message.text)
    }

    @Test
    fun `a stream that ends before the reply is finished is a network failure`() {
        enqueueSse(delta(""""content":"It is """"))
        val error = failsWith<LlmException.Network> { stream() }
        assertTrue(error.retryable)
    }

    @Test
    fun `an error reported inside the stream is mapped like any other`() {
        enqueueSse(delta(""""content":"It is """"), """{"error":{"code":429,"message":"Rate limit exceeded: free-models-per-min."}}""")
        failsWith<LlmException.RateLimited> { stream() }

        enqueueSse("""{"error":{"message":"The server had an error while processing your request.","type":"server_error"}}""")
        assertEquals(500, failsWith<LlmException.Server> { stream() }.status)
    }

    @Test
    fun `a streamed call whose arguments do not parse is reported on the call`() {
        enqueueSse(
            delta(""""tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]"""),
            delta(""""tool_calls":[{"index":0,"function":{"arguments":"{\"city\": Oslo}"}}]"""),
            delta("", finish = "tool_calls"),
            "[DONE]",
        )
        val chunks = stream()

        val call = assertIs<ChatChunk.ToolCall>(chunks.single { it is ChatChunk.ToolCall }).call
        assertEquals("get_weather", call.name)
        assertTrue(call.arguments.isEmpty())
        assertTrue("not valid JSON" in assertNotNull(call.argumentsError))
        assertEquals(listOf(call), chunks.completed().response.message.toolCalls)
        assertEquals(FinishReason.TOOL_CALLS, chunks.completed().response.finishReason)
    }

    @Test
    fun `an event that is not json is a malformed response`() {
        enqueueSse(delta(""""content":"It is """"), "<html>gateway error</html>")
        failsWith<LlmException.MalformedResponse> { stream() }
    }
}
