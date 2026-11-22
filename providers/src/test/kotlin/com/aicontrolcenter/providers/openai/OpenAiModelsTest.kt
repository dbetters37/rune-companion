package com.aicontrolcenter.providers.openai

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.CannedClient
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.TEST_KEY
import com.aicontrolcenter.providers.failsWith
import com.aicontrolcenter.providers.simpleRequest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Model listing and the health check of the OpenAI-compatible adapter. */
class OpenAiModelsTest : MockServerTest() {
    private val factory = OpenAiCompatibleAdapterFactory()

    private fun adapter(baseUrl: String? = baseUrl("/v1"), apiKey: String? = TEST_KEY): ProviderAdapter =
        factory.create(connection(ProviderKind.OPENAI_COMPATIBLE, baseUrl, apiKey))

    private fun list(body: String): List<RemoteModel> {
        enqueue(body)
        return runBlocking { adapter().listModels() }
    }

    @Test
    fun `the bare protocol listing gives names and owners and leaves capabilities to estimation`() {
        val models = list(
            """{"object":"list","data":[
                {"id":"gpt-test","object":"model","created":1686935002,"owned_by":"openai"},
                {"id":"text-embedding-3-small","object":"model","created":1705948997,"owned_by":"system"}]}""",
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer $TEST_KEY", request.headers["Authorization"])
        assertEquals(
            listOf(RemoteModel("gpt-test", ownedBy = "openai"), RemoteModel("text-embedding-3-small", ownedBy = "system")),
            models,
        )
    }

    @Test
    fun `openrouter metadata becomes capabilities`() {
        val models = list(
            """{"data":[
                {"id":"vendor/vision-tools","canonical_slug":"vendor/vision-tools-20260101","name":"Vendor: Vision Tools","context_length":400000,
                 "architecture":{"modality":"text+image->text","input_modalities":["text","image","file"],"output_modalities":["text"],"tokenizer":"GPT"},
                 "top_provider":{"context_length":400000,"max_completion_tokens":128000,"is_moderated":true},
                 "supported_parameters":["max_tokens","response_format","structured_outputs","tool_choice","tools","reasoning"]},
                {"id":"vendor/plain-text","name":"Vendor: Plain Text","context_length":32768,
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
                 "top_provider":{"context_length":32768,"max_completion_tokens":null,"is_moderated":false},
                 "supported_parameters":["max_tokens","temperature","top_p"]}]}""",
        )
        val (rich, plain) = models
        assertEquals("vendor/vision-tools", rich.name)
        assertEquals("Vendor: Vision Tools", rich.displayName)
        val richCaps = assertNotNull(rich.capabilities)
        assertTrue(richCaps.chat && richCaps.toolCalling && richCaps.vision && richCaps.jsonOutput)
        assertEquals(400_000, richCaps.contextWindow)
        assertEquals(128_000, richCaps.maxOutputTokens)

        val plainCaps = assertNotNull(plain.capabilities)
        assertTrue(plainCaps.chat)
        assertFalse(plainCaps.toolCalling || plainCaps.vision || plainCaps.jsonOutput)
        assertEquals(32_768, plainCaps.contextWindow)
        assertNull(plainCaps.maxOutputTokens)
    }

    @Test
    fun `metadata of other services is used where present`() {
        val models = list(
            """{"object":"list","data":[
                {"id":"mistral-like","owned_by":"mistralai","max_context_length":131072,
                 "capabilities":{"completion_chat":true,"function_calling":true,"vision":false}},
                {"id":"groq-like","owned_by":"Meta","context_window":131072,"max_completion_tokens":32768},
                {"id":"org/vllm-served","owned_by":"vllm","max_model_len":16384},
                {"id":"qwen2.5-7b-instruct-q4_k_m.gguf","owned_by":"llamacpp","meta":{"n_ctx_train":32768,"n_params":7615616512,"size":4683073536}},
                {"object":"model"}]}""",
        ).associateBy { it.name }
        assertEquals(4, models.size, "an entry without an id is skipped")

        val mistral = assertNotNull(models.getValue("mistral-like").capabilities)
        assertTrue(mistral.toolCalling)
        assertFalse(mistral.vision)
        assertEquals(131_072, mistral.contextWindow)

        val groq = assertNotNull(models.getValue("groq-like").capabilities)
        assertEquals(131_072, groq.contextWindow)
        assertEquals(32_768, groq.maxOutputTokens)

        assertEquals(16_384, models.getValue("org/vllm-served").capabilities?.contextWindow)

        val llamaCpp = models.getValue("qwen2.5-7b-instruct-q4_k_m.gguf")
        assertEquals(4_683_073_536, llamaCpp.sizeBytes)
        assertNull(llamaCpp.capabilities, "a file size alone says nothing about capabilities")
    }

    @Test
    fun `a bare array and a models key are also understood`() {
        assertEquals(listOf("a", "b"), list("""[{"id":"a"},{"id":"b"}]""").map { it.name })
        assertEquals(listOf("llama3.2:latest"), list("""{"models":[{"name":"llama3.2:latest"}]}""").map { it.name })
    }

    @Test
    fun `a listing in no known shape is malformed and a rejected one is mapped`() {
        enqueue("""{"status":"ok"}""")
        failsWith<LlmException.MalformedResponse> { runBlocking { adapter().listModels() } }
        enqueue("""{"error":{"message":"Incorrect API key provided.","code":"invalid_api_key"}}""", 401)
        failsWith<LlmException.Authentication> { runBlocking { adapter().listModels() } }
    }

    @Test
    fun `health is online when the models endpoint answers`() {
        enqueue("""{"object":"list","data":[{"id":"gpt-test"}]}""")
        val health = runBlocking { adapter().checkHealth() }
        assertEquals(ConnectionStatus.ONLINE, health.status)
        assertNull(health.message)
        assertNotNull(health.latencyMs)
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `health reports failures as a status and a sentence`() {
        enqueue("""{"error":{"message":"Incorrect API key provided."}}""", 401)
        val rejected = runBlocking { adapter().checkHealth() }
        assertEquals(ConnectionStatus.AUTH_ERROR, rejected.status)
        assertTrue("Incorrect API key" in rejected.message!!)

        enqueue("<html>Bad Gateway</html>", 502)
        assertEquals(ConnectionStatus.ERROR, runBlocking { adapter().checkHealth() }.status)

        enqueue("""{"hello":"world"}""")
        assertEquals(ConnectionStatus.ERROR, runBlocking { adapter().checkHealth() }.status, "not a model API")

        val gone = MockWebServer().apply { start() }
        val address = gone.url("/v1").toString()
        gone.close()
        val offline = runBlocking { adapter(address).checkHealth() }
        assertEquals(ConnectionStatus.OFFLINE, offline.status)
        assertNotNull(offline.message)
    }

    @Test
    fun `plain http beyond the local network is refused before anything is sent`() {
        val remote = adapter("http://api.example.com/v1")
        val error = failsWith<LlmException.BlockedByPolicy> { runBlocking { remote.listModels() } }
        assertTrue("https://" in error.message!!)
        failsWith<LlmException.BlockedByPolicy> { runBlocking { remote.client.chat(simpleRequest()) } }

        val health = runBlocking { remote.checkHealth() }
        assertEquals(ConnectionStatus.ERROR, health.status)
        assertTrue("unencrypted" in health.message!!)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a blank address means openai itself`() {
        val canned = CannedClient("""{"object":"list","data":[{"id":"gpt-test"}]}""")
        val adapter = OpenAiCompatibleAdapterFactory(canned.client).create(connection(ProviderKind.OPENAI_COMPATIBLE, baseUrl = null))
        assertEquals(listOf("gpt-test"), runBlocking { adapter.listModels() }.map { it.name })
        assertEquals("https://api.openai.com/v1/models", canned.requests.single().url.toString())
    }
}
