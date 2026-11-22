package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.TokenUsage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class StreamingTest : MockApiTest() {

    private val request = ChatRequest("claude-opus-5-5", listOf(ChatMessage.user("Weather in Oslo?")))

    private fun start(model: String = "claude-opus-5-5", usage: String = """{"input_tokens":25,"cache_read_input_tokens":100,"output_tokens":1}""") =
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"$model",
            |"content":[],"stop_reason":null,"stop_sequence":null,"usage":$usage}}""".trimMargin().replace("\n", "")

    private fun blockStart(index: Int, block: String) =
        "content_block_start" to """{"type":"content_block_start","index":$index,"content_block":$block}"""

    private fun delta(index: Int, delta: String) = "content_block_delta" to """{"type":"content_block_delta","index":$index,"delta":$delta}"""

    private fun blockStop(index: Int) = "content_block_stop" to """{"type":"content_block_stop","index":$index}"""

    private fun messageDelta(stopReason: String, outputTokens: Int = 42, stopDetails: String = "null") = "message_delta" to
        """{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null,"stop_details":$stopDetails},"usage":{"output_tokens":$outputTokens}}"""

    private val messageStop = "message_stop" to """{"type":"message_stop"}"""
    private val ping = "ping" to """{"type":"ping"}"""

    private fun inputJson(part: String) = """{"type":"input_json_delta","partial_json":${JsonPrimitive(part)}}"""

    private fun collect(request: ChatRequest = this.request): List<ChatChunk> = runBlocking { adapter().client.chatStream(request).toList() }

    @Test
    fun `thinking, text and an incrementally streamed tool call arrive as chunks and one completed response`() {
        enqueueSse(
            start(),
            blockStart(0, """{"type":"thinking","thinking":"","signature":""}"""),
            delta(0, """{"type":"thinking_delta","thinking":"Need the "}"""),
            delta(0, """{"type":"thinking_delta","thinking":"forecast."}"""),
            delta(0, """{"type":"signature_delta","signature":"sig-part-1+"}"""),
            delta(0, """{"type":"signature_delta","signature":"part-2=="}"""),
            blockStop(0),
            ping,
            blockStart(1, """{"type":"text","text":""}"""),
            delta(1, """{"type":"text_delta","text":"Let me "}"""),
            delta(1, """{"type":"text_delta","text":"check."}"""),
            blockStop(1),
            blockStart(2, """{"type":"tool_use","id":"toolu_01","name":"get_weather","input":{}}"""),
            delta(2, inputJson("")),
            delta(2, inputJson("""{"city": "Os""")),
            delta(2, inputJson("""lo", "days"""")),
            delta(2, inputJson(""": 2}""")),
            blockStop(2),
            blockStart(3, """{"type":"tool_use","id":"toolu_02","name":"get_time","input":{}}"""),
            blockStop(3),
            messageDelta("tool_use"),
            messageStop,
        )

        val chunks = collect()

        assertEquals(listOf("Need the ", "forecast."), chunks.filterIsInstance<ChatChunk.Reasoning>().map { it.delta })
        assertEquals(listOf("Let me ", "check."), chunks.filterIsInstance<ChatChunk.Text>().map { it.delta })
        val calls = chunks.filterIsInstance<ChatChunk.ToolCall>().map { it.call }
        assertEquals(listOf("toolu_01", "toolu_02"), calls.map { it.id })
        assertEquals(buildJsonObject { put("city", "Oslo"); put("days", 2) }, calls[0].arguments)
        assertTrue(calls[1].arguments.isEmpty())
        assertTrue(chunks.indexOfFirst { it is ChatChunk.ToolCall } > chunks.indexOfLast { it is ChatChunk.Text }, "chunks keep stream order")

        assertEquals(1, chunks.count { it is ChatChunk.Completed })
        val completed = assertIs<ChatChunk.Completed>(chunks.last()).response
        assertEquals(FinishReason.TOOL_CALLS, completed.finishReason)
        assertEquals("Let me check.", completed.message.text)
        assertEquals("Need the forecast.", completed.message.reasoning)
        assertEquals(calls, completed.message.toolCalls)
        assertEquals("claude-opus-5-5", completed.modelName)
        assertEquals(TokenUsage(inputTokens = 125, outputTokens = 42, cachedInputTokens = 100), completed.usage)

        val stored = completed.message.providerData!!.payload.jsonObject.getValue("content").jsonArray
        assertEquals(
            Json.parseToJsonElement(
                """[{"type":"thinking","thinking":"Need the forecast.","signature":"sig-part-1+part-2=="},
                    {"type":"text","text":"Let me check."},
                    {"type":"tool_use","id":"toolu_01","name":"get_weather","input":{"city":"Oslo","days":2}},
                    {"type":"tool_use","id":"toolu_02","name":"get_time","input":{}}]""",
            ).jsonArray.map { it.jsonObject },
            // The SDK adds fields it models but the stream did not send (as null); they are harmless on replay.
            stored.map { block -> kotlinx.serialization.json.JsonObject(block.jsonObject.filterValues { it !is kotlinx.serialization.json.JsonNull }) },
        )
    }

    @Test
    fun `a streamed turn replays like any other`() {
        enqueueSse(
            start(),
            blockStart(0, """{"type":"thinking","thinking":"","signature":""}"""),
            delta(0, """{"type":"signature_delta","signature":"only-a-signature"}"""),
            blockStop(0),
            blockStart(1, """{"type":"text","text":""}"""),
            delta(1, """{"type":"text_delta","text":"Hello."}"""),
            blockStop(1),
            messageDelta("end_turn"),
            messageStop,
        )
        enqueueJson(messageJson(TEXT_REPLY))
        val client = adapter().client
        val first = runBlocking { client.chatStream(request).toList() }.filterIsInstance<ChatChunk.Completed>().single().response
        takeRequest()

        runBlocking { client.chat(request.copy(messages = request.messages + first.message + ChatMessage.user("And tomorrow?"))) }

        val replayed = takeBody().array("messages")[1].jsonObject.array("content").map { it.jsonObject }
        assertEquals(listOf("thinking", "text"), replayed.map { it.string("type") })
        assertEquals("", replayed[0].string("thinking"))
        assertEquals("only-a-signature", replayed[0].string("signature"))
        assertNull(first.message.reasoning, "an omitted summary is not reported as reasoning")
    }

    @Test
    fun `a tool call cut off by the token limit ends the reply with LENGTH instead of failing`() {
        enqueueSse(
            start(),
            blockStart(0, """{"type":"text","text":""}"""),
            delta(0, """{"type":"text_delta","text":"Saving"}"""),
            blockStop(0),
            blockStart(1, """{"type":"tool_use","id":"toolu_01","name":"write_file","input":{}}"""),
            delta(1, inputJson("""{"path": "notes.txt", "content": "Dear di""")),
            blockStop(1),
            messageDelta("max_tokens"),
            messageStop,
        )

        val chunks = collect()

        assertTrue(chunks.none { it is ChatChunk.ToolCall }, "half a call is never announced")
        val completed = assertIs<ChatChunk.Completed>(chunks.last()).response
        assertEquals(FinishReason.LENGTH, completed.finishReason)
        assertEquals("Saving", completed.message.text)
        assertTrue(completed.message.toolCalls.isEmpty())
    }

    @Test
    fun `a refusal in mid-stream completes as refused with the explanation`() {
        enqueueSse(
            start(model = "claude-fable-5-2"),
            blockStart(0, """{"type":"text","text":""}"""),
            delta(0, """{"type":"text_delta","text":"The first step is"}"""),
            blockStop(0),
            messageDelta("refusal", stopDetails = """{"type":"refusal","category":"bio","explanation":"Declined under the usage policy."}"""),
            messageStop,
        )

        val completed = assertIs<ChatChunk.Completed>(collect(request.copy(model = "claude-fable-5-2")).last()).response

        assertEquals(FinishReason.REFUSED, completed.finishReason)
        assertEquals("Declined under the usage policy.", completed.message.text)
        assertNull(completed.message.providerData)
    }

    @Test
    fun `a fallback in mid-stream reports the model that took over`() {
        enqueueSse(
            start(model = "claude-fable-5-2"),
            blockStart(0, """{"type":"text","text":""}"""),
            delta(0, """{"type":"text_delta","text":"Partial "}"""),
            blockStop(0),
            blockStart(1, """{"type":"fallback","from":{"model":"claude-fable-5-2"},"to":{"model":"claude-opus-4-8"}}"""),
            blockStop(1),
            blockStart(2, """{"type":"text","text":""}"""),
            delta(2, """{"type":"text_delta","text":"answer."}"""),
            blockStop(2),
            messageDelta("end_turn"),
            messageStop,
        )

        val completed = assertIs<ChatChunk.Completed>(collect(request.copy(model = "claude-fable-5-2")).last()).response

        assertEquals("claude-opus-4-8", completed.modelName)
        assertEquals("Partial answer.", completed.message.text)
        val stored = completed.message.providerData!!.payload.jsonObject.getValue("content").jsonArray.map { it.jsonObject.string("type") }
        assertEquals(listOf("text", "fallback", "text"), stored)
    }

    @Test
    fun `an error event inside the stream is mapped like the same error as a response`() {
        enqueueSse(
            start(),
            blockStart(0, """{"type":"text","text":""}"""),
            delta(0, """{"type":"text_delta","text":"Hel"}"""),
            "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        )

        val seen = ArrayList<ChatChunk>()
        val error = assertFailsWith<LlmException.Overloaded> {
            runBlocking { adapter().client.chatStream(request).collect { seen += it } }
        }

        assertEquals(listOf<ChatChunk>(ChatChunk.Text("Hel")), seen)
        assertTrue(error.retryable)
    }

    @Test
    fun `a stream that ends before the message is complete is a network failure`() {
        enqueueSse(start(), blockStart(0, """{"type":"text","text":""}"""), delta(0, """{"type":"text_delta","text":"Hel"}"""))

        assertFailsWith<LlmException.Network> { collect() }
    }

    @Test
    fun `an error status on a streaming call is mapped before any chunk`() {
        enqueueError(401, "authentication_error", "invalid x-api-key")

        assertFailsWith<LlmException.Authentication> { collect() }
    }

    @Test
    fun `a chat call too large for a plain request is streamed and returns the assembled reply`() {
        enqueueSse(
            start(),
            blockStart(0, """{"type":"text","text":""}"""),
            delta(0, """{"type":"text_delta","text":"A long "}"""),
            delta(0, """{"type":"text_delta","text":"answer."}"""),
            blockStop(0),
            messageDelta("end_turn", outputTokens = 9),
            messageStop,
        )

        val response = runBlocking { adapter().client.chat(request.copy(maxOutputTokens = 64_000)) }

        assertEquals("A long answer.", response.message.text)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(9, response.usage!!.outputTokens)
        val sent = takeBody()
        assertEquals(JsonPrimitive(true), sent["stream"])
        assertEquals(JsonPrimitive(64_000), sent["max_tokens"])
    }

    @Test
    fun `a streaming call also drops a rejected fallback opt-in and goes on`() {
        enqueueError(400, "invalid_request_error", "fallbacks: Extra inputs are not permitted")
        enqueueSse(
            start(model = "claude-fable-5-2"),
            blockStart(0, """{"type":"text","text":""}"""),
            delta(0, """{"type":"text_delta","text":"Hi."}"""),
            blockStop(0),
            messageDelta("end_turn"),
            messageStop,
        )

        val chunks = collect(request.copy(model = "claude-fable-5-2"))

        assertEquals("Hi.", assertIs<ChatChunk.Completed>(chunks.last()).response.message.text)
        assertEquals(JsonPrimitive("default"), takeBody()["fallbacks"])
        val resent = takeBody()
        assertNull(resent["fallbacks"])
        assertEquals(JsonPrimitive(true), resent["stream"])
    }
}
