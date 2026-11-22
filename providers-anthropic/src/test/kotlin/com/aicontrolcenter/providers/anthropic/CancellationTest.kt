package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderConnection
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cancellation must end the HTTP exchange, not merely stop waiting for it. A raw socket server
 * shows that from the provider's side: it sees the client's connection go away.
 */
internal class CancellationTest {

    /** Accepts one request, optionally starts a response, then waits for the client to hang up. */
    private class HangingServer(private val responsePrefix: String?) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val requestReceived = CountDownLatch(1)
        val clientHungUp = CountDownLatch(1)
        val url: String get() = "http://127.0.0.1:${socket.localPort}"

        @Volatile
        private var accepted: Socket? = null

        private val worker = thread(isDaemon = true) {
            runCatching {
                socket.accept().use { connection ->
                    accepted = connection
                    readRequest(connection.getInputStream())
                    requestReceived.countDown()
                    responsePrefix?.let {
                        connection.getOutputStream().write(it.toByteArray())
                        connection.getOutputStream().flush()
                    }
                    awaitHangUp(connection)
                    clientHungUp.countDown()
                }
            }
        }

        private fun readRequest(input: InputStream) {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().also { check(it >= 0) }.toChar())
            val length = Regex("(?i)content-length: (\\d+)").find(head)!!.groupValues[1].toInt()
            repeat(length) { check(input.read() >= 0) }
        }

        /** A read returns end-of-stream or fails once the peer has closed or reset the connection. */
        private fun awaitHangUp(connection: Socket) {
            runCatching {
                while (connection.getInputStream().read() >= 0) {
                    // Discard whatever the client still sends; only the end of the stream matters.
                }
            }
        }

        /** Also hangs up on a client that is still connected, so a failed test cannot leave a call blocked. */
        override fun close() {
            accepted?.close()
            socket.close()
            worker.join(2_000)
        }
    }

    private val closeables = ArrayList<AutoCloseable>()

    /** Not a child of the test's coroutine: a call that ignores cancellation must fail the test, not hang it. */
    private val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun assertCancelsPromptly(job: Job) {
        assertNotNull(withTimeoutOrNull(5_000) { job.cancelAndJoin() }, "the call did not end when it was cancelled")
    }

    @After
    fun cleanUp() {
        callers.cancel()
        closeables.forEach { it.close() }
    }

    private fun adapterFor(server: HangingServer): ProviderAdapter {
        closeables += server
        val provider = Samples.provider(name = "Claude", kind = ProviderKind.ANTHROPIC, baseUrl = server.url, locality = Locality.CLOUD)
        val adapter = AnthropicAdapterFactory(AnthropicOptions(maxRetries = 0)).create(ProviderConnection(provider, TEST_KEY))
        closeables += AutoCloseable { adapter.close() }
        return adapter
    }

    private val request = ChatRequest("claude-opus-4-8", listOf(ChatMessage.user("Write a long essay.")))

    @Test
    fun `cancelling a chat call that is waiting for the reply closes the connection`() = runBlocking {
        val server = HangingServer(responsePrefix = null)
        val client = adapterFor(server).client

        val job = callers.launch { client.chat(request) }
        assertTrue(server.requestReceived.await(10, TimeUnit.SECONDS), "the request reached the server")

        assertCancelsPromptly(job)

        assertTrue(job.isCancelled)
        assertTrue(server.clientHungUp.await(5, TimeUnit.SECONDS), "the server saw the client go away")
    }

    @Test
    fun `cancelling the collection of a stream closes the connection`() = runBlocking {
        val events = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\"," +
            "\"role\":\"assistant\",\"model\":\"claude-opus-4-8\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null," +
            "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}\n\n" +
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Once\"}}\n\n"
        // No Content-Length and no chunking: the body runs until the connection closes, like a live stream.
        val server = HangingServer("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n$events")
        val client = adapterFor(server).client
        val firstChunk = CompletableDeferred<ChatChunk>()

        val job = callers.launch {
            client.chatStream(request).collect { firstChunk.complete(it) }
        }
        assertEquals(ChatChunk.Text("Once"), withTimeout(10_000) { firstChunk.await() })

        assertCancelsPromptly(job)

        assertTrue(server.clientHungUp.await(5, TimeUnit.SECONDS), "the server saw the client go away")
    }

    @Test
    fun `a collector that stops after the first chunk also closes the connection`() = runBlocking {
        val events = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\"," +
            "\"role\":\"assistant\",\"model\":\"claude-opus-4-8\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null," +
            "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}\n\n" +
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Once\"}}\n\n"
        val server = HangingServer("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n$events")

        val client = adapterFor(server).client

        val first = callers.async { client.chatStream(request).first() }

        assertIs<ChatChunk.Text>(assertNotNull(withTimeoutOrNull(10_000) { first.await() }, "taking one chunk did not end the stream"))
        assertTrue(server.clientHungUp.await(5, TimeUnit.SECONDS), "the server saw the client go away")
    }
}
