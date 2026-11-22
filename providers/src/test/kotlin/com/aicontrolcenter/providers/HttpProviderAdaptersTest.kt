package com.aicontrolcenter.providers

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.gemini.GeminiAdapter
import com.aicontrolcenter.providers.http.HttpTimeouts
import com.aicontrolcenter.providers.ollama.OllamaAdapter
import com.aicontrolcenter.providers.openai.OpenAiCompatibleAdapter
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The module's entry point: one factory per provider kind, sharing the app's HTTP client. */
class HttpProviderAdaptersTest : MockServerTest() {

    @Test
    fun `there is one factory for each http provider kind`() {
        val factories = HttpProviderAdapters.factories()
        assertEquals(listOf(ProviderKind.OPENAI_COMPATIBLE, ProviderKind.OLLAMA, ProviderKind.GEMINI), factories.map { it.kind })
        val adapters = factories.map { it.create(connection(it.kind)) }
        assertIs<OpenAiCompatibleAdapter>(adapters[0])
        assertIs<OllamaAdapter>(adapters[1])
        assertIs<GeminiAdapter>(adapters[2])
        adapters.forEach { it.close() }
    }

    @Test
    fun `each adapter answers a health check and a chat call against its own protocol`() {
        val replies = mapOf(
            ProviderKind.OPENAI_COMPATIBLE to listOf(
                """{"data":[{"id":"m"}]}""",
                """{"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}""",
            ),
            ProviderKind.OLLAMA to listOf(
                """{"version":"0.12.6"}""",
                """{"model":"m","message":{"role":"assistant","content":"hi"},"done":true,"done_reason":"stop"}""",
            ),
            ProviderKind.GEMINI to listOf(
                """{"models":[]}""",
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"hi"}]},"finishReason":"STOP"}]}""",
            ),
        )
        for (factory in HttpProviderAdapters.factories()) {
            val adapter = factory.create(connection(factory.kind))
            replies.getValue(factory.kind).forEach { enqueue(it) }
            assertEquals(ConnectionStatus.ONLINE, runBlocking { adapter.checkHealth() }.status, "${factory.kind}")
            assertEquals("hi", runBlocking { adapter.client.chat(simpleRequest("m")) }.message.text, "${factory.kind}")
        }
    }

    @Test
    fun `a shared client is used by all three and keeps its interceptors`() {
        val seen = AtomicInteger()
        val shared = OkHttpClient.Builder().addInterceptor { chain ->
            seen.incrementAndGet()
            chain.proceed(chain.request())
        }.build()
        for (factory in HttpProviderAdapters.factories(shared)) {
            enqueue("{}", 500)
            runBlocking { factory.create(connection(factory.kind)).checkHealth() }
        }
        assertEquals(3, seen.get())
    }

    @Test
    fun `the given timeouts apply to every adapter`() {
        for (factory in HttpProviderAdapters.factories(timeouts = HttpTimeouts(idleMillis = 200))) {
            server.enqueue(MockResponse.Builder().body("{}").headersDelay(5, TimeUnit.SECONDS).build())
            val error = failsWith<LlmException.Timeout> { runBlocking { factory.create(connection(factory.kind)).client.chat(simpleRequest("m")) } }
            assertTrue(error.retryable, "${factory.kind}")
        }
    }
}
