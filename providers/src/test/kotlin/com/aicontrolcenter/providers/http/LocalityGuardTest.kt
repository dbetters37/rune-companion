package com.aicontrolcenter.providers.http

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.MockServerTest
import com.aicontrolcenter.providers.failsWith
import com.aicontrolcenter.providers.ollama.OllamaAdapterFactory
import com.aicontrolcenter.providers.openai.OpenAiCompatibleAdapterFactory
import com.aicontrolcenter.providers.simpleRequest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** An address that claims the device or the local network is held to it when the connection is made. */
class LocalityGuardTest : MockServerTest() {
    private val ok = """{"model":"m","message":{"role":"assistant","content":"ok"},"done":true,"done_reason":"stop"}"""

    private fun address(text: String): InetAddress = InetAddress.getByName(text)

    /** A resolver that answers [answers] for [name] and fails for everything else. */
    private fun resolver(name: String, vararg answers: InetAddress) = Dns { host ->
        if (host == name) answers.toList() else throw java.net.UnknownHostException(host)
    }

    /** Counts the sockets a client opens, to prove that a refused request never got one. */
    private class CountingSocketFactory : SocketFactory() {
        val created = AtomicInteger()
        private val real = getDefault()
        override fun createSocket(): Socket = real.createSocket().also { created.incrementAndGet() }
        override fun createSocket(host: String?, port: Int): Socket = real.createSocket(host, port).also { created.incrementAndGet() }
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            real.createSocket(host, port, localHost, localPort).also { created.incrementAndGet() }
        override fun createSocket(host: InetAddress?, port: Int): Socket = real.createSocket(host, port).also { created.incrementAndGet() }
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            real.createSocket(address, port, localAddress, localPort).also { created.incrementAndGet() }
    }

    @Test
    fun `a local-network name that resolves to a public address is refused before anything is sent`() {
        val sockets = CountingSocketFactory()
        val shared = OkHttpClient.Builder().dns(resolver("ollama.local", address("203.0.113.7"))).socketFactory(sockets).build()
        val client = OllamaAdapterFactory(shared).create(connection(ProviderKind.OLLAMA, baseUrl = "http://ollama.local:${server.port}")).client

        val error = failsWith<LlmException.Network> { runBlocking { client.chat(simpleRequest("llama3.2")) } }

        assertEquals("ollama.local resolved to an address outside your local network; the request was not sent.", error.message)
        assertEquals(0, sockets.created.get(), "no connection was even opened")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a local-network name that resolves inside the local network is used`() {
        enqueue(ok)
        // The test server listens on loopback, which also counts as inside the local network.
        val shared = OkHttpClient.Builder().dns(resolver("ollama.local", *Dns.SYSTEM.lookup("localhost").toTypedArray())).build()
        val client = OllamaAdapterFactory(shared).create(connection(ProviderKind.OLLAMA, baseUrl = "http://ollama.local:${server.port}")).client

        assertEquals("ok", runBlocking { client.chat(simpleRequest("llama3.2")) }.message.text)
        assertEquals("ollama.local:${server.port}", server.takeRequest().headers["Host"])
    }

    @Test
    fun `every resolved address must match the claimed locality`() {
        val local = LocalityDns(
            Dns { host ->
                when (host) {
                    "nas.lan" -> listOf(address("192.168.1.20"), address("10.1.2.3"), address("172.16.0.9"), address("169.254.3.4"))
                    "tailnet.home.arpa" -> listOf(address("100.64.0.1"), address("100.127.255.254"), address("fd12::1"), address("fe80::1"))
                    "mixed.lan" -> listOf(address("192.168.1.20"), address("8.8.8.8"))
                    "localhost" -> listOf(address("127.0.0.1"), address("::1"))
                    else -> listOf(address("192.168.1.5"))
                }
            },
        )
        assertEquals(4, local.lookup("nas.lan").size)
        assertEquals(4, local.lookup("tailnet.home.arpa").size)
        assertEquals(2, local.lookup("localhost").size)

        val mixed = assertFailsWith<LocalityRefusedException> { local.lookup("mixed.lan") }
        assertEquals(Locality.LOCAL_NETWORK, mixed.claimed)
        // A loopback name must stay on the device; a LAN address is not good enough.
        val onDevice = LocalityDns(resolver("localhost", address("192.168.1.5")))
        val refused = assertFailsWith<LocalityRefusedException> { onDevice.lookup("localhost") }
        assertEquals("localhost resolved to an address outside your device; the request was not sent.", refused.message)
    }

    @Test
    fun `addresses just outside the private ranges are refused for the local network`() {
        for (outside in listOf("172.32.0.1", "172.15.255.255", "100.128.0.1", "100.63.255.255", "11.0.0.1", "2001:db8::1", "fec0::1", "64:ff9b::c0a8:105")) {
            val dns = LocalityDns(resolver("box.local", address(outside)))
            assertFailsWith<LocalityRefusedException>(outside) { dns.lookup("box.local") }
        }
    }

    @Test
    fun `a cloud name is not restricted`() {
        val publicAddresses = listOf(address("203.0.113.7"), address("2001:db8::7"))
        val dns = LocalityDns(Dns { publicAddresses })

        assertEquals(publicAddresses, dns.lookup("api.example.com"))
        assertEquals(Locality.CLOUD, localityOfHost("api.example.com"))
        assertEquals(Locality.ON_DEVICE, localityOfHost("[::1]"))
        assertEquals(Locality.LOCAL_NETWORK, localityOfHost("fd00::5"))
    }

    @Test
    fun `local addresses never use a proxy and cloud addresses use the configured one`() {
        val cloudProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 8080))
        val system = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(cloudProxy)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        val selector = LocalityProxySelector(delegate = system)

        for (local in listOf("http://127.0.0.1:11434/", "http://localhost:8080/v1", "http://nas.local/", "http://192.168.1.5/", "http://[fd00::5]:11434/", "http://100.100.1.1/")) {
            assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI(local)), local)
        }
        assertEquals(listOf(cloudProxy), selector.select(URI("https://api.openai.com/v1")))
        assertEquals(listOf(cloudProxy), LocalityProxySelector(fixed = cloudProxy).select(URI("https://generativelanguage.googleapis.com/")))
        assertEquals(listOf(Proxy.NO_PROXY), LocalityProxySelector(fixed = cloudProxy).select(URI("http://10.0.0.2:1234/")))
    }

    @Test
    fun `the provider client keeps an app proxy for cloud hosts only`() {
        val appProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 3128))
        val derived = providerHttpClient(OkHttpClient.Builder().proxy(appProxy).build(), HttpTimeouts())

        assertNull(derived.proxy, "a fixed proxy would bypass the selector")
        assertEquals(listOf(Proxy.NO_PROXY), derived.proxySelector.select(URI("http://192.168.1.5:11434/")))
        assertEquals(listOf(appProxy), derived.proxySelector.select(URI("https://api.openai.com/")))
        assertTrue(derived.dns is LocalityDns)
        val again = providerHttpClient(derived, HttpTimeouts())
        assertSame(derived.proxySelector, again.proxySelector, "a client built here is not wrapped twice")
        assertTrue((again.dns as LocalityDns).resolver !is LocalityDns)
    }

    @Test
    fun `a request to a local server bypasses the app's proxy`() {
        MockWebServer().use { proxy ->
            proxy.start()
            proxy.enqueue(MockResponse.Builder().code(502).body("the proxy saw the request").build())
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(OK_OPENAI).build())
            val shared = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.hostName, proxy.port))).build()
            val client = OpenAiCompatibleAdapterFactory(shared).create(connection(ProviderKind.OPENAI_COMPATIBLE, baseUrl("/v1"))).client

            assertEquals("hi", runBlocking { client.chat(simpleRequest()) }.message.text)
            assertEquals(0, proxy.requestCount, "the proxy received nothing")
            assertEquals(1, server.requestCount)
        }
    }

    private companion object {
        const val OK_OPENAI = """{"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}"""
    }
}
