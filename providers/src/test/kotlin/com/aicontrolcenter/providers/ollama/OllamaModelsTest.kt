package com.aicontrolcenter.providers.ollama

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.CannedClient
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.failsWith
import com.aicontrolcenter.providers.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Model listing (`/api/tags` plus `/api/show`) and the health check of the Ollama adapter. */
class OllamaModelsTest : MockServerTest() {
    private fun adapter(baseUrl: String? = baseUrl()): ProviderAdapter = OllamaAdapterFactory().create(connection(ProviderKind.OLLAMA, baseUrl, apiKey = null))

    private fun reply(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private val tags = """{"models":[
        {"name":"llama3.2:latest","model":"llama3.2:latest","modified_at":"2026-10-01T10:00:00Z","size":2019393189,"digest":"a80c",
         "details":{"format":"gguf","family":"llama","families":["llama"],"parameter_size":"3.2B","quantization_level":"Q4_K_M"}},
        {"name":"qwen3-vl:30b","model":"qwen3-vl:30b","size":19000000000,"details":{"family":"qwen3","parameter_size":"30.5B"}},
        {"name":"nomic-embed-text:latest","model":"nomic-embed-text:latest","size":274302450,"details":{"family":"nomic-bert","parameter_size":"137M"}},
        {"name":"legacy:7b","model":"legacy:7b","size":4100000000,"details":{"parameter_size":"7B"}},
        {"name":"broken:latest","model":"broken:latest","size":1}
    ]}"""

    private val shows = mapOf(
        "llama3.2:latest" to """{"modelfile":"FROM llama3.2","parameters":"num_ctx                        16384\nstop                           \"<|eot_id|>\"",
            "template":"{{ .Prompt }}","details":{"parameter_size":"3.2B"},
            "model_info":{"general.architecture":"llama","general.parameter_count":3212749888,"llama.context_length":131072},
            "capabilities":["completion","tools"]}""",
        "qwen3-vl:30b" to """{"parameters":"temperature 0.7\ntop_k 20","details":{"parameter_size":"30.5B"},
            "model_info":{"general.architecture":"qwen3vl","qwen3vl.context_length":262144},
            "capabilities":["completion","vision","tools","thinking"]}""",
        "nomic-embed-text:latest" to """{"details":{"parameter_size":"137M"},"model_info":{"nomic-bert.context_length":2048},"capabilities":["embedding"]}""",
        "legacy:7b" to """{"modelfile":"FROM legacy","details":{"parameter_size":"7B"},"model_info":{"llama.context_length":4096}}""",
    )

    /** `/api/show` is asked for several models at once, so replies are chosen by request rather than queued. */
    private fun serveListing(): MutableList<String> {
        val shown = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/tags" -> reply(tags)
                "/api/show" -> {
                    val model = json(request.body!!.utf8()).jsonObject.getValue("model").jsonPrimitive.content
                    shown += "${request.method} $model"
                    shows[model]?.let { reply(it) } ?: reply("""{"error":"something went wrong"}""", 500)
                }
                else -> reply("""{"error":"not found"}""", 404)
            }
        }
        return shown
    }

    @Test
    fun `models are listed with sizes and the capabilities ollama reports`() {
        val shown = serveListing()
        val models = runBlocking { adapter().listModels() }
        assertEquals(listOf("llama3.2:latest", "qwen3-vl:30b", "nomic-embed-text:latest", "legacy:7b", "broken:latest"), models.map { it.name })
        assertEquals(listOf<Long?>(2_019_393_189, 19_000_000_000, 274_302_450, 4_100_000_000, 1), models.map { it.sizeBytes })
        assertEquals(
            setOf("POST llama3.2:latest", "POST qwen3-vl:30b", "POST nomic-embed-text:latest", "POST legacy:7b", "POST broken:latest"),
            shown.toSet(),
        )

        val llama = assertNotNull(models[0].capabilities)
        assertTrue(llama.chat && llama.toolCalling && llama.streaming && llama.jsonOutput)
        assertFalse(llama.vision || llama.embeddings)
        assertEquals(16_384, llama.contextWindow, "num_ctx of the Modelfile wins over the trained length")
        assertEquals(CapabilityTier.BASIC, llama.reasoning)

        val qwen = assertNotNull(models[1].capabilities)
        assertTrue(qwen.toolCalling && qwen.vision)
        assertEquals(262_144, qwen.contextWindow, "the trained length when the Modelfile sets none")
        assertEquals(CapabilityTier.STRONG, qwen.reasoning, "a large model with a thinking mode")
        assertEquals(CapabilityTier.GOOD, qwen.coding)

        val embedder = assertNotNull(models[2].capabilities)
        assertTrue(embedder.embeddings)
        assertFalse(embedder.chat || embedder.toolCalling || embedder.streaming)
    }

    @Test
    fun `a model whose details are missing or unreadable is still listed`() {
        serveListing()
        val models = runBlocking { adapter().listModels() }.associateBy { it.name }
        assertNull(models.getValue("legacy:7b").capabilities, "a server too old to report capabilities")
        assertNull(models.getValue("broken:latest").capabilities, "show failed")
    }

    @Test
    fun `a listing that fails or has an unknown shape is an error`() {
        enqueue("""{"error":"unauthorized"}""", 401)
        failsWith<LlmException.Authentication> { runBlocking { adapter().listModels() } }
        enqueue("""{"object":"list","data":[]}""")
        failsWith<LlmException.MalformedResponse> { runBlocking { adapter().listModels() } }
    }

    @Test
    fun `health asks for the version`() {
        enqueue("""{"version":"0.12.6"}""")
        val health = runBlocking { adapter().checkHealth() }
        assertEquals(ConnectionStatus.ONLINE, health.status)
        assertNotNull(health.latencyMs)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/version", request.url.encodedPath)
    }

    @Test
    fun `health tells another kind of server and an absent one apart`() {
        enqueue("""{"error":{"message":"Not Found"}}""", 404)
        val other = runBlocking { adapter().checkHealth() }
        assertEquals(ConnectionStatus.ERROR, other.status)
        assertTrue("base URL" in other.message!!)

        enqueue("""{"status":"ok"}""")
        val impostor = runBlocking { adapter().checkHealth() }
        assertEquals(ConnectionStatus.ERROR, impostor.status)
        assertTrue("Ollama" in impostor.message!!)

        val gone = MockWebServer().apply { start() }
        val address = gone.url("/").toString()
        gone.close()
        assertEquals(ConnectionStatus.OFFLINE, runBlocking { adapter(address).checkHealth() }.status)
    }

    @Test
    fun `a blank address means this device at ollama's default port`() {
        assertEquals(11434, OllamaAdapter.DEFAULT_PORT)
        val canned = CannedClient("""{"version":"0.12.6"}""")
        val local = OllamaAdapterFactory(canned.client).create(connection(ProviderKind.OLLAMA, baseUrl = null, apiKey = null))
        assertEquals(ConnectionStatus.ONLINE, runBlocking { local.checkHealth() }.status)
        assertEquals("http://127.0.0.1:11434/api/version", canned.requests.single().url.toString())
    }

    @Test
    fun `a base address with a path keeps it and plain http to the internet is refused`() {
        enqueue("""{"version":"0.12.6"}""")
        runBlocking { adapter(baseUrl("/ollama/")).checkHealth() }
        assertEquals("/ollama/api/version", server.takeRequest().url.encodedPath)

        val remote = adapter("http://ollama.example.com:11434")
        failsWith<LlmException.BlockedByPolicy> { runBlocking { remote.listModels() } }
        assertEquals(ConnectionStatus.ERROR, runBlocking { remote.checkHealth() }.status)
        assertEquals(1, server.requestCount)
    }
}
