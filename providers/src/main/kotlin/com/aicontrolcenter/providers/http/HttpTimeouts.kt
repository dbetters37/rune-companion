package com.aicontrolcenter.providers.http

import okhttp3.Dns
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
 * pool, dispatcher, socket factory and interceptors; the timeouts, the redirect policy and the
 * locality checks are always set here.
 *
 * Redirects are not followed. Model APIs do not redirect, and following one would send the
 * conversation, and for providers that authenticate with a custom header also the key, to
 * whatever address a misconfigured or hostile server names.
 *
 * Addresses that claim this device or the local network are held to it ([LocalityDns]): the
 * app's resolver, or the system's, still answers, but a name that resolves anywhere else is
 * refused before a connection is opened. Such addresses also never go through a proxy
 * ([LocalityProxySelector]); the app's proxy, or the system's, is used for cloud hosts only.
 *
 * OkHttp's own recovery is left on. It re-sends a request whose pooled connection the server
 * had already closed, which would otherwise surface as a network failure after every idle
 * period, and it repeats a request once after a 408. Every other retry decision belongs to the
 * framework, which sees the mapped [com.aicontrolcenter.core.llm.LlmException].
 */
internal fun providerHttpClient(shared: OkHttpClient?, timeouts: HttpTimeouts): OkHttpClient {
    // A client built here and passed back in is not checked twice.
    val resolver = shared?.dns?.let { (it as? LocalityDns)?.resolver ?: it } ?: Dns.SYSTEM
    val proxySelector = (shared?.proxySelector as? LocalityProxySelector) ?: LocalityProxySelector(
        fixed = shared?.proxy,
        // With a fixed proxy OkHttp reports a selector that selects nothing; the system's is not wanted then either.
        delegate = if (shared?.proxy == null) shared?.proxySelector else null,
    )
    return (shared?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.idleMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeouts.writeMillis, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(LocalityDns(resolver))
        // OkHttp consults the selector only while no fixed proxy is set.
        .proxy(null)
        .proxySelector(proxySelector)
        .build()
}
