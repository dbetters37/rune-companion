package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.LlmException
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.ContentTooLargeException
import com.anthropic.errors.GatewayTimeoutException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PaymentRequiredException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.RequestTimeoutException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.ErrorType
import java.io.IOException
import java.io.InterruptedIOException
import java.io.UncheckedIOException
import java.net.SocketTimeoutException
import kotlin.jvm.optionals.getOrNull

/**
 * Translates whatever a Claude call can throw into core's [LlmException] taxonomy.
 *
 * The SDK has already spent its bounded retries by the time an exception arrives here, so the
 * `retryable` flag of the result tells the framework whether trying again later can help.
 *
 * Messages are written from the provider's own error text and the HTTP status. The API key is
 * removed from every message, and an SDK exception whose text contains the key (a gateway
 * that echoes credentials back) is not attached as a cause, so the key cannot reach a log
 * through a stack trace either.
 */
internal class ErrorMapper(private val providerName: String, private val apiKey: String?) {

    fun map(error: Throwable): LlmException = when (error) {
        is LlmException -> error
        is AnthropicServiceException -> mapStatus(error)
        is AnthropicIoException, is AnthropicRetryableException, is IOException, is UncheckedIOException -> mapConnection(error)
        is AnthropicInvalidDataException ->
            LlmException.MalformedResponse("$providerName sent a response that could not be read: ${describe(error)}", safeCause(error))
        is AnthropicException -> LlmException.InvalidRequest("The call to $providerName failed: ${describe(error)}", cause = safeCause(error))
        // The SDK validates parameters before sending; anything else here is equally not worth repeating.
        else -> LlmException.InvalidRequest("The call to $providerName could not be made: ${describe(error)}", cause = safeCause(error))
    }

    /** Most specific class first, then the error type from the body, then the bare status. */
    private fun mapStatus(error: AnthropicServiceException): LlmException {
        val status = error.statusCode()
        val detail = detail(error)
        val cause = safeCause(error)
        return when (error) {
            is UnauthorizedException -> LlmException.Authentication("$providerName rejected the API key: $detail", cause)
            is PermissionDeniedException ->
                LlmException.Authentication("The API key for $providerName is not allowed to do this: $detail", cause)
            is PaymentRequiredException ->
                LlmException.Authentication("$providerName refused the call because of a billing problem on the account: $detail", cause)
            is RateLimitException -> LlmException.RateLimited("$providerName rate limit reached: $detail", retryAfterMs(error), cause)
            is NotFoundException -> notFound(status, detail, cause)
            is ContentTooLargeException -> LlmException.ContextTooLong("The request is too large for $providerName: $detail", cause)
            is BadRequestException -> badRequest(status, detail, cause)
            is OverloadedException -> LlmException.Overloaded("$providerName is overloaded: $detail", cause)
            is RequestTimeoutException, is GatewayTimeoutException -> LlmException.Timeout("$providerName timed out: $detail", cause)
            else -> byErrorType(error.type().getOrNull(), status, detail, cause)
        }
    }

    /**
     * Covers errors without a class of their own, above all those that arrive as an `error`
     * event inside a stream whose HTTP status was already 200.
     */
    private fun byErrorType(type: ErrorType?, status: Int, detail: String, cause: Throwable?): LlmException = when (type) {
        ErrorType.AUTHENTICATION_ERROR, ErrorType.PERMISSION_ERROR, ErrorType.BILLING_ERROR ->
            LlmException.Authentication("$providerName rejected the credentials: $detail", cause)
        ErrorType.RATE_LIMIT_ERROR -> LlmException.RateLimited("$providerName rate limit reached: $detail", cause = cause)
        ErrorType.OVERLOADED_ERROR -> LlmException.Overloaded("$providerName is overloaded: $detail", cause)
        ErrorType.NOT_FOUND_ERROR -> notFound(status, detail, cause)
        ErrorType.TIMEOUT_ERROR -> LlmException.Timeout("$providerName timed out: $detail", cause)
        ErrorType.INVALID_REQUEST_ERROR -> badRequest(status, detail, cause)
        ErrorType.API_ERROR -> LlmException.Server("$providerName had an internal error: $detail", status, cause)
        else -> when (status) {
            529 -> LlmException.Overloaded("$providerName is overloaded: $detail", cause)
            in 500..599 -> LlmException.Server("$providerName had an internal error: $detail", status, cause)
            else -> LlmException.InvalidRequest("$providerName $REJECTED$detail", status, cause)
        }
    }

    /**
     * An unknown or unavailable model answers 404 with a message that starts with "model:";
     * any other 404 means the address does not lead to a Messages API.
     */
    private fun notFound(status: Int, detail: String, cause: Throwable?): LlmException =
        if ("model" in detail.lowercase()) {
            LlmException.ModelNotFound("$providerName does not know this model, or the key may not use it: $detail", cause)
        } else {
            LlmException.InvalidRequest("$providerName has no such endpoint; check the provider's address: $detail", status, cause)
        }

    /** A prompt beyond the context window is a 400 `invalid_request_error`, told apart only by its text. */
    private fun badRequest(status: Int, detail: String, cause: Throwable?): LlmException {
        val text = detail.lowercase()
        val tooLong = "prompt is too long" in text || "context window" in text || "context length" in text
        return if (tooLong) {
            LlmException.ContextTooLong("The conversation no longer fits the model's context window: $detail", cause)
        } else {
            LlmException.InvalidRequest("$providerName $REJECTED$detail", status, cause)
        }
    }

    private fun mapConnection(error: Throwable): LlmException {
        val timedOut = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).any {
            it is SocketTimeoutException || (it is InterruptedIOException && it.message?.contains("timeout", ignoreCase = true) == true)
        }
        val cause = safeCause(error)
        return if (timedOut) {
            LlmException.Timeout("$providerName did not answer in time.", cause)
        } else {
            LlmException.Network("Could not reach $providerName: ${describe(rootCause(error))}", cause)
        }
    }

    /** `retry-after-ms` when present, else `retry-after` in (possibly fractional) seconds. A spend-cap 429 has neither. */
    private fun retryAfterMs(error: AnthropicServiceException): Long? {
        val headers = error.headers()
        headers.values("retry-after-ms").firstOrNull()?.trim()?.toDoubleOrNull()?.let { return it.toLong().coerceAtLeast(0) }
        return headers.values("retry-after").firstOrNull()?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong().coerceAtLeast(0) }
    }

    private fun detail(error: AnthropicServiceException): String {
        val message = error.apiMessage().getOrNull()?.takeIf { it.isNotBlank() }
        val type = error.type().getOrNull()?.asString()
        val status = "HTTP ${error.statusCode()}"
        return scrub(listOfNotNull(message, listOfNotNull(status, type).joinToString(", ", "(", ")")).joinToString(" ")).take(MAX_DETAIL)
    }

    private fun describe(error: Throwable): String =
        scrub(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName).take(MAX_DETAIL)

    private fun rootCause(error: Throwable): Throwable = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).last()

    private fun scrub(text: String): String = if (apiKey.isNullOrEmpty()) text else text.replace(apiKey, "[key removed]")

    private fun safeCause(error: Throwable): Throwable? {
        if (apiKey.isNullOrEmpty()) return error
        val leaks = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).any { apiKey in it.toString() }
        return if (leaks) null else error
    }

    companion object {
        private const val MAX_DETAIL = 500
        private const val MAX_CAUSE_DEPTH = 12
        private const val REJECTED = "rejected the request: "

        /**
         * The provider's own explanation of a rejected request, without the provider's name
         * that the message starts with, so a check of the wording cannot match the name.
         */
        fun rejectionDetail(error: LlmException.InvalidRequest): String = error.message.orEmpty().substringAfter(REJECTED, "")
    }
}
