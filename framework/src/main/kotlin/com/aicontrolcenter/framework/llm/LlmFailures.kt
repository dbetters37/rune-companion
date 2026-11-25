package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import java.io.IOException

/**
 * Turns whatever an adapter threw into an [LlmException], so callers only ever handle one
 * family of failures. The text of an unexpected exception is not copied into the message: it
 * was written by code that had the key and the request in hand.
 */
internal fun Throwable.asLlmException(providerName: String, lease: ProviderLease): LlmException = when (this) {
    is LlmException -> withoutSecret(lease)
    is TimeoutCancellationException -> LlmException.Timeout("$providerName did not answer in time.")
    is IOException -> LlmException.Network("Could not reach $providerName (${this::class.simpleName}).", safeCause(lease))
    is SerializationException -> LlmException.MalformedResponse("$providerName sent a reply that could not be read.", safeCause(lease))
    else -> LlmException.MalformedResponse("$providerName failed unexpectedly (${this::class.simpleName}).", safeCause(lease))
}

private fun Throwable.chain(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH)

private fun Throwable.safeCause(lease: ProviderLease): Throwable? = takeUnless { e -> e.chain().any { lease.exposes(it.message) } }

/**
 * The same failure with the provider's key removed, should an adapter or a server have put it
 * into the message or a cause. Rebuilt without its cause in that case, because a cause is
 * printed with every stack trace.
 */
internal fun LlmException.withoutSecret(lease: ProviderLease): LlmException {
    if (chain().none { lease.exposes(it.message) }) return this
    val text = lease.redact(message.orEmpty())
    return when (this) {
        is LlmException.Authentication -> LlmException.Authentication(text)
        is LlmException.RateLimited -> LlmException.RateLimited(text, retryAfterMs)
        is LlmException.Overloaded -> LlmException.Overloaded(text)
        is LlmException.ModelNotFound -> LlmException.ModelNotFound(text)
        is LlmException.ContextTooLong -> LlmException.ContextTooLong(text)
        is LlmException.InvalidRequest -> LlmException.InvalidRequest(text, status)
        is LlmException.Server -> LlmException.Server(text, status)
        is LlmException.Network -> LlmException.Network(text)
        is LlmException.Timeout -> LlmException.Timeout(text)
        is LlmException.MalformedResponse -> LlmException.MalformedResponse(text)
        is LlmException.Unsupported -> LlmException.Unsupported(text)
        is LlmException.BlockedByPolicy -> LlmException.BlockedByPolicy(text)
        is LlmException.DailyLimitReached -> LlmException.DailyLimitReached(text)
    }
}

private const val MAX_CAUSE_DEPTH = 8

/**
 * For `catch (e: Exception)` around code that suspends: rethrows when this coroutine was
 * cancelled, and returns when [e] is anything else, including a time-out that leaked out of
 * the code just called, which is a failure of that code and not a reason to stop the caller.
 */
internal suspend fun rethrowIfCancelled(e: Exception) {
    if (e is CancellationException) currentCoroutineContext().ensureActive()
}

/** Roughly how many bytes of content a request carries, for the record of what left the device. */
internal fun ChatRequest.approximateBytes(): Long {
    var total = 0L
    for (message in messages) {
        total += message.text.encodeToByteArray().size
        for (image in message.images) total += image.base64.length
        for (call in message.toolCalls) total += call.name.length + call.arguments.toString().encodeToByteArray().size
        message.toolResult?.let { total += it.content.encodeToByteArray().size }
    }
    for (tool in tools) {
        total += tool.name.length + tool.description.encodeToByteArray().size + tool.parameters.toString().encodeToByteArray().size
    }
    return total
}
