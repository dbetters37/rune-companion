package com.aicontrolcenter.providers.web

import com.aicontrolcenter.core.operator.WebAddressPolicy
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.SocketException
import java.net.UnknownHostException

/*
 * Where a web read may connect: public addresses only ([WebAddressPolicy]), checked at every
 * point where OkHttp could reach an address, so that neither a redirect, nor a name that
 * resolves to this device or the local network, nor a name whose answer changes between two
 * lookups (DNS rebinding), nor a literal address in a URL can make the app fetch something
 * from inside the owner's network:
 *
 *  1. [literalRefusal]: a URL whose host is a literal address, before anything else; OkHttp
 *     connects to literal addresses without asking the resolver.
 *  2. [GuardedDns]: every address a name resolves to; one refused address refuses the name.
 *  3. [ConnectGuard]: the address of every connection attempt, before its socket connects.
 *  4. [PeerGuard]: the address the connected socket actually reached, before the request is
 *     written to it, on every hop, a reused connection included.
 *
 * Connections are direct (the reader sets no proxy), so the address checked is the address
 * the request goes to.
 */

/**
 * Why a connection was refused. An [UnknownHostException] (an IOException) so OkHttp treats it
 * like a failed lookup or connection: no request is written, and the call fails with it.
 */
internal class RefusedAddressException(val host: String, val reason: String) : UnknownHostException("$host: $reason")

/** [WebAddressPolicy] with this device's own addresses, read once per page read. */
internal class AddressGuard(private val ownAddresses: Set<InetAddress>) {
    fun refusal(address: InetAddress): String? = WebAddressPolicy.refusal(address, ownAddresses)

    fun check(host: String, address: InetAddress) {
        refusal(address)?.let { throw RefusedAddressException(host, it) }
    }

    /**
     * Why [url]'s host is refused before anything is looked up, or null if it is a name to resolve
     * or a public address. The same literal check as the `web.read_page` tool's, before it is
     * authorised (Phase 6, D8: [WebAddressPolicy.literalRefusal], which also refuses `localhost`,
     * local and dotless names, IPv4-mapped, IPv4-compatible, 6to4, NAT64, ISATAP and Teredo forms
     * and numbers in unusual forms), then this device's own addresses for a literal one.
     */
    fun literalRefusal(url: HttpUrl): String? {
        val host = url.host
        WebAddressPolicy.literalRefusal(host)?.let { return it }
        val address = when {
            ':' in host -> try {
                // A colon makes this an IPv6 literal, which is parsed without any lookup.
                InetAddress.getByName(host)
            } catch (e: UnknownHostException) {
                return NOT_AN_ADDRESS
            }
            NUMERIC.matches(host) -> dottedQuad(host)?.let(InetAddress::getByAddress) ?: return NOT_AN_ADDRESS
            else -> return null
        }
        return refusal(address)
    }

    companion object {
        /** What OkHttp takes for an IPv4 literal; it would connect to it without asking the resolver. */
        private val NUMERIC = Regex("[0-9.]+")

        const val NOT_AN_ADDRESS = "The address is not a usual web address, so it is not read."

        /**
         * The four bytes of a plain `a.b.c.d` address, or null for anything else. Shortened and
         * single-number forms (`127.1`, `2130706433`) and leading zeros, which systems read in
         * different ways, are not accepted: no public page is addressed like that.
         */
        internal fun dottedQuad(text: String): ByteArray? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for ((i, part) in parts.withIndex()) {
                if (part.isEmpty() || part.length > 3 || (part.length > 1 && part[0] == '0') || part.any { it !in '0'..'9' }) return null
                val value = part.toInt()
                if (value > 255) return null
                bytes[i] = value.toByte()
            }
            return bytes
        }
    }
}

/** Resolves with [resolver] and refuses a name if any address it resolves to is refused. */
internal class GuardedDns(val resolver: Dns, private val guard: AddressGuard) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = resolver.lookup(hostname)
        // All of them: a name that also resolves to a private address is a rebinding attempt or a misconfiguration.
        addresses.forEach { guard.check(hostname, it) }
        return addresses
    }
}

/**
 * Checks every connection attempt before its socket connects. This also covers literal
 * addresses, which OkHttp never passes to the resolver, and refuses anything but a direct route.
 */
internal class ConnectGuard(private val guard: AddressGuard) : EventListener() {
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        val host = call.request().url.host
        if (proxy.type() != Proxy.Type.DIRECT) throw RefusedAddressException(host, "Pages are read over a direct connection only, not through a proxy.")
        val address = inetSocketAddress.address ?: throw RefusedAddressException(host, AddressGuard.NOT_AN_ADDRESS)
        guard.check(host, address)
    }
}

/**
 * Network interceptor: checks the address the connection's socket is connected to, then
 * records the host as contacted and reports the request through [onSent], since the request is
 * written right after. Runs once per hop, whether the connection is new or reused.
 */
internal class PeerGuard(
    private val guard: AddressGuard,
    private val onSent: (host: String, approxBytes: Long) -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val peer = chain.connection()?.socket()?.inetAddress ?: throw RefusedAddressException(host, AddressGuard.NOT_AN_ADDRESS)
        guard.check(host, peer)
        val requestLine = "${request.method} ${request.url.encodedPath}${request.url.encodedQuery?.let { "?$it" }.orEmpty()} HTTP/1.1\r\n"
        onSent(host, requestLine.length + request.headers.byteCount() + 2)
        return chain.proceed(request)
    }
}

/** This device's interface addresses, which [WebAddressPolicy] refuses like loopback; empty if they cannot be read. */
internal fun interfaceAddresses(): Set<InetAddress> =
    try {
        NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty().flatMap { it.inetAddresses.asSequence() }.toSet()
    } catch (e: SocketException) {
        emptySet()
    }
