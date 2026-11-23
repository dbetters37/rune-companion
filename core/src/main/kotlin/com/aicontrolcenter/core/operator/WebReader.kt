package com.aicontrolcenter.core.operator

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Bounds on one page fetch. */
data class WebReadLimits(
    val maxBytes: Long = 2_000_000,
    val timeoutMs: Long = 20_000,
    val maxRedirects: Int = 5,
) {
    init {
        require(maxBytes > 0 && timeoutMs > 0 && maxRedirects >= 0) { "limits must be positive" }
    }
}

/** The outcome of [WebReader.read]. */
sealed interface WebReadResult {
    /** A response, any HTTP status. [body] is decoded text, cut at the byte limit when [truncated]. */
    data class Page(
        val finalUrl: String,
        val status: Int,
        val contentType: String?,
        val body: String,
        val truncated: Boolean,
        /** Every host contacted, in order, the first included; for the egress log. */
        val hostsContacted: List<String> = emptyList(),
    ) : WebReadResult

    /** Not fetched: not http(s), a refused address ([WebAddressPolicy]), or a redirect to either. */
    data class Refused(val reason: String) : WebReadResult

    /** Network failure or time-out. */
    data class Failed(val reason: String) : WebReadResult
}

/**
 * A plain HTTP GET of one public http(s) page, with no cookies and no credentials. Implemented
 * with OkHttp in `:providers` (JVM, tested against a mock server). On every hop, the first and
 * each redirect, it re-checks the scheme and checks every address the host resolves to with
 * [WebAddressPolicy.refusal] at connect time (a `Dns` wrapper plus a check of the connected
 * socket's address), so DNS rebinding and redirects cannot reach this device or the local
 * network. Turning HTML into text is the tool's job in `:framework`; egress logging and
 * local-only mode are the broker's and the tool's.
 */
fun interface WebReader {
    suspend fun read(url: String, limits: WebReadLimits): WebReadResult
}

/** Which addresses a web read may connect to: public unicast only. */
object WebAddressPolicy {
    /**
     * Why connecting to [address] is refused, or null if it is a public address. Refused:
     * unspecified, loopback, link-local, site-local (RFC 1918), carrier-grade NAT
     * (100.64.0.0/10), unique-local IPv6 (fc00::/7), multicast, broadcast, the 0.0.0.0/8 and
     * 240.0.0.0/4 ranges, IPv4-mapped and NAT64 forms of any of these, and [ownAddresses] (this
     * device's interface addresses).
     */
    fun refusal(address: InetAddress, ownAddresses: Set<InetAddress> = emptySet()): String? {
        val why = when {
            address in ownAddresses -> "this device"
            address.isAnyLocalAddress -> "an unspecified address"
            address.isLoopbackAddress -> "this device (loopback)"
            address.isLinkLocalAddress -> "a link-local address"
            address.isSiteLocalAddress -> "a private network address"
            address.isMulticastAddress -> "a multicast address"
            address is Inet4Address -> ipv4Refusal(address.address)
            address is Inet6Address -> ipv6Refusal(address.address)
            else -> null
        } ?: return null
        return "Pages on $why are not read: only public web addresses are."
    }

    private fun ipv4Refusal(b: ByteArray): String? {
        val a = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return when {
            a == 0 -> "an unspecified address"
            a == 10 || (a == 172 && second in 16..31) || (a == 192 && second == 168) -> "a private network address"
            a == 100 && second in 64..127 -> "a carrier-grade NAT address"
            a == 127 -> "this device (loopback)"
            a == 169 && second == 254 -> "a link-local address"
            a >= 224 -> "a multicast or reserved address"
            else -> null
        }
    }

    private fun ipv6Refusal(b: ByteArray): String? {
        val first = b[0].toInt() and 0xFF
        if (first and 0xFE == 0xFC) return "a private network address"
        // ::ffff:a.b.c.d (mapped) and 64:ff9b::a.b.c.d (NAT64) carry an IPv4 address in the last four bytes.
        val mapped = (0 until 10).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
        val nat64 = (b[0].toInt() and 0xFF) == 0x00 && (b[1].toInt() and 0xFF) == 0x64 && (b[2].toInt() and 0xFF) == 0xFF &&
            (b[3].toInt() and 0xFF) == 0x9B && (4 until 12).all { b[it].toInt() == 0 }
        if (mapped || nat64) {
            val v4 = InetAddress.getByAddress(b.copyOfRange(12, 16))
            return refusal(v4)?.let { "an address that maps to a refused one" }
        }
        return null
    }
}
