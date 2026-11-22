package com.aicontrolcenter.providers.gemini

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.CannedClient
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.TEST_KEY
import com.aicontrolcenter.providers.failsWith
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Model listing and the health check of the Gemini adapter. */
class GeminiModelsTest : MockServerTest() {
    private fun adapter(baseUrl: String? = baseUrl("/v1beta")): ProviderAdapter = GeminiAdapterFactory().create(connection(ProviderKind.GEMINI, baseUrl))

    private val firstPage = """{"models":[
        {"name":"models/gemini-2.5-flash","version":"001","displayName":"Gemini 2.5 Flash","description":"Stable version of Gemini 2.5 Flash",
         "inputTokenLimit":1048576,"outputTokenLimit":65536,
         "supportedGenerationMethods":["generateContent","countTokens","createCachedContent","batchGenerateContent"],
         "temperature":1,"topP":0.95,"topK":64,"maxTemperature":2,"thinking":true},
        {"name":"models/gemini-embedding-001","displayName":"Gemini Embedding 001","inputTokenLimit":2048,"outputTokenLimit":1,
         "supportedGenerationMethods":["embedContent","countTextTokens"]},
        {"name":"models/aqa","displayName":"Model that performs Attributed Question Answering","inputTokenLimit":7168,"outputTokenLimit":1024,
         "supportedGenerationMethods":["generateAnswer"]}],
        "nextPageToken":"page-2"}"""
    private val secondPage = """{"models":[
        {"name":"models/gemma-3-27b-it","displayName":"Gemma 3 27B","inputTokenLimit":131072,"outputTokenLimit":8192,
         "supportedGenerationMethods":["generateContent","countTokens"]}]}"""

    @Test
    fun `models that can generate content are listed across pages with their token limits`() {
        enqueue(firstPage)
        enqueue(secondPage)
        val models = runBlocking { adapter().listModels() }

        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/v1beta/models", first.url.encodedPath)
        assertEquals("1000", first.url.queryParameter("pageSize"))
        assertNull(first.url.queryParameter("pageToken"))
        assertNull(first.url.queryParameter("key"), "the key must not travel in the address")
        assertEquals(TEST_KEY, first.headers["x-goog-api-key"])
        assertEquals("page-2", server.takeRequest().url.queryParameter("pageToken"))

        assertEquals(listOf("gemini-2.5-flash", "gemma-3-27b-it"), models.map { it.name }, "embedding and answer-only models are left out")
        val flash = models[0]
        assertEquals("Gemini 2.5 Flash", flash.displayName)
        assertEquals("google", flash.ownedBy)
        val flashCaps = assertNotNull(flash.capabilities)
        assertEquals(1_048_576, flashCaps.contextWindow)
        assertEquals(65_536, flashCaps.maxOutputTokens)
        assertTrue(flashCaps.toolCalling && flashCaps.vision && flashCaps.chat)
        assertEquals(CapabilityTier.STRONG, flashCaps.reasoning, "the listing says it thinks")

        val gemmaCaps = assertNotNull(models[1].capabilities)
        assertEquals(131_072, gemmaCaps.contextWindow)
        assertEquals(8_192, gemmaCaps.maxOutputTokens)
        assertFalse(gemmaCaps.toolCalling, "tool calling is estimated from the name, and not claimed for gemma")
    }

    @Test
    fun `a listing that fails or has an unknown shape is an error`() {
        enqueue("""{"error":{"code":403,"message":"Method doesn't allow unregistered callers.","status":"PERMISSION_DENIED"}}""", 403)
        failsWith<LlmException.Authentication> { runBlocking { adapter().listModels() } }
        enqueue("""{"data":[]}""")
        failsWith<LlmException.MalformedResponse> { runBlocking { adapter().listModels() } }
    }

    @Test
    fun `health asks for a single model`() {
        enqueue("""{"models":[{"name":"models/gemini-2.5-flash"}],"nextPageToken":"more"}""")
        val health = runBlocking { adapter().checkHealth() }
        assertEquals(ConnectionStatus.ONLINE, health.status)
        assertNotNull(health.latencyMs)
        val request = server.takeRequest()
        assertEquals("/v1beta/models", request.url.encodedPath)
        assertEquals("1", request.url.queryParameter("pageSize"))
        assertEquals(1, server.requestCount, "further pages are not fetched for a health check")
    }

    @Test
    fun `health reports an invalid key as an authentication problem`() {
        enqueue(
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}""",
            400,
        )
        val health = runBlocking { adapter().checkHealth() }
        assertEquals(ConnectionStatus.AUTH_ERROR, health.status)
        assertTrue("API key not valid" in health.message!!)

        enqueue("""{"error":{"code":500,"message":"Internal error encountered.","status":"INTERNAL"}}""", 500)
        assertEquals(ConnectionStatus.ERROR, runBlocking { adapter().checkHealth() }.status)
    }

    @Test
    fun `a blank address means google's v1beta endpoint`() {
        val canned = CannedClient("""{"models":[]}""")
        val adapter = GeminiAdapterFactory(canned.client).create(connection(ProviderKind.GEMINI, baseUrl = null))
        assertTrue(runBlocking { adapter.listModels() }.isEmpty())
        val request = canned.requests.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", request.url.toString())
        assertEquals(TEST_KEY, request.header("x-goog-api-key"))
    }

    @Test
    fun `plain http to the internet is refused`() {
        val remote = adapter("http://generativelanguage.googleapis.com/v1beta")
        failsWith<LlmException.BlockedByPolicy> { runBlocking { remote.listModels() } }
        assertEquals(ConnectionStatus.ERROR, runBlocking { remote.checkHealth() }.status)
        assertEquals(0, server.requestCount)
    }
}
