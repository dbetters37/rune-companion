package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.InMemoryUsageStore
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** WP4 item 9 (F4): usage history is kept as long as the owner chose, and today's rows never go. */
class UsageRetentionTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 11, 25, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val day = 24 * 3_600_000L

    private fun sample(at: Long) = ModelCallSample(
        at = at, modelId = ModelId("mdl_1"), modelLabel = "Model", providerId = ProviderId("prv_1"), providerLabel = "Provider",
        locality = Locality.CLOUD, agentId = AgentId("agt_1"), source = UsageSource.OTHER, outcome = CallOutcome.OK, answerMs = 10,
    )

    private suspend fun storeWithRowsAt(vararg daysAgo: Int): InMemoryUsageStore {
        val store = InMemoryUsageStore(Clock { now }) { zone }
        for (ago in daysAgo) store.record(sample(now - ago * day))
        return store
    }

    @Test
    fun `each choice keeps that many local days, today included`() = runTest {
        for ((days, expectedLeft) in listOf(90 to 3, 365 to 4, 730 to 5)) {
            val store = storeWithRowsAt(0, 1, 89, 300, 700, 800)
            val settings = InMemorySettingsRepository().apply { update { it.copy(usageRetentionDays = days) } }

            val pruned = UsageRetention(store, settings, Clock { now }) { zone }.prune()

            assertEquals(6 - expectedLeft, pruned.hourRows, "$days days")
            assertEquals(expectedLeft, store.hourRows.size, "$days days")
        }
    }

    @Test
    fun `zero keeps everything`() = runTest {
        val store = storeWithRowsAt(0, 1, 2_000)
        val settings = InMemorySettingsRepository().apply { update { it.copy(usageRetentionDays = 0) } }

        assertEquals(0, UsageRetention(store, settings, Clock { now }) { zone }.prune().hourRows)
        assertEquals(3, store.hourRows.size)
        assertNull(UsageRetention.cutFor(0, now, zone))
        assertNull(UsageRetention.cutFor(-5, now, zone))
    }

    @Test
    fun `a cut inside today deletes nothing of today`() = runTest {
        val store = storeWithRowsAt(0, 1)
        store.record(sample(now - 3_600_000L))
        val settings = InMemorySettingsRepository().apply { update { it.copy(usageRetentionDays = 1) } }

        UsageRetention(store, settings, Clock { now }) { zone }.prune()
        // Even asked directly for everything up to now, the store keeps today's rows.
        store.pruneHours(now + day)

        val today = UsageRange.day(now, zone)
        assertEquals(2, store.hourRows.size)
        assertEquals(true, store.hourRows.keys.all { today.containsHour(it[0] as Long) })
        assertEquals(today.fromMs, UsageRetention.cutFor(1, now, zone))
    }
}
