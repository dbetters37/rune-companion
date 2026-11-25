package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.LlmException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When a provider's "slow down" (429) is waited for, and when its quota counts as used up (INT-1, and NEW-2 of the
 * wave-2a verification): R4's waits, 30 seconds and then 2 minutes, for a 429 without Retry-After; up to three waits
 * with one; none for a 429 that names a daily quota; and the verdict ends when the provider answers.
 */
class ProviderSlowDownsTest {
    private val groq = ProviderId("prv_groq")
    private val other = ProviderId("prv_other")

    private fun perMinute() = LlmException.RateLimited("Groq is limiting requests (HTTP 429): Rate limit reached on requests per minute (RPM).")

    @Test
    fun `a 429 without Retry-After is waited for twice, 30 seconds and then 2 minutes, then counts as used up until the provider answers`() {
        val slowDowns = ProviderSlowDowns()
        var now = 1_000_000L
        assertTrue(slowDowns.slowedDown(groq, perMinute(), now, now + 30_000, mayWait = true))
        assertEquals(now + 30_000, slowDowns.heldUntil(groq, now)?.first)
        assertNull(slowDowns.heldUntil(other, now), "another provider is not held")

        // The second strike waits 2 minutes, whichever step it hits (this one asks for its own first wait of 30 seconds).
        now += 30_000
        assertTrue(slowDowns.slowedDown(groq, perMinute(), now, now + 30_000, mayWait = true))
        assertEquals(now + 120_000, slowDowns.heldUntil(groq, now)?.first)
        // A call that was already on its way when that answer came waits with it.
        assertTrue(slowDowns.slowedDown(groq, perMinute(), now + 1, now + 30_001, mayWait = true))

        now += 120_000
        assertFalse(slowDowns.slowedDown(groq, perMinute(), now, now + 120_000, mayWait = true), "the third strike: used up")
        assertNull(slowDowns.heldUntil(groq, now))
        assertFalse(slowDowns.slowedDown(groq, perMinute(), now + 60_000, now + 90_000, mayWait = true), "and so is every 429 after it")

        slowDowns.answered(groq)
        assertTrue(slowDowns.slowedDown(groq, perMinute(), now + 61_000, now + 91_000, mayWait = true), "answered: a 429 is waited for again")
    }

    @Test
    fun `a 429 that names a daily quota counts as used up at once, also when it asks for a short wait`() {
        for (error in listOf(
            LlmException.RateLimited("OpenRouter is limiting requests (HTTP 429): Rate limit exceeded: free-models-per-day."),
            LlmException.RateLimited("Groq is limiting requests (HTTP 429): Rate limit reached on tokens per day (TPD).", retryAfterMs = 7_000),
            LlmException.RateLimited("Gemini is limiting requests (HTTP 429): You exceeded your current quota.", retryAfterMs = 35_000, dailyQuota = true),
            LlmException.RateLimited("Quota exceeded for quota metric 'Generate requests' and limit 'GenerateRequestsPerDayPerProjectPerModel'"),
            LlmException.RateLimited("Daily request limit reached"),
            LlmException.RateLimited("You have reached your daily quota."),
        )) {
            val slowDowns = ProviderSlowDowns()
            assertTrue(error.dailyQuota, error.message)
            assertFalse(slowDowns.slowedDown(groq, error, 0, 30_000, mayWait = true), error.message)
            assertNull(slowDowns.heldUntil(groq, 0))
        }
    }

    @Test
    fun `a per-minute 429 is not taken for a daily quota`() {
        for (message in listOf(
            "Rate limit reached for requests per minute", "Rate limit exceeded: free-models-per-min.", "Too many requests", "Resource has been exhausted (e.g. check quota).",
            "Rate limit reached on tokens per minute (TPM): Limit 6000. Please try again in 2s.", "You exceeded your current quota, please check your plan and billing details.",
            // Groq's per-minute answers end with an advert (wave-2a residual verification, R3): "today" alone names no daily limit.
            "Rate limit reached on tokens per minute (TPM): Limit 12000. Please try again in 8.3s. Need more tokens? Upgrade to Dev Tier today at https://console.groq.com/settings/billing",
            "Quota exceeded for today's burst; try again in a minute.",
        )) {
            assertFalse(LlmException.RateLimited(message).dailyQuota, message)
        }
    }

    @Test
    fun `with Retry-After a 429 is waited for up to three times`() {
        val slowDowns = ProviderSlowDowns()
        var now = 0L
        repeat(ProviderWaits.MAX_WAITS_AFTER_FAILURE) {
            assertTrue(slowDowns.slowedDown(groq, LlmException.RateLimited("Slow down", retryAfterMs = 20_000), now, now + 30_000, mayWait = true), "wait ${it + 1}")
            now = slowDowns.heldUntil(groq, now)!!.first
        }
        assertFalse(slowDowns.slowedDown(groq, LlmException.RateLimited("Slow down", retryAfterMs = 20_000), now, now + 30_000, mayWait = true))
    }

    @Test
    fun `a 429 that names a daily quota leaves the provider's strikes and hold alone, so another model's per-minute limit still gets its waits`() {
        // Wave-2a residual verification, NEW-R3-CROSS-MODEL: Gemini's and Groq's quotas are per model, not per provider.
        val slowDowns = ProviderSlowDowns()
        val daily = LlmException.RateLimited("Gemini is limiting requests (HTTP 429): You exceeded your current quota.", retryAfterMs = 35_000, dailyQuota = true)
        var now = 0L
        repeat(6) { assertFalse(slowDowns.slowedDown(groq, daily, now + it, now + 30_000, mayWait = true), "a used-up daily quota is never waited for") }
        assertNull(slowDowns.heldUntil(groq, now), "and holds nothing")

        // Another model of the same provider: R4's first wait, 30 seconds.
        assertTrue(slowDowns.slowedDown(groq, perMinute(), now, now + 30_000, mayWait = true))
        assertEquals(now + 30_000, slowDowns.heldUntil(groq, now)?.first)
        // A daily 429 meanwhile neither ends that hold nor adds a strike.
        assertFalse(slowDowns.slowedDown(groq, daily, now + 1_000, now + 31_000, mayWait = true))
        assertEquals(now + 30_000, slowDowns.heldUntil(groq, now + 1_000)?.first)
        // So the per-minute limit's next 429 is its second strike: 2 minutes, not a verdict.
        now += 30_000
        assertTrue(slowDowns.slowedDown(groq, perMinute(), now, now + 30_000, mayWait = true))
        assertEquals(now + 120_000, slowDowns.heldUntil(groq, now)?.first)
    }
}
