package com.aicontrolcenter.providers.web

import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.operator.WebReadLimits
import com.aicontrolcenter.core.operator.WebReadResult
import com.aicontrolcenter.core.operator.WebReader
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/**
 * [WebReader] on OkHttp: a plain GET of one public http(s) page.
 *
 * - **Addresses.** Only public addresses are contacted ([com.aicontrolcenter.core.operator.WebAddressPolicy],
 *   with this device's own interface addresses from [deviceAddresses]). The URL's literal host,
 *   every address its name resolves to, every connection attempt and the address the socket
 *   actually reached are each checked, on the first request and on every redirect (see
 *   `AddressGuard.kt`), so a redirect to `127.0.0.1`, a name resolving to the home network and
 *   DNS rebinding are all refused before a request is written.
 * - **Redirects** are followed here, one hop at a time, up to [WebReadLimits.maxRedirects]; each
 *   target's scheme is checked again. Too many redirects is a [WebReadResult.Failed].
 * - **Privacy.** No cookies, no cache, no credentials (a URL with a user name or password is
 *   refused), no authenticator, no proxy, and none of the given client's interceptors. Each read
 *   uses its own connection pool, closed afterwards, so nothing carries over between reads.
 *   Connections are always direct, so the address checked is the address contacted; where a
 *   network only works through a proxy, reads fail there.
 * - **Limits.** [WebReadLimits.timeoutMs] bounds the whole read, redirects included; the body is
 *   read up to [WebReadLimits.maxBytes] (at most [MAX_BYTES_CEILING]) after transparent gzip
 *   decoding, and [WebReadResult.Page.truncated] says whether more was there. Cancelling the
 *   calling coroutine cancels the request.
 * - **Content.** Responses of any status are returned as [WebReadResult.Page]. A body that is
 *   not text (an image, a PDF, an archive) is not read: [WebReadResult.Refused] names its type.
 *   A response without a content type is read if it looks like text. The charset comes from a
 *   byte-order mark, the content type, an HTML `<meta charset>`, else UTF-8 (see [TextBody]).
 * - **Egress.** [WebReadResult.Page.hostsContacted] lists the hosts a request was written to,
 *   in order. [onRequestSent] is told of every request written, also when the read then fails
 *   or a redirect is refused, which a [WebReadResult.Refused] or [WebReadResult.Failed] cannot
 *   carry; [WebEgress] turns either into the activity log's [EgressInfo].
 *
 * @param client lends its dispatcher, resolver, socket factory and TLS settings; everything
 *   listed above is set here regardless. When null, a client is created.
 * @param deviceAddresses this device's addresses, read once per page read.
 * @param onRequestSent called on an OkHttp thread for each request written; it must be quick
 *   and must not throw.
 */
class OkHttpWebReader(
    client: OkHttpClient? = null,
    private val deviceAddresses: () -> Set<InetAddress> = ::interfaceAddresses,
    private val onRequestSent: (EgressInfo) -> Unit = {},
) : WebReader {
    private val resolver: Dns = client?.dns?.let { (it as? GuardedDns)?.resolver ?: it } ?: Dns.SYSTEM

    private val base: OkHttpClient = (client?.newBuilder() ?: OkHttpClient.Builder())
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun read(url: String, limits: WebReadLimits): WebReadResult {
        val first = url.trim().toHttpUrlOrNull() ?: return WebReadResult.Refused(notReadable(url))
        val guard = AddressGuard(
            try {
                deviceAddresses()
            } catch (e: Exception) {
                emptySet()
            },
        )
        val contacted = CopyOnWriteArrayList<String>()
        val client = base.newBuilder()
            .connectionPool(ConnectionPool())
            .dns(GuardedDns(resolver, guard))
            .eventListener(ConnectGuard(guard))
            .addNetworkInterceptor(
                PeerGuard(guard) { host, bytes ->
                    contacted.addIfAbsent(host)
                    onRequestSent(WebEgress.of(host, bytes))
                },
            )
            .callTimeout(limits.timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(limits.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(limits.timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return try {
            withTimeoutOrNull(limits.timeoutMs) { follow(client, guard, first, limits, contacted) }
                ?: WebReadResult.Failed("No complete answer within ${seconds(limits.timeoutMs)}.")
        } finally {
            client.connectionPool.evictAll()
        }
    }

    private suspend fun follow(
        client: OkHttpClient,
        guard: AddressGuard,
        first: HttpUrl,
        limits: WebReadLimits,
        contacted: List<String>,
    ): WebReadResult {
        var url = first
        var redirects = 0
        while (true) {
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
                return WebReadResult.Refused("Addresses with a user name or password are not read.")
            }
            guard.literalRefusal(url)?.let { return WebReadResult.Refused(it) }
            when (val hop = fetch(client, url, limits)) {
                is Hop.Done -> return hop.page.copy(hostsContacted = contacted.toList())
                is Hop.Stop -> return hop.result
                is Hop.Error -> return failure(hop.error, url)
                is Hop.Redirect -> {
                    if (redirects >= limits.maxRedirects) {
                        return WebReadResult.Failed("The page redirected more than ${limits.maxRedirects} times; the last redirect was not followed.")
                    }
                    redirects++
                    url = url.resolve(hop.location) ?: return WebReadResult.Refused(redirectNotReadable(hop.location))
                }
            }
        }
    }

    /** One request and its response, read on OkHttp's thread. Cancelling the coroutine cancels the call. */
    private suspend fun fetch(client: OkHttpClient, url: HttpUrl, limits: WebReadLimits): Hop =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request(url))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resume(Hop.Error(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // Nothing may escape this callback: the continuation would never resume.
                        val hop = try {
                            response.use { interpret(it, limits) }
                        } catch (e: IOException) {
                            Hop.Error(e)
                        } catch (e: RuntimeException) {
                            Hop.Error(IOException(e.javaClass.simpleName, e))
                        }
                        continuation.resume(hop)
                    }
                },
            )
        }

    private fun request(url: HttpUrl): Request = Request.Builder()
        .url(url)
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Accept", ACCEPT)
        .build()

    private fun interpret(response: Response, limits: WebReadLimits): Hop {
        val location = response.header("Location")
        if (response.code in REDIRECT_CODES && location != null) return Hop.Redirect(location)

        val declared = response.header("Content-Type")
        val type: MediaType? = declared?.toMediaTypeOrNull()
        if (type != null && !TextBody.isTextual(type)) {
            return Hop.Stop(WebReadResult.Refused("The page is ${type.type}/${type.subtype}, not text, so it was not read."))
        }
        val limit = minOf(limits.maxBytes, MAX_BYTES_CEILING)
        val source = response.body.source()
        val truncated = source.request(limit + 1)
        val bytes = source.buffer.readByteArray(minOf(source.buffer.size, limit))
        if (type == null && !TextBody.looksLikeText(bytes)) {
            return Hop.Stop(WebReadResult.Refused("The page has no text content type and does not look like text, so it was not read."))
        }
        val body = TextBody.decode(bytes, TextBody.charsetOf(type, bytes), truncated)
        return Hop.Done(
            WebReadResult.Page(
                finalUrl = response.request.url.toString(),
                status = response.code,
                contentType = declared,
                body = body,
                truncated = truncated,
            ),
        )
    }

    private fun failure(error: IOException, url: HttpUrl): WebReadResult {
        refusalIn(error)?.let { return WebReadResult.Refused(it.reason) }
        val host = url.host
        return when {
            error is SocketTimeoutException || (error is InterruptedIOException && error.message == "timeout") ->
                WebReadResult.Failed("$host did not answer in time.")
            error is java.net.UnknownHostException -> WebReadResult.Failed("The address of $host could not be found.")
            error is ConnectException -> WebReadResult.Failed("Could not connect to $host.")
            error is SSLException -> WebReadResult.Failed("A secure connection to $host could not be set up.")
            else -> WebReadResult.Failed("The page on $host could not be read (${error.javaClass.simpleName}).")
        }
    }

    /** A refusal anywhere in [error]: as itself, its cause, or suppressed (OkHttp keeps the failures of other routes there). */
    private fun refusalIn(error: Throwable, depth: Int = 0): RefusedAddressException? {
        if (error is RefusedAddressException) return error
        if (depth > 8) return null
        return (listOfNotNull(error.cause) + error.suppressed).firstNotNullOfOrNull { refusalIn(it, depth + 1) }
    }

    private fun notReadable(url: String): String =
        if (schemeOf(url).let { it != null && it != "http" && it != "https" }) {
            "Only http and https pages can be read."
        } else {
            "This is not a web address that can be read."
        }

    private fun redirectNotReadable(location: String): String =
        if (schemeOf(location).let { it != null && it != "http" && it != "https" }) {
            "The page redirected to an address that is not http or https, which was not followed."
        } else {
            "The page redirected to an address that cannot be read."
        }

    private fun schemeOf(text: String): String? = SCHEME.find(text.trim())?.groupValues?.get(1)?.lowercase()

    private fun seconds(ms: Long): String = if (ms % 1000 == 0L) "${ms / 1000} seconds" else "$ms ms"

    /** The outcome of one request. */
    private sealed interface Hop {
        data class Done(val page: WebReadResult.Page) : Hop

        data class Redirect(val location: String) : Hop

        /** A final answer other than a page (a body that is not text). */
        data class Stop(val result: WebReadResult) : Hop

        data class Error(val error: IOException) : Hop
    }

    companion object {
        /** No page is read into memory beyond this, whatever [WebReadLimits.maxBytes] says. */
        const val MAX_BYTES_CEILING: Long = 16L * 1024 * 1024

        /** Generic, so the request says nothing about the device, the owner or the app. */
        const val USER_AGENT = "Mozilla/5.0 (compatible; PageReader/1.0)"

        const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,application/json;q=0.8,*/*;q=0.5"

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
    }
}
