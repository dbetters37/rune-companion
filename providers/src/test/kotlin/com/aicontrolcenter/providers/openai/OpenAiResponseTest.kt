package com.aicontrolcenter.providers.openai

import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.failsWith
import com.aicontrolcenter.providers.json
import com.aicontrolcenter.providers.simpleRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a plain (non-streamed) Chat Completions reply becomes a [ChatResponse]. */
class OpenAiResponseTest : MockServerTest() {
    private val factory = OpenAiCompatibleAdapterFactory()

    private fun chat(reply: String): ChatResponse {
        enqueue(reply)
        return runBlocking { factory.create(connection(ProviderKind.OPENAI_COMPATIBLE, baseUrl("/v1"))).client.chat(simpleRequest("asked-model")) }
    }

    private fun message(fields: String, finish: String? = "stop") =
        """{"choices":[{"index":0,"message":{"role":"assistant",$fields},"finish_reason":${finish?.let { "\"$it\"" }}}]}"""

    @Test
    fun `a text reply carries its text model and usage`() {
        val response = chat(
            """{"id":"chatcmpl-1","object":"chat.completion","model":"gpt-test-2026-01-01",
                "choices":[{"index":0,"message":{"role":"assistant","content":"It is 4 degrees.","refusal":null},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":120,"completion_tokens":9,"total_tokens":129,
                         "prompt_tokens_details":{"cached_tokens":100},"completion_tokens_details":{"reasoning_tokens":0}}}""",
        )
        assertEquals(ChatRole.ASSISTANT, response.message.role)
        assertEquals("It is 4 degrees.", response.message.text)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(TokenUsage(inputTokens = 120, outputTokens = 9, cachedInputTokens = 100), response.usage)
        assertEquals("gpt-test-2026-01-01", response.modelName)
        assertNull(response.message.reasoning)
        assertNull(response.message.providerData, "an ordinary reply needs nothing replayed")
    }

    @Test
    fun `tool calls are parsed with their ids and arguments`() {
        val response = chat(
            message(
                """"content":null,"tool_calls":[
                    {"id":"call_a","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Oslo\",\"days\":2}"}},
                    {"id":"call_b","type":"function","function":{"name":"get_time","arguments":"{}"}}]""",
                finish = "tool_calls",
            ),
        )
        assertEquals(
            listOf(
                ToolCallRequest("call_a", "get_weather", json("""{"city":"Oslo","days":2}""").jsonObject),
                ToolCallRequest("call_b", "get_time", json("{}").jsonObject),
            ),
            response.message.toolCalls,
        )
        assertEquals("", response.message.text)
        assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
        assertNull(response.message.providerData, "standard calls are rebuilt from the neutral fields")
    }

    @Test
    fun `arguments are accepted as an object an empty string or a doubly encoded string`() {
        val response = chat(
            message(
                """"content":"","tool_calls":[
                    {"id":"a","type":"function","function":{"name":"one","arguments":{"city":"Oslo"}}},
                    {"id":"b","type":"function","function":{"name":"two","arguments":""}},
                    {"id":"c","type":"function","function":{"name":"three","arguments":"\"{\\\"city\\\":\\\"Oslo\\\"}\""}}]""",
                finish = "tool_calls",
            ),
        )
        val oslo = json("""{"city":"Oslo"}""").jsonObject
        assertEquals(listOf(oslo, json("{}").jsonObject, oslo), response.message.toolCalls.map { it.arguments })
    }

    @Test
    fun `a call without an id is given one`() {
        val calls = chat(
            message(""""content":"","tool_calls":[{"function":{"name":"one","arguments":"{}"}},{"function":{"name":"two","arguments":"{}"}}]"""),
        ).message.toolCalls
        assertEquals(listOf("one", "two"), calls.map { it.name })
        assertTrue(calls.all { it.id.startsWith("call_") && it.id.length > 10 })
        assertEquals(2, calls.map { it.id }.distinct().size)
    }

    @Test
    fun `finish reasons map onto the neutral ones`() {
        assertEquals(FinishReason.LENGTH, chat(message(""""content":"cut of"""", finish = "length")).finishReason)
        assertEquals(FinishReason.REFUSED, chat(message(""""content":""""", finish = "content_filter")).finishReason)
        assertEquals(FinishReason.UNKNOWN, chat(message(""""content":"hm"""", finish = "something_new")).finishReason)
        assertEquals(FinishReason.UNKNOWN, chat(message(""""content":"hm"""", finish = null)).finishReason)
        // Servers that say "stop" for a turn that calls tools, and "tool_calls" for one that does not.
        val calls = """"tool_calls":[{"id":"a","type":"function","function":{"name":"one","arguments":"{}"}}]"""
        assertEquals(FinishReason.TOOL_CALLS, chat(message(""""content":"",$calls""", finish = "stop")).finishReason)
        assertEquals(FinishReason.STOP, chat(message(""""content":"done"""", finish = "tool_calls")).finishReason)
    }

    @Test
    fun `a refusal becomes the text of a refused reply`() {
        val response = chat(message(""""content":null,"refusal":"I can't help with that.""""))
        assertEquals(FinishReason.REFUSED, response.finishReason)
        assertEquals("I can't help with that.", response.message.text)
    }

    @Test
    fun `reasoning text is read from either field name`() {
        val deepSeek = chat(message(""""content":"42","reasoning_content":"Six times seven.""""))
        assertEquals("Six times seven.", deepSeek.message.reasoning)
        assertEquals("42", deepSeek.message.text)
        assertNull(deepSeek.message.providerData, "reasoning of a turn without tool calls is not sent back")

        val openRouter = chat(message(""""content":"42","reasoning":"Six times seven.""""))
        assertEquals("Six times seven.", openRouter.message.reasoning)
    }

    @Test
    fun `content given as typed parts is joined`() {
        val response = chat(
            message(
                """"content":[{"type":"thinking","thinking":[{"type":"text","text":"Hmm. "}]},{"type":"text","text":"Forty"},{"type":"text","text":"-two"}]""",
            ),
        )
        assertEquals("Forty-two", response.message.text)
        assertEquals("Hmm. ", response.message.reasoning)
    }

    @Test
    fun `usage is optional and read in its variants`() {
        assertNull(chat(message(""""content":"hi"""")).usage)
        val deepSeek = chat(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":50,"completion_tokens":5,"prompt_cache_hit_tokens":40,"prompt_cache_miss_tokens":10}}""",
        )
        assertEquals(TokenUsage(50, 5, 40), deepSeek.usage)
        assertEquals("asked-model", deepSeek.modelName, "the requested name stands in when the provider reports none")
    }

    @Test
    fun `a reply without choices or with a call that has no name is malformed`() {
        failsWith<LlmException.MalformedResponse> { chat("""{"id":"x","choices":[]}""") }
        failsWith<LlmException.MalformedResponse> { chat("""["not","an","object"]""") }
        failsWith<LlmException.MalformedResponse> {
            chat(message(""""content":"","tool_calls":[{"id":"a","type":"function","function":{"arguments":"{}"}}]"""))
        }
    }

    @Test
    fun `a call with unreadable arguments is reported with the reason instead of failing the reply`() {
        val response = chat(
            message(
                """"content":"","tool_calls":[
                    {"id":"a","type":"function","function":{"name":"get_weather","arguments":"{\"city\": \"Oslo\",}"}},
                    {"id":"b","type":"function","function":{"name":"get_time","arguments":"[\"UTC\"]"}},
                    {"id":"c","type":"function","function":{"name":"get_weather","arguments":"{\"city\": \"Bergen\"}"}}]""",
                finish = "tool_calls",
            ),
        )

        assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
        val (trailingComma, array, good) = response.message.toolCalls
        assertEquals("get_weather", trailingComma.name)
        assertEquals("a", trailingComma.id)
        assertTrue(trailingComma.arguments.isEmpty())
        val reason = assertNotNull(trailingComma.argumentsError)
        assertTrue("not valid JSON" in reason, reason)
        assertTrue("""{"city": "Oslo",}""" in reason, "short arguments are quoted so the model sees its mistake: $reason")
        assertTrue("JSON object" in assertNotNull(array.argumentsError), array.argumentsError)
        assertEquals(ToolCallRequest("c", "get_weather", json("""{"city":"Bergen"}""").jsonObject), good)
    }

    @Test
    fun `arguments that are a number, an array, a boolean or null are reported, not read as none`() {
        val response = chat(
            message(
                """"content":"","tool_calls":[
                    {"id":"n","type":"function","function":{"name":"reset","arguments":5}},
                    {"id":"a","type":"function","function":{"name":"reset","arguments":["all"]}},
                    {"id":"b","type":"function","function":{"name":"reset","arguments":true}},
                    {"id":"z","type":"function","function":{"name":"reset","arguments":null}},
                    {"id":"none","type":"function","function":{"name":"reset"}},
                    {"id":"empty","type":"function","function":{"name":"reset","arguments":""}}]""",
                finish = "tool_calls",
            ),
        )

        val calls = response.message.toolCalls.associateBy { it.id }
        for ((id, kind) in listOf("n" to "a single JSON value", "a" to "a JSON array", "b" to "a single JSON value", "z" to "null")) {
            val call = calls.getValue(id)
            assertTrue(call.arguments.isEmpty(), id)
            val reason = assertNotNull(call.argumentsError, "$id must not look like a valid call without arguments")
            assertTrue("must be one JSON object of named values, but were $kind." in reason, reason)
        }
        assertTrue("Received: [\"all\"]" in calls.getValue("a").argumentsError!!, calls.getValue("a").argumentsError)
        // Arguments that are absent or empty text still mean a call without arguments.
        assertNull(calls.getValue("none").argumentsError)
        assertNull(calls.getValue("empty").argumentsError)
    }

    @Test
    fun `long unreadable arguments are described but not repeated`() {
        val long = """{\"text\": \"""" + "x".repeat(500)
        val response = chat(message(""""content":"","tool_calls":[{"id":"a","type":"function","function":{"name":"write","arguments":"$long"}}]"""))

        val reason = assertNotNull(response.message.toolCalls.single().argumentsError)
        assertFalse("xxxxxxxxxx" in reason, reason)
        assertTrue(reason.length < 400, reason)
    }

    @Test
    fun `a call cut off by the token limit is dropped rather than failing the reply`() {
        val response = chat(
            message(
                """"content":"Let me check.","tool_calls":[{"id":"a","type":"function","function":{"name":"get_weather","arguments":"{\"city\": \"Os"}}]""",
                finish = "length",
            ),
        )
        assertEquals(FinishReason.LENGTH, response.finishReason)
        assertEquals("Let me check.", response.message.text)
        assertTrue(response.message.toolCalls.isEmpty())
    }
}
