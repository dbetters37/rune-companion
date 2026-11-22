package com.aicontrolcenter.providers

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ImageContent
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderConnection
import com.aicontrolcenter.core.llm.ReasoningEffort
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.llm.ToolDefinition
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.fail

/** A key that is long and distinctive enough to search for in anything that might leak it. */
const val TEST_KEY = "sk-test-SECRET-0123456789abcdef"

fun json(text: String): JsonElement = Json.parseToJsonElement(text)

fun assertJsonEquals(expected: String, actual: JsonElement) {
    val wanted = json(expected)
    assertEquals(wanted, actual, "JSON differs.\nexpected: $wanted\nactual:   $actual\n")
}

/** Runs [block] and returns the [LlmException] subtype it must throw. */
inline fun <reified T : LlmException> failsWith(block: () -> Unit): T {
    try {
        block()
    } catch (e: LlmException) {
        if (e is T) return e
        fail("Expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}")
    }
    fail("Expected ${T::class.simpleName} but nothing was thrown")
}

fun <T> Flow<T>.collectBlocking(): List<T> = runBlocking { toList() }

fun List<ChatChunk>.completed(): ChatChunk.Completed = single { it is ChatChunk.Completed } as ChatChunk.Completed

val WEATHER_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
    putJsonArray("required") { add("city") }
}

const val WEATHER_SCHEMA_JSON = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""

val WEATHER_TOOL = ToolDefinition("get_weather", "Looks up the weather.", WEATHER_SCHEMA)

/**
 * One turn that exercises every kind of message: merged leading instructions, a user message
 * with an image, an assistant turn that calls a tool, the tool's result, a plain assistant
 * reply, a late system message and a final user message.
 */
fun fullConversation(model: String) = ChatRequest(
    model = model,
    messages = listOf(
        ChatMessage.system("You are terse."),
        ChatMessage.system("Answer in English."),
        ChatMessage.user("What is the weather in Oslo?", images = listOf(ImageContent("image/png", "aGVsbG8="))),
        ChatMessage.assistant("", toolCalls = listOf(ToolCallRequest("call_1", "get_weather", buildJsonObject { put("city", "Oslo") }))),
        ChatMessage.toolResult("call_1", "get_weather", "{\"temp\":4}"),
        ChatMessage.assistant("It is 4 degrees."),
        ChatMessage.system("Be brief."),
        ChatMessage.user("And tomorrow?"),
    ),
    tools = listOf(WEATHER_TOOL),
    temperature = 0.2,
    maxOutputTokens = 256,
    reasoningEffort = ReasoningEffort.LOW,
    jsonOutput = true,
)

fun simpleRequest(model: String = "test-model", text: String = "Hello") = ChatRequest(model = model, messages = listOf(ChatMessage.user(text)))

/** Base for tests that talk to a [MockWebServer]. */
abstract class MockServerTest {
    @get:Rule
    val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

    protected val server = MockWebServer()

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    /** The server's address with [path] appended, as an owner would enter it. */
    protected fun baseUrl(path: String = ""): String = server.url("/").toString().removeSuffix("/") + path

    protected fun connection(
        kind: ProviderKind,
        baseUrl: String? = baseUrl(),
        apiKey: String? = TEST_KEY,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = ProviderConnection(Samples.provider(kind = kind, baseUrl = baseUrl).copy(name = "Test provider", extraHeaders = extraHeaders), apiKey)

    protected fun enqueue(body: String, code: Int = 200, vararg headers: Pair<String, String>) {
        val response = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
        headers.forEach { (name, value) -> response.addHeader(name, value) }
        server.enqueue(response.build())
    }

    /** Enqueues a server-sent-event stream with one event per element of [data]. */
    protected fun enqueueSse(vararg data: String) {
        val body = data.joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").chunkedBody(body, 37).build())
    }

    /** Enqueues a newline-delimited JSON stream. */
    protected fun enqueueLines(vararg lines: String) {
        val body = lines.joinToString("") { "$it\n" }
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/x-ndjson").chunkedBody(body, 37).build())
    }

    protected fun takeBody(): JsonObject = json(server.takeRequest().body!!.utf8()).jsonObject
}
