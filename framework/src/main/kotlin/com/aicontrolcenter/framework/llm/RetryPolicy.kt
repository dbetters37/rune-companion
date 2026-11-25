package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.LlmException

/**
 * How the gateway repeats a model call that failed in a way that may pass (rate limit,
 * overload, server or network error). Deliberately small: an agent turn makes many calls and
 * the owner is watching, so a provider that stays down should be reported, not waited out.
 *
 * There is no random jitter. It exists to spread many clients apart; this app is one client.
 */
data class RetryPolicy(
    /** Extra attempts after the first one. */
    val maxRetries: Int = 2,
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 8_000,
    /** A provider asking for a longer pause than this is not waited for; the call fails at once. */
    val maxRetryAfterMs: Long = 20_000,
) {
    init {
        require(maxRetries in 0..5) { "maxRetries must be between 0 and 5" }
        require(initialDelayMs >= 0 && maxDelayMs >= initialDelayMs) { "delays must be ordered and not negative" }
    }

    /**
     * The pause before trying again after failed attempt number [attempt] (1 is the first
     * call), or null when the call should fail now. A provider's Retry-After is honoured, never
     * shortened: sending earlier than asked only burns another request. A provider that says its
     * daily quota is used up ([LlmException.RateLimited.dailyQuota]) is not asked again.
     *
     * [callerWaits]: the caller waits itself when the provider asks the app to slow down (an
     * objective's step waits 30 seconds, then 2 minutes). A "slow down" that names no wait is then
     * not repeated within the call: a per-minute limit is not over in a second or two, and each
     * repeat is a request the provider may count (wave-2a residual verification, NEW-R3-INT1-BOUND).
     */
    fun delayBeforeRetry(attempt: Int, error: LlmException, callerWaits: Boolean = false): Long? {
        if (!error.retryable || attempt > maxRetries) return null
        if (error is LlmException.RateLimited && (error.dailyQuota || (callerWaits && error.retryAfterMs == null))) return null
        val backoff = (initialDelayMs shl (attempt - 1).coerceAtMost(16)).coerceAtMost(maxDelayMs)
        val asked = (error as? LlmException.RateLimited)?.retryAfterMs ?: return backoff
        if (asked > maxRetryAfterMs) return null
        return maxOf(asked, backoff)
    }

    companion object {
        val NONE = RetryPolicy(maxRetries = 0)
    }
}
