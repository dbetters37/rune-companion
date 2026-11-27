package com.aicontrolcenter.core.usage

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.registry.Locality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId

/*
 * Usage, cost and daily limits (Phase 6, K11; goals 1 and 2; rules 6, 7, 29).
 *
 * Every model call the gateway makes is recorded as a [ModelCallSample] and summed into one row
 * per hour, model, agent and source (`usage_hours`) and into one row per work item and model
 * (`work_usage`). Usage records hold numbers, ids and the labels of models and providers only:
 * no message content, no keys, no addresses (rule 7). They never leave the phone (the backup
 * carries prices, not usage), and no agent tool reads them. Costs are estimates from prices the
 * owner typed in ([ModelPrice]); nothing is ever fetched (rule 29).
 */

/** What a call was made for: a chat with the owner, an objective, or anything else (memory upkeep, a check). */
enum class UsageSource { CHAT, OBJECTIVE, OTHER }

/** How a call ended. */
enum class CallOutcome {
    /** The model answered. */
    OK,

    /** The call failed after its retries; [ModelCallSample.errorClass] says why. */
    FAILED,

    /** The owner or the app stopped it. */
    CANCELLED,

    /** The framework refused to make it (local-only mode, a disabled model, today's limit). */
    BLOCKED,
}

/** Why a call failed, one column each in `usage_hours` (`err_auth` … `err_other`). */
enum class ErrorClass {
    AUTH, RATE_LIMITED, OVERLOADED, TIMEOUT, NETWORK, SERVER, INVALID, CONTEXT, OTHER;

    /** Mapping from the gateway's failures. */
    companion object {
        fun of(e: LlmException): ErrorClass = when (e) {
            is LlmException.Authentication -> AUTH
            is LlmException.RateLimited -> RATE_LIMITED
            is LlmException.Overloaded -> OVERLOADED
            is LlmException.Timeout -> TIMEOUT
            is LlmException.Network -> NETWORK
            is LlmException.Server -> SERVER
            is LlmException.InvalidRequest, is LlmException.ModelNotFound, is LlmException.Unsupported -> INVALID
            is LlmException.ContextTooLong -> CONTEXT
            is LlmException.MalformedResponse, is LlmException.BlockedByPolicy, is LlmException.DailyLimitReached -> OTHER
        }
    }
}

/** What a cost is counted for: a task, an objective (all its tasks) or a chat. */
enum class WorkKind { TASK, OBJECTIVE, CHAT }

/** One work item a call's tokens are added to ([ModelCallSample.workItems]). */
data class WorkItem(val kind: WorkKind, val id: String)

/**
 * One model call as the gateway saw it. Numbers, ids and labels only (rule 7).
 *
 * Invariants: counts are not negative; [errorClass] is set exactly when [outcome] is FAILED;
 * [answerMs] (start of the attempt that answered to its last byte) is set exactly when the model
 * answered (OK); [ttftMs] (to the first chunk of a streamed answer) only then. Token counts are
 * null when the provider reported none; [cachedInputTokens] are part of [inputTokens], as in
 * `TokenUsage`.
 */
data class ModelCallSample(
    val at: Long,
    val modelId: ModelId,
    val modelLabel: String,
    val providerId: ProviderId,
    val providerLabel: String,
    val locality: Locality,
    val agentId: AgentId? = null,
    val taskId: TaskId? = null,
    val rootTaskId: TaskId? = null,
    val conversationId: ConversationId? = null,
    val source: UsageSource,
    val outcome: CallOutcome,
    val errorClass: ErrorClass? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val answerMs: Long? = null,
    val ttftMs: Long? = null,
    /** Attempts after the first. */
    val retries: Int = 0,
    /** The provider answered with another model than the one asked for. */
    val substituted: Boolean = false,
    /** The answer was cut off at the output limit. */
    val truncated: Boolean = false,
    /** Times the call waited because the provider said "slow down" (429). */
    val rateLimitedWaits: Int = 0,
    /** Bytes of the requests sent, all attempts. */
    val bytesSent: Long = 0,
) {
    init {
        require((errorClass != null) == (outcome == CallOutcome.FAILED)) { "A failed call, and only a failed call, carries an error class." }
        require((answerMs != null) == (outcome == CallOutcome.OK)) { "An answered call, and only an answered call, carries its answer time." }
        require(ttftMs == null || outcome == CallOutcome.OK) { "Only an answered call has a time to its first chunk." }
        require(listOfNotNull(inputTokens, outputTokens, cachedInputTokens, answerMs, ttftMs).all { it >= 0 }) { "Counts and times cannot be negative." }
        require(retries >= 0 && rateLimitedWaits >= 0 && bytesSent >= 0) { "Counts cannot be negative." }
    }

    /** The start of the hour (UTC) this sample is summed into. */
    val hourStart: Long get() = UsageRange.hourStart(at)

    /**
     * The work rows this call adds to: its TASK row ([taskId]) and its OBJECTIVE row
     * ([rootTaskId]) when it belongs to an objective; else its CHAT row ([conversationId]); else none.
     */
    val workItems: List<WorkItem>
        get() = if (taskId != null || rootTaskId != null) {
            listOfNotNull(taskId?.let { WorkItem(WorkKind.TASK, it.value) }, rootTaskId?.let { WorkItem(WorkKind.OBJECTIVE, it.value) })
        } else {
            listOfNotNull(conversationId?.let { WorkItem(WorkKind.CHAT, it.value) })
        }
}

/**
 * Counts summed over samples: one row of `usage_hours`, or several added up.
 *
 * [calls] every sample; [ok], [failed], [cancelled], [blocked] by outcome; [errors] the failed
 * ones by class (non-zero entries only); [retries], [substituted], [truncated],
 * [rateLimitedWaits] summed or counted from the samples; [noUsage] answered calls whose provider
 * reported no token counts; tokens summed over the samples that reported them; [answerMsSum]
 * over answered calls; [ttftMsSum] and [ttftCount] over the calls that measured a first chunk.
 */
data class UsageTotals(
    val calls: Long = 0,
    val ok: Long = 0,
    val failed: Long = 0,
    val cancelled: Long = 0,
    val blocked: Long = 0,
    val retries: Long = 0,
    val substituted: Long = 0,
    val truncated: Long = 0,
    val noUsage: Long = 0,
    val rateLimitedWaits: Long = 0,
    val errors: Map<ErrorClass, Long> = emptyMap(),
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val answerMsSum: Long = 0,
    val ttftMsSum: Long = 0,
    val ttftCount: Long = 0,
    val bytesSent: Long = 0,
) {
    init {
        require(errors.values.all { it > 0 }) { "Error counts hold non-zero entries only." }
    }

    val totalTokens: Long get() = inputTokens + outputTokens

    /**
     * The calls that count as model answers: those that sent a request (answered, failed or stopped), but not those a
     * "slow down" answer ended, nor those the app refused before sending anything. Today's limits, the Today tile and
     * every "model answers" line count with this one rule (INT3-1).
     */
    val countedCalls: Long get() = (ok + failed + cancelled - (errors[ErrorClass.RATE_LIMITED] ?: 0)).coerceAtLeast(0)

    /** Mean time to a whole answer, or null without answered calls. */
    val averageAnswerMs: Long? get() = if (ok > 0) answerMsSum / ok else null

    /** Mean time to the first chunk, or null when none was measured. */
    val averageTtftMs: Long? get() = if (ttftCount > 0) ttftMsSum / ttftCount else null

    operator fun plus(other: UsageTotals): UsageTotals = UsageTotals(
        calls = calls + other.calls, ok = ok + other.ok, failed = failed + other.failed, cancelled = cancelled + other.cancelled,
        blocked = blocked + other.blocked, retries = retries + other.retries, substituted = substituted + other.substituted,
        truncated = truncated + other.truncated, noUsage = noUsage + other.noUsage, rateLimitedWaits = rateLimitedWaits + other.rateLimitedWaits,
        errors = (errors.keys + other.errors.keys).associateWith { (errors[it] ?: 0) + (other.errors[it] ?: 0) },
        inputTokens = inputTokens + other.inputTokens, outputTokens = outputTokens + other.outputTokens,
        cachedInputTokens = cachedInputTokens + other.cachedInputTokens, answerMsSum = answerMsSum + other.answerMsSum,
        ttftMsSum = ttftMsSum + other.ttftMsSum, ttftCount = ttftCount + other.ttftCount, bytesSent = bytesSent + other.bytesSent,
    )

    /** Building totals. */
    companion object {
        /** The counts one [sample] adds. */
        fun of(sample: ModelCallSample): UsageTotals = UsageTotals(
            calls = 1,
            ok = if (sample.outcome == CallOutcome.OK) 1 else 0,
            failed = if (sample.outcome == CallOutcome.FAILED) 1 else 0,
            cancelled = if (sample.outcome == CallOutcome.CANCELLED) 1 else 0,
            blocked = if (sample.outcome == CallOutcome.BLOCKED) 1 else 0,
            retries = sample.retries.toLong(),
            substituted = if (sample.substituted) 1 else 0,
            truncated = if (sample.truncated) 1 else 0,
            noUsage = if (sample.outcome == CallOutcome.OK && sample.inputTokens == null && sample.outputTokens == null) 1 else 0,
            rateLimitedWaits = sample.rateLimitedWaits.toLong(),
            errors = sample.errorClass?.let { mapOf(it to 1L) } ?: emptyMap(),
            inputTokens = sample.inputTokens ?: 0, outputTokens = sample.outputTokens ?: 0, cachedInputTokens = sample.cachedInputTokens ?: 0,
            answerMsSum = sample.answerMs ?: 0, ttftMsSum = sample.ttftMs ?: 0, ttftCount = if (sample.ttftMs != null) 1 else 0,
            bytesSent = sample.bytesSent,
        )

        fun of(samples: Iterable<ModelCallSample>): UsageTotals = samples.fold(UsageTotals()) { sum, sample -> sum + of(sample) }
    }
}

/**
 * A span of time for reports: the hours (UTC) whose start lies in [fromMs, untilMs). A sample
 * belongs to it when its hour ([ModelCallSample.hourStart]) does. The local day and month are cut
 * at whole UTC hours: [day] starts at the start of the UTC hour holding local midnight. For a
 * zone with a whole-hour offset that is exactly the local day; for one with a half or quarter
 * hour (India, Nepal) the day starts up to 59 minutes before local midnight. Room's sums and the
 * in-memory sums therefore agree to the call.
 */
data class UsageRange(val fromMs: Long, val untilMs: Long) {
    init {
        require(fromMs <= untilMs) { "A range cannot end before it starts." }
    }

    /** True when an hour starting at [hourStart] belongs to this range. */
    fun containsHour(hourStart: Long): Boolean = hourStart >= fromMs && hourStart < untilMs

    /** Building ranges. */
    companion object {
        const val HOUR_MS: Long = 3_600_000L

        /** Every hour there is. */
        val ALL: UsageRange = UsageRange(Long.MIN_VALUE, Long.MAX_VALUE)

        /** The start of the UTC hour holding [at]. */
        fun hourStart(at: Long): Long = Math.floorDiv(at, HOUR_MS) * HOUR_MS

        /** The owner's local day holding [now] in [zone], cut at whole UTC hours. */
        fun day(now: Long, zone: ZoneId): UsageRange {
            val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return UsageRange(
                hourStart(date.atStartOfDay(zone).toInstant().toEpochMilli()),
                hourStart(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()),
            )
        }

        /** The owner's local month holding [now] in [zone], cut at whole UTC hours. */
        fun month(now: Long, zone: ZoneId): UsageRange {
            val first = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
            return UsageRange(
                hourStart(first.atStartOfDay(zone).toInstant().toEpochMilli()),
                hourStart(first.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()),
            )
        }

        /** The last [days] local days up to and including today, cut at whole UTC hours. */
        fun lastDays(days: Int, now: Long, zone: ZoneId): UsageRange {
            require(days >= 1) { "At least one day." }
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return UsageRange(
                hourStart(today.minusDays(days - 1L).atStartOfDay(zone).toInstant().toEpochMilli()),
                hourStart(today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()),
            )
        }
    }
}

/** Usage of one model over a range. Labels and locality as recorded by the range's newest sample of the model. */
data class ModelUsage(
    val modelId: ModelId,
    val modelLabel: String,
    val providerId: ProviderId,
    val providerLabel: String,
    val locality: Locality,
    val totals: UsageTotals,
)

/** Usage of one agent over a range; [agentId] null for calls made for no agent. */
data class AgentUsage(val agentId: AgentId?, val totals: UsageTotals)

/**
 * Usage of one agent with one model over a range (wave 3): what an estimated cost per agent needs, since prices are per
 * model. [agentId] null for calls made for no agent.
 */
data class AgentModelUsage(val agentId: AgentId?, val modelId: ModelId, val totals: UsageTotals)

/** Usage of one provider over a range: its reliability (failures by class) and speed. Labels as recorded by its newest sample. */
data class ProviderUsage(val providerId: ProviderId, val providerLabel: String, val locality: Locality, val totals: UsageTotals)

/** One row of `work_usage`: what one model's calls for one work item came to. [updatedAt] is the time of the last call recorded. */
data class WorkUsage(
    val kind: WorkKind,
    val workId: String,
    val modelId: ModelId,
    val calls: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val updatedAt: Long,
) {
    val tokens: TokenCounts get() = TokenCounts(inputTokens, outputTokens, cachedInputTokens)
}

/**
 * Where the gateway records usage (Phase 6, K11). Agent-facing code may hold it (the gateway's
 * journal and the daily limiter, WP4): it adds and sums, and can delete nothing (security review
 * of wave 1, F4; deleting is [UsageHousekeeping], the upkeep job's). Implementations: Room
 * (`RoomUsageLedger`, WP1b) and in-memory (`:testkit`); both pass `UsageLedgerContract`.
 */
interface UsageLedger {
    /**
     * Adds [sample] to its hour row (hour, model, agent or "" for none, source) and to its work
     * rows ([ModelCallSample.workItems], with the call's model) in one transaction. The labels
     * and locality of the hour row become the sample's. Recording is best effort for the caller:
     * the gateway never fails a call because recording failed.
     */
    suspend fun record(sample: ModelCallSample)

    /** Totals of every sample whose hour starts at or after [fromMs]: all models, agents and sources. */
    suspend fun totalsSince(fromMs: Long): UsageTotals

    /** Records nothing and reports nothing used. */
    object None : UsageLedger {
        override suspend fun record(sample: ModelCallSample) {}

        override suspend fun totalsSince(fromMs: Long): UsageTotals = UsageTotals()
    }
}

/**
 * Deleting old usage (Phase 6, K11, retention; security review of wave 1, F4). Held only by the
 * upkeep side (`UpkeepGraph`, WP2; WP4's retention step), never by anything an agent reaches: both
 * architecture tests list it. Today's rows can never go: they seed the daily limits after a
 * restart (rule 6, S29), so every implementation clamps [pruneHours] to the start of the owner's
 * day ([UsageRange.day]) whatever it is asked. Implementations: Room (`RoomUsageHousekeeping`) and
 * the in-memory store (`:testkit`); both pass `UsageLedgerContract`.
 */
interface UsageHousekeeping {
    /**
     * Deletes the hour rows that started before [beforeMs] (retention, `usageRetentionDays`), but
     * never one of today's: a time inside today (or later) deletes only the rows before today.
     * Work rows are kept. Returns how many rows went.
     */
    suspend fun pruneHours(beforeMs: Long): Int

    /**
     * Deletes work rows whose task, objective or chat no longer exists. Room does it with one
     * statement against `tasks` and `conversations`; deleting a task tree or a chat already
     * deletes its rows in the same transaction (WP1b). Returns how many rows went; 0 where the
     * store cannot know (the in-memory store).
     */
    suspend fun pruneOrphanedWork(): Int

    /** Deletes nothing. */
    object None : UsageHousekeeping {
        override suspend fun pruneHours(beforeMs: Long): Int = 0

        override suspend fun pruneOrphanedWork(): Int = 0
    }
}

/**
 * What the Usage tab, the Dashboard and the daily limiter read (Phase 6, K11). Ranges are built
 * with [UsageRange] (today: [UsageRange.day]; this month: [UsageRange.month]). Lists are sorted
 * by calls descending, then id ascending (a null agent last). Flows emit at once and again after
 * a change that alters what they emit. Implementations: Room (`RoomUsageReports`, WP1b) and
 * in-memory (`:testkit`); both pass `UsageLedgerContract`.
 */
interface UsageReports {
    suspend fun totals(range: UsageRange): UsageTotals

    fun observeTotals(range: UsageRange): Flow<UsageTotals>

    suspend fun byModel(range: UsageRange): List<ModelUsage>

    fun observeByModel(range: UsageRange): Flow<List<ModelUsage>>

    suspend fun byAgent(range: UsageRange): List<AgentUsage>

    /**
     * Usage by agent and model together over [range], most calls first (then by agent, calls for no agent last, then by
     * model): for an estimated cost per agent from the per-model prices. Nothing for a store that cannot tell.
     */
    suspend fun byAgentAndModel(range: UsageRange): List<AgentModelUsage> = emptyList()

    /** Reliability and speed per provider. */
    suspend fun byProvider(range: UsageRange): List<ProviderUsage>

    /** The rows of one work item, one per model, by model id. Empty when nothing was recorded for it. */
    suspend fun workUsage(kind: WorkKind, id: String): List<WorkUsage>

    fun observeWorkUsage(kind: WorkKind, id: String): Flow<List<WorkUsage>>

    /** Reports nothing. */
    object None : UsageReports {
        override suspend fun totals(range: UsageRange): UsageTotals = UsageTotals()

        override fun observeTotals(range: UsageRange): Flow<UsageTotals> = flowOf(UsageTotals())

        override suspend fun byModel(range: UsageRange): List<ModelUsage> = emptyList()

        override fun observeByModel(range: UsageRange): Flow<List<ModelUsage>> = flowOf(emptyList())

        override suspend fun byAgent(range: UsageRange): List<AgentUsage> = emptyList()

        override suspend fun byProvider(range: UsageRange): List<ProviderUsage> = emptyList()

        override suspend fun workUsage(kind: WorkKind, id: String): List<WorkUsage> = emptyList()

        override fun observeWorkUsage(kind: WorkKind, id: String): Flow<List<WorkUsage>> = flowOf(emptyList())
    }
}

/**
 * The owner's "Clear usage history" (Phase 6, K11). Owner-only: only the Usage tab holds it
 * (both architecture tests list it). Implementations know the clock and the owner's zone.
 */
interface OwnerUsage {
    /**
     * Deletes the usage history before today: hour rows that started before today's range
     * ([UsageRange.day]) and work rows last updated before it. Today's rows stay, so clearing
     * never resets today's limits (S29). Returns how many hour rows went.
     */
    suspend fun clearHistory(): Int
}

/** Token counts of one or more calls; [cachedInput] is part of [input]. */
data class TokenCounts(val input: Long, val output: Long, val cachedInput: Long = 0) {
    init {
        require(input >= 0 && output >= 0 && cachedInput >= 0) { "Token counts cannot be negative." }
    }

    val total: Long get() = input + output
}

/**
 * A model's price, as the owner typed it from their provider's price page, in millionths of the
 * currency unit per million tokens ("micros per Mtok": US$3.00 per million tokens is 3_000_000).
 * [cachedInputPerMTokMicros] null means cached input costs the input price. Never fetched.
 *
 * **One currency** (wave 3, D3A-8; the lead's decision, which the owner may change later): the app keeps prices and the
 * money limit in US$, since every provider the owner uses prices in US$. The price fields offer no other currency. A
 * price stored in another currency (typed before this rule, or brought by a backup) does not [count]: it is treated as
 * no price everywhere (the limiter, the routine checks, every screen) and never added into a US$ total.
 */
data class ModelPrice(
    val modelId: ModelId,
    val inputPerMTokMicros: Long,
    val outputPerMTokMicros: Long,
    val cachedInputPerMTokMicros: Long? = null,
    val currencyLabel: String = DEFAULT_CURRENCY,
    /** Set by the store when the owner saves the price. */
    val updatedAt: Long = 0,
) {
    init {
        check(inputPerMTokMicros, outputPerMTokMicros, cachedInputPerMTokMicros, currencyLabel)?.let { throw IllegalArgumentException(it) }
    }

    /** Whether this price counts: it is in US$ ("US$", "USD" or "$", any case, spaces ignored). */
    val counts: Boolean get() = isUsDollars(currencyLabel)

    /** Bounds and validation. */
    companion object {
        /** The app's one currency (D3A-8). */
        const val DEFAULT_CURRENCY: String = "US$"

        /** What a screen says of a stored price in another currency, which does not count. */
        const val OTHER_CURRENCY: String = "This price is in another currency. Enter it in US$ to count it."

        /** True for the labels that mean US dollars. */
        fun isUsDollars(label: String): Boolean = label.filterNot(Char::isWhitespace).uppercase() in US_DOLLARS

        private val US_DOLLARS = setOf("US$", "USD", "$")

        /** The highest price accepted: 10,000 per million tokens. */
        const val MAX_PER_MTOK_MICROS: Long = 10_000_000_000L

        const val MAX_CURRENCY_CHARS: Int = 8

        /** Why these values cannot be a price (a sentence for the owner), or null when they can. */
        fun check(input: Long, output: Long, cachedInput: Long?, currencyLabel: String): String? = when {
            listOfNotNull(input, output, cachedInput).any { it < 0 } -> "A price cannot be negative."
            listOfNotNull(input, output, cachedInput).any { it > MAX_PER_MTOK_MICROS } -> "A price can be at most 10,000 per million tokens."
            currencyLabel.isBlank() -> "Say which currency the price is in, for example US$."
            currencyLabel.length > MAX_CURRENCY_CHARS -> "A currency is written in at most $MAX_CURRENCY_CHARS characters."
            else -> null
        }
    }
}

/** The prices that count ([ModelPrice.counts]), each labelled [ModelPrice.DEFAULT_CURRENCY]; the others are left out. */
fun Iterable<ModelPrice>.counted(): List<ModelPrice> =
    filter { it.counts }.map { if (it.currencyLabel == ModelPrice.DEFAULT_CURRENCY) it else it.copy(currencyLabel = ModelPrice.DEFAULT_CURRENCY) }

/**
 * [ModelPrices] as everything that adds up money reads them (D3A-8): only the prices that count, each in US$. Only the
 * model editor reads every stored price, to say that one in another currency does not count.
 */
fun ModelPrices.counted(): ModelPrices = this as? CountedModelPrices ?: CountedModelPrices(this)

private class CountedModelPrices(private val stored: ModelPrices) : ModelPrices {
    override fun observeAll(): Flow<List<ModelPrice>> = stored.observeAll().map { it.counted() }

    override suspend fun getAll(): List<ModelPrice> = stored.getAll().counted()

    override suspend fun get(modelId: ModelId): ModelPrice? = stored.get(modelId)?.let { listOf(it).counted().singleOrNull() }
}

/** The owner's prices, read (the daily limiter, the Usage tab). Implementations pass `ModelPricesContract`. */
interface ModelPrices {
    /** Every price, by model id. */
    fun observeAll(): Flow<List<ModelPrice>>

    suspend fun getAll(): List<ModelPrice>

    suspend fun get(modelId: ModelId): ModelPrice?

    /** No prices. */
    object None : ModelPrices {
        override fun observeAll(): Flow<List<ModelPrice>> = flowOf(emptyList())

        override suspend fun getAll(): List<ModelPrice> = emptyList()

        override suspend fun get(modelId: ModelId): ModelPrice? = null
    }
}

/** The owner typing in prices (Settings > Models). Owner-only: both architecture tests list it. */
interface OwnerModelPrices {
    /**
     * Stores [price] for its model, replacing an earlier one, with updatedAt = now, and returns
     * the stored price. [price] is valid by construction ([ModelPrice.check]); a price of a model
     * that is later removed is kept and simply not shown. Refused only when storing failed.
     */
    suspend fun set(price: ModelPrice): OwnerResult<ModelPrice>

    /** Removes the price of [modelId]; its costs are no longer shown. Returns false when it had none. */
    suspend fun remove(modelId: ModelId): Boolean
}

/** Estimated costs (rule 29): "estimated from the prices you entered; your provider's bill is what counts". */
object CostEstimate {
    const val DISCLAIMER: String = "Estimated from the prices you entered; your provider's bill is what counts."

    /**
     * The cost of [tokens] at [price], in millionths of a US dollar, rounded half up; null without a
     * price, or with one in another currency, which does not count (D3A-8; no money is shown then).
     * Cached input tokens are charged at the cached price (the input price when none is set), the
     * rest of the input at the input price. Cached tokens above the input count are treated as input.
     */
    fun of(tokens: TokenCounts, price: ModelPrice?): Long? {
        if (price == null || !price.counts) return null
        val cached = minOf(tokens.cachedInput, tokens.input)
        val uncached = tokens.input - cached
        val cachedPrice = price.cachedInputPerMTokMicros ?: price.inputPerMTokMicros
        val millionths = big(uncached) * big(price.inputPerMTokMicros) + big(cached) * big(cachedPrice) +
            big(tokens.output) * big(price.outputPerMTokMicros)
        val rounded = (millionths + HALF_MILLION) / MILLION
        return if (rounded.bitLength() < 63) rounded.toLong() else Long.MAX_VALUE
    }

    /** The cost of every row of [usage] at [prices]: null when no row's model has a price that counts. Rows without one add nothing. */
    fun of(usage: List<WorkUsage>, prices: Map<ModelId, ModelPrice>): Long? {
        val costs = usage.mapNotNull { row -> of(row.tokens, prices[row.modelId]) }
        if (costs.isEmpty()) return null
        return costs.fold(0L) { sum, cost -> if (Long.MAX_VALUE - sum < cost) Long.MAX_VALUE else sum + cost }
    }

    private fun big(value: Long): BigInteger = BigInteger.valueOf(value)

    private val MILLION: BigInteger = BigInteger.valueOf(1_000_000)
    private val HALF_MILLION: BigInteger = BigInteger.valueOf(500_000)
}

/** Which of today's limits was reached. */
enum class DailyLimitKind { CALLS, TOKENS, SPEND }

/**
 * Today's limits (Phase 6, Q2, rule 6): whichever comes first. Null means no limit of that kind.
 * The defaults are the owner's decision Q2: 200 model answers, 500,000 tokens and US$1.00 of
 * estimated cost (counted only for models with a price). Providers on this phone are counted,
 * not limited.
 */
data class DailyLimits(val maxCalls: Int?, val maxTokens: Long?, val maxSpendMicros: Long?) {
    init {
        require(maxCalls == null || maxCalls >= 0) { "A limit cannot be negative." }
        require(maxTokens == null || maxTokens >= 0) { "A limit cannot be negative." }
        require(maxSpendMicros == null || maxSpendMicros >= 0) { "A limit cannot be negative." }
    }

    /** The stricter of this and [other], limit by limit; a limit beats no limit (rule 8: a restore never relaxes them). */
    fun stricter(other: DailyLimits): DailyLimits = DailyLimits(
        minOfNullable(maxCalls, other.maxCalls), minOfNullable(maxTokens, other.maxTokens), minOfNullable(maxSpendMicros, other.maxSpendMicros),
    )

    /**
     * The limit already reached when today's use is [calls] calls, [tokens] tokens and
     * [spendMicros] of estimated cost, or null when another call may go. A limit is reached when
     * the use is at or above it: with 200 calls allowed, the 200th call goes and the 201st does not.
     */
    fun reachedBy(calls: Long, tokens: Long, spendMicros: Long): DailyLimitKind? = when {
        maxCalls != null && calls >= maxCalls -> DailyLimitKind.CALLS
        maxTokens != null && tokens >= maxTokens -> DailyLimitKind.TOKENS
        maxSpendMicros != null && spendMicros >= maxSpendMicros -> DailyLimitKind.SPEND
        else -> null
    }

    /**
     * The sentence for the owner when [kind] was reached, also the message of `LlmException.DailyLimitReached`. Money is
     * in the app's one currency, US$ (D3A-8).
     */
    fun sentence(kind: DailyLimitKind): String {
        val what = when (kind) {
            // "1 model answer", "200 model answers" (wave 3: plain words with a limit of one).
            DailyLimitKind.CALLS -> "today's limit of ${grouped(maxCalls?.toLong() ?: 0)} model ${if (maxCalls == 1) "answer" else "answers"}"
            DailyLimitKind.TOKENS -> "today's limit of ${grouped(maxTokens ?: 0)} ${if (maxTokens == 1L) "token" else "tokens"}"
            DailyLimitKind.SPEND -> "today's spending limit of ${money(maxSpendMicros ?: 0, ModelPrice.DEFAULT_CURRENCY)}"
        }
        return what.replaceFirstChar { it.uppercase() } + " is reached. It starts again at midnight, or you can raise it in Settings › Daily limits."
    }

    /** Defaults and bounds. */
    companion object {
        const val DEFAULT_MAX_CALLS: Int = 200
        const val DEFAULT_MAX_TOKENS: Long = 500_000L

        /** US$1.00. */
        const val DEFAULT_MAX_SPEND_MICROS: Long = 1_000_000L

        /** The owner's decision Q2. */
        val DEFAULT: DailyLimits = DailyLimits(DEFAULT_MAX_CALLS, DEFAULT_MAX_TOKENS, DEFAULT_MAX_SPEND_MICROS)

        /** No limits at all. */
        val NONE: DailyLimits = DailyLimits(null, null, null)

        /** What the Settings screen accepts. */
        val CALLS_RANGE: IntRange = 1..100_000
        val TOKENS_RANGE: LongRange = 1_000L..1_000_000_000L
        val SPEND_RANGE_MICROS: LongRange = 10_000L..1_000_000_000L

        /** Pauses: "Paused: today's limit is reached." */
        const val PAUSED_TEXT: String = "Paused: today's limit is reached. Resume after midnight or raise the limit in Settings."

        private fun <T : Comparable<T>> minOfNullable(a: T?, b: T?): T? = when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }

        private fun grouped(value: Long): String = "%,d".format(java.util.Locale.ROOT, value)

        /** [micros] of [currencyLabel] in plain figures: "US$1.00", "US$0.25". */
        fun money(micros: Long, currencyLabel: String): String {
            val cents = (micros + 5_000) / 10_000
            return currencyLabel + grouped(cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
        }
    }
}
