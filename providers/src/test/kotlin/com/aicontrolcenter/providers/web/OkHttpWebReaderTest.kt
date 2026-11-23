package com.aicontrolcenter.providers.web

import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.operator.WebReadLimits
import com.aicontrolcenter.core.operator.WebReadResult
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.providers.MockServerTest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web reader against a local server. The server listens on loopback, which a web read must
 * never reach, so these tests give it public names: [dns] answers documentation addresses
 * (TEST-NET, public as far as the policy is concerned) and [ServerPlaysEveryHost] connects every
 * socket to the server while reporting the address OkHttp asked for. [HonestSockets] connects to
 * the server and reports where it really is, as a lying resolver would leave things.
 */
class OkHttpWebReaderTest : MockServerTest() {
    private val public1: InetAddress = InetAddress.getByName("203.0.113.10")
    private val public2: InetAddress = InetAddress.getByName("198.51.100.7")

    /** Answers per name; a name with several entries answers them in turn (the last one from then on). */
    private val answers = ConcurrentHashMap<String, List<List<InetAddress>>>()
    private val lookups = CopyOnWriteArrayList<String>()
    private val dns = Dns { host ->
        val count = lookups.count { it == host }
        lookups += host
        val all = answers[host] ?: throw UnknownHostException(host)
        all[minOf(count, all.size - 1)]
    }

    private val sent = CopyOnWriteArrayList<EgressInfo>()
    private val sockets = ServerPlaysEveryHost()

    private fun resolve(host: String, vararg addresses: InetAddress) {
        answers[host] = listOf(addresses.toList())
    }

    private fun reader(socketFactory: SocketFactory = sockets, own: Set<InetAddress> = emptySet(), client: OkHttpClient.Builder = OkHttpClient.Builder()) =
        OkHttpWebReader(client.dns(dns).socketFactory(socketFactory).build(), { own }, { sent += it })

    private fun read(url: String, limits: WebReadLimits = WebReadLimits(), reader: OkHttpWebReader = reader()) = runBlocking { reader.read(url, limits) }

    private fun url(host: String, path: String = "/") = "http://$host:${server.port}$path"

    private fun page(body: String, contentType: String? = "text/html; charset=utf-8", code: Int = 200): MockResponse =
        MockResponse.Builder().code(code).apply { contentType?.let { addHeader("Content-Type", it) } }.body(body).build()

    private fun bytes(body: ByteArray, contentType: String?): MockResponse =
        MockResponse.Builder().apply { contentType?.let { addHeader("Content-Type", it) } }.body(Buffer().write(body)).build()

    private fun redirect(location: String, code: Int = 302, vararg headers: Pair<String, String>): MockResponse =
        MockResponse.Builder().code(code).addHeader("Location", location).apply { headers.forEach { (n, v) -> addHeader(n, v) } }.build()

    /** Connects every socket to the test server, and reports the address OkHttp asked for as the peer. */
    private inner class ServerPlaysEveryHost : SocketFactory() {
        val attempted: MutableList<InetAddress> = CopyOnWriteArrayList()

        override fun createSocket(): Socket = object : Socket() {
            @Volatile private var asked: InetAddress? = null

            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val address = (endpoint as InetSocketAddress).address
                attempted += address
                asked = address
                super.connect(server.socketAddress, timeout)
            }

            override fun getInetAddress(): InetAddress? = asked ?: super.getInetAddress()
        }

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }

    /** Connects every socket to the test server and leaves its address as it is: loopback. */
    private inner class HonestSockets : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) = super.connect(server.socketAddress, timeout)
        }

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }

    @Test
    fun `a public page is read with a plain GET that carries no cookies, credentials or identifying headers`() {
        resolve("news.example", public1)
        server.enqueue(page("<html><body>Café, \"today\" ✓</body></html>"))

        val result = read(url("news.example", "/today?lang=en"))

        assertEquals(
            WebReadResult.Page(
                finalUrl = url("news.example", "/today?lang=en"), status = 200, contentType = "text/html; charset=utf-8",
                body = "<html><body>Café, \"today\" ✓</body></html>", truncated = false, hostsContacted = listOf("news.example"),
            ),
            result,
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/today?lang=en", request.target)
        assertEquals(OkHttpWebReader.USER_AGENT, request.headers["User-Agent"])
        for (name in listOf("Cookie", "Authorization", "Proxy-Authorization", "Accept-Language")) {
            assertNull(request.headers[name], name)
        }
        assertEquals(listOf(public1), sockets.attempted)
        val egress = sent.single()
        assertEquals(EgressInfo("news.example", Locality.CLOUD, purpose = WebEgress.PURPOSE, approxBytes = egress.approxBytes), egress)
        assertTrue(egress.approxBytes!! > 40, "${egress.approxBytes}")
        assertEquals(listOf(WebEgress.of("news.example")), WebEgress.forPage(result as WebReadResult.Page))
    }

    @Test
    fun `literal addresses of this device and its networks are refused without a lookup or a connection`() {
        val refused = listOf(
            url("127.0.0.1"), url("[::1]"), url("0.0.0.0"), url("[::ffff:127.0.0.1]"), "http://10.0.0.8/", "http://172.16.4.1/",
            "http://192.168.1.1/admin", "http://169.254.169.254/latest/meta-data/", "http://100.64.0.1/", "http://[fd00::1]/",
            "http://[fe80::1]/", "http://224.0.0.1/", "http://255.255.255.255/",
        )
        for (address in refused) {
            assertIs<WebReadResult.Refused>(read(address), address)
        }
        // Forms that systems read in different ways are not read at all.
        for (address in listOf(url("2130706433"), url("127.1"), url("0177.0.0.1"), url("1.2.3.4.5"))) {
            assertEquals(WebReadResult.Refused(AddressGuard.NOT_AN_ADDRESS), read(address), address)
        }
        assertEquals(0, server.requestCount)
        assertTrue(sockets.attempted.isEmpty(), "${sockets.attempted}")
        assertTrue(lookups.isEmpty(), "$lookups")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a name that resolves to this device or the local network is refused before a connection is made`() {
        resolve("intranet.example", InetAddress.getByName("192.168.1.20"))
        resolve("localhost", InetAddress.getByName("127.0.0.1"))
        resolve("v6.example", InetAddress.getByName("fd12::5"))
        // One private answer among public ones refuses the name: it is how DNS rebinding starts.
        resolve("mixed.example", public1, InetAddress.getByName("10.0.0.1"))
        resolve("self.example", public2)

        for (host in listOf("intranet.example", "localhost", "v6.example", "mixed.example")) {
            val result = read(url(host))
            assertIs<WebReadResult.Refused>(result, host)
            assertTrue("not read" in result.reason, result.reason)
        }
        // This device's own public address counts as this device.
        assertEquals(
            WebReadResult.Refused("Pages on this device are not read: only public web addresses are."),
            read(url("self.example"), reader = reader(own = setOf(public2))),
        )
        assertEquals(0, server.requestCount)
        assertTrue(sockets.attempted.isEmpty(), "${sockets.attempted}")
    }

    @Test
    fun `a redirect to the loopback address is refused and nothing is fetched there`() {
        resolve("news.example", public1)
        server.enqueue(redirect(url("127.0.0.1", "/admin")))
        server.enqueue(page("secret"))

        val result = read(url("news.example"))

        assertIs<WebReadResult.Refused>(result)
        assertEquals(1, server.requestCount)
        assertEquals("/", server.takeRequest().target)
        assertEquals(listOf(public1), sockets.attempted)
        // The first request did leave the device, and is reported.
        assertEquals(listOf("news.example"), sent.map { it.destinationHost })
    }

    @Test
    fun `a redirect to a name that resolves to the local network is refused`() {
        resolve("news.example", public1)
        resolve("router.example", InetAddress.getByName("192.168.0.1"))
        server.enqueue(redirect(url("router.example", "/config")))

        assertIs<WebReadResult.Refused>(read(url("news.example")))
        assertEquals(1, server.requestCount)
        assertEquals(listOf(public1), sockets.attempted)
    }

    @Test
    fun `a name that rebinds to a private address for the next connection is refused`() {
        // Public for the first lookup, the home network for every later one.
        answers["news.example"] = listOf(listOf(public1), listOf(InetAddress.getByName("192.168.1.1")))
        server.enqueue(redirect("/next", 302, "Connection" to "close"))
        server.enqueue(page("from the router"))

        assertIs<WebReadResult.Refused>(read(url("news.example")))
        assertEquals(1, server.requestCount)
        assertEquals(listOf("news.example", "news.example"), lookups)
        assertEquals(listOf(public1), sockets.attempted)
    }

    @Test
    fun `the address a socket really reached is checked before the request is written`() {
        // The resolver says public, but the connection ends up on loopback.
        resolve("news.example", public1)
        server.enqueue(page("should not be read"))

        val result = read(url("news.example"), reader = reader(socketFactory = HonestSockets()))

        assertEquals(WebReadResult.Refused("Pages on this device (loopback) are not read: only public web addresses are."), result)
        assertEquals(0, server.requestCount)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `every connection attempt is checked before its socket connects, and only direct routes are allowed`() {
        val guard = ConnectGuard(AddressGuard(emptySet()))
        val call = OkHttpClient().newCall(Request.Builder().url("http://news.example/").build())
        val loopback = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 80)
        val public = InetSocketAddress(public1, 80)
        assertFailsWith<RefusedAddressException> { guard.connectStart(call, loopback, Proxy.NO_PROXY) }
        assertFailsWith<RefusedAddressException> { guard.connectStart(call, public, Proxy(Proxy.Type.HTTP, public)) }
        assertFailsWith<RefusedAddressException> { guard.connectStart(call, InetSocketAddress.createUnresolved("news.example", 80), Proxy.NO_PROXY) }
        guard.connectStart(call, public, Proxy.NO_PROXY)
    }

    @Test
    fun `the resolver guard refuses a name if any of its addresses is refused, on its own`() {
        // Each guard is checked alone here: end to end, the next layer would catch what one misses.
        resolve("mixed.example", public1, InetAddress.getByName("10.0.0.1"))
        resolve("news.example", public1, public2)
        val guarded = GuardedDns(dns, AddressGuard(emptySet()))
        val refused = assertFailsWith<RefusedAddressException> { guarded.lookup("mixed.example") }
        assertEquals("Pages on a private network address are not read: only public web addresses are.", refused.reason)
        assertEquals(listOf(public1, public2), guarded.lookup("news.example"))
        assertFailsWith<RefusedAddressException> { GuardedDns(dns, AddressGuard(setOf(public2))).lookup("news.example") }
    }

    @Test
    fun `only http and https addresses are read`() {
        val other = listOf("file:///etc/passwd", "ftp://news.example/file", "javascript:alert(1)", "data:text/html,<b>hi</b>", "content://media/1")
        for (address in other) {
            assertEquals(WebReadResult.Refused("Only http and https pages can be read."), read(address), address)
        }
        for (address in listOf("", "news.example/page", "http://", "http://bad host/")) {
            assertEquals(WebReadResult.Refused("This is not a web address that can be read."), read(address), address)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a redirect to another scheme or to an address with credentials is not followed`() {
        resolve("news.example", public1)
        server.enqueue(redirect("file:///etc/passwd"))
        assertEquals(
            WebReadResult.Refused("The page redirected to an address that is not http or https, which was not followed."),
            read(url("news.example")),
        )
        server.enqueue(redirect("http://admin:secret@news.example:${server.port}/"))
        assertEquals(WebReadResult.Refused("Addresses with a user name or password are not read."), read(url("news.example")))
        assertEquals(WebReadResult.Refused("Addresses with a user name or password are not read."), read("http://me:pw@news.example:${server.port}/"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `redirects are followed across hosts, without cookies, up to the limit`() {
        resolve("news.example", public1)
        resolve("cdn.example", public2)
        server.enqueue(redirect(url("cdn.example", "/a"), 301, "Set-Cookie" to "session=abc; Path=/"))
        server.enqueue(redirect("/b", 307))
        server.enqueue(page("final", "text/plain"))

        val result = read(url("news.example"))

        assertEquals(
            WebReadResult.Page(url("cdn.example", "/b"), 200, "text/plain", "final", truncated = false, hostsContacted = listOf("news.example", "cdn.example")),
            result,
        )
        assertEquals(listOf(public1, public2), sockets.attempted.distinct())
        repeat(3) { assertNull(server.takeRequest().headers["Cookie"]) }
        assertEquals(listOf("news.example", "cdn.example", "cdn.example"), sent.map { it.destinationHost })

        repeat(3) { server.enqueue(redirect("/again")) }
        assertEquals(
            WebReadResult.Failed("The page redirected more than 2 times; the last redirect was not followed."),
            read(url("news.example"), WebReadLimits(maxRedirects = 2)),
        )
        server.enqueue(redirect("/elsewhere"))
        assertIs<WebReadResult.Failed>(read(url("news.example"), WebReadLimits(maxRedirects = 0)))
        assertEquals(3 + 3 + 1, server.requestCount)
    }

    @Test
    fun `a proxy set on the given client is not used`() {
        resolve("news.example", public1)
        server.enqueue(page("direct"))
        val viaProxy = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(public2, 3128)))

        assertIs<WebReadResult.Page>(read(url("news.example"), reader = reader(client = viaProxy)))
        // A proxied request names the whole URL; a direct one only the path.
        assertEquals("/", server.takeRequest().target)
        assertEquals(listOf(public1), sockets.attempted)
    }

    @Test
    fun `the body is cut at the size limit without splitting a character`() {
        resolve("news.example", public1)
        server.enqueue(page("a".repeat(9) + "é" + "zzz", "text/plain; charset=utf-8"))
        val cut = read(url("news.example"), WebReadLimits(maxBytes = 10)) as WebReadResult.Page
        assertEquals("a".repeat(9), cut.body)
        assertTrue(cut.truncated)

        server.enqueue(page("0123456789", "text/plain"))
        val exact = read(url("news.example"), WebReadLimits(maxBytes = 10)) as WebReadResult.Page
        assertEquals("0123456789", exact.body)
        assertTrue(!exact.truncated)

        server.enqueue(page("x".repeat(3_000_000), "text/plain"))
        val large = read(url("news.example")) as WebReadResult.Page
        assertEquals(WebReadLimits().maxBytes.toInt(), large.body.length)
        assertTrue(large.truncated)
    }

    @Test
    fun `a server that does not answer in time fails the read within the limit`() {
        resolve("news.example", public1)
        server.enqueue(page("late").newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
        val started = System.nanoTime()
        val result = read(url("news.example"), WebReadLimits(timeoutMs = 400))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertIs<WebReadResult.Failed>(result)
        assertTrue(tookMs < 5_000, "took $tookMs ms")

        server.enqueue(page("slow body").newBuilder().bodyDelay(10, TimeUnit.SECONDS).build())
        assertIs<WebReadResult.Failed>(read(url("news.example"), WebReadLimits(timeoutMs = 400)))
    }

    @Test
    fun `a name that does not resolve is a failure, not a refusal`() {
        assertEquals(WebReadResult.Failed("The address of nowhere.example could not be found."), read(url("nowhere.example")))
    }

    @Test
    fun `only text is read, whatever the status`() {
        resolve("news.example", public1)
        server.enqueue(bytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0), "image/png"))
        assertEquals(WebReadResult.Refused("The page is image/png, not text, so it was not read."), read(url("news.example")))
        server.enqueue(bytes(byteArrayOf(1, 2, 0, 3), "application/octet-stream"))
        assertIs<WebReadResult.Refused>(read(url("news.example")))
        server.enqueue(bytes(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()), null))
        assertEquals(
            WebReadResult.Refused("The page has no text content type and does not look like text, so it was not read."),
            read(url("news.example")),
        )
        for (type in listOf("application/json", "application/rss+xml", "application/ld+json", "text/csv", "application/xhtml+xml")) {
            server.enqueue(page("{\"ok\":true}", type))
            assertEquals("{\"ok\":true}", (read(url("news.example")) as WebReadResult.Page).body, type)
        }
        server.enqueue(page("plain words", contentType = null))
        assertEquals(WebReadResult.Page(url("news.example"), 200, null, "plain words", false, listOf("news.example")), read(url("news.example")))
        server.enqueue(page("<h1>Not found</h1>", code = 404))
        assertEquals(404, (read(url("news.example")) as WebReadResult.Page).status)
    }

    @Test
    fun `text is decoded in the charset the page declares`() {
        resolve("news.example", public1)
        server.enqueue(bytes(byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte(), 0x80.toByte()), "text/plain; charset=ISO-8859-1"))
        assertEquals("café€", (read(url("news.example")) as WebReadResult.Page).body)
        val html = "<html><head><meta charset=\"windows-1252\"></head><body>naïve</body></html>".toByteArray(Charsets.ISO_8859_1)
        server.enqueue(bytes(html, "text/html"))
        assertEquals("<html><head><meta charset=\"windows-1252\"></head><body>naïve</body></html>", (read(url("news.example")) as WebReadResult.Page).body)
    }

    @Test
    fun `plain dotted addresses are the only literal IPv4 form accepted`() {
        assertEquals(listOf(1, 2, 3, 4), AddressGuard.dottedQuad("1.2.3.4")?.map { it.toInt() and 0xFF })
        assertEquals(listOf(255, 0, 0, 9), AddressGuard.dottedQuad("255.0.0.9")?.map { it.toInt() and 0xFF })
        for (text in listOf("01.2.3.4", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1..3.4", "2130706433", "1.2.3.1000")) {
            assertNull(AddressGuard.dottedQuad(text), text)
        }
    }

    @Test
    fun `nothing carries over between reads`() {
        resolve("news.example", public1)
        server.enqueue(page("one").newBuilder().addHeader("Set-Cookie", "id=1").build())
        server.enqueue(page("two"))
        val reader = reader()
        read(url("news.example"), reader = reader)
        read(url("news.example"), reader = reader)
        server.takeRequest()
        val second = server.takeRequest()
        assertNull(second.headers["Cookie"])
        // Each read opens its own connection: one read's connection is never handed to the next.
        assertEquals(1, second.connectionIndex)
        assertEquals(0, second.exchangeIndex)
    }
}
