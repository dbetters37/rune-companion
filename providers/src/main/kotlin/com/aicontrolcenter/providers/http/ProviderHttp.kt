package com.aicontrolcenter.providers.http

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderConnection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Offers a corrected request body after a rejection, or null to let the rejection stand. */
internal fun interface Recovery {
    fun adjust(failure: HttpFailure): JsonObject?
}

/**
 * JSON over HTTP for one provider connection: the shared plumbing under every adapter in this
 * module.
 *
 *  - The address is checked ([Endpoint]) and the headers are built for each request, so the key
 *    is read at the moment of use and a bad address or key surfaces as an [LlmException] from
 *    the call rather than from constructing the adapter.
 *  - Cancelling the calling coroutine cancels the HTTP call, which also unblocks a thread
 *    waiting on a silent stream.
 *  - Every failure leaves as an [LlmException] produced by [errors].
 *
 * @param authHeader turns the stored key into the provider's credential header
 */
internal class ProviderHttp(
    private val client: OkHttpClient,
    private val connection: ProviderConnection,
    private val defaultBaseUrl: String,
    private val authHeader: (apiKey: String) -> Pair<String, String>,
) {
    private val redactor = Redactor(listOf(connection.apiKey))
    val errors = ErrorMapper(connection.provider.name, redactor)

    /** The host requests go to. @throws LlmException when the address is unusable */
    fun host(): String = endpoint().host

    /** The failure described by an error object that arrived inside a 200 reply or stream. */
    fun embeddedError(body: JsonElement): LlmException = errors.fromEmbedded(ErrorDetail.fromJson(body, redactor))

    /** @throws LlmException */
    suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonElement {
        val reply = exchange(request(path, query, body = null, accept = JSON))
        if (reply.status !in 200..299) throw errors.fromHttp(reply.failure(redactor))
        return decode(reply.body)
    }

    /**
     * @param recovery consulted when the provider rejects the request; see [Recovery]
     * @throws LlmException
     */
    suspend fun postJson(
        path: String,
        body: JsonObject,
        query: Map<String, String> = emptyMap(),
        recovery: Recovery? = null,
    ): JsonElement {
        var payload = body
        var recoveries = 0
        while (true) {
            val reply = exchange(request(path, query, payload, accept = JSON))
            if (reply.status in 200..299) return decode(reply.body)
            val failure = reply.failure(redactor)
            payload = recovery?.takeIf { recoveries++ < MAX_RECOVERIES }?.adjust(failure) ?: throw errors.fromHttp(failure)
        }
    }

    /**
     * Posts [body] and emits the reply line by line as it arrives: the common base of
     * server-sent events and NDJSON. A rejection is reported (or recovered from) before the
     * first line; a connection lost afterwards fails the flow with [LlmException.Network].
     */
    fun postLines(
        path: String,
        body: JsonObject,
        query: Map<String, String> = emptyMap(),
        accept: String = EVENT_STREAM,
        recovery: Recovery? = null,
    ): Flow<String> = flow {
        var payload = body
        var recoveries = 0
        while (true) {
            val failure = streamOnce(request(path, query, payload, accept), this) ?: return@flow
            payload = recovery?.takeIf { recoveries++ < MAX_RECOVERIES }?.adjust(failure) ?: throw errors.fromHttp(failure)
        }
    }.flowOn(Dispatchers.IO)

    fun decode(body: String): JsonElement {
        if (body.isBlank()) throw errors.malformed("the body was empty")
        return try {
            Json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw errors.malformed("the body was not JSON", e)
        }
    }

    private fun endpoint(): Endpoint = Endpoint.resolve(connection.provider.kind, connection.provider.baseUrl, defaultBaseUrl)

    private fun request(path: String, query: Map<String, String>, body: JsonObject?, accept: String): Request {
        val builder = Request.Builder().url(endpoint().url(path, query)).headers(headers()).header("Accept", accept)
        if (body != null) builder.post(body.toString().encodeToByteArray().toRequestBody(JSON_MEDIA_TYPE))
        return builder.build()
    }

    /**
     * The owner's extra headers, then the credential, which therefore wins over an extra header
     * of the same name. Values are validated here because OkHttp's own rejection of an illegal
     * header value quotes the value, which for a key would put it into an exception message.
     */
    private fun headers(): Headers {
        val builder = Headers.Builder()
        for ((name, value) in connection.provider.extraHeaders) {
            try {
                builder.set(name, value)
            } catch (e: IllegalArgumentException) {
                throw LlmException.InvalidRequest("The extra header \"${name.take(40)}\" contains characters that cannot be sent in an HTTP header.")
            }
        }
        val key = connection.apiKey?.trim().orEmpty()
        if (key.isNotEmpty()) {
            if (key.any { it < '!' || it > '~' }) {
                throw LlmException.Authentication(
                    "The stored API key for ${connection.provider.name} contains spaces or characters that cannot be sent. Enter it again.",
                )
            }
            val (name, value) = authHeader(key)
            builder.set(name, value)
        }
        return builder.build()
    }

    /** Runs a call to completion, reading the whole body. Cancellation cancels the call. */
    private suspend fun exchange(request: Request): HttpReply = try {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // Nothing may escape this callback: the continuation would never resume.
                    val reply = try {
                        response.use { HttpReply(it.code, it.headers, it.body.source().readLimited(MAX_BODY_BYTES)) }
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(reply)
                }
            })
        }
    } catch (e: IOException) {
        throw errors.fromIo(e)
    }

    /**
     * One streaming attempt. Returns the rejection when the provider answered with an error
     * status, null once the stream has been delivered in full.
     *
     * The reads block a thread of the IO dispatcher, where coroutine cancellation alone would
     * go unnoticed until the next byte arrived. A watcher coroutine therefore cancels the HTTP
     * call as soon as this scope is cancelled, which fails the blocked read at once.
     */
    private suspend fun streamOnce(request: Request, out: FlowCollector<String>): HttpFailure? = coroutineScope {
        val call = client.newCall(request)
        val finished = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body.source().readLimited(MAX_ERROR_BODY_BYTES, truncate = true)
                    return@coroutineScope HttpReply(response.code, response.headers, text).failure(redactor)
                }
                val source = response.body.source()
                while (true) out.emit(source.readLine() ?: break)
                null
            }
        } catch (e: IOException) {
            // A read that failed because the watcher cancelled the call is a cancellation, not a network error.
            currentCoroutineContext().ensureActive()
            throw errors.fromIo(e)
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

    private fun BufferedSource.readLine(): String? {
        val newline = indexOf('\n'.code.toByte(), 0, MAX_LINE_BYTES)
        if (newline == -1L && buffer.size >= MAX_LINE_BYTES) throw errors.malformed("a line of the stream was too long")
        return readUtf8Line()
    }

    private fun BufferedSource.readLimited(limit: Long, truncate: Boolean = false): String {
        val complete = !request(limit + 1)
        if (!complete && !truncate) throw errors.malformed("the body was larger than ${limit / (1024 * 1024)} MB")
        return buffer.readUtf8(minOf(buffer.size, limit))
    }

    override fun toString(): String = "ProviderHttp(${connection.provider.name})"

    private class HttpReply(val status: Int, val headers: Headers, val body: String) {
        fun failure(redactor: Redactor) = HttpFailure(status, ErrorDetail.parse(body, redactor), retryAfterMillis(headers))
    }

    companion object {
        const val JSON = "application/json"
        const val EVENT_STREAM = "text/event-stream"
        const val NDJSON = "application/x-ndjson"

        private val JSON_MEDIA_TYPE = JSON.toMediaType()

        /** Enough for a parameter that is rejected, replaced, and rejected again several times over. */
        private const val MAX_RECOVERIES = 6
        private const val MAX_BODY_BYTES = 32L * 1024 * 1024
        private const val MAX_ERROR_BODY_BYTES = 64L * 1024
        private const val MAX_LINE_BYTES = 8L * 1024 * 1024

        /**
         * The wait a provider asks for: `retry-after-ms` (OpenAI), or `Retry-After` as seconds
         * or as an HTTP date.
         */
        internal fun retryAfterMillis(headers: Headers, nowMillis: Long = System.currentTimeMillis()): Long? {
            headers["retry-after-ms"]?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { return it.toLong() }
            val value = headers["Retry-After"]?.trim() ?: return null
            value.toDoubleOrNull()?.let { return if (it >= 0) (it * 1000).toLong() else null }
            val date = headers.getDate("Retry-After") ?: return null
            return (date.time - nowMillis).coerceAtLeast(0)
        }
    }
}
