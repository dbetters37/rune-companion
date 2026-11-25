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
     * 240.0.0.0/4 ranges, IPv4-mapped, IPv4-translated (::ffff:0:0/96), NAT64, IPv4-compatible
     * (::/96), 6to4 (2002::/16), ISATAP and Teredo forms of any of these, the local-use NAT64 prefix
     * 64:ff9b:1::/48, and [ownAddresses] (this device's interface addresses).
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

    /** Refusal for a host that is not a usual name or a plain address (a number in an unusual form, an unparsable literal). */
    const val NOT_A_USUAL_ADDRESS: String = "The address is not a usual web address, so it is not read."

    /**
     * Why [host], as written in a URL, is refused before anything is looked up, or null when it
     * must still be resolved (a public-looking name) or is a public literal address (Phase 6,
     * K6, D8). Pure: no lookup, no network. The tool calls it before it is authorised, so the
     * owner is never asked about a page that would be refused; `AddressGuard` uses it as its
     * literal check. Names that resolve to a private address are still refused only at read
     * time ([refusal] on every resolved address).
     *
     * Refused: an empty host; `localhost` and any `*.localhost`; names under `.local`,
     * `.internal`, `.home.arpa` and `.lan`; dotless names ("router", "nas"), which only a local
     * resolver answers; a literal IPv4 or IPv6 address that [refusal] refuses (IPv4-mapped, NAT64,
     * IPv4-compatible and 6to4 forms included); and every number in a form other than a plain `a.b.c.d`: decimal
     * (`2130706433`), octal (`0177.0.0.1`), hexadecimal (`0x7f.0.0.1`), shortened (`127.1`)
     * and leading zeros, which systems read in different ways. Case, square brackets around an
     * IPv6 literal and trailing dots (`router.local.`) are ignored.
     */
    fun literalRefusal(host: String): String? {
        var name = host.trim().lowercase()
        if (name.startsWith("[") && name.endsWith("]")) name = name.substring(1, name.length - 1)
        name = name.trimEnd('.')
        if (name.isEmpty()) return NOT_A_USUAL_ADDRESS
        if (':' in name) {
            // An IPv6 literal: parsed without any lookup, because it holds a colon.
            // Java parses a literal only when it starts with a hex digit or a colon; anything else it would look up.
            if (!(name[0] == ':' || name[0] in '0'..'9' || name[0] in 'a'..'f')) return NOT_A_USUAL_ADDRESS
            if (!name.all { it in '0'..'9' || it in 'a'..'z' || it == ':' || it == '.' || it == '%' }) return NOT_A_USUAL_ADDRESS
            val address = try {
                InetAddress.getByName(name)
            } catch (e: java.net.UnknownHostException) {
                return NOT_A_USUAL_ADDRESS
            }
            return refusal(address)
        }
        val labels = name.split('.')
        if (labels.all { NUMBER_LABEL.matches(it) }) {
            val quad = dottedQuad(labels) ?: return NOT_A_USUAL_ADDRESS
            return refusal(InetAddress.getByAddress(quad))
        }
        val why = when {
            name == "localhost" || name.endsWith(".localhost") -> "this device (loopback)"
            LOCAL_SUFFIXES.any { name.endsWith(it) } -> "a local network name"
            '.' !in name -> "a local network name"
            else -> return null
        }
        return "Pages on $why are not read: only public web addresses are."
    }

    /** What some systems read as a part of an IPv4 address: decimal, octal (leading 0) or hexadecimal (0x). */
    private val NUMBER_LABEL = Regex("[0-9]+|0x[0-9a-f]*")

    private val LOCAL_SUFFIXES = listOf(".local", ".internal", ".home.arpa", ".lan")

    /** The four bytes of a plain `a.b.c.d` (each 0 to 255, no leading zeros), or null for any other form. */
    private fun dottedQuad(labels: List<String>): ByteArray? {
        if (labels.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in labels.withIndex()) {
            if (part.isEmpty() || part.length > 3 || (part.length > 1 && part[0] == '0') || part.any { it !in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            bytes[i] = value.toByte()
        }
        return bytes
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
        // ::ffff:a.b.c.d (mapped), 64:ff9b::a.b.c.d (NAT64) and the deprecated IPv4-compatible ::a.b.c.d (::/96) carry
        // an IPv4 address in the last four bytes; 6to4 (2002:AABB:CCDD::/48, RFC 3056) carries one in bytes 2 to 5.
        val mapped = (0 until 10).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
        val nat64 = (b[0].toInt() and 0xFF) == 0x00 && (b[1].toInt() and 0xFF) == 0x64 && (b[2].toInt() and 0xFF) == 0xFF &&
            (b[3].toInt() and 0xFF) == 0x9B && (4 until 12).all { b[it].toInt() == 0 }
        val compatible = (0 until 12).all { b[it].toInt() == 0 }
        // Second review of F14: SIIT's IPv4-translated form ::ffff:0:a.b.c.d (RFC 6052 section 2.1, RFC 7915).
        val translated = (0 until 8).all { b[it].toInt() == 0 } && (b[8].toInt() and 0xFF) == 0xFF && (b[9].toInt() and 0xFF) == 0xFF &&
            b[10].toInt() == 0 && b[11].toInt() == 0
        // The local-use NAT64 prefix 64:ff9b:1::/48 (RFC 8215) only exists inside a network that runs its own translator.
        val localNat64 = (b[0].toInt() and 0xFF) == 0x00 && (b[1].toInt() and 0xFF) == 0x64 && (b[2].toInt() and 0xFF) == 0xFF &&
            (b[3].toInt() and 0xFF) == 0x9B && b[4].toInt() == 0 && (b[5].toInt() and 0xFF) == 0x01
        if (localNat64) return "an address that maps to a refused one"
        // ISATAP (RFC 5214): an interface identifier ::0:5efe:a.b.c.d or ::200:5efe:a.b.c.d after any prefix.
        val isatap = (b[8].toInt() and 0xFD) == 0x00 && b[9].toInt() == 0 && (b[10].toInt() and 0xFF) == 0x5E && (b[11].toInt() and 0xFF) == 0xFE
        // Teredo (2001:0::/32, RFC 4380): the server's IPv4 address in bytes 4 to 7, the client's, inverted, in the last four.
        val teredo = (b[0].toInt() and 0xFF) == 0x20 && (b[1].toInt() and 0xFF) == 0x01 && b[2].toInt() == 0 && b[3].toInt() == 0
        if (teredo) {
            val server = b.copyOfRange(4, 8)
            val client = ByteArray(4) { (b[12 + it].toInt() xor 0xFF).toByte() }
            return if (refusal(InetAddress.getByAddress(server)) != null || refusal(InetAddress.getByAddress(client)) != null) {
                "an address that maps to a refused one"
            } else {
                null
            }
        }
        val embedded = when {
            mapped || nat64 || compatible || translated || isatap -> b.copyOfRange(12, 16)
            (b[0].toInt() and 0xFF) == 0x20 && (b[1].toInt() and 0xFF) == 0x02 -> b.copyOfRange(2, 6)
            else -> return null
        }
        return refusal(InetAddress.getByAddress(embedded))?.let { "an address that maps to a refused one" }
    }
}
