package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderConnection
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where the Claude adapter's requests can go: never to a redirect target, and only where a local address claims. */
internal class TransportSafetyTest : MockApiTest() {
    /** A followed redirect to an address with nothing queued would wait forever; this turns that into a failure. */
    @get:Rule
    val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)

    private val request = ChatRequest("claude-opus-5-5", listOf(ChatMessage.user("PRIVATE PROMPT TEXT")))
    private val others = ArrayList<AutoCloseable>()

    @After
    fun closeOthers() = others.forEach { it.close() }

    private fun otherServer(address: String = "127.0.0.1"): MockWebServer =
        MockWebServer().also { it.start(InetAddress.getByName(address), 0); others += it }

    private fun adapterAt(baseUrl: String, resolver: Dns): ProviderAdapter {
        val provider = Samples.provider(name = "Claude", kind = ProviderKind.ANTHROPIC, baseUrl = baseUrl, locality = Locality.LOCAL_NETWORK)
        return AnthropicAdapter(ProviderConnection(provider, TEST_KEY), AnthropicOptions(maxRetries = 0), resolver).also { others += AutoCloseable { it.close() } }
    }

    private fun resolver(name: String, vararg answers: InetAddress) = Dns { host ->
        if (host == name) answers.toList() else throw UnknownHostException(host)
    }

    @Test
    fun `a redirect to another host is not followed and that host receives nothing`() {
        val target = otherServer()
        target.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(messageJson(TEXT_REPLY)).build())
        server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "http://127.0.0.1:${target.port}/v1/messages?token=abc123").build())

        // The server is addressed as "localhost", so 127.0.0.1 is another host to the HTTP client.
        val error = assertFailsWith<LlmException.InvalidRequest> { runBlocking { adapter().client.chat(request) } }

        assertEquals(0, target.requestCount, "the redirect target received nothing")
        assertEquals(1, server.requestCount)
        assertEquals(307, error.status)
        assertFalse(error.retryable)
        val message = error.message!!
        assertTrue("redirect" in message && "127.0.0.1" in message, message)
        assertFalse("/v1/messages" in message || "token" in message || "abc123" in message, "only the host is named: $message")
    }

    @Test
    fun `no redirect status is followed, streaming included`() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            server.enqueue(MockResponse.Builder().code(status).addHeader("Location", "/elsewhere").build())
            val error = assertFailsWith<LlmException.InvalidRequest>("HTTP $status") { runBlocking { adapter().client.chat(request) } }
            assertTrue("same server" in error.message!!, error.message)
        }
        server.enqueue(MockResponse.Builder().code(308).addHeader("Location", "https://elsewhere.example/v1/messages").build())
        val streamed = assertFailsWith<LlmException.InvalidRequest> { runBlocking { adapter().client.chatStream(request).collect { } } }
        assertTrue("elsewhere.example" in streamed.message!!, streamed.message)
        assertEquals(6, server.requestCount, "one request per call, none to a redirect target")
    }

    @Test
    fun `a local-network name that resolves to a public address is refused before anything is sent`() {
        val adapter = adapterAt("http://claude.local:${server.port}", resolver("claude.local", InetAddress.getByName("203.0.113.7")))

        val error = assertFailsWith<LlmException.Network> { runBlocking { adapter.client.chat(request) } }

        assertEquals("claude.local resolved to an address outside your local network; the request was not sent.", error.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a local-network name that resolves inside the local network is used`() {
        enqueueJson(messageJson(TEXT_REPLY))
        val adapter = adapterAt("http://claude.local:${server.port}", resolver("claude.local", *Dns.SYSTEM.lookup("localhost").toTypedArray()))

        assertEquals("Hello there.", runBlocking { adapter.client.chat(request) }.message.text)
        assertEquals("claude.local:${server.port}", takeRequest().headers["Host"])
    }

    @Test
    fun `a dual-stack name is reached through its local addresses only`() {
        val lan = InetAddress.getByName("192.168.1.10")
        val uniqueLocal = InetAddress.getByName("fd12:3456::10")
        val global = InetAddress.getByName("2a02:8070::10")
        assertEquals(listOf(lan, uniqueLocal), LocalityDns(resolver("claude.lan", lan, uniqueLocal, global)).lookup("claude.lan"))

        // End to end, with this machine standing in for the LAN address.
        enqueueJson(messageJson(TEXT_REPLY))
        val loopback = Dns.SYSTEM.lookup("localhost")
        val adapter = adapterAt("http://claude.lan:${server.port}", resolver("claude.lan", global, *loopback.toTypedArray()))

        assertEquals("Hello there.", runBlocking { adapter.client.chat(request) }.message.text)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a local-network name with only public addresses is still refused`() {
        val publicOnly = resolver("claude.lan", InetAddress.getByName("2a02:8070::10"), InetAddress.getByName("8.8.8.8"))
        val adapter = adapterAt("http://claude.lan:${server.port}", publicOnly)

        val error = assertFailsWith<LlmException.Network> { runBlocking { adapter.client.chat(request) } }

        assertEquals("claude.lan resolved to an address outside your local network; the request was not sent.", error.message)
        assertEquals(0, server.requestCount)
        assertFailsWith<LocalityRefusedException> { LocalityDns(publicOnly).lookup("claude.lan") }
    }

    @Test
    fun `a system proxy is not used for an address on this device`() {
        val proxy = otherServer()
        proxy.enqueue(MockResponse.Builder().code(502).body("the proxy saw the request").build())
        enqueueJson(messageJson(TEXT_REPLY))
        val everythingViaProxy = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.hostName, proxy.port)))
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(everythingViaProxy)
        try {
            assertEquals("Hello there.", runBlocking { adapter().client.chat(request) }.message.text)
        } finally {
            ProxySelector.setDefault(previous)
        }
        assertEquals(0, proxy.requestCount, "the proxy received nothing")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the request still carries the key, the body and the SDK's headers`() {
        enqueueJson(messageJson(TEXT_REPLY))
        runBlocking { adapter().client.chat(request) }

        val sent = takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/v1/messages", sent.url.encodedPath)
        assertTrue(sent.headers["x-api-key"] == TEST_KEY || sent.headers["Authorization"] == "Bearer $TEST_KEY")
        assertTrue(sent.headers["anthropic-version"]!!.isNotBlank())
        assertTrue(sent.headers["X-Stainless-Timeout"]!!.toLong() > 0)
        assertTrue("PRIVATE PROMPT TEXT" in sent.body!!.utf8())
    }
}
