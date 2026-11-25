package com.aicontrolcenter.app.di.wiring

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.framework.llm.UsageRetention
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

/**
 * WP4 item 9 on Room (security review of wave 1, F4): the upkeep job's usage retention, through the data layer's
 * `RoomUsageHousekeeping`, keeps the days the owner chose, and never deletes today's rows, whatever it is asked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UsageRetentionRoomTest {
    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val now = ZonedDateTime.of(2026, 11, 25, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val clock = Clock { now }
    private val day = 24 * 3_600_000L
    private val data = DataLayer(
        ApplicationProvider.getApplicationContext<Application>(),
        databaseName = "usage-retention-test.db", databaseEncryption = DatabaseEncryption.None, clock = clock, zone = { zone },
    )

    @After
    fun close() = runBlocking { data.close() }

    private fun sample(at: Long) = ModelCallSample(
        at = at, modelId = ModelId("mdl_1"), modelLabel = "Model", providerId = ProviderId("prv_1"), providerLabel = "Provider",
        locality = Locality.CLOUD, agentId = AgentId("agt_1"), source = UsageSource.OTHER, outcome = CallOutcome.OK, answerMs = 10,
    )

    private suspend fun calls(range: UsageRange = UsageRange.ALL) = data.usageReports.totals(range).calls

    @Test
    fun `ninety days keeps ninety local days, today included`() = runBlocking {
        for (ago in listOf(0, 1, 89, 90, 400)) data.usageLedger.record(sample(now - ago * day))
        val settings = InMemorySettingsRepository().apply { update { it.copy(usageRetentionDays = 90) } }

        val pruned = UsageRetention(data.usageHousekeeping, settings, clock) { zone }.prune()

        assertEquals(2, pruned.hourRows)
        assertEquals(3, calls())
    }

    @Test
    fun `forever keeps everything`() = runBlocking {
        for (ago in listOf(0, 1_000, 2_000)) data.usageLedger.record(sample(now - ago * day))
        val settings = InMemorySettingsRepository().apply { update { it.copy(usageRetentionDays = 0) } }

        assertEquals(0, UsageRetention(data.usageHousekeeping, settings, clock) { zone }.prune().hourRows)
        assertEquals(3, calls())
    }

    @Test
    fun `a cut inside today deletes nothing of today`() = runBlocking {
        val today = UsageRange.day(now, zone)
        for (at in listOf(today.fromMs + 1_000, now - 3_600_000L, now, now - day)) data.usageLedger.record(sample(at))
        val settings = InMemorySettingsRepository().apply { update { it.copy(usageRetentionDays = 1) } }

        UsageRetention(data.usageHousekeeping, settings, clock) { zone }.prune()
        data.usageHousekeeping.pruneHours(now + day)

        assertEquals(3, calls(today))
        assertEquals(3, calls())
    }
}
