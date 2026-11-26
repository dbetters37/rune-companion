package com.aicontrolcenter.app.usage

import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryModelPrices
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.InMemoryUsageStore
import com.aicontrolcenter.testkit.contract.UsageSamples
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Today's use for the owner's screens (wave 3, WP8): the same numbers as the daily limiter (calls off the phone, their
 * tokens, money only for priced models), the owner's limits beside them, again on every change, and a new day when the
 * day changes; no timer runs unless one is given.
 */
class TodayUsageTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val u = UsageSamples

    @Test
    fun `today counts what the limiter counts, with the limits, and moves on when the day changes`() = runTest {
        val clock = FakeClock(u.at("2026-11-25T12:00", zone))
        val store = InMemoryUsageStore(clock) { zone }
        val prices = InMemoryModelPrices(clock)
        val settings = InMemorySettingsRepository(FrameworkSettings(maxModelCallsPerDay = 3))
        val days = MutableSharedFlow<Unit>()
        val today = TodayUsage(store, prices, settings, clock, { zone }, dayChanges = days)
        val seen = ArrayList<TodayUse>()
        backgroundScope.launch { today.observe().collect { seen += it } }
        runCurrent()
        assertEquals(TodayUse.NONE.use, seen.last().use)
        assertEquals(DailyLimits(3, 500_000, 1_000_000), seen.last().limits)

        store.record(u.call(u.at("2026-11-25T09:00", zone), model = "mdl_grok"))
        store.record(u.call(u.at("2026-11-25T09:05", zone), model = "mdl_phone", locality = Locality.ON_DEVICE))
        store.record(u.call(u.at("2026-11-24T23:00", zone), model = "mdl_grok"))
        runCurrent()
        assertEquals(1, seen.last().use.calls, "today's calls off the phone only")
        assertEquals(1_100, seen.last().use.tokens)
        assertFalse(seen.last().use.priced, "no price: no money")

        // One currency (D3A-8): a price in euros is no price, as the limiter counts it.
        prices.set(ModelPrice(ModelId("mdl_grok"), inputPerMTokMicros = 2_000_000, outputPerMTokMicros = 6_000_000, currencyLabel = "€"))
        runCurrent()
        assertFalse(seen.last().use.priced, "a price in another currency does not count")
        assertEquals(0, seen.last().use.spendMicros)

        prices.set(ModelPrice(ModelId("mdl_grok"), inputPerMTokMicros = 2_000_000, outputPerMTokMicros = 6_000_000, cachedInputPerMTokMicros = 500_000, currencyLabel = "US$"))
        runCurrent()
        assertTrue(seen.last().use.priced)
        assertEquals(today.now(), seen.last())

        settings.update { it.copy(maxModelCallsPerDay = 1) }
        runCurrent()
        assertTrue(seen.last().reached, "1 of 1 answers: the limit is reached")

        // The next day starts empty, without a new record.
        clock.current = u.at("2026-11-26T00:00", zone) + 1_000
        days.emit(Unit)
        runCurrent()
        assertEquals(0, seen.last().use.calls)
    }

    @Test
    fun `the next local midnight is found in the owner's zone`() {
        assertEquals(12 * 3_600_000L, DayChanges.untilNextDay(u.at("2026-11-25T12:00", zone), zone))
        // The day the clocks go back is 25 hours long in Berlin.
        assertEquals(25 * 3_600_000L, DayChanges.untilNextDay(u.at("2026-10-25T00:00", zone), zone))
        assertEquals(1, DayChanges.untilNextDay(u.at("2026-11-26T00:00", zone) - 1, zone))
    }
}
