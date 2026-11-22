package com.aicontrolcenter.providers

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.registry.ProviderKind
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The API key is used for the credential header and nowhere else: not in the address, not in
 * what an adapter prints, and not in any failure it reports, even when the server echoes it.
 */
class KeySecrecyTest : MockServerTest() {
    private val factories = HttpProviderAdapters.factories().associateBy { it.kind }

    private fun adapter(kind: ProviderKind): ProviderAdapter = factories.getValue(kind).create(connection(kind))

    private val kinds = listOf(ProviderKind.OPENAI_COMPATIBLE, ProviderKind.OLLAMA, ProviderKind.GEMINI)

    private fun assertClean(text: String?, what: String) = assertFalse(TEST_KEY in text.orEmpty(), "$what contains the key: $text")

    @Test
    fun `the key travels only in the credential header`() {
        val expected = mapOf(
            ProviderKind.OPENAI_COMPATIBLE to ("Authorization" to "Bearer $TEST_KEY"),
            ProviderKind.OLLAMA to ("Authorization" to "Bearer $TEST_KEY"),
            ProviderKind.GEMINI to ("x-goog-api-key" to TEST_KEY),
        )
        for (kind in kinds) {
            enqueue("{}", 500)
            runCatching { runBlocking { adapter(kind).client.chat(simpleRequest()) } }
            val request = server.takeRequest()
            val (header, value) = expected.getValue(kind)
            assertEquals(value, request.headers[header], "$kind")
            assertClean(request.url.toString(), "the address used for $kind")
            assertClean(request.body!!.utf8(), "the body sent for $kind")
            assertEquals(1, request.headers.count { (_, sent) -> TEST_KEY in sent }, "$kind sent the key in more than one header")
            if (kind == ProviderKind.GEMINI) assertNull(request.headers["Authorization"])
        }
    }

    @Test
    fun `nothing an adapter prints contains the key`() {
        for (kind in kinds) {
            val connection = connection(kind)
            val factory = factories.getValue(kind)
            val adapter = factory.create(connection)
            for (printed in listOf(connection, factory, adapter, adapter.client)) assertClean(printed.toString(), "toString of ${printed.javaClass.simpleName}")
            assertTrue("Test provider" in adapter.toString(), "an adapter is identified by its provider's name")
        }
    }

    @Test
    fun `a key echoed by the server never reaches an exception or a health message`() {
        val echo = """{"error":{"code":401,"message":"Invalid credentials: Bearer $TEST_KEY / x-goog-api-key: $TEST_KEY","status":"UNAUTHENTICATED"}}"""
        for (kind in kinds) {
            enqueue(echo, 401)
            val chatError = failsWith<LlmException.Authentication> { runBlocking { adapter(kind).client.chat(simpleRequest()) } }
            assertClean(chatError.stackTraceToString(), "the chat failure of $kind")

            enqueue(echo, 401)
            val streamError = failsWith<LlmException.Authentication> { adapter(kind).client.chatStream(simpleRequest()).collectBlocking() }
            assertClean(streamError.stackTraceToString(), "the stream failure of $kind")

            enqueue(echo, 401)
            val listError = failsWith<LlmException.Authentication> { runBlocking { adapter(kind).listModels() } }
            assertClean(listError.stackTraceToString(), "the listing failure of $kind")

            enqueue(echo, 401)
            assertClean(runBlocking { adapter(kind).checkHealth() }.message, "the health message of $kind")
        }
    }

    @Test
    fun `a key echoed in plain text or in a broken reply is not repeated either`() {
        for (kind in kinds) {
            enqueue("upstream rejected header Authorization: Bearer $TEST_KEY", 502)
            val plain = failsWith<LlmException.Server> { runBlocking { adapter(kind).client.chat(simpleRequest()) } }
            assertClean(plain.stackTraceToString(), "the plain-text failure of $kind")

            enqueue("""{"debug":"$TEST_KEY" """)
            val broken = failsWith<LlmException.MalformedResponse> { runBlocking { adapter(kind).client.chat(simpleRequest()) } }
            assertClean(broken.stackTraceToString(), "the malformed-reply failure of $kind")

            server.enqueue(MockResponse.Builder().body("{\"x\":\"$TEST_KEY\"" + " ".repeat(4096)).onResponseBody(SocketEffect.CloseSocket()).build())
            val dropped = failsWith<LlmException.Network> { runBlocking { adapter(kind).client.chat(simpleRequest()) } }
            assertClean(dropped.stackTraceToString(), "the network failure of $kind")
        }
    }
}
