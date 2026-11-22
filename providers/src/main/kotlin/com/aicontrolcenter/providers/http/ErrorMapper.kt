package com.aicontrolcenter.providers.http

import com.aicontrolcenter.core.llm.LlmException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The one place where transport outcomes become the framework's error taxonomy. Every adapter
 * in this module reports failures through it, so a status means the same thing whichever
 * provider returned it.
 *
 * | Outcome | Result |
 * |---|---|
 * | 401, 403, or a 400 that says the key is invalid (Gemini) | [LlmException.Authentication] |
 * | 404 whose body is about the model | [LlmException.ModelNotFound] |
 * | 404 otherwise (wrong base URL) | [LlmException.InvalidRequest] |
 * | 408, 504 | [LlmException.Timeout] |
 * | 429 | [LlmException.RateLimited], with the wait from Retry-After or the body |
 * | 400, 413, 422 saying the context window was exceeded | [LlmException.ContextTooLong] |
 * | 400, 413, 422 otherwise, and every other 3xx/4xx | [LlmException.InvalidRequest] |
 * | 529, or 503 saying the provider is overloaded | [LlmException.Overloaded] |
 * | other 5xx | [LlmException.Server] |
 * | socket or call timeout | [LlmException.Timeout] |
 * | any other IOException | [LlmException.Network] |
 * | a 2xx body that cannot be decoded | [LlmException.MalformedResponse] |
 *
 * Messages are written for the owner: the provider's name, what went wrong, and the provider's
 * own words when it gave any. They never contain request headers or the key.
 *
 * @param label the provider's name as the owner entered it
 */
internal class ErrorMapper(private val label: String, private val redactor: Redactor) {

    fun fromHttp(failure: HttpFailure): LlmException {
        val status = failure.status
        val detail = failure.detail
        return when {
            status == 401 || status == 403 ->
                LlmException.Authentication(describe("rejected the API key or does not allow this request", status, detail))
            status == 404 ->
                if (detail.code == "model_not_found" || "model" in detail.searchable) {
                    LlmException.ModelNotFound(describe("does not have this model", status, detail))
                } else {
                    LlmException.InvalidRequest(describe("has nothing at this address; check the base URL", status, detail), status)
                }
            status == 408 || status == 504 -> LlmException.Timeout(describe("timed out", status, detail))
            status == 429 -> LlmException.RateLimited(
                describe("is limiting requests", status, detail),
                retryAfterMs = failure.retryAfterMs ?: detail.retryDelayMs,
            )
            status == 529 || (status == 503 && saysOverloaded(detail)) ->
                LlmException.Overloaded(describe("is overloaded", status, detail))
            status in 500..599 -> LlmException.Server(describe("had an internal error", status, detail), status)
            status == 400 || status == 413 || status == 422 -> when {
                saysBadKey(detail) -> LlmException.Authentication(describe("rejected the API key", status, detail))
                saysContextTooLong(detail) ->
                    LlmException.ContextTooLong(describe("says the conversation no longer fits the model's context window", status, detail))
                saysModelMissing(detail) -> LlmException.ModelNotFound(describe("does not have this model", status, detail))
                else -> LlmException.InvalidRequest(describe("rejected the request", status, detail), status)
            }
            status in 300..399 -> LlmException.InvalidRequest(
                describe("answered with a redirect, which is not followed; check the base URL", status, detail), status,
            )
            else -> LlmException.InvalidRequest(describe("rejected the request", status, detail), status)
        }
    }

    /**
     * An error object delivered inside a successful (200) reply or stream. It carries no HTTP
     * status of its own, so it is classified by the numeric code the provider embedded
     * (OpenRouter and Google send the would-be status there), or as a 500 when there is none.
     */
    fun fromEmbedded(detail: ErrorDetail): LlmException {
        val status = detail.code?.toIntOrNull()?.takeIf { it in 300..599 } ?: 500
        return fromHttp(HttpFailure(status, detail, retryAfterMs = null))
    }

    fun fromIo(e: IOException): LlmException {
        val reason = redactor.clean(e.message ?: e.javaClass.simpleName)
        return when {
            e is SocketTimeoutException || (e is InterruptedIOException && e.message == "timeout") ->
                LlmException.Timeout("$label did not respond in time ($reason).", e)
            e is UnknownHostException -> LlmException.Network("$label could not be found: the host name does not resolve.", e)
            e is ConnectException -> LlmException.Network("Could not connect to $label ($reason).", e)
            e is SSLException -> LlmException.Network("Could not establish a secure connection to $label ($reason).", e)
            else -> LlmException.Network("The connection to $label failed ($reason).", e)
        }
    }

    fun malformed(what: String, cause: Throwable? = null): LlmException.MalformedResponse =
        LlmException.MalformedResponse("$label sent a reply that could not be understood: $what.", cause)

    /** The stream closed before the provider marked the reply as finished. */
    fun droppedStream(): LlmException.Network = LlmException.Network("The reply from $label was cut off before it was complete.")

    private fun describe(what: String, status: Int, detail: ErrorDetail): String {
        val theirWords = detail.message.ifEmpty { null }?.let { ": $it" } ?: "."
        return "$label $what (HTTP $status)$theirWords"
    }

    private fun saysBadKey(detail: ErrorDetail): Boolean =
        detail.reason == "API_KEY_INVALID" || detail.code == "invalid_api_key" ||
            BAD_KEY_PHRASES.any { it in detail.searchable }

    private fun saysContextTooLong(detail: ErrorDetail): Boolean =
        detail.code == "context_length_exceeded" || CONTEXT_PHRASES.any { it in detail.searchable }

    private fun saysModelMissing(detail: ErrorDetail): Boolean =
        detail.code == "model_not_found" || MODEL_MISSING.containsMatchIn(detail.searchable)

    private fun saysOverloaded(detail: ErrorDetail): Boolean = OVERLOAD_PHRASES.any { it in detail.searchable }

    private companion object {
        val BAD_KEY_PHRASES = listOf("api key not valid", "invalid api key", "incorrect api key", "api key is invalid")
        val CONTEXT_PHRASES = listOf(
            "context length", "context window", "context_length", "context size", "maximum context", "exceeds the context",
            "exceed context", "prompt is too long", "input is too long", "too many tokens", "input token count",
            "exceeds the maximum number of tokens", "maximum prompt length", "reduce the length", "request too large",
        )
        val MODEL_MISSING = Regex("model.{0,80}(not found|does not exist|not exist|is not available)|(unknown|invalid|no such) model")
        val OVERLOAD_PHRASES = listOf("overload", "capacity", "high demand", "busy", "unavailable")
    }
}
