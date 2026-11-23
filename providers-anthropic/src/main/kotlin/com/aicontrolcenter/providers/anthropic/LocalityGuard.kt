package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ProviderKind
import okhttp3.Dns
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException

/*
 * Network-level enforcement of what [Locality.infer] concludes from an address's text.
 *
 * A name such as "nas.local" is classified as the local network, which permits plain http and
 * labels the provider "local network" everywhere. Whether the name really leads there is up to
 * whatever DNS the current network supplies, and a café hotspot can answer with an internet
 * address. A system or Wi-Fi proxy can likewise receive a request meant for a machine at home.
 * The two classes here close both gaps at the point where OkHttp opens a connection.
 *
 * This is a copy of the providers module's http/LocalityGuard.kt, because this module does not
 * depend on that one (the SDK is meant to stay confined here); change both together.
 */

/** Where a connection to [host] is claimed to go, judged from the host alone, as [Locality.infer] does. */
internal fun localityOfHost(host: String): Locality {
    val bare = host.removePrefix("[").removeSuffix("]")
    if (bare.isEmpty()) return Locality.CLOUD
    val authority = if (':' in bare) "[$bare]" else bare
    // The kind only matters for a blank address, which this never is.
    return Locality.infer(ProviderKind.ANTHROPIC, "http://$authority/")
}

/** True when [address] lies inside [locality]: loopback for the device; loopback or a private range for the local network. */
internal fun isWithin(address: InetAddress, locality: Locality): Boolean = when (locality) {
    Locality.CLOUD -> true
    Locality.ON_DEVICE -> address.isLoopbackAddress
    Locality.LOCAL_NETWORK -> address.isLoopbackAddress || isPrivate(address)
}

/**
 * IPv4: RFC 1918 (10/8, 172.16/12, 192.168/16), link-local 169.254/16 and shared address space
 * 100.64/10, which core counts as the owner's network for overlay networks such as Tailscale.
 * IPv6: unique-local fc00::/7 and link-local fe80::/10.
 */
private fun isPrivate(address: InetAddress): Boolean {
    val bytes = address.address
    val first = bytes[0].toInt() and 0xFF
    val second = bytes[1].toInt() and 0xFF
    return when (address) {
        is Inet4Address -> first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168) ||
            (first == 169 && second == 254) || (first == 100 && second in 64..127)
        is Inet6Address -> (first and 0xFE) == 0xFC || (first == 0xFE && (second and 0xC0) == 0x80)
        else -> false
    }
}

/**
 * The connection to [host] was refused because the name resolved outside the place its address
 * claims. It is an [UnknownHostException] so that OkHttp treats it like any failed lookup: no
 * socket is opened and nothing is sent.
 */
internal class LocalityRefusedException(val host: String, val claimed: Locality) :
    UnknownHostException("$host resolved to an address outside your ${placeOf(claimed)}; the request was not sent.") {
    private companion object {
        fun placeOf(locality: Locality): String = if (locality == Locality.ON_DEVICE) "device" else "local network"
    }
}

/**
 * Resolves names with [resolver] and holds a name whose address text claims this device or the
 * local network to it: only the resolved addresses inside that place are returned, and the name
 * is refused, before any connection is made, when none are. Cloud names are not restricted.
 *
 * OkHttp connects only to the addresses returned here, so leaving the others out is as safe as
 * refusing the name, and keeps working on dual-stack home networks, where a LAN name usually
 * also resolves to the machine's global IPv6 address.
 */
internal class LocalityDns(val resolver: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val claimed = localityOfHost(hostname)
        val addresses = resolver.lookup(hostname)
        if (claimed == Locality.CLOUD) return addresses
        return addresses.filter { isWithin(it, claimed) }.ifEmpty { throw LocalityRefusedException(hostname, claimed) }
    }
}

/**
 * Never routes a connection for this device or the local network through a proxy: a system,
 * Wi-Fi or device-management proxy would otherwise receive requests meant for 127.0.0.1 or a
 * machine at home. Cloud hosts use [fixed] when the app configured one, else [delegate], else
 * the system's selector at the time of the request.
 */
internal class LocalityProxySelector(
    private val fixed: Proxy? = null,
    private val delegate: ProxySelector? = null,
) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host ?: return DIRECT
        if (localityOfHost(host) != Locality.CLOUD) return DIRECT
        fixed?.let { return listOf(it) }
        return cloudSelector()?.select(uri)?.takeIf { it.isNotEmpty() } ?: DIRECT
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        val host = uri?.host ?: return
        if (fixed == null && localityOfHost(host) == Locality.CLOUD) cloudSelector()?.connectFailed(uri, sa, ioe)
    }

    private fun cloudSelector(): ProxySelector? = (delegate ?: ProxySelector.getDefault())?.takeUnless { it === this }

    private companion object {
        val DIRECT: List<Proxy> = listOf(Proxy.NO_PROXY)
    }
}
