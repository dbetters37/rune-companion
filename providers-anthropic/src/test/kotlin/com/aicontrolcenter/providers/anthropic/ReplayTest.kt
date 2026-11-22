package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.ProviderData
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.registry.ProviderKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Preserved thinking: an assistant turn from Claude goes back exactly as it came. */
internal class ReplayTest : MockApiTest() {

    // A signature with every kind of character that an escaping or re-encoding step could damage.
    private val signature = "EqQBCkYIAxgCIkD9+/aZ==\\u00e9\\n\\\"q\\\"/\\\\ end"
    private val firstReply = """
        [{"type":"thinking","thinking":"Plan: look it up, then answer.\nSecond line.","signature":"$signature"},
         {"type":"thinking","thinking":"","signature":"sig-of-an-empty-block"},
         {"type":"redacted_thinking","data":"b3BhcXVl"},
         {"type":"text","text":"I'll check the forecast.","citations":null},
         {"type":"tool_use","id":"toolu_01","name":"get_weather","input":{"city":"Oslo","ratio":1.5,"big":12345678901234567,"nested":{"a":[1,2,{"b":null}]}},
          "caller":{"type":"direct"}},
         {"type":"block_from_the_future","payload":{"keep":"me"}}]
    """.trimIndent()

    private fun secondRequestMessages(history: List<ChatMessage>): List<JsonObject> {
        enqueueJson(messageJson(TEXT_REPLY))
        runBlocking { adapter().client.chat(ChatRequest("claude-opus-5-5", history)) }
        return takeBody().array("messages").map { it.jsonObject }
    }

    @Test
    fun `a stored turn is replayed block for block with signatures intact`() {
        enqueueJson(messageJson(firstReply, stopReason = "tool_use"))
        val client = adapter().client
        val user = ChatMessage.user("Weather in Oslo?")
        val first = runBlocking { client.chat(ChatRequest("claude-opus-5-5", listOf(user))) }
        takeRequest()

        // The framework persists messages as JSON between turns; the replay must survive that too.
        val restored = Json.decodeFromString(ChatMessage.serializer(), Json.encodeToString(ChatMessage.serializer(), first.message))
        val messages = secondRequestMessages(listOf(user, restored, ChatMessage.toolResult("toolu_01", "get_weather", "4 degrees")))

        assertEquals(listOf("user", "assistant", "user"), messages.map { it.string("role") })
        val replayed = messages[1].array("content")
        assertEquals(Json.parseToJsonElement(firstReply), replayed)

        val expectedSignature = Json.parseToJsonElement(firstReply).jsonArray[0].jsonObject.string("signature")
        assertEquals(expectedSignature, replayed[0].jsonObject.string("signature"))
        assertEquals("Plan: look it up, then answer.\nSecond line.", replayed[0].jsonObject.string("thinking"))
        assertEquals("", replayed[1].jsonObject.string("thinking"), "a thinking block with empty text is still sent back")
        assertEquals("toolu_01", messages[2].array("content")[0].jsonObject.string("tool_use_id"))
    }

    @Test
    fun `stored blocks win over the neutral fields of the same message`() {
        val stored = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = "edited text that must not be sent",
            providerData = ProviderData(ProviderKind.ANTHROPIC, """{"content":[{"type":"text","text":"original"}]}""".parseObject()),
        )

        val messages = secondRequestMessages(listOf(ChatMessage.user("Hi"), stored, ChatMessage.user("Go on")))

        assertEquals("""[{"type":"text","text":"original"}]""", messages[1].array("content").toString())
    }

    @Test
    fun `turns from another provider or without provider data are rebuilt from the neutral fields`() {
        val foreign = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = "From another model.",
            toolCalls = listOf(ToolCallRequest("call_9", "get_time", JsonObject(emptyMap()))),
            providerData = ProviderData(ProviderKind.OPENAI_COMPATIBLE, """{"content":[{"type":"text","text":"foreign payload"}]}""".parseObject()),
        )
        val malformed = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = "Plain answer.",
            providerData = ProviderData(ProviderKind.ANTHROPIC, JsonPrimitive("not the expected shape")),
        )

        val messages = secondRequestMessages(
            listOf(
                ChatMessage.user("Time?"), foreign, ChatMessage.toolResult("call_9", "get_time", "12:00"),
                malformed, ChatMessage.user("Thanks"),
            ),
        )

        assertEquals(listOf("user", "assistant", "user", "assistant", "user"), messages.map { it.string("role") })
        val rebuilt = messages[1].array("content").map { it.jsonObject }
        assertEquals(listOf("text", "tool_use"), rebuilt.map { it.string("type") })
        assertEquals("From another model.", rebuilt[0].string("text"))
        assertEquals("call_9", rebuilt[1].string("id"))
        assertEquals("Plain answer.", messages[3].array("content")[0].jsonObject.string("text"))
        assertTrue("foreign payload" !in messages.toString())
    }

    @Test
    fun `the tool calls of a stored turn are the ones that get results`() {
        val stored = ChatMessage(
            role = ChatRole.ASSISTANT,
            providerData = ProviderData(
                ProviderKind.ANTHROPIC,
                """{"content":[{"type":"tool_use","id":"toolu_a","name":"get_time","input":{}},
                               {"type":"tool_use","id":"toolu_b","name":"get_time","input":{}}]}""".parseObject(),
            ),
        )

        val messages = secondRequestMessages(listOf(ChatMessage.user("Hi"), stored, ChatMessage.toolResult("toolu_b", "get_time", "12:00")))

        val results = messages[2].array("content").map { it.jsonObject }
        assertEquals(setOf("toolu_a", "toolu_b"), results.map { it.string("tool_use_id") }.toSet())
        assertEquals(JsonPrimitive(true), results.first { it.string("tool_use_id") == "toolu_a" }["is_error"])
    }

    @Test
    fun `after a fallback the declined model's thinking and tool calls are left out and the boundary stays`() {
        val content = """
            {"content":[{"type":"thinking","thinking":"declined model","signature":"s1"},
                        {"type":"text","text":"Partial "},
                        {"type":"tool_use","id":"toolu_void","name":"get_time","input":{}},
                        {"type":"fallback","from":{"model":"claude-fable-5-2"},"to":{"model":"claude-opus-4-8"}},
                        {"type":"thinking","thinking":"fallback model","signature":"s2"},
                        {"type":"text","text":"answer."}]}
        """.trimIndent()
        val stored = ChatMessage(role = ChatRole.ASSISTANT, providerData = ProviderData(ProviderKind.ANTHROPIC, content.parseObject()))

        val messages = secondRequestMessages(listOf(ChatMessage.user("Hi"), stored, ChatMessage.user("Thanks")))

        val replayed = messages[1].array("content").map { it.jsonObject }
        assertEquals(listOf("text", "fallback", "thinking", "text"), replayed.map { it.string("type") })
        assertEquals("s2", replayed[2].string("signature"))
        assertEquals(listOf("text"), messages[2].array("content").map { it.jsonObject.string("type") }, "the void call needs no result")
    }

    private val boundElsewhere = "messages.1.content.0: Invalid `signature` in `thinking` block. The block is bound to a different " +
        "conversation. Remove the block, or set `thinking.block_binding.prefix_mismatch_behavior` to \"drop_block\"."

    private fun editedHistory(): List<ChatMessage> {
        val withTools = ChatMessage(
            role = ChatRole.ASSISTANT,
            providerData = ProviderData(
                ProviderKind.ANTHROPIC,
                """{"content":[{"type":"thinking","thinking":"plan","signature":"s1"},{"type":"redacted_thinking","data":"x"},
                               {"type":"text","text":"Checking."},{"type":"tool_use","id":"toolu_1","name":"get_time","input":{}}]}""".parseObject(),
            ),
        )
        val thinkingOnly = ChatMessage(
            role = ChatRole.ASSISTANT,
            text = "It is noon.",
            providerData = ProviderData(ProviderKind.ANTHROPIC, """{"content":[{"type":"thinking","thinking":"","signature":"s2"}]}""".parseObject()),
        )
        return listOf(
            ChatMessage.user("Time?"), withTools, ChatMessage.toolResult("toolu_1", "get_time", "12:00"),
            thinkingOnly, ChatMessage.user("Thanks"),
        )
    }

    @Test
    fun `when replayed thinking no longer fits the history the turn is sent once more without thinking blocks`() {
        enqueueError(400, "invalid_request_error", boundElsewhere)
        enqueueJson(messageJson(TEXT_REPLY))

        val response = runBlocking { adapter().client.chat(ChatRequest("claude-fable-5-2", editedHistory())) }

        assertEquals("Hello there.", response.message.text)
        val first = takeBody().array("messages").map { it.jsonObject }
        assertEquals(listOf("thinking", "redacted_thinking", "text", "tool_use"), first[1].array("content").map { it.jsonObject.string("type") })

        val second = takeBody().array("messages").map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user", "assistant", "user"), second.map { it.string("role") })
        assertEquals(listOf("text", "tool_use"), second[1].array("content").map { it.jsonObject.string("type") })
        assertEquals("toolu_1", second[2].array("content")[0].jsonObject.string("tool_use_id"))
        assertEquals(
            """[{"text":"It is noon.","type":"text"}]""",
            second[3].array("content").toString(),
            "a turn left with nothing but thinking is rebuilt from its text",
        )
        assertTrue("thinking" !in second.toString(), "no thinking block of any kind remains")
    }

    @Test
    fun `the recovery is tried once and a repeated rejection is reported`() {
        repeat(2) { enqueueError(400, "invalid_request_error", boundElsewhere) }

        val error = kotlin.test.assertFailsWith<com.aicontrolcenter.core.llm.LlmException.InvalidRequest> {
            runBlocking { adapter().client.chat(ChatRequest("claude-fable-5-2", editedHistory())) }
        }

        assertTrue(error.message!!.contains("bound to a different conversation"))
        assertEquals(2, server.requestCount)
    }
}
