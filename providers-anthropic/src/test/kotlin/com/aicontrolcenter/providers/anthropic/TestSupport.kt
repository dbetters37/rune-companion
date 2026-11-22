package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderConnection
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.testkit.Samples
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import java.util.concurrent.TimeUnit

internal const val TEST_KEY = "sk-ant-test-0123456789-SECRET-abcdef"

/** A MockWebServer standing in for the Messages API, and adapters pointed at it. */
internal abstract class MockApiTest {
    protected val server = MockWebServer()
    private val adapters = ArrayList<ProviderAdapter>()

    @Before
    fun startServer() = server.start()

    @After
    fun stopServer() {
        adapters.forEach { it.close() }
        server.close()
    }

    /** Retries are off unless a test is about them, so a canned error is answered exactly once. */
    protected fun adapter(
        options: AnthropicOptions = AnthropicOptions(maxRetries = 0),
        apiKey: String? = TEST_KEY,
        baseUrl: String = server.url("/").toString(),
    ): ProviderAdapter {
        val provider = Samples.provider(name = "Claude", kind = ProviderKind.ANTHROPIC, baseUrl = baseUrl, locality = Locality.CLOUD)
        return AnthropicAdapterFactory(options).create(ProviderConnection(provider, apiKey)).also { adapters += it }
    }

    protected fun enqueueJson(body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) {
        val response = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
        headers.forEach { (name, value) -> response.addHeader(name, value) }
        server.enqueue(response.build())
    }

    protected fun enqueueError(code: Int, type: String, message: String, headers: Map<String, String> = emptyMap()) =
        enqueueJson("""{"type":"error","error":{"type":"$type","message":${JsonPrimitive(message)}},"request_id":"req_1"}""", code, headers)

    protected fun enqueueSse(vararg events: Pair<String, String>) {
        val body = events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).build())
    }

    protected fun takeRequest(): RecordedRequest =
        server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("The adapter sent no request")

    protected fun takeBody(): JsonObject = takeRequest().json()
}

internal fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

internal fun String.parseObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

internal fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

/** A complete Messages API response body with the given content blocks. */
internal fun messageJson(
    content: String,
    stopReason: String = "end_turn",
    model: String = "claude-opus-5-5",
    usage: String = """{"input_tokens":12,"output_tokens":34}""",
    stopDetails: String = "null",
): String = """
    {"id":"msg_01","type":"message","role":"assistant","model":"$model","content":$content,
     "stop_reason":"$stopReason","stop_sequence":null,"stop_details":$stopDetails,"usage":$usage}
""".trimIndent()

internal const val TEXT_REPLY = """[{"type":"text","text":"Hello there."}]"""
