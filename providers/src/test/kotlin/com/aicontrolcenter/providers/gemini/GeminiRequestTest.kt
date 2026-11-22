package com.aicontrolcenter.providers.gemini

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderData
import com.aicontrolcenter.core.llm.ReasoningEffort
import com.aicontrolcenter.core.llm.ToolCallRequest
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

/** What the adapter puts on the wire for `generateContent` and `streamGenerateContent`. */
class GeminiRequestTest : MockServerTest() {
    private val factory = GeminiAdapterFactory()
    private val reply = """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP","index":0}],"modelVersion":"gemini-2.5-flash"}"""

    private fun client() = factory.create(connection(ProviderKind.GEMINI, baseUrl("/v1beta"))).client

    private fun bodyOf(request: ChatRequest) = runBlocking {
        enqueue(reply)
        client().chat(request)
        takeBody()
    }

    private val fullTurn = """{
        "systemInstruction": {"parts": [{"text": "You are terse.\n\nAnswer in English."}]},
        "contents": [
          {"role": "user", "parts": [
            {"text": "What is the weather in Oslo?"},
            {"inlineData": {"mimeType": "image/png", "data": "aGVsbG8="}}
          ]},
          {"role": "model", "parts": [{"functionCall": {"name": "get_weather", "args": {"city": "Oslo"}}}]},
          {"role": "user", "parts": [{"functionResponse": {"name": "get_weather", "response": {"result": "{\"temp\":4}"}}}]},
          {"role": "model", "parts": [{"text": "It is 4 degrees."}]},
          {"role": "user", "parts": [{"text": "Be brief."}]},
          {"role": "user", "parts": [{"text": "And tomorrow?"}]}
        ],
        "tools": [{"functionDeclarations": [
          {"name": "get_weather", "description": "Looks up the weather.", "parametersJsonSchema": $WEATHER_SCHEMA_JSON}
        ]}],
        "generationConfig": {
          "temperature": 0.2,
          "maxOutputTokens": 256,
          "responseMimeType": "application/json",
          "thinkingConfig": {"includeThoughts": true, "thinkingBudget": 1024}
        }
    }"""

    @Test
    fun `a full turn is sent as generateContent json with the key in its header`() {
        enqueue(reply)
        runBlocking { client().chat(fullConversation("gemini-2.5-flash")) }
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", request.url.encodedPath)
        assertNull(request.url.query, "the key must not travel in the address")
        assertEquals(TEST_KEY, request.headers["x-goog-api-key"])
        assertNull(request.headers["Authorization"])
        assertJsonEquals(fullTurn, json(request.body!!.utf8()))
    }

    @Test
    fun `a streamed turn uses the streaming method with sse and the same body`() {
        enqueueSse(reply)
        client().chatStream(fullConversation("gemini-2.5-flash")).collectBlocking()
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash:streamGenerateContent", request.url.encodedPath)
        assertEquals("alt=sse", request.url.query)
        assertEquals("text/event-stream", request.headers["Accept"])
        assertJsonEquals(fullTurn, json(request.body!!.utf8()))
    }

    @Test
    fun `a minimal request has only contents`() {
        assertJsonEquals("""{"contents":[{"role":"user","parts":[{"text":"Hello"}]}]}""", bodyOf(simpleRequest("gemini-2.5-flash")))
    }

    @Test
    fun `forbidding tool use keeps the declarations and sets the mode`() {
        val body = bodyOf(simpleRequest("gemini-2.5-flash").copy(tools = listOf(WEATHER_TOOL), toolChoice = ToolChoice.NONE))
        assertJsonEquals("""{"functionCallingConfig":{"mode":"NONE"}}""", body.getValue("toolConfig"))
        assertEquals(1, body.getValue("tools").jsonArray.size)
    }

    @Test
    fun `reasoning effort is a level for gemini 3 and later and a budget before`() {
        fun thinking(model: String, effort: ReasoningEffort) =
            bodyOf(simpleRequest(model).copy(reasoningEffort = effort)).getValue("generationConfig").jsonObject.getValue("thinkingConfig")
        assertJsonEquals("""{"includeThoughts":true,"thinkingLevel":"HIGH"}""", thinking("gemini-3-flash-preview", ReasoningEffort.HIGH))
        assertJsonEquals("""{"includeThoughts":true,"thinkingLevel":"LOW"}""", thinking("gemini-4-pro", ReasoningEffort.LOW))
        assertJsonEquals("""{"includeThoughts":true,"thinkingBudget":8192}""", thinking("gemini-2.5-pro", ReasoningEffort.MEDIUM))
        assertJsonEquals("""{"includeThoughts":true,"thinkingBudget":24576}""", thinking("gemini-2.5-flash", ReasoningEffort.HIGH))
    }

    @Test
    fun `the results of one turn's calls are grouped and a failed tool is reported as an error`() {
        fun call(id: String, name: String) = ToolCallRequest(id, name, json("{}").jsonObject)
        val request = ChatRequest(
            model = "gemini-2.5-flash",
            messages = listOf(
                ChatMessage.user("Compare Oslo and Bergen."),
                ChatMessage.assistant("Checking both.", listOf(call("a", "weather_oslo"), call("b", "weather_bergen"))),
                ChatMessage.toolResult("a", "weather_oslo", "4 degrees"),
                ChatMessage.toolResult("b", "weather_bergen", "station offline", isError = true),
            ),
        )
        assertJsonEquals(
            """[
              {"role":"user","parts":[{"text":"Compare Oslo and Bergen."}]},
              {"role":"model","parts":[{"text":"Checking both."},{"functionCall":{"name":"weather_oslo","args":{}}},{"functionCall":{"name":"weather_bergen","args":{}}}]},
              {"role":"user","parts":[
                {"functionResponse":{"name":"weather_oslo","response":{"result":"4 degrees"}}},
                {"functionResponse":{"name":"weather_bergen","response":{"error":"station offline"}}}]}
            ]""",
            bodyOf(request).getValue("contents"),
        )
    }

    @Test
    fun `a call that gemini did not produce gets the documented placeholder signature where one is required`() {
        val foreignTurn = ChatMessage.assistant(
            "",
            listOf(ToolCallRequest("call_1", "one", json("{}").jsonObject), ToolCallRequest("call_2", "two", json("{}").jsonObject)),
        ).copy(providerData = ProviderData(ProviderKind.OPENAI_COMPATIBLE, json("""{"origin":"api.example.com"}""")))
        val messages = listOf(ChatMessage.user("Go."), foreignTurn)

        val gemini3 = bodyOf(ChatRequest(model = "gemini-3-pro-preview", messages = messages)).getValue("contents").jsonArray[1]
        assertJsonEquals(
            """{"role":"model","parts":[
                {"functionCall":{"name":"one","args":{}},"thoughtSignature":"skip_thought_signature_validator"},
                {"functionCall":{"name":"two","args":{}}}]}""",
            gemini3,
        )
        val gemini2 = bodyOf(ChatRequest(model = "gemini-2.5-flash", messages = messages)).getValue("contents").jsonArray[1]
        assertJsonEquals("""{"role":"model","parts":[{"functionCall":{"name":"one","args":{}}},{"functionCall":{"name":"two","args":{}}}]}""", gemini2)
    }

    @Test
    fun `model names are checked and full resource names are used as they are`() {
        enqueue(reply)
        runBlocking { client().chat(simpleRequest("tunedModels/my-model")) }
        assertEquals("/v1beta/tunedModels/my-model:generateContent", server.takeRequest().url.encodedPath)

        enqueue(reply)
        runBlocking { client().chat(simpleRequest("models/gemini-2.5-flash")) }
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", server.takeRequest().url.encodedPath)

        for (bad in listOf("../v1/files", "gemini 2.5", "models/a/b", "gemini?key=x")) {
            failsWith<LlmException.ModelNotFound> { runBlocking { client().chat(simpleRequest(bad)) } }
        }
        failsWith<LlmException.InvalidRequest> { runBlocking { client().chat(simpleRequest("")) } }
        assertEquals(2, server.requestCount)
    }
}
