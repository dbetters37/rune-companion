package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.InMemoryModelPrices
import com.aicontrolcenter.testkit.InMemoryUsageStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.testTimeSource
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [TestWorld] whose gateway records usage and keeps today's limits (Phase 6, WP4), on a clock that follows the
 * test's virtual time from [start] and in the owner's [zone]. The gateway is built as the app builds it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MeteredWorld(
    private val test: TestScope,
    val zone: ZoneId = ZoneOffset.UTC,
    val start: Long = NOON_UTC,
    retry: RetryPolicy = RetryPolicy(initialDelayMs = 1_000, maxDelayMs = 8_000),
    reports: ((InMemoryUsageStore) -> UsageReports)? = null,
    prices: ((InMemoryModelPrices) -> ModelPrices)? = null,
) {
    val world = TestWorld(retry = retry)

    /** Wall time: [start] plus the test's virtual time, plus whatever a test adds (a phone that slept, a jump to midnight). */
    @Volatile
    var shift: Long = 0
    val clock = Clock { start + test.testScheduler.currentTime + shift }

    val usage = InMemoryUsageStore(clock) { zone }
    val ownerPrices = InMemoryModelPrices(clock)
    val limiter = DailyLimiter(reports?.invoke(usage) ?: usage, prices?.invoke(ownerPrices) ?: ownerPrices, world.settings, clock) { zone }
    val gateway = DefaultModelGateway(
        world.providers, world.models, world.settings, world.connections, world.activity, clock, retry,
        usage = usage, limiter = limiter, timeSource = test.testTimeSource, zone = { zone },
    )

    private val port = AtomicInteger()

    /** A model on a provider on the internet, answering through [client]. */
    suspend fun cloud(client: LlmClient, name: String = "Cloud ${port.incrementAndGet()}"): ModelDescriptor {
        val provider = world.provider(FakeProviderAdapter(client = client), baseUrl = "https://api${port.incrementAndGet()}.example.com/v1", name = name)
        return world.model(provider)
    }

    /** A model on a server on this phone, answering through [client]. */
    suspend fun onPhone(client: LlmClient): ModelDescriptor = world.serve(client)

    suspend fun settings(change: (FrameworkSettings) -> FrameworkSettings) {
        world.settings.update(change)
    }

    suspend fun price(model: ModelId, inputUsdPerMTok: Double, outputUsdPerMTok: Double, cachedUsdPerMTok: Double? = null) {
        ownerPrices.set(ModelPrice(model, micros(inputUsdPerMTok), micros(outputUsdPerMTok), cachedUsdPerMTok?.let(::micros)))
    }

    suspend fun today() = usage.totals(UsageRange.day(clock.now(), zone))

    fun modelCalls() = world.events(ActivityType.MODEL_CALL)

    private fun micros(usd: Double): Long = Math.round(usd * 1_000_000)

    companion object {
        /** 2026-11-25 12:00 UTC. */
        const val NOON_UTC: Long = 1_795_608_000_000L
    }
}
