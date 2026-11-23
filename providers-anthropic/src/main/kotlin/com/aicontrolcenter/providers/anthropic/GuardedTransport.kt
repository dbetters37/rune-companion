package com.aicontrolcenter.providers.anthropic

import com.anthropic.backends.Backend
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.core.http.Headers
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpMethod
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpRequestBody
import com.anthropic.core.http.HttpResponse
import com.anthropic.errors.AnthropicIoException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import kotlin.jvm.optionals.getOrNull

/**
 * The SDK's HTTP transport, owned by this adapter so that the adapter, not the SDK, decides how
 * a connection is made. It does what the SDK's own OkHttp transport (anthropic-java-client-okhttp
 * 3.12.0) does, request for request, with these differences:
 *
 *  - Redirects are never followed, whatever the request options say. A redirect would re-send
 *    the conversation to a host that no policy check and no egress record has seen; it is
 *    reported as a rejected request instead (see [ErrorMapper]).
 *  - Names resolve through [LocalityDns]: an address that claims this device or the local
 *    network is refused before a connection is opened unless it really resolves there.
 *  - Such addresses never go through a system or Wi-Fi proxy ([LocalityProxySelector]).
 *
 * The SDK's transport accepts neither a resolver nor a proxy selector, which is why it is
 * replaced rather than configured.
 *
 * As in the SDK's transport, OkHttp does not silently repeat a request after a connection
 * failure; whether to try again is decided above this class, and by default only by the
 * framework (see [AnthropicOptions.maxRetries]).
 */
internal class GuardedTransport(
    private val backend: Backend,
    resolver: Dns = Dns.SYSTEM,
    defaults: Timeout = Timeout.default(),
) : HttpClient {

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .pingInterval(Duration.ofMinutes(1))
        .connectTimeout(defaults.connect())
        .readTimeout(defaults.read())
        .writeTimeout(defaults.write())
        .callTimeout(defaults.request())
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(LocalityDns(resolver))
        .proxySelector(LocalityProxySelector())
        .build()
        // Every request goes to the same host, so the per-host limit is raised to the overall one.
        .apply { dispatcher.maxRequestsPerHost = dispatcher.maxRequests }

    override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse {
        val prepared = prepare(request)
        val call = newCall(prepared, requestOptions)
        return try {
            prepareResponse(call.execute().toHttpResponse(call))
        } catch (e: IOException) {
            throw AnthropicIoException("Request failed", e)
        } finally {
            prepared.body().getOrNull()?.close()
        }
    }

    override fun executeAsync(request: HttpRequest, requestOptions: RequestOptions): CompletableFuture<HttpResponse> {
        val prepared = prepare(request)
        val future = CompletableFuture<HttpResponse>()
        val call = newCall(prepared, requestOptions)
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // A throw here would never reach onFailure and would leave the future pending.
                val result = try {
                    prepareResponse(response.toHttpResponse(call))
                } catch (e: IOException) {
                    onFailure(call, e)
                    return
                } catch (e: Throwable) {
                    future.completeExceptionally(e)
                    return
                }
                // Already completed, for instance cancelled: nobody else will close the response.
                if (!future.complete(result)) result.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                future.completeExceptionally(AnthropicIoException("Request failed", e))
            }
        })
        future.whenComplete { _, error ->
            if (error is CancellationException) call.cancel()
            prepared.body().getOrNull()?.close()
        }
        return future
    }

    override fun close() {
        backend.close()
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()
    }

    /** The backend's preparation, the complete URL, then the credentials, in the SDK's order. */
    private fun prepare(request: HttpRequest): HttpRequest {
        val prepared = backend.prepareRequest(request)
        return backend.authorizeRequest(prepared.toBuilder().baseUrl(fullUrl(prepared)).build())
    }

    private fun prepareResponse(response: HttpResponse): HttpResponse = try {
        backend.prepareResponse(response)
    } catch (e: Throwable) {
        try {
            response.close()
        } catch (closeError: Throwable) {
            e.addSuppressed(closeError)
        }
        throw e
    }

    private fun newCall(request: HttpRequest, requestOptions: RequestOptions): Call {
        val builder = okHttp.newBuilder()
        requestOptions.timeout().getOrNull()?.let {
            builder.connectTimeout(it.connect()).readTimeout(it.read()).writeTimeout(it.write()).callTimeout(it.request())
        }
        // Deliberately not taken from the request options: redirects are never followed.
        val client = builder.followRedirects(false).followSslRedirects(false).build()
        return client.newCall(toRequest(request, client))
    }

    /** [request] after [prepare]: its base URL is already the complete URL. */
    private fun toRequest(request: HttpRequest, client: OkHttpClient): Request {
        var body: RequestBody? = request.body().getOrNull()?.toRequestBody()
        if (body == null && request.method.needsBody()) body = "".toRequestBody()
        val builder = Request.Builder().url(request.baseUrl).method(request.method.name, body)
        val headers = request.headers
        headers.names().forEach { name -> headers.values(name).forEach { builder.addHeader(name, it) } }
        // The SDK tells the API how long it is prepared to wait.
        if ("X-Stainless-Read-Timeout" !in headers.names() && client.readTimeoutMillis != 0) {
            builder.addHeader("X-Stainless-Read-Timeout", Duration.ofMillis(client.readTimeoutMillis.toLong()).seconds.toString())
        }
        if ("X-Stainless-Timeout" !in headers.names() && client.callTimeoutMillis != 0) {
            builder.addHeader("X-Stainless-Timeout", Duration.ofMillis(client.callTimeoutMillis.toLong()).seconds.toString())
        }
        return builder.build()
    }

    private companion object {
        fun HttpMethod.needsBody(): Boolean = this == HttpMethod.POST || this == HttpMethod.PUT || this == HttpMethod.PATCH

        /** The base URL with the request's path segments and query, encoded as the SDK encodes them. */
        fun fullUrl(request: HttpRequest): String {
            val builder = request.baseUrl.toHttpUrl().newBuilder()
            request.pathSegments.forEach { builder.addEncodedPathSegment(encodePathSegment(it)) }
            val query = request.queryParams
            query.keys().forEach { key -> query.values(key).forEach { builder.addQueryParameter(key, it) } }
            return builder.toString()
        }

        private val KEEP_IN_PATH = BooleanArray(128) {
            val char = it.toChar()
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~!$&'()*+,;=:@"
        }

        private fun encodePathSegment(segment: String): String {
            if (segment.all { it.code < 128 && KEEP_IN_PATH[it.code] }) return segment
            return buildString {
                for (byte in segment.toByteArray()) {
                    val code = byte.toInt() and 0xFF
                    if (code < 128 && KEEP_IN_PATH[code]) {
                        append(code.toChar())
                    } else {
                        append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
                    }
                }
            }
        }

        private const val HEX = "0123456789ABCDEF"

        fun HttpRequestBody.toRequestBody(): RequestBody {
            val mediaType = contentType()?.toMediaType()
            val length = contentLength()
            val source = this
            return object : RequestBody() {
                override fun contentType(): MediaType? = mediaType

                override fun contentLength(): Long = length

                override fun isOneShot(): Boolean = !source.repeatable()

                override fun writeTo(sink: BufferedSink) = source.writeTo(sink.outputStream())
            }
        }

        fun Response.toHttpResponse(call: Call?): HttpResponse {
            val headers = Headers.builder().apply { this@toHttpResponse.headers.forEach { (name, value) -> put(name, value) } }.build()
            val status = code
            val stream = ResponseBodyInputStream(body, call)
            return object : HttpResponse {
                override fun statusCode(): Int = status

                override fun headers(): Headers = headers

                override fun body(): InputStream = stream

                override fun close() = stream.close()
            }
        }
    }

    /**
     * A response body that one thread may close while another reads it, as in the SDK's
     * transport: OkHttp's own close drains the body, which would race the read, so a close
     * during a read cancels the call instead (failing the read), and the reader closes the body
     * once its read unwinds. A close with no read in flight closes the body directly, which
     * keeps the connection reusable. This is how closing a stream aborts it.
     */
    private class ResponseBodyInputStream(private val body: ResponseBody, private val call: Call?) : InputStream() {
        private val delegate = body.byteStream()
        private val lock = Any()
        private var closed = false
        private var readsInFlight = 0

        override fun read(): Int = tracked { delegate.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = tracked { delegate.read(b, off, len) }

        override fun available(): Int = tracked { delegate.available() }

        private inline fun <T> tracked(read: () -> T): T {
            synchronized(lock) {
                if (closed) throw IOException("closed")
                readsInFlight++
            }
            try {
                return read()
            } finally {
                val closeBody = synchronized(lock) { --readsInFlight == 0 && closed }
                if (closeBody) body.close()
            }
        }

        override fun close() {
            val readInFlight = synchronized(lock) {
                if (closed) return
                closed = true
                readsInFlight > 0
            }
            if (readInFlight) call?.cancel() else body.close()
        }
    }
}
