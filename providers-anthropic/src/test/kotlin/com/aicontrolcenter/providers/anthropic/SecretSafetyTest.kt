package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The API key may travel in the request's credential header and nowhere else. */
internal class SecretSafetyTest : MockApiTest() {

    private val request = ChatRequest("claude-opus-4-8", listOf(ChatMessage.user("Hi")))

    /** Everything a log line or crash report could contain about a failure. */
    private fun Throwable.everything(): String {
        val trace = StringWriter().also { printStackTrace(PrintWriter(it)) }.toString()
        val chain = generateSequence(this) { it.cause }.joinToString("\n") { "$it ${it.message} ${it.localizedMessage}" }
        val suppressedOnes = generateSequence(this) { it.cause }.flatMap { it.suppressed.asSequence() }.joinToString("\n")
        return "$trace\n$chain\n$suppressedOnes"
    }

    /** Runs [block] and returns what it wrote to the console, where a careless SDK or adapter would log. */
    private fun consoleOutputOf(block: () -> Unit): String {
        val captured = ByteArrayOutputStream()
        val out = System.out
        val err = System.err
        val stream = PrintStream(captured, true)
        System.setOut(stream)
        System.setErr(stream)
        try {
            block()
        } finally {
            System.setOut(out)
            System.setErr(err)
        }
        return captured.toString()
    }

    @Test
    fun `an error that echoes the key back is reported without it`() {
        // A gateway that repeats the credential in its error text, in the body and in a header.
        enqueueError(401, "authentication_error", "invalid x-api-key: $TEST_KEY is not recognised", mapOf("x-echo" to TEST_KEY))

        val error = assertFailsWith<LlmException.Authentication> { runBlocking { adapter().client.chat(request) } }

        assertFalse(TEST_KEY in error.everything())
        assertTrue("[key removed]" in error.message!!, "the rest of the provider's message is kept")
    }

    @Test
    fun `no failure of any kind carries the key`() {
        val failures = ArrayList<Throwable>()
        fun attempt(block: suspend () -> Unit) {
            runCatching { runBlocking { block() } }.exceptionOrNull()?.let { failures += it }
        }

        enqueueError(429, "rate_limit_error", "slow down, $TEST_KEY")
        attempt { adapter().client.chat(request) }
        enqueueError(400, "invalid_request_error", "bad request from key $TEST_KEY")
        attempt { adapter().client.chatStream(request).toList() }
        enqueueJson("""{"not":"a message","key":"$TEST_KEY"}""")
        attempt { adapter().client.chat(request) }
        enqueueError(500, "api_error", "internal error while handling $TEST_KEY")
        attempt { adapter().listModels() }
        attempt { adapter(baseUrl = "http://127.0.0.1:1").client.chat(request) }
        enqueueSse("error" to """{"type":"error","error":{"type":"api_error","message":"stream broke for $TEST_KEY"}}""")
        attempt { adapter().client.chatStream(request).toList() }

        assertEquals(6, failures.size)
        failures.forEach { failure ->
            assertTrue(failure is LlmException, "unexpected ${failure.javaClass}")
            assertFalse(TEST_KEY in failure.everything(), "${failure.javaClass.simpleName} leaks the key")
        }
    }

    @Test
    fun `health messages, string forms and console output never contain the key`() {
        val adapter = adapter()
        enqueueError(403, "permission_error", "key $TEST_KEY may not list models")
        enqueueJson(messageJson(TEXT_REPLY))

        lateinit var healthMessage: String
        lateinit var responseText: String
        val console = consoleOutputOf {
            healthMessage = assertNotNull(runBlocking { adapter.checkHealth() }.message)
            responseText = runBlocking { adapter.client.chat(request) }.toString()
        }

        assertFalse(TEST_KEY in healthMessage)
        assertFalse(TEST_KEY in responseText)
        assertFalse(TEST_KEY in console)
        assertFalse(TEST_KEY in "$adapter ${adapter.client} ${AnthropicOptions()} ${AnthropicAdapterFactory()}")
        assertTrue("Claude" in adapter.toString(), "the string form names the provider instead")
    }
}
