package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.registry.ProviderKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ResponseMappingTest : MockApiTest() {

    private fun reply(body: String, model: String = "claude-opus-5-5"): ChatResponse {
        enqueueJson(body)
        return runBlocking { adapter().client.chat(ChatRequest(model, listOf(ChatMessage.user("Hi")))) }
    }

    @Test
    fun `text, the thinking summary and tool calls are mapped and the raw blocks are kept`() {
        val content = """
            [{"type":"thinking","thinking":"The user wants weather.","signature":"sig-AbC+/=="},
             {"type":"text","text":"Let me look that up."},
             {"type":"tool_use","id":"toolu_01","name":"get_weather","input":{"city":"Oslo","days":2}},
             {"type":"tool_use","id":"toolu_02","name":"get_time","input":{}}]
        """.trimIndent()

        val response = reply(messageJson(content, stopReason = "tool_use"))

        assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
        assertEquals("Let me look that up.", response.message.text)
        assertEquals("The user wants weather.", response.message.reasoning)
        assertEquals(listOf("toolu_01", "toolu_02"), response.message.toolCalls.map { it.id })
        assertEquals("get_weather", response.message.toolCalls[0].name)
        assertEquals(buildJsonObject { put("city", "Oslo"); put("days", 2) }, response.message.toolCalls[0].arguments)
        assertTrue(response.message.toolCalls[1].arguments.isEmpty())
        assertEquals("claude-opus-5-5", response.modelName)

        val stored = assertNotNull(response.message.providerData)
        assertEquals(ProviderKind.ANTHROPIC, stored.kind)
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement(content),
            stored.payload.jsonObject.getValue("content").jsonArray,
            "every block is stored as received",
        )
    }

    @Test
    fun `usage counts the whole prompt and reports the cached part`() {
        val usage = """{"input_tokens":20,"cache_read_input_tokens":3000,"cache_creation_input_tokens":500,"output_tokens":77}"""

        val response = reply(messageJson(TEXT_REPLY, usage = usage))

        assertEquals(TokenUsage(inputTokens = 3520, outputTokens = 77, cachedInputTokens = 3000), response.usage)
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    @Test
    fun `stop reasons map to finish reasons`() {
        assertEquals(FinishReason.STOP, reply(messageJson(TEXT_REPLY, stopReason = "end_turn")).finishReason)
        assertEquals(FinishReason.STOP, reply(messageJson(TEXT_REPLY, stopReason = "stop_sequence")).finishReason)
        assertEquals(FinishReason.LENGTH, reply(messageJson(TEXT_REPLY, stopReason = "max_tokens")).finishReason)
        assertEquals(FinishReason.LENGTH, reply(messageJson(TEXT_REPLY, stopReason = "model_context_window_exceeded")).finishReason)
        assertEquals(FinishReason.UNKNOWN, reply(messageJson(TEXT_REPLY, stopReason = "pause_turn")).finishReason)
        assertEquals(FinishReason.UNKNOWN, reply(messageJson(TEXT_REPLY, stopReason = "a_reason_from_the_future")).finishReason)
    }

    @Test
    fun `a refusal carries the explanation and none of the partial output`() {
        val body = messageJson(
            content = """[{"type":"thinking","thinking":"hmm","signature":"s"},{"type":"text","text":"Here is how you would"}]""",
            stopReason = "refusal",
            stopDetails = """{"type":"refusal","category":"cyber","explanation":"This request was declined under the usage policy."}""",
        )

        val response = reply(body)

        assertEquals(FinishReason.REFUSED, response.finishReason)
        assertEquals("This request was declined under the usage policy.", response.message.text)
        assertTrue(response.message.toolCalls.isEmpty())
        assertNull(response.message.reasoning)
        assertNull(response.message.providerData, "a refused turn has nothing that may be replayed")
        assertEquals(TokenUsage(12, 34), response.usage)
    }

    @Test
    fun `a refusal without details or content is still a clean refused response`() {
        val response = reply(messageJson("[]", stopReason = "refusal"))

        assertEquals(FinishReason.REFUSED, response.finishReason)
        assertEquals("", response.message.text)
    }

    @Test
    fun `a reply cut off at the token limit offers no tool calls but keeps its blocks`() {
        val content = """[{"type":"text","text":"Writing the file"},{"type":"tool_use","id":"toolu_01","name":"write_file","input":{}}]"""

        val response = reply(messageJson(content, stopReason = "max_tokens"))

        assertEquals(FinishReason.LENGTH, response.finishReason)
        assertEquals("Writing the file", response.message.text)
        assertTrue(response.message.toolCalls.isEmpty(), "a call from a cut-off reply may be incomplete")
        assertEquals(2, response.message.providerData!!.payload.jsonObject.getValue("content").jsonArray.size)
    }

    @Test
    fun `thinking blocks with empty text give no reasoning and several text blocks are joined`() {
        val content = """
            [{"type":"thinking","thinking":"","signature":"sig1"},{"type":"text","text":"One. "},
             {"type":"redacted_thinking","data":"opaque"},{"type":"text","text":"Two."}]
        """.trimIndent()

        val response = reply(messageJson(content))

        assertNull(response.message.reasoning)
        assertEquals("One. Two.", response.message.text)
    }

    @Test
    fun `the model that answered is reported, also when a fallback model took over`() {
        assertEquals("claude-opus-5-5-20260901", reply(messageJson(TEXT_REPLY, model = "claude-opus-5-5-20260901")).modelName)

        val content = """
            [{"type":"tool_use","id":"toolu_declined","name":"get_time","input":{}},
             {"type":"fallback","from":{"model":"claude-fable-5-2"},"to":{"model":"claude-opus-4-8"}},
             {"type":"text","text":"Checking."},
             {"type":"tool_use","id":"toolu_kept","name":"get_time","input":{}}]
        """.trimIndent()
        val fellBack = reply(messageJson(content, stopReason = "tool_use", model = "claude-opus-4-8"), model = "claude-fable-5-2")

        assertEquals("claude-opus-4-8", fellBack.modelName)
        assertEquals(listOf("toolu_kept"), fellBack.message.toolCalls.map { it.id }, "calls of the model that declined are void")
    }
}
