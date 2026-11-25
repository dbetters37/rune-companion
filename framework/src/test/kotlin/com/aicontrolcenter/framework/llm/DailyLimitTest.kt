package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.ModelUsage
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rule 6 and the owner's Q2: today's limits of model answers, tokens and estimated money, checked before every attempt
 * that would reach a provider off this phone, retries and streamed calls included, under one lock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyLimitTest {
    private val ask = ChatRequest(messages = listOf(ChatMessage.user("Hello")))

    private val callsSentence =
        "Today's limit of 200 model answers is reached. It starts again at midnight, or you can raise it in Settings > Daily limits."

    @Test
    fun `the 200th answer goes, the 201st is refused before anything is sent, streamed or not, and asking again is refused too`() = runTest {
        val metered = MeteredWorld(this)
        assertEquals(DailyLimits.DEFAULT, metered.world.settings.get().dailyLimits, "the owner's Q2 limits are on from the start")
        val client = ScriptedLlmClient.replies("ok")
        val model = metered.cloud(client)

        repeat(200) { metered.gateway.chat(model.id, ask) }
        val refused = assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(model.id, ask) }
        val again = assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chatStream(model.id, ask).toList() }

        assertEquals(callsSentence, refused.message)
        assertEquals(callsSentence, again.message)
        assertEquals(200, client.requests.size, "nothing was sent for the refused calls")
        val today = metered.today()
        assertEquals(200, today.ok)
        assertEquals(2, today.blocked)
        val event = metered.modelCalls().last()
        assertEquals("Model call blocked", event.title)
        assertTrue(callsSentence in event.detail)
    }

    @Test
    fun `a retry is checked again, so a limit reached while it waited stops it`() = runTest {
        val metered = MeteredWorld(this)
        val flaky = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Server("Busy", 503)), ScriptStep.Reply("late"))
        val flakyModel = metered.cloud(flaky)
        val big = metered.cloud(ScriptedLlmClient.script(ScriptStep.Reply("big", TokenUsage(450_000, 60_000))))

        val first = async { runCatching { metered.gateway.chat(flakyModel.id, ask) } }
        runCurrent()
        // While the first call waits a second to try again, another call uses 510,000 tokens: over today's 500,000.
        metered.gateway.chat(big.id, ask)
        val refused = first.await().exceptionOrNull()

        assertTrue(refused is LlmException.DailyLimitReached, "got $refused")
        assertEquals(
            "Today's limit of 500,000 tokens is reached. It starts again at midnight, or you can raise it in Settings > Daily limits.",
            refused.message,
        )
        assertEquals(1, flaky.requests.size, "the retry was not sent")
        assertEquals(2, metered.limiter.today().calls, "the stopped call sent a request, so it counts")
        assertTrue("not repeated after 1 attempt" in metered.modelCalls().first { it.title == "Model call blocked" }.detail)
    }

    @Test
    fun `a retry never counts its own call twice`() = runTest {
        val metered = MeteredWorld(this)
        metered.settings { it.copy(maxModelCallsPerDay = 1) }
        val client = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Server("Busy", 503)), ScriptStep.Reply("ok"))
        val model = metered.cloud(client)

        metered.gateway.chat(model.id, ask)

        assertEquals(2, client.requests.size, "the one call allowed today was retried")
        assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(model.id, ask) }
        assertEquals(2, client.requests.size)
    }

    @Test
    fun `two calls that start together at 199 of 200 cannot both pass`() = runTest {
        val metered = MeteredWorld(this)
        val fast = metered.cloud(ScriptedLlmClient.replies("ok"))
        val gate = CompletableDeferred<Unit>()
        val slow = ScriptedLlmClient { _, _ ->
            gate.await()
            ScriptStep.Reply("done")
        }
        val slowModel = metered.cloud(slow)
        repeat(199) { metered.gateway.chat(fast.id, ask) }

        val a = async { runCatching { metered.gateway.chat(slowModel.id, ask) } }
        val b = async { runCatching { metered.gateway.chat(slowModel.id, ask) } }
        runCurrent()
        gate.complete(Unit)
        val results = listOf(a.await(), b.await())

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is LlmException.DailyLimitReached })
        assertEquals(1, slow.requests.size)
    }

    @Test
    fun `many calls on many threads at once never pass the limit`() = runTest {
        val metered = MeteredWorld(this)
        metered.settings { it.copy(maxModelCallsPerDay = 10) }
        val client = ScriptedLlmClient { _, _ ->
            yield()
            ScriptStep.Reply("ok")
        }
        val model = metered.cloud(client)

        val results = withContext(Dispatchers.Default) {
            (1..40).map { async { runCatching { metered.gateway.chat(model.id, ask) } } }.awaitAll()
        }

        assertEquals(10, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is LlmException.DailyLimitReached })
        assertEquals(10, client.requests.size)
    }

    /** A request of about 100,000 estimated tokens (400,000 characters) leaving room for a 20,000-token answer. */
    private val big = ChatRequest(messages = listOf(ChatMessage.user("a".repeat(400_000))), maxOutputTokens = 20_000)

    @Test
    fun `calls in flight count with their estimated tokens and money, so calls started together cannot all pass`() = runTest {
        // Security review of wave 2a, SEC-W2A-4: each answer costs US$0.60 at US$3 / US$15 per million (100,000 in, 20,000 out).
        val metered = MeteredWorld(this)
        metered.settings { it.copy(maxTokensPerDay = 100_000_000L, maxSpendPerDayMicros = 1_000_000L) }
        val client = ScriptedLlmClient { _, _ ->
            kotlinx.coroutines.delay(2_000)
            ScriptStep.Reply("ok", TokenUsage(100_000, 20_000))
        }
        val model = metered.cloud(client)
        metered.price(model.id, inputUsdPerMTok = 3.0, outputUsdPerMTok = 15.0)

        val results = (1..3).map { async { runCatching { metered.gateway.chat(model.id, big) } } }.awaitAll()

        // The first reserves about US$0.60; the second sees that, under US$1.00, and goes; the third sees about US$1.20.
        assertEquals(listOf(true, true, false), results.map { it.isSuccess })
        assertTrue(results.last().exceptionOrNull() is LlmException.DailyLimitReached)
        assertEquals(2, client.requests.size, "nothing was sent for the refused call")
        assertEquals(1_200_000, metered.limiter.today().spendMicros, "settled at the reported tokens, the estimates gone")
        assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(model.id, big) }
    }

    @Test
    fun `calls in flight count with their estimated tokens against the token limit too, and a retry does not count its own`() = runTest {
        val metered = MeteredWorld(this)
        metered.settings { it.copy(maxTokensPerDay = 150_000L) }
        val gate = CompletableDeferred<Unit>()
        var first = true
        val client = ScriptedLlmClient { _, _ ->
            if (first) {
                first = false
                ScriptStep.Fail(LlmException.Server("Busy", 503))
            } else {
                gate.await()
                ScriptStep.Reply("ok", TokenUsage(100_000, 20_000))
            }
        }
        val model = metered.cloud(client)
        // Each call reserves about 120,000 tokens (100,000 estimated in, 20,000 of room for the answer).
        // The first fails once and waits a second before its retry.
        val a = async { runCatching { metered.gateway.chat(model.id, big) } }
        runCurrent()
        // A second call meanwhile sees the first's 120,000 reserved, under 150,000, and goes.
        val b = async { runCatching { metered.gateway.chat(model.id, big) } }
        runCurrent()
        // The first one's retry sees the second's 120,000 but not its own, and goes too.
        advanceTimeBy(5_000)
        runCurrent()
        // A third sees 240,000 reserved and is refused before anything is sent.
        val c = async { runCatching { metered.gateway.chat(model.id, big) } }
        runCurrent()
        gate.complete(Unit)
        val results = listOf(a.await(), b.await(), c.await())
        assertEquals(listOf(true, true, false), results.map { it.isSuccess })
        assertTrue(results.last().exceptionOrNull() is LlmException.DailyLimitReached)
        assertEquals(240_000, metered.limiter.today().tokens)
    }

    @Test
    fun `a call that ended because the provider asked the app to slow down is not one of today's answers`() = runTest {
        // Review of wave 2a, INT-1: a used-up quota answers nothing and costs nothing; it must not use up the other providers' answers.
        val metered = MeteredWorld(this)
        metered.settings { it.copy(maxModelCallsPerDay = 2) }
        val usedUp = metered.cloud(ScriptedLlmClient { _, _ -> ScriptStep.Fail(LlmException.RateLimited("Quota exceeded for today", retryAfterMs = 3_600_000L)) })
        repeat(5) { assertFailsWith<LlmException.RateLimited> { metered.gateway.chat(usedUp.id, ask) } }
        assertEquals(0, metered.limiter.today().calls)
        // The usage records say the same, as the screens read them and as a restarted limiter does.
        val rows = metered.usage.byModel(UsageRange.day(metered.clock.now(), metered.zone))
        assertEquals(5, rows.sumOf { it.totals.failed })
        assertEquals(0, DailyUse.of(rows, emptyMap()).calls)
        val restarted = DailyLimiter(metered.usage, metered.ownerPrices, metered.world.settings, metered.clock) { metered.zone }
        assertEquals(0, restarted.today().calls)
        // Another provider still gets the day's two answers.
        val healthy = metered.cloud(ScriptedLlmClient.replies("ok"))
        repeat(2) { metered.gateway.chat(healthy.id, ask) }
        assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(healthy.id, ask) }
    }

    @Test
    fun `a call stopped while it waited after only slow-down answers is not one of today's answers either`() = runTest {
        // Wave-2a residual verification, NEW-LIMIT-CANCELLED-429: an objective paused or stopped while its call waits on a short
        // Retry-After must not use up one of the owner's answers, now or after a restart.
        val metered = MeteredWorld(this)
        metered.settings { it.copy(maxModelCallsPerDay = 2) }
        val healthy = metered.cloud(ScriptedLlmClient.replies("One", "Two", "Three"))
        val limited = metered.cloud(ScriptedLlmClient { _, _ -> ScriptStep.Fail(LlmException.RateLimited("Rate limit exceeded: free-models-per-min.", retryAfterMs = 10_000)) })
        metered.gateway.chat(healthy.id, ask)

        val waiting = async { runCatching { metered.gateway.chat(limited.id, ask) } }
        runCurrent()
        advanceTimeBy(3_000)
        waiting.cancel()
        runCurrent()

        assertEquals(1, metered.limiter.today().calls)
        val rows = metered.usage.byModel(UsageRange.day(metered.clock.now(), metered.zone))
        assertEquals(1, DailyUse.of(rows, emptyMap()).calls)
        assertEquals(1, DailyLimiter(metered.usage, metered.ownerPrices, metered.world.settings, metered.clock) { metered.zone }.today().calls)
        assertEquals("Two", metered.gateway.chat(healthy.id, ask).message.text)
        assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(healthy.id, ask) }
        // Its event still says it was stopped.
        assertEquals("Model call cancelled", metered.modelCalls()[1].title)
    }

    @Test
    fun `a call stopped while its second attempt was on its way still counts`() = runTest {
        // The attempt in flight may have been answered and billed: only calls whose every attempt was refused are left out.
        val metered = MeteredWorld(this)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val limited = metered.cloud(ScriptedLlmClient { _, _ ->
            if (attempts.getAndIncrement() == 0) ScriptStep.Fail(LlmException.RateLimited("Slow down.", retryAfterMs = 2_000))
            else { kotlinx.coroutines.awaitCancellation() }
        })
        val waiting = async { runCatching { metered.gateway.chat(limited.id, ask) } }
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(2, attempts.get())
        waiting.cancel()
        runCurrent()
        assertEquals(1, metered.limiter.today().calls)
    }

    @Test
    fun `raising the limit lets the next call go at once`() = runTest {
        val metered = MeteredWorld(this)
        metered.settings { it.copy(maxModelCallsPerDay = 1) }
        val model = metered.cloud(ScriptedLlmClient.replies("ok"))
        metered.gateway.chat(model.id, ask)
        assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(model.id, ask) }

        metered.settings { it.copy(maxModelCallsPerDay = 2) }

        metered.gateway.chat(model.id, ask)
    }

    @Test
    fun `the counts start again at midnight in the owner's zone`() = runTest {
        val newYork = ZoneId.of("America/New_York")
        val lateEvening = ZonedDateTime.of(2026, 11, 25, 23, 58, 0, 0, newYork).toInstant().toEpochMilli()
        val metered = MeteredWorld(this, zone = newYork, start = lateEvening)
        metered.settings { it.copy(maxModelCallsPerDay = 2) }
        val model = metered.cloud(ScriptedLlmClient.replies("ok"))
        repeat(2) { metered.gateway.chat(model.id, ask) }
        assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(model.id, ask) }

        advanceTimeBy(60_000)
        assertFailsWith<LlmException.DailyLimitReached>("23:59 is still today") { metered.gateway.chat(model.id, ask) }
        advanceTimeBy(61_000)

        metered.gateway.chat(model.id, ask)
        assertEquals(1, metered.limiter.today().calls)
    }

    @Test
    fun `in a half-hour zone the counts start again at local midnight, and the half hour before it counts for both days`() = runTest {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val start = ZonedDateTime.of(2026, 11, 25, 23, 0, 0, 0, kolkata).toInstant().toEpochMilli()
        val metered = MeteredWorld(this, zone = kolkata, start = start)
        metered.settings { it.copy(maxModelCallsPerDay = 2) }
        val model = metered.cloud(ScriptedLlmClient.replies("ok"))
        metered.gateway.chat(model.id, ask)
        // 23:40: the usage records file this call under the UTC hour that holds local midnight, so tomorrow's.
        metered.shift = 40 * 60_000L
        metered.gateway.chat(model.id, ask)
        metered.shift = 50 * 60_000L
        assertFailsWith<LlmException.DailyLimitReached>("23:50 is still today") { metered.gateway.chat(model.id, ask) }

        // Past midnight the counts are read again for the new day: the 23:40 call is in them (never fewer than made).
        metered.shift = 61 * 60_000L
        assertEquals(1, metered.limiter.today().calls)
        metered.gateway.chat(model.id, ask)
        assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(model.id, ask) }

        val fresh = DailyLimiter(metered.usage, metered.ownerPrices, metered.world.settings, metered.clock) { kolkata }
        assertEquals(2, fresh.today().calls, "a restart reads the same day")
        assertEquals(2, DailyUse.of(metered.usage.byModel(UsageRange.day(metered.clock.now(), kolkata)), emptyMap()).calls)
    }

    @Test
    fun `providers on this phone are counted but never limited, and do not use up the limit`() = runTest {
        val metered = MeteredWorld(this)
        metered.settings { it.copy(maxModelCallsPerDay = 2, maxTokensPerDay = 1_000) }
        val local = metered.onPhone(ScriptedLlmClient.script(ScriptStep.Reply("big", TokenUsage(5_000, 5_000))))
        val cloud = metered.cloud(ScriptedLlmClient.replies("ok"))

        repeat(5) { metered.gateway.chat(local.id, ask) }
        repeat(2) { metered.gateway.chat(cloud.id, ask) }
        assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(cloud.id, ask) }
        metered.gateway.chat(local.id, ask)

        val onPhone = metered.usage.byModel(UsageRange.ALL).single { it.locality == Locality.ON_DEVICE }
        assertEquals(6, onPhone.totals.ok, "counted in the usage records")
        assertEquals(2, metered.limiter.today().calls)
    }

    @Test
    fun `the money limit counts priced models at the owner's prices and stops every call once reached`() = runTest {
        val metered = MeteredWorld(this)
        metered.settings { it.copy(maxTokensPerDay = 1_000_000_000) }
        val priced = metered.cloud(ScriptedLlmClient.script(ScriptStep.Reply("costly", TokenUsage(400_000, 50_000))))
        val unpriced = metered.cloud(ScriptedLlmClient.replies("free"))
        metered.price(priced.id, inputUsdPerMTok = 2.0, outputUsdPerMTok = 6.0)

        metered.gateway.chat(priced.id, ask)

        // 400,000 × US$2 + 50,000 × US$6 per million: US$0.80 + US$0.30.
        assertEquals(1_100_000, metered.limiter.today().spendMicros)
        val refused = assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(unpriced.id, ask) }
        assertEquals(
            "Today's spending limit of US$1.00 is reached. It starts again at midnight, or you can raise it in Settings > Daily limits.",
            refused.message,
        )
    }

    @Test
    fun `without a price no money is counted, and cached input costs the cached price`() = runTest {
        val metered = MeteredWorld(this)
        metered.settings { it.copy(maxTokensPerDay = 1_000_000_000) }
        val unpriced = metered.cloud(ScriptedLlmClient.script(ScriptStep.Reply("a lot", TokenUsage(4_000_000, 1_000_000))))
        repeat(3) { metered.gateway.chat(unpriced.id, ask) }
        assertEquals(0, metered.limiter.today().spendMicros)
        assertFalse(metered.limiter.today().priced)

        val cached = metered.cloud(ScriptedLlmClient.script(ScriptStep.Reply("cached", TokenUsage(400_000, 50_000, cachedInputTokens = 300_000))))
        metered.price(cached.id, inputUsdPerMTok = 2.0, outputUsdPerMTok = 6.0, cachedUsdPerMTok = 0.5)
        metered.gateway.chat(cached.id, ask)

        // 100,000 × US$2 + 300,000 × US$0.50 + 50,000 × US$6 per million = US$0.65: under the dollar.
        assertEquals(650_000, metered.limiter.today().spendMicros)
        metered.gateway.chat(cached.id, ask)
    }

    @Test
    fun `the money sentence names the currency the prices are in`() = runTest {
        val metered = MeteredWorld(this)
        metered.settings { it.copy(maxTokensPerDay = 1_000_000_000) }
        val model = metered.cloud(ScriptedLlmClient.script(ScriptStep.Reply("costly", TokenUsage(1_000_000, 0))))
        metered.ownerPrices.set(ModelPrice(model.id, 1_000_000, 1_000_000, currencyLabel = "€"))
        metered.gateway.chat(model.id, ask)

        val refused = assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(model.id, ask) }

        assertTrue(refused.message!!.startsWith("Today's spending limit of €1.00 is reached."))
    }

    @Test
    fun `today's counts survive a restart, and clearing the usage history keeps them`() = runTest {
        val metered = MeteredWorld(this)
        metered.settings { it.copy(maxModelCallsPerDay = 3) }
        val model = metered.cloud(ScriptedLlmClient.replies("ok"))
        repeat(3) { metered.gateway.chat(model.id, ask) }

        fun restarted() = DailyLimiter(metered.usage, metered.ownerPrices, metered.world.settings, metered.clock) { metered.zone }
        assertFailsWith<LlmException.DailyLimitReached> { restarted().reserve(model.id, Locality.CLOUD) }
        metered.usage.clearHistory()
        assertFailsWith<LlmException.DailyLimitReached> { restarted().reserve(model.id, Locality.CLOUD) }
        assertEquals(3, restarted().today().calls)
    }

    @Test
    fun `when today's use cannot be read, nothing is sent off the phone`() = runTest {
        val broken = object : UsageReports by UsageReports.None {
            override suspend fun byModel(range: UsageRange): List<ModelUsage> = throw IllegalStateException("disk")
        }
        val metered = MeteredWorld(this, reports = { broken })
        val client = ScriptedLlmClient.replies("ok")
        val cloud = metered.cloud(client)
        val local = metered.onPhone(ScriptedLlmClient.replies("local"))

        val refused = assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(cloud.id, ask) }

        assertEquals(DailyLimiter.USE_UNREADABLE, refused.message)
        assertEquals(0, client.requests.size)
        assertEquals("local", metered.gateway.chat(local.id, ask).message.text, "a model on this phone is not limited")
    }

    @Test
    fun `when the prices cannot be read, the money limit fails closed`() = runTest {
        val metered = MeteredWorld(this, prices = {
            object : com.aicontrolcenter.core.usage.ModelPrices by com.aicontrolcenter.core.usage.ModelPrices.None {
                override suspend fun getAll(): List<ModelPrice> = throw IllegalStateException("disk")
            }
        })
        val client = ScriptedLlmClient.replies("ok")
        val cloud = metered.cloud(client)

        assertEquals(DailyLimiter.PRICES_UNREADABLE, assertFailsWith<LlmException.DailyLimitReached> { metered.gateway.chat(cloud.id, ask) }.message)

        // Without a money limit the prices are not needed.
        metered.settings { it.copy(maxSpendPerDayMicros = null) }
        metered.gateway.chat(cloud.id, ask)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `the counted use the screens show is the limiter's`() = runTest {
        val metered = MeteredWorld(this)
        val model = metered.cloud(ScriptedLlmClient.script(ScriptStep.Reply("ok", TokenUsage(1_000, 500))))
        val local = metered.onPhone(ScriptedLlmClient.replies("local"))
        metered.price(model.id, inputUsdPerMTok = 3.0, outputUsdPerMTok = 15.0)
        repeat(2) { metered.gateway.chat(model.id, ask) }
        metered.gateway.chat(local.id, ask)

        val shown = DailyUse.of(
            metered.usage.byModel(UsageRange.day(metered.clock.now(), metered.zone)),
            metered.ownerPrices.getAll().associateBy { it.modelId },
        )

        assertEquals(metered.limiter.today(), shown)
        assertEquals(DailyUse(calls = 2, tokens = 3_000, spendMicros = 21_000, priced = true), shown)
    }
}
