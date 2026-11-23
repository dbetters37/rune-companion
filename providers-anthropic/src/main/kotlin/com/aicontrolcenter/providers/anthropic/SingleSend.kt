package com.aicontrolcenter.providers.anthropic

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/*
 * Every request leaves the device exactly once per call. The framework's gateway is the only
 * place that tries again, and the only place that re-checks policy and records each transfer,
 * so nothing below it may repeat a request on its own. OkHttp would, in these cases:
 *
 *  - after a failed connection, including a pooled one the server had already closed, and after
 *    a 408, while retryOnConnectionFailure is on: turned off by [singleSend];
 *  - after a 503 whose Retry-After is 0, whatever retryOnConnectionFailure says;
 *  - after a 401 or 407 an authenticator answers, or a 421 on a coalesced HTTP/2 connection.
 *
 * Request bodies are therefore made one-shot, which OkHttp never sends twice. A request without
 * a body (a model listing, a health check) cannot be marked that way, so the Retry-After of a
 * 503 (and of a 408) is held back from OkHttp's follow-up decision and put back afterwards;
 * OkHttp then has nothing that asks for an immediate repeat. Holding it back also keeps OkHttp
 * from parsing it: a value too large for an Int would otherwise throw out of the call (and, for
 * a queued call, out of OkHttp's dispatcher thread). What remains for a body-less request is a
 * repeat after a 401 or 407 that an authenticator the app installed answers, or after a 421 on
 * a coalesced HTTP/2 connection; neither carries a prompt.
 *
 * Where each interceptor sees the Retry-After of a 408 or 503: an application interceptor, and
 * everything above the call (the SDK's own retry logic included), sees it restored under its own
 * name. A network interceptor installed before [singleSend] sits outside [HoldRetryAfter], so it
 * sees the response with the value moved to X-AICC-Held-Retry-After and no Retry-After. One
 * added to a derived builder without calling [singleSend] again sits inside it and sees the
 * response as the server sent it. This module's transport installs no network interceptor of its
 * own, so nothing here is affected.
 *
 * This is a copy of the providers module's http/SingleSend.kt, because this module does not
 * depend on that one (the SDK is meant to stay confined here); change both together.
 */

/** The response codes whose Retry-After OkHttp's follow-up logic reads. */
private val FOLLOW_UP_CODES = setOf(408, 503)
private const val RETRY_AFTER = "Retry-After"

/** Where [HoldRetryAfter] keeps a Retry-After while OkHttp decides on a follow-up. */
private const val HELD_RETRY_AFTER = "X-AICC-Held-Retry-After"

/**
 * Application interceptor, the last one: sends the request with a one-shot body, and gives the
 * response back its Retry-After once OkHttp has decided not to follow up.
 */
private object SendOnce : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        val once = if (body == null || body.isOneShot()) request else request.newBuilder().method(request.method, OneShotBody(body)).build()
        val response = chain.proceed(once)
        val held = response.headers.values(HELD_RETRY_AFTER)
        if (held.isEmpty()) return response
        val headers = response.headers.newBuilder().removeAll(HELD_RETRY_AFTER)
        held.forEach { headers.addUnsafeNonAscii(RETRY_AFTER, it) }
        return response.newBuilder().headers(headers.build()).build()
    }
}

/**
 * Network interceptor, the last one: hides a 408's or 503's Retry-After from OkHttp's follow-up
 * logic. Values are moved as received, without the validation that would throw on odd bytes.
 *
 * Being the innermost network interceptor, it runs before any other network interceptor sees
 * the response: those see a 408's or 503's Retry-After under [HELD_RETRY_AFTER], not under its
 * own name, until [SendOnce] puts it back for the application interceptors.
 */
private object HoldRetryAfter : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val held = if (response.code in FOLLOW_UP_CODES) response.headers.values(RETRY_AFTER) else emptyList()
        // Only what this interceptor put there may be restored, never a header the server sent under that name.
        if (held.isEmpty() && response.header(HELD_RETRY_AFTER) == null) return response
        val headers = response.headers.newBuilder().removeAll(HELD_RETRY_AFTER)
        if (held.isNotEmpty()) headers.removeAll(RETRY_AFTER)
        held.forEach { headers.addUnsafeNonAscii(HELD_RETRY_AFTER, it) }
        return response.newBuilder().headers(headers.build()).build()
    }
}

/** [delegate], marked as a body that must not be sent twice. */
private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = true

    override fun isDuplex(): Boolean = delegate.isDuplex()

    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
}

/**
 * Makes the client send every request once (see above): no recovery from connection failures,
 * one-shot bodies, and no immediate repeat after a 503. The interceptors are installed last, so
 * that they sit next to OkHttp's own follow-up logic; a client built here is not given them twice.
 * A network interceptor already on the builder therefore sees a 408's or 503's Retry-After as
 * X-AICC-Held-Retry-After; application interceptors see it under its own name (see above).
 */
internal fun OkHttpClient.Builder.singleSend(): OkHttpClient.Builder = apply {
    retryOnConnectionFailure(false)
    interceptors().remove(SendOnce)
    addInterceptor(SendOnce)
    networkInterceptors().remove(HoldRetryAfter)
    addNetworkInterceptor(HoldRetryAfter)
}
