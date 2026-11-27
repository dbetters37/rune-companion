package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.usage.CostEstimate
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.ModelUsage
import com.aicontrolcenter.core.usage.TokenCounts
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.core.usage.UsageTotals
import com.aicontrolcenter.core.usage.counted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * What today's daily limits count (Phase 6, rule 6, Q2): the model answers, tokens and estimated money of calls to
 * providers that are not on this phone. [calls] counts every call that sent at least one request (answered, failed
 * or stopped), except a call that ended because the provider asked the app to slow down (a used-up quota answers
 * nothing and costs nothing, so it never uses up the owner's answers for the other providers, INT-1); a call the app
 * refused before sending anything does not count either. [spendMicros] is the tokens of each
 * model at the price the owner entered for it now; models without a price add nothing ([priced] is false when no
 * model used today has one). The Usage screen and the New objective form show the same numbers as the limiter with
 * [of] over `UsageReports.byModel(UsageRange.day(now, zone))` and the owner's prices.
 */
data class DailyUse(val calls: Long, val tokens: Long, val spendMicros: Long, val priced: Boolean) {
    /** Building the counts. */
    companion object {
        val NONE: DailyUse = DailyUse(0, 0, 0, priced = false)

        /** Today's counted use from the usage rows of today ([rows]) and the owner's [prices]. Rows on this phone are left out. */
        fun of(rows: List<ModelUsage>, prices: Map<ModelId, ModelPrice>): DailyUse {
            val limited = rows.filter { it.locality.leavesDevice }
            val tokens = limited.associate { it.modelId to TokenCounts(it.totals.inputTokens, it.totals.outputTokens, it.totals.cachedInputTokens) }
            return DailyUse(
                calls = limited.sumOf { counted(it.totals) },
                tokens = tokens.values.sumOf { it.total },
                spendMicros = spend(tokens, prices),
                priced = tokens.keys.any { prices[it]?.counts == true },
            )
        }

        /** The calls of [totals] the limits count: those that sent a request, but not those a "slow down" answer ended. */
        fun counted(totals: UsageTotals): Long = totals.countedCalls

        internal fun spend(tokens: Map<ModelId, TokenCounts>, prices: Map<ModelId, ModelPrice>): Long =
            tokens.entries.fold(0L) { sum, (model, counts) ->
                val cost = CostEstimate.of(counts, prices[model]) ?: 0L
                if (Long.MAX_VALUE - sum < cost) Long.MAX_VALUE else sum + cost
            }
    }
}

/**
 * Today's limits of model answers, tokens and estimated money (Phase 6, rule 6, Q2), checked by the gateway before
 * every attempt that would reach a provider not on this phone, retries included.
 *
 * How it holds:
 *  - **Reserve, then settle, under one lock.** The first attempt of a call reserves a place ([reserve]); the
 *    reservation counts as a call at once, so two calls that start together at 199 of 200 cannot both pass: the
 *    second sees 200. It also reserves the call's estimated tokens (the request's estimated input and the room it
 *    leaves for the answer) and so their price: a call is checked against what has settled plus what every other
 *    call in flight reserved, so calls started together cannot all pass at US$0.99 of US$1.00 (security review of
 *    wave 2a, SEC-W2A-4). Like a single call, the last one let through can still take the day past the limit by its
 *    own cost. A retry of the same call checks again ([reserve] with its ticket) without counting itself twice, so a
 *    limit reached meanwhile by other calls stops the retry. When the call ends, [settle] turns the reservation into
 *    a counted call and replaces the estimate with the tokens the provider reported.
 *  - **Today** is the owner's local date in [zone] (read each time, so travelling moves it): the counts start again
 *    at local midnight. At first use, and again when the date changes, they are read from the usage records for
 *    [UsageRange.day] ([UsageReports.byModel]), so a restart does not reset them; clearing the usage history never
 *    clears today (S29). The records cut days at whole UTC hours: in a half- or quarter-hour zone the records of a
 *    day start up to 59 minutes before local midnight, so calls made in that last stretch before midnight count for
 *    the day they were made in and again for the next one. The limits may count a call twice; they never miss one.
 *  - **Money** is each model's tokens today at the price the owner entered for it now ([CostEstimate]); a model
 *    without a price adds nothing, so the money limit only ever stops priced models' spending.
 *  - **On this phone** a provider is counted in the usage records but not limited and not counted here: it costs
 *    nothing and never leaves the phone.
 *  - **Fails closed.** When today's use or the prices cannot be read, a call to a provider off the phone is refused
 *    with a sentence that says so; nothing is sent. A call it cannot account for is never let through.
 *
 * Only usage reports and the read-only prices: nothing here can change usage, prices or settings (rule 3, F4).
 */
class DailyLimiter(
    private val reports: UsageReports,
    private val prices: ModelPrices,
    private val settings: SettingsView,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /**
     * One call's place in today's count, from its first attempt to its end. [limited] is false for a provider on this
     * phone, which is never refused and not counted here.
     */
    class Ticket internal constructor(
        val limited: Boolean,
        internal val modelId: ModelId? = null,
        /** The tokens reserved for the call until it settles; null when none were. */
        internal val estimate: TokenCounts? = null,
    ) {
        private val ended = java.util.concurrent.atomic.AtomicBoolean(false)

        /** True the first time only. */
        internal fun end(): Boolean = ended.compareAndSet(false, true)
    }

    private val lock = Mutex()

    /** The day the counts below belong to; null until the first use. */
    private var day: UsageRange? = null
    private var calls: Long = 0
    private val tokens = HashMap<ModelId, TokenCounts>()

    /** Calls that reserved a place and have not ended: counted as calls already. */
    private var inFlight: Int = 0

    /** The estimated tokens of the calls in flight, by model: counted with today's tokens and money until they settle. */
    private val reserved = HashMap<ModelId, TokenCounts>()

    /**
     * Reserves a place for the next attempt of a call to [modelId] on a provider at [locality]: the first attempt
     * when [held] is null, a retry of the call that holds [held] otherwise. A first attempt also reserves [estimate],
     * the call's estimated tokens, which count for the other calls until it settles. Returns the ticket the call keeps
     * until [settle].
     *
     * @throws LlmException.DailyLimitReached when a limit is reached (nothing may be sent), with the owner's sentence.
     */
    suspend fun reserve(modelId: ModelId, locality: Locality, held: Ticket? = null, estimate: TokenCounts? = null): Ticket {
        if (held != null && !held.limited) return held
        if (!locality.leavesDevice) return held ?: Ticket(limited = false)
        val limits = settings.get().dailyLimits
        // Read before the lock: the store must not be read while every other call waits.
        val priced = if (limits.maxSpendMicros != null) readPrices() else emptyMap()
        // Twice at most: the day may change between reading today's use and taking the lock (midnight).
        repeat(2) {
            val now = clock.now()
            val seed = seedIfNeeded(now)
            lock.withLock {
                enterDay(now, seed)
                if (day != null) {
                    val others = if (held == null) inFlight else inFlight - 1
                    // Today's tokens with what the other calls in flight reserved (not this call's own estimate).
                    val counted = plus(tokens, reserved)
                    held?.estimate?.let { own -> held.modelId?.let { counted[it] = minus(counted[it], own) } }
                    val spend = DailyUse.spend(counted, priced)
                    limits.reachedBy(calls + others, counted.values.sumOf { it.total }, spend)?.let { kind ->
                        throw LlmException.DailyLimitReached(limits.sentence(kind))
                    }
                    if (held != null) return held
                    inFlight++
                    estimate?.let { reserved[modelId] = add(reserved[modelId], it) }
                    return Ticket(limited = true, modelId = modelId, estimate = estimate)
                }
            }
            if (seed != null && seed.rows == null) throw LlmException.DailyLimitReached(USE_UNREADABLE)
        }
        throw LlmException.DailyLimitReached(USE_UNREADABLE)
    }

    /**
     * Ends [ticket]'s call: it counts as one call of today's (the day holding [at]) with the tokens the provider
     * reported ([used], null when none were), at [modelId]'s price; not as a call when [counted] is false (it ended
     * because the provider asked the app to slow down, see [DailyUse]). Safe to call more than once and when
     * cancelled; never throws.
     */
    suspend fun settle(ticket: Ticket, modelId: ModelId, used: TokenCounts?, at: Long, counted: Boolean = true) {
        if (!ticket.end() || !ticket.limited) return
        withContext(NonCancellable) {
            val seed = try {
                seedIfNeeded(at)
            } catch (e: Exception) {
                null
            }
            lock.withLock {
                inFlight = (inFlight - 1).coerceAtLeast(0)
                val estimate = ticket.estimate
                val model = ticket.modelId
                if (estimate != null && model != null) {
                    val left = minus(reserved[model], estimate)
                    if (left.total == 0L && left.cachedInput == 0L) reserved.remove(model) else reserved[model] = left
                }
                enterDay(at, seed)
                if (counted) calls++
                if (used != null) tokens[modelId] = (tokens[modelId] ?: TokenCounts(0, 0)).let {
                    TokenCounts(it.input + used.input, it.output + used.output, it.cachedInput + used.cachedInput)
                }
            }
        }
    }

    /** Today's counted use as the limiter sees it now, for tests and the owner's screens. */
    suspend fun today(): DailyUse {
        val priced = try {
            readPrices()
        } catch (e: LlmException.DailyLimitReached) {
            emptyMap()
        }
        val now = clock.now()
        val seed = seedIfNeeded(now)
        return lock.withLock {
            enterDay(now, seed)
            DailyUse(calls + inFlight, tokens.values.sumOf { it.total }, DailyUse.spend(tokens, priced), tokens.keys.any { it in priced })
        }
    }

    /** Today's rows from the usage records, read only when the counts are not of the day holding [now]; null otherwise. */
    private suspend fun seedIfNeeded(now: Long): Seed? {
        val range = UsageRange.day(now, zone())
        if (lock.withLock { day == range }) return null
        val rows = try {
            reports.byModel(range)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Seed(range, null)
        }
        return Seed(range, rows)
    }

    /** Under the lock: moves the counts to the day holding [now], from [seed] when it was read for that day. */
    private fun enterDay(now: Long, seed: Seed?) {
        val range = UsageRange.day(now, zone())
        if (day == range) return
        val rows = seed?.takeIf { it.range == range }?.rows
        tokens.clear()
        if (rows == null) {
            // Today's use could not be read: start from nothing but remember that the day is not known yet, so the
            // next reservation reads again (and refuses meanwhile, see reserve).
            calls = 0
            day = null
            return
        }
        val limited = rows.filter { it.locality.leavesDevice }
        calls = limited.sumOf { DailyUse.counted(it.totals) }
        limited.forEach { tokens[it.modelId] = TokenCounts(it.totals.inputTokens, it.totals.outputTokens, it.totals.cachedInputTokens) }
        day = range
    }

    // Only the prices that count (US$, D3A-8): one in another currency is no price here, as on every screen.
    private suspend fun readPrices(): Map<ModelId, ModelPrice> = try {
        prices.getAll().counted().associateBy { it.modelId }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw LlmException.DailyLimitReached(PRICES_UNREADABLE)
    }

    private class Seed(val range: UsageRange, val rows: List<ModelUsage>?)

    private fun add(a: TokenCounts?, b: TokenCounts): TokenCounts =
        if (a == null) b else TokenCounts(a.input + b.input, a.output + b.output, a.cachedInput + b.cachedInput)

    private fun minus(a: TokenCounts?, b: TokenCounts): TokenCounts =
        if (a == null) TokenCounts(0, 0)
        else TokenCounts((a.input - b.input).coerceAtLeast(0), (a.output - b.output).coerceAtLeast(0), (a.cachedInput - b.cachedInput).coerceAtLeast(0))

    private fun plus(a: Map<ModelId, TokenCounts>, b: Map<ModelId, TokenCounts>): HashMap<ModelId, TokenCounts> =
        HashMap(a).also { sum -> b.forEach { (model, counts) -> sum[model] = add(sum[model], counts) } }

    /** Sentences. */
    companion object {
        /** The answer's tokens a call is assumed to use when its request does not bound them: for the estimate only. */
        const val DEFAULT_ANSWER_ESTIMATE: Long = 1_000

        const val USE_UNREADABLE: String =
            "The app could not read today's use, so it cannot check your daily limits. Nothing was sent. Try again in a moment."
        const val PRICES_UNREADABLE: String =
            "The app could not read the prices you entered, so it cannot check your daily spending limit. Nothing was sent. Try again in a moment."
    }
}
