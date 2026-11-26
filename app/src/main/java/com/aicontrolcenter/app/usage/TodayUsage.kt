package com.aicontrolcenter.app.usage

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.framework.llm.DailyUse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import java.time.Instant
import java.time.ZoneId

/**
 * Today's use as the daily limits count it, with the limits themselves (Phase 6, rule 6, Q2): what the Today tile, the
 * Daily limits screen and the New objective form show ("Today: 37 of 200 answers").
 *
 * It counts exactly what the gateway's limiter counts ([DailyUse.of] over today's usage rows and the owner's prices):
 * calls to providers off the phone that sent a request, their reported tokens, and money only for models with a price.
 */
data class TodayUse(
    val use: DailyUse,
    val limits: DailyLimits,
    /**
     * The currency the money limit is read in ([limitCurrencyOf] the owner's prices): every screen that says "US$0.12 of
     * US$1.00" uses it, as the limiter's own sentence does (D3A-8).
     */
    val currency: String = ModelPrice.DEFAULT_CURRENCY,
) {
    /** Which limit is reached now, if any (the limiter refuses the next call off the phone then). */
    val reached: Boolean get() = limits.reachedBy(use.calls, use.tokens, use.spendMicros) != null

    companion object {
        val NONE: TodayUse = TodayUse(DailyUse.NONE, DailyLimits.NONE)
    }
}

/**
 * Reads today's use for the owner's screens (WP8, wave 3; read only: usage reports, the prices as read, the settings
 * as read). Nothing here can change a limit, a price or the history: the screens that do hold the owner's ports.
 *
 * "Today" is the owner's local day in [zone], cut as the usage records cut it ([UsageRange.day]). The day is looked at
 * again whenever the usage, the prices or the settings change and whenever [dayChanges] emits (production: just after
 * each local midnight, [DayChanges.daily]); tests pass nothing, so no timer runs in them.
 */
class TodayUsage(
    private val reports: UsageReports,
    private val prices: ModelPrices,
    private val settings: SettingsView,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val dayChanges: Flow<Unit> = emptyFlow(),
) {
    /** Today's use and limits, again on every change. Built off the caller's thread by the stores' own dispatchers. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<TodayUse> =
        merge(dayChanges, settings.settings.map { }).onStart { emit(Unit) }
            .map { UsageRange.day(clock.now(), zone()) }
            .distinctUntilChanged()
            .flatMapLatest { range ->
                combine(reports.observeByModel(range), prices.observeAll(), settings.settings) { rows, priced, current ->
                    TodayUse(DailyUse.of(rows, priced.associateBy { it.modelId }), current.dailyLimits, limitCurrencyOf(priced))
                }
            }
            .distinctUntilChanged()

    /** Today's use and limits once. */
    suspend fun now(): TodayUse {
        val range = UsageRange.day(clock.now(), zone())
        val priced = prices.getAll()
        return TodayUse(DailyUse.of(reports.byModel(range), priced.associateBy { it.modelId }), settings.get().dailyLimits, limitCurrencyOf(priced))
    }
}

/**
 * The currency the money limit is read in: the one the owner's prices are in when they all share one, else US$ (the
 * limiter adds the estimated cost of every priced model, whatever its currency, and names its sentence the same way).
 */
fun limitCurrencyOf(prices: Collection<ModelPrice>): String =
    prices.map { it.currencyLabel }.distinct().singleOrNull() ?: ModelPrice.DEFAULT_CURRENCY

/** When the owner's day changes, for [TodayUsage] in the app. */
object DayChanges {
    /** Emits just after every local midnight in [zone] (read each time: the owner can travel). Never ends; production only. */
    fun daily(clock: Clock, zone: () -> ZoneId): Flow<Unit> = flow {
        while (true) {
            delay(untilNextDay(clock.now(), zone()) + SLACK_MS)
            emit(Unit)
        }
    }

    /** Milliseconds from [now] to the next local midnight in [zone] (at least one). */
    fun untilNextDay(now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val next = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return (next - now).coerceAtLeast(1)
    }

    /** A little after midnight, so the new day's range is the one read. */
    private const val SLACK_MS = 1_000L
}
