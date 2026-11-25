package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.testkit.FakeNetworkStatus
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D28, the re-checks: a provider that stopped answering is checked again on a schedule, when the network is back and
 * when work is about to wait for it; never one whose key was rejected, and never one off the phone in local-only mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderRechecksTest {
    /** A provider whose health the test sets, counting the checks it answers. */
    private class Health(var status: ConnectionStatus = ConnectionStatus.OFFLINE, var hang: CompletableDeferred<Unit>? = null) : ProviderAdapter {
        val checks = AtomicInteger()
        override val client: LlmClient = ScriptedLlmClient.replies("ok")

        override suspend fun checkHealth(): ProviderHealth {
            checks.incrementAndGet()
            hang?.await()
            return ProviderHealth(status, if (status == ConnectionStatus.ONLINE) null else "Nothing answered.")
        }

        override suspend fun listModels(): List<RemoteModel> = emptyList()
    }

    private class Rechecks(test: TestScope, network: FakeNetworkStatus = FakeNetworkStatus()) {
        val world = TestWorld()

        /** Wall time: the test's virtual time, plus whatever a test adds for a phone that slept through a delay. */
        @Volatile
        var slept = 0L
        val clock = Clock { START + test.testScheduler.currentTime + slept }
        val connections = ProviderConnections(world.providers, world.vault, world.factories.values.toList(), clock)
        val rechecks = DefaultProviderRechecks(
            world.providers, world.settings, connections, world.activity, clock, test.backgroundScope, network, timeSource = test.testTimeSource,
        )

        suspend fun provider(health: Health, baseUrl: String = "https://api.example.com/v1", status: ConnectionStatus = ConnectionStatus.OFFLINE): ModelProvider {
            val registered = world.provider(health, baseUrl = baseUrl)
            val marked = registered.copy(status = status, lastCheckedAt = clock.now())
            world.providers.upsert(marked)
            return marked
        }

        suspend fun status(id: ProviderId) = world.providers.get(id)!!.status

        val due get() = rechecks.timedWaits.nextDueAt.value
    }

    private companion object {
        const val START = 1_795_608_000_000L
        const val MINUTE = 60_000L
    }

    @Test
    fun `a provider that stopped answering is checked after 1, 2, 5 and 10 minutes, then every 10 minutes for an hour`() = runTest {
        val fixture = Rechecks(this)
        val health = Health()
        fixture.provider(health)
        fixture.rechecks.start()
        runCurrent()
        assertEquals(START + MINUTE, fixture.due)

        val expected = listOf(1L, 2L, 5L, 10L, 20L, 30L, 40L, 50L, 60L)
        for ((index, minute) in expected.withIndex()) {
            advanceTimeBy(minute * MINUTE - currentTime)
            runCurrent()
            assertEquals(index + 1, health.checks.get(), "checked at minute $minute")
        }
        assertNull(fixture.due, "nothing is due after the hour")
        advanceTimeBy(3 * 60 * MINUTE)
        runCurrent()
        assertEquals(expected.size, health.checks.get())
    }

    @Test
    fun `once it answers again the checks stop`() = runTest {
        val fixture = Rechecks(this)
        val health = Health()
        val provider = fixture.provider(health)
        fixture.rechecks.start()
        runCurrent()
        advanceTimeBy(MINUTE)
        runCurrent()
        health.status = ConnectionStatus.ONLINE
        advanceTimeBy(MINUTE)
        runCurrent()

        assertEquals(ConnectionStatus.ONLINE, fixture.status(provider.id))
        assertNull(fixture.due)
        advanceTimeBy(60 * MINUTE)
        runCurrent()
        assertEquals(2, health.checks.get())
    }

    @Test
    fun `a failing provider (ERROR) is checked again too`() = runTest {
        val fixture = Rechecks(this)
        val health = Health(status = ConnectionStatus.ONLINE)
        val provider = fixture.provider(health, status = ConnectionStatus.ERROR)
        fixture.rechecks.start()
        runCurrent()
        advanceTimeBy(MINUTE)
        runCurrent()

        assertEquals(1, health.checks.get())
        assertEquals(ConnectionStatus.ONLINE, fixture.status(provider.id))
    }

    @Test
    fun `a rejected key, a disabled provider and, in local-only mode, a provider off the phone are never checked by themselves`() = runTest {
        val fixture = Rechecks(this)
        val rejected = Health()
        val disabled = Health()
        val cloud = Health()
        val onPhone = Health()
        val rejectedProvider = fixture.provider(rejected, baseUrl = "https://a.example.com/v1", status = ConnectionStatus.AUTH_ERROR)
        val disabledProvider = fixture.provider(disabled, baseUrl = "https://b.example.com/v1")
        fixture.world.providers.upsert(disabledProvider.copy(enabled = false))
        val cloudProvider = fixture.provider(cloud, baseUrl = "https://c.example.com/v1")
        fixture.provider(onPhone, baseUrl = "http://127.0.0.1:9999/v1")
        fixture.world.settings.update { it.copy(localOnlyMode = true) }
        fixture.rechecks.start()
        runCurrent()

        advanceTimeBy(61 * MINUTE)
        runCurrent()

        assertEquals(0, rejected.checks.get())
        assertEquals(0, disabled.checks.get())
        assertEquals(0, cloud.checks.get())
        assertEquals(9, onPhone.checks.get(), "a server on this phone is checked in local-only mode")
        // Asking does not change that.
        advanceTimeBy(5 * MINUTE)
        for (id in listOf(rejectedProvider.id, disabledProvider.id, cloudProvider.id)) assertNull(fixture.rechecks.recheckIfStale(id, 60_000))
        assertEquals(0, rejected.checks.get() + disabled.checks.get() + cloud.checks.get())
        assertNull(fixture.rechecks.recheckIfStale(ProviderId("prv_unknown"), 60_000))
    }

    @Test
    fun `work about to wait asks again - only a status older than asked is checked, and the answer comes back`() = runTest {
        val fixture = Rechecks(this)
        val health = Health(status = ConnectionStatus.ONLINE)
        val provider = fixture.provider(health)

        advanceTimeBy(30_000)
        assertNull(fixture.rechecks.recheckIfStale(provider.id, 60_000), "checked 30 seconds ago: nothing to do")
        assertEquals(0, health.checks.get())

        advanceTimeBy(40_000)
        assertEquals(ConnectionStatus.ONLINE, fixture.rechecks.recheckIfStale(provider.id, 60_000))
        assertEquals(1, health.checks.get())
        assertEquals(ConnectionStatus.ONLINE, fixture.status(provider.id))
    }

    @Test
    fun `asking again takes at most 10 seconds, even when the provider never answers`() = runTest {
        val fixture = Rechecks(this)
        val health = Health(hang = CompletableDeferred())
        val provider = fixture.provider(health)
        advanceTimeBy(2 * MINUTE)
        val before = currentTime

        val status = fixture.rechecks.recheckIfStale(provider.id, 60_000)

        assertTrue(currentTime - before <= RecheckSchedule.CHECK_BOUND_MS, "took ${currentTime - before} ms")
        assertEquals(ConnectionStatus.OFFLINE, status)
        assertTrue(fixture.world.providers.get(provider.id)!!.statusMessage!!.contains("did not answer in time"))
    }

    @Test
    fun `two callers asking at once share one check`() = runTest {
        val fixture = Rechecks(this)
        val gate = CompletableDeferred<Unit>()
        val health = Health(status = ConnectionStatus.ONLINE, hang = gate)
        val provider = fixture.provider(health)
        advanceTimeBy(2 * MINUTE)

        val first = async { fixture.rechecks.recheckIfStale(provider.id, 60_000) }
        val second = async { fixture.rechecks.recheckIfStale(provider.id, 60_000) }
        runCurrent()
        gate.complete(Unit)

        assertEquals(ConnectionStatus.ONLINE, first.await())
        assertEquals(ConnectionStatus.ONLINE, second.await())
        assertEquals(1, health.checks.get())
    }

    @Test
    fun `asking again after the hour starts the schedule again`() = runTest {
        val fixture = Rechecks(this)
        val health = Health()
        val provider = fixture.provider(health)
        fixture.rechecks.start()
        runCurrent()
        advanceTimeBy(61 * MINUTE)
        runCurrent()
        assertNull(fixture.due)

        assertEquals(ConnectionStatus.OFFLINE, fixture.rechecks.recheckIfStale(provider.id, 60_000))
        runCurrent()

        assertEquals(10, health.checks.get())
        assertEquals(START + currentTime + MINUTE, fixture.due)
    }

    @Test
    fun `when the network comes back every provider in trouble is checked at once, and while it is gone scheduled checks wait`() = runTest {
        val network = FakeNetworkStatus(initial = false)
        val fixture = Rechecks(this, network)
        val health = Health(status = ConnectionStatus.ONLINE)
        val provider = fixture.provider(health)
        fixture.rechecks.start()
        runCurrent()

        advanceTimeBy(3 * MINUTE)
        runCurrent()
        assertEquals(0, health.checks.get(), "no network: a check could not succeed")

        network.online.value = true
        runCurrent()

        assertEquals(1, health.checks.get())
        assertEquals(ConnectionStatus.ONLINE, fixture.status(provider.id))
    }

    @Test
    fun `the wake alarm makes due checks run against the wall clock, even when the phone slept through the timer`() = runTest {
        val fixture = Rechecks(this)
        val health = Health()
        fixture.provider(health)
        fixture.rechecks.start()
        runCurrent()
        assertEquals(START + MINUTE, fixture.due)

        // The phone slept: the wall clock moved on, the timer did not.
        fixture.slept = 2 * MINUTE
        fixture.rechecks.timedWaits.wake()
        runCurrent()

        assertEquals(1, health.checks.get())
        assertEquals(START + 5 * MINUTE, fixture.due, "the missed two-minute slot is not made up; the next is at five")
    }
}
