package com.aicontrolcenter.providers.anthropic

import com.anthropic.core.RequestOptions
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Makes the SDK's blocking calls abortable by interrupting the calling thread.
 *
 * A thread blocked in a socket read ignores interrupts, so interrupting a blocking SDK call
 * would not end it before the provider answered, which for a model call can take minutes.
 * The SDK's own transport does cancel its HTTP call when the future of an asynchronous
 * request is cancelled, so this wrapper runs every blocking request through that path and
 * waits on the future instead: the wait is interruptible, and an interrupt cancels the call,
 * which closes the connection. Coroutine cancellation reaches here through `runInterruptible`.
 *
 * Only the wait for the response headers is covered, which is where a non-streaming call
 * spends its time. A streaming body is aborted by closing the stream.
 */
internal class InterruptibleHttpClient(private val delegate: HttpClient) : HttpClient {
    override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse {
        val pending = delegate.executeAsync(request, requestOptions)
        try {
            return pending.get()
        } catch (e: InterruptedException) {
            // A response that slipped in before the cancellation would otherwise stay open.
            if (!pending.cancel(true)) pending.thenAccept { it.close() }
            throw e
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    override fun executeAsync(request: HttpRequest, requestOptions: RequestOptions): CompletableFuture<HttpResponse> =
        delegate.executeAsync(request, requestOptions)

    override fun close() = delegate.close()
}
