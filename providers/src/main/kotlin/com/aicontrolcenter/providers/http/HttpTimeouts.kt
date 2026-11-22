package com.aicontrolcenter.providers.http

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * How long the adapters wait on a provider.
 *
 * There is deliberately no limit on a whole call: a long generation is healthy for as long as
 * bytes keep arriving. [idleMillis] bounds the silence between bytes instead, and has to be
 * generous because a non-streamed reply from a slow local model or a reasoning model sends
 * nothing at all until it is finished.
 */
data class HttpTimeouts(
    val connectMillis: Long = 15_000,
    val idleMillis: Long = 10 * 60_000L,
    val writeMillis: Long = 60_000,
)

/**
 * Builds the client the adapters use. A client passed in by the app contributes its connection
 * pool, dispatcher, proxy, DNS and interceptors; the timeouts and the redirect policy are
 * always set here.
 *
 * Redirects are not followed. Model APIs do not redirect, and following one would send the
 * conversation, and for providers that authenticate with a custom header also the key, to
 * whatever address a misconfigured or hostile server names.
 *
 * OkHttp's own recovery is left on. It re-sends a request whose pooled connection the server
 * had already closed, which would otherwise surface as a network failure after every idle
 * period, and it repeats a request once after a 408. Every other retry decision belongs to the
 * framework, which sees the mapped [com.aicontrolcenter.core.llm.LlmException].
 */
internal fun providerHttpClient(shared: OkHttpClient?, timeouts: HttpTimeouts): OkHttpClient =
    (shared?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.idleMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeouts.writeMillis, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
