package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ImageContent
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ReasoningEffort
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.llm.ToolChoice
import com.aicontrolcenter.core.llm.ToolDefinition
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class RequestBodyTest : MockApiTest() {

    private val weatherSchema = """
        {"type":"object","properties":{"city":{"type":"string","description":"City name"},"days":{"type":"integer","minimum":1}},
         "required":["city"],"additionalProperties":false}
    """.trimIndent().parseObject()

    private val tools = listOf(
        ToolDefinition("get_weather", "Looks up the forecast for a city.", weatherSchema),
        ToolDefinition("get_time", "Reads the clock.", """{"type":"object","properties":{}}""".parseObject()),
    )

    private fun call(id: String, name: String, vararg args: Pair<String, String>) =
        ToolCallRequest(id, name, buildJsonObject { args.forEach { (key, value) -> put(key, value) } })

    /** Sends the request against a canned reply and returns the body the API received. */
    private fun bodyOf(request: ChatRequest, options: AnthropicOptions = AnthropicOptions(maxRetries = 0)): JsonObject {
        enqueueJson(messageJson(TEXT_REPLY))
        runBlocking { adapter(options).client.chat(request) }
        return takeBody()
    }

    private fun simple(model: String, vararg more: ChatMessage) =
        ChatRequest(model = model, messages = listOf(ChatMessage.user("Hi")) + more)

    @Test
    fun `a full turn is sent as system, cached tools and a grouped tool result message`() {
        val request = ChatRequest(
            model = "claude-opus-5-5",
            messages = listOf(
                ChatMessage.system("You are the weather desk."),
                ChatMessage.system("Answer in one sentence."),
                ChatMessage.user("What is the weather in Oslo and what time is it?"),
                ChatMessage.assistant("Let me check.", listOf(call("toolu_1", "get_weather", "city" to "Oslo"), call("toolu_2", "get_time"))),
                ChatMessage.toolResult("toolu_1", "get_weather", "4 degrees, rain"),
                ChatMessage.toolResult("toolu_2", "get_time", "clock unavailable", isError = true),
            ),
            tools = tools,
            temperature = 0.7,
            jsonOutput = true,
        )

        val body = bodyOf(request)

        assertEquals("claude-opus-5-5", body.string("model"))
        assertEquals(16_000, body.getValue("max_tokens").jsonPrimitive.long)

        val system = body.array("system").map { it.jsonObject }
        assertEquals(listOf("You are the weather desk.", "Answer in one sentence."), system.map { it.string("text") })
        assertNull(system[0]["cache_control"], "only the end of the stable prefix carries a breakpoint")
        assertEquals("ephemeral", system[1].obj("cache_control").string("type"))
        assertEquals("ephemeral", body.obj("cache_control").string("type"), "the growing conversation is cached automatically")

        val sentTools = body.array("tools").map { it.jsonObject }
        assertEquals(listOf("get_weather", "get_time"), sentTools.map { it.string("name") })
        assertEquals("Looks up the forecast for a city.", sentTools[0].string("description"))
        assertEquals(weatherSchema, sentTools[0].obj("input_schema"), "the JSON Schema goes through untouched")
        assertTrue(sentTools.none { "cache_control" in it }, "the system breakpoint already covers the tools")

        val messages = body.array("messages").map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.string("role") })
        assertEquals("What is the weather in Oslo and what time is it?", messages[0].array("content")[0].jsonObject.string("text"))

        val assistant = messages[1].array("content").map { it.jsonObject }
        assertEquals(listOf("text", "tool_use", "tool_use"), assistant.map { it.string("type") })
        assertEquals("toolu_1", assistant[1].string("id"))
        assertEquals(buildJsonObject { put("city", "Oslo") }, assistant[1].obj("input"))
        assertEquals(JsonObject(emptyMap()), assistant[2].obj("input"))

        val results = messages[2].array("content").map { it.jsonObject }
        assertEquals(listOf("tool_result", "tool_result"), results.map { it.string("type") }, "both results share one user message")
        assertEquals(listOf("toolu_1", "toolu_2"), results.map { it.string("tool_use_id") })
        assertEquals("4 degrees, rain", results[0].string("content"))
        assertNull(results[0]["is_error"])
        assertEquals(JsonPrimitive(true), results[1]["is_error"])

        for (rejected in listOf("temperature", "top_p", "top_k", "tool_choice", "stop_sequences", "stream")) {
            assertFalse(rejected in body, "$rejected must not be sent")
        }
        assertFalse("budget_tokens" in body.toString())
    }

    @Test
    fun `the request carries the key and API version in headers and goes to the messages endpoint`() {
        enqueueJson(messageJson(TEXT_REPLY))
        runBlocking { adapter().client.chat(simple("claude-opus-4-8")) }

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/messages", request.url.encodedPath)
        // Which of the two credential headers the SDK uses is its business; the key must be in one of them.
        assertTrue(request.headers["x-api-key"] == TEST_KEY || request.headers["Authorization"] == "Bearer $TEST_KEY")
        assertTrue(!request.headers["anthropic-version"].isNullOrBlank())
        assertEquals("AI Control Center", request.headers["X-Title"], "the provider's extra headers are sent")
        assertFalse(TEST_KEY in request.body!!.utf8())
    }

    @Test
    fun `a base URL entered with the version segment still reaches the endpoint`() {
        enqueueJson(messageJson(TEXT_REPLY))
        runBlocking { adapter(baseUrl = server.url("/v1/").toString()).client.chat(simple("claude-opus-4-8")) }

        assertEquals("/v1/messages", takeRequest().url.encodedPath)
    }

    @Test
    fun `tool choice none is sent and auto is left to the API default`() {
        val auto = bodyOf(ChatRequest("claude-opus-5-5", listOf(ChatMessage.user("Hi")), tools))
        assertFalse("tool_choice" in auto)

        val none = bodyOf(ChatRequest("claude-opus-5-5", listOf(ChatMessage.user("Hi")), tools, toolChoice = ToolChoice.NONE))
        assertEquals(buildJsonObject { put("type", "none") }, none.obj("tool_choice"))
        assertEquals(2, none.array("tools").size, "the tools stay declared so the cached prefix does not change")
    }

    @Test
    fun `without a system prompt the last tool carries the cache breakpoint`() {
        val body = bodyOf(ChatRequest("claude-opus-5-5", listOf(ChatMessage.user("Hi")), tools))

        assertFalse("system" in body)
        val sentTools = body.array("tools").map { it.jsonObject }
        assertNull(sentTools[0]["cache_control"])
        assertEquals("ephemeral", sentTools[1].obj("cache_control").string("type"))
    }

    @Test
    fun `thinking is requested per model and never with a budget`() {
        val summarized = buildJsonObject { put("type", "adaptive"); put("display", "summarized") }
        for (model in listOf("claude-fable-5-2", "claude-opus-5-5", "claude-sonnet-5", "claude-haiku-5-5", "claude-opus-4-8", "claude-opus-4-7")) {
            assertEquals(summarized, bodyOf(simple(model)).obj("thinking"), model)
        }
        for (model in listOf("claude-opus-4-6", "claude-sonnet-4-6")) {
            assertEquals(buildJsonObject { put("type", "adaptive") }, bodyOf(simple(model)).obj("thinking"), model)
        }
        for (model in listOf("claude-haiku-4-5-20251001", "claude-opus-4-5", "some-gateway-alias")) {
            assertFalse("thinking" in bodyOf(simple(model)), model)
        }
    }

    @Test
    fun `reasoning effort becomes output_config effort where the model has it`() {
        fun effortOf(model: String, effort: ReasoningEffort?) =
            bodyOf(simple(model).copy(reasoningEffort = effort))["output_config"]?.jsonObject?.get("effort")

        assertEquals(JsonPrimitive("low"), effortOf("claude-opus-5-5", ReasoningEffort.LOW))
        assertEquals(JsonPrimitive("medium"), effortOf("claude-sonnet-4-6", ReasoningEffort.MEDIUM))
        assertEquals(JsonPrimitive("high"), effortOf("claude-fable-5-2", ReasoningEffort.HIGH))
        assertNull(effortOf("claude-opus-5-5", null))
        assertNull(effortOf("claude-haiku-4-5", ReasoningEffort.HIGH), "Haiku 4.5 has no effort parameter")
        assertNull(effortOf("unknown-model", ReasoningEffort.HIGH))
    }

    @Test
    fun `max tokens follows the request and defaults higher when streaming`() {
        assertEquals(2048, bodyOf(simple("claude-opus-5-5").copy(maxOutputTokens = 2048)).getValue("max_tokens").jsonPrimitive.long)

        enqueueSse()
        runBlocking { runCatching { adapter().client.chatStream(simple("claude-opus-5-5")).toList() } }
        val streamed = takeBody()
        assertEquals(64_000, streamed.getValue("max_tokens").jsonPrimitive.long)
        assertEquals(JsonPrimitive(true), streamed["stream"])
    }

    @Test
    fun `refusal fallbacks are opted in only for models that document them`() {
        fun fallbackOf(model: String, options: AnthropicOptions = AnthropicOptions(maxRetries = 0)): Pair<Any?, String?> {
            enqueueJson(messageJson(TEXT_REPLY))
            runBlocking { adapter(options).client.chat(simple(model)) }
            val request = takeRequest()
            return request.json()["fallbacks"] to request.headers["anthropic-beta"]
        }

        for (model in listOf("claude-fable-5-2", "claude-mythos-5-2", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")) {
            assertEquals(JsonPrimitive("default") to "server-side-fallback-2026-07-01", fallbackOf(model), model)
        }
        val (list, beta) = fallbackOf("claude-fable-5-1")
        assertEquals("""[{"model":"claude-opus-4-8"}]""", list.toString())
        assertEquals("server-side-fallback-2026-07-01", beta)

        for (model in listOf("claude-haiku-5-5", "claude-opus-4-8", "claude-sonnet-5", "claude-mythos-5", "unknown-model")) {
            assertEquals(null to null, fallbackOf(model), model)
        }
        assertEquals(null to null, fallbackOf("claude-fable-5-2", AnthropicOptions(refusalFallbacks = false, maxRetries = 0)))
    }

    @Test
    fun `an endpoint that rejects the fallback opt-in is asked again without it and not offered it afterwards`() {
        enqueueError(400, "invalid_request_error", "Unexpected value(s) `server-side-fallback-2026-07-01` for the `anthropic-beta` header.")
        enqueueJson(messageJson(TEXT_REPLY))
        enqueueJson(messageJson(TEXT_REPLY))
        val client = adapter().client

        val response = runBlocking { client.chat(simple("claude-opus-5-5")) }
        runBlocking { client.chat(simple("claude-opus-5-5")) }

        assertEquals("Hello there.", response.message.text)
        assertTrue("fallbacks" in takeBody())
        val resent = takeRequest()
        assertFalse("fallbacks" in resent.json())
        assertNull(resent.headers["anthropic-beta"])
        assertFalse("fallbacks" in takeBody(), "the rejection is remembered for the adapter's lifetime")
    }

    @Test
    fun `images precede the text of a user message`() {
        val body = bodyOf(
            ChatRequest("claude-opus-5-5", listOf(ChatMessage.user("What is this?", listOf(ImageContent("image/png", "aGVsbG8="))))),
        )

        val content = body.array("messages")[0].jsonObject.array("content").map { it.jsonObject }
        assertEquals(listOf("image", "text"), content.map { it.string("type") })
        assertEquals(
            buildJsonObject { put("type", "base64"); put("media_type", "image/png"); put("data", "aGVsbG8=") },
            JsonObject(content[0].obj("source").filterKeys { it in setOf("type", "media_type", "data") }),
        )
    }

    @Test
    fun `a later system message is a system entry where the model allows it and a marked user block elsewhere`() {
        val history = arrayOf(ChatMessage.system("Terse mode is on."))

        val native = bodyOf(simple("claude-opus-5-5", *history)).array("messages").map { it.jsonObject }
        assertEquals(listOf("user", "system"), native.map { it.string("role") })
        assertEquals(JsonPrimitive("Terse mode is on."), native[1]["content"])

        val older = bodyOf(simple("claude-sonnet-5", *history)).array("messages").map { it.jsonObject }
        assertEquals(listOf("user"), older.map { it.string("role") })
        assertEquals(
            listOf("Hi", "<system-reminder>\nTerse mode is on.\n</system-reminder>"),
            older[0].array("content").map { it.jsonObject.string("text") },
        )

        // A system entry may not sit between an assistant turn and the next user turn.
        val misplaced = bodyOf(
            simple("claude-opus-5-5", ChatMessage.assistant("Hello."), ChatMessage.system("Terse mode is on."), ChatMessage.user("Go on")),
        ).array("messages").map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), misplaced.map { it.string("role") })
        assertEquals(2, misplaced[2].array("content").size)
    }

    @Test
    fun `a tool call that never got a result is answered with an error result`() {
        val body = bodyOf(
            ChatRequest(
                "claude-opus-5-5",
                listOf(
                    ChatMessage.user("Weather and time?"),
                    ChatMessage.assistant("", listOf(call("toolu_1", "get_weather", "city" to "Oslo"), call("toolu_2", "get_time"))),
                    ChatMessage.toolResult("toolu_2", "get_time", "12:00"),
                    ChatMessage.user("Never mind the weather."),
                ),
                tools,
            ),
        )

        val messages = body.array("messages").map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.string("role") })
        val followUp = messages[2].array("content").map { it.jsonObject }
        assertEquals(listOf("tool_result", "tool_result", "text"), followUp.map { it.string("type") }, "results come before other content")
        assertEquals(setOf("toolu_1", "toolu_2"), followUp.take(2).map { it.string("tool_use_id") }.toSet())
        val missing = followUp.first { it["tool_use_id"] == JsonPrimitive("toolu_1") }
        assertEquals(JsonPrimitive(true), missing["is_error"])
        assertTrue(missing.string("content").contains("not run"))
    }

    @Test
    fun `requests that cannot be valid calls fail before anything is sent`() {
        val client = adapter().client

        val prefill = assertFailsWith<LlmException.InvalidRequest> {
            runBlocking { client.chat(simple("claude-opus-5-5", ChatMessage.assistant("{"))) }
        }
        assertTrue(prefill.message!!.contains("prefilled"))
        assertFailsWith<LlmException.InvalidRequest> { runBlocking { client.chat(simple("  ")) } }
        assertFailsWith<LlmException.InvalidRequest> {
            runBlocking { client.chat(ChatRequest("claude-opus-5-5", listOf(ChatMessage.system("Only a system prompt.")))) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `empty messages are left out instead of being sent as empty content`() {
        val body = bodyOf(
            simple("claude-opus-5-5", ChatMessage.assistant(""), ChatMessage.user(" "), ChatMessage.user("Still there?")),
        )

        val messages = body.array("messages").map { it.jsonObject }
        assertEquals(listOf("user"), messages.map { it.string("role") })
        assertEquals(listOf("Hi", "Still there?"), messages[0].array("content").map { it.jsonObject.string("text") })
        assertTrue(messages[0].array("content").none { it == JsonNull })
    }
}
