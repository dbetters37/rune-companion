package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ConnectionStatus
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ModelsAndHealthTest : MockApiTest() {

    private fun model(id: String, name: String, input: Long?, output: Long?, vision: Boolean? = true): String {
        val capabilities = vision?.let {
            """{"batch":{"supported":true},"citations":{"supported":true},"code_execution":{"supported":true},
                "context_management":{"supported":false,"clear_thinking_20251015":{"supported":false},
                    "clear_tool_uses_20250919":{"supported":false},"compact_20260112":{"supported":false}},
                "effort":{"supported":true,"low":{"supported":true},"medium":{"supported":true},"high":{"supported":true},"max":{"supported":true}},
                "image_input":{"supported":$it},"pdf_input":{"supported":true},"structured_outputs":{"supported":true},
                "thinking":{"supported":true,"types":{"adaptive":{"supported":true},"enabled":{"supported":false}}}}"""
        }
        return """{"type":"model","id":"$id","display_name":"$name","created_at":"2026-09-01T00:00:00Z",
            "max_input_tokens":$input,"max_tokens":$output,"capabilities":$capabilities}"""
    }

    private fun page(models: List<String>, hasMore: Boolean, lastId: String) =
        """{"data":[${models.joinToString(",")}],"has_more":$hasMore,"first_id":"first","last_id":"$lastId"}"""

    @Test
    fun `models are listed across pages with their limits and capabilities`() {
        enqueueJson(page(listOf(model("claude-fable-5-2", "Claude Fable 5.2", 1_000_000, 128_000)), hasMore = true, lastId = "claude-fable-5-2"))
        enqueueJson(
            page(
                listOf(
                    model("claude-haiku-4-5-20251001", "Claude Haiku 4.5", 200_000, 64_000),
                    model("text-only-model", "Text Only", 50_000, 4_096, vision = false),
                    model("no-details", "", null, null, vision = null),
                ),
                hasMore = false, lastId = "no-details",
            ),
        )

        val models = runBlocking { adapter().listModels() }

        assertEquals(listOf("claude-fable-5-2", "claude-haiku-4-5-20251001", "text-only-model", "no-details"), models.map { it.name })
        val fable = models[0]
        assertEquals("Claude Fable 5.2", fable.displayName)
        assertEquals("anthropic", fable.ownedBy)
        val capabilities = assertNotNull(fable.capabilities)
        assertEquals(1_000_000, capabilities.contextWindow)
        assertEquals(128_000, capabilities.maxOutputTokens)
        assertTrue(capabilities.chat && capabilities.toolCalling && capabilities.vision && capabilities.streaming)
        assertFalse(capabilities.jsonOutput, "JSON cannot be enforced without a schema")
        assertFalse(capabilities.embeddings)
        assertEquals(CapabilityTier.STRONG, capabilities.reasoning)

        assertEquals(CapabilityTier.GOOD, models[1].capabilities!!.reasoning)
        assertEquals(200_000, models[1].capabilities!!.contextWindow)
        assertFalse(models[2].capabilities!!.vision)
        assertEquals(CapabilityTier.UNKNOWN, models[2].capabilities!!.coding)
        assertNull(models[3].displayName)
        assertNull(models[3].capabilities!!.contextWindow)
        assertTrue(models[3].capabilities!!.toolCalling)

        val first = takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/v1/models", first.url.encodedPath)
        assertEquals("claude-fable-5-2", takeRequest().url.queryParameter("after_id"), "the second page continues after the first")
    }

    @Test
    fun `listing failures are mapped like chat failures`() {
        enqueueError(401, "authentication_error", "invalid x-api-key")

        assertFailsWith<LlmException.Authentication> { runBlocking { adapter().listModels() } }
    }

    @Test
    fun `health is online with a latency when the listing answers`() {
        enqueueJson(page(listOf(model("claude-opus-5-5", "Claude Opus 5.5", 1_000_000, 128_000)), hasMore = true, lastId = "claude-opus-5-5"))

        val health = runBlocking { adapter().checkHealth() }

        assertEquals(ConnectionStatus.ONLINE, health.status)
        assertNull(health.message)
        assertTrue(assertNotNull(health.latencyMs) >= 0)
        assertEquals("1", takeRequest().url.queryParameter("limit"), "the check asks for a single model")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `health never throws and names what is wrong`() {
        enqueueError(401, "authentication_error", "invalid x-api-key")
        val rejected = runBlocking { adapter().checkHealth() }
        assertEquals(ConnectionStatus.AUTH_ERROR, rejected.status)
        assertTrue(rejected.message!!.contains("invalid x-api-key"))

        val noKey = runBlocking { adapter(apiKey = null).checkHealth() }
        assertEquals(ConnectionStatus.AUTH_ERROR, noKey.status)
        assertTrue(noKey.message!!.contains("No API key"))

        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        assertEquals(ConnectionStatus.OFFLINE, runBlocking { adapter().checkHealth() }.status)
        assertEquals(ConnectionStatus.OFFLINE, runBlocking { adapter(baseUrl = "http://127.0.0.1:1").checkHealth() }.status)

        enqueueError(500, "api_error", "Internal server error")
        val broken = runBlocking { adapter().checkHealth() }
        assertEquals(ConnectionStatus.ERROR, broken.status)
        assertNotNull(broken.message)

        enqueueJson("""{"data":"not a list"}""")
        assertEquals(ConnectionStatus.ERROR, runBlocking { adapter().checkHealth() }.status)
    }

    @Test
    fun `a closed adapter reports the problem instead of throwing from health`() {
        val adapter = adapter()
        adapter.close()

        assertEquals(ConnectionStatus.ERROR, runBlocking { adapter.checkHealth() }.status)
        assertFailsWith<LlmException> { runBlocking { adapter.listModels() } }
    }
}
