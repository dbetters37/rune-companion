package com.aicontrolcenter.framework.registry

import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.testkit.InMemoryAgentRepository
import com.aicontrolcenter.testkit.InMemoryAiAppRepository
import com.aicontrolcenter.testkit.InMemoryModelRepository
import com.aicontrolcenter.testkit.InMemoryProviderRepository
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * P8: every observer of the registry shares one snapshot while anyone observes, instead of building its own flows over
 * the stores; a caller that has just changed something still reads its own change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedRegistrySnapshotTest {
    /** Counts how often the providers' flow is started and how many are running. */
    private class CountingProviders(private val store: InMemoryProviderRepository) : ProviderDirectory by store {
        val started = AtomicInteger()
        val running = AtomicInteger()

        override fun observeAll(): Flow<List<ModelProvider>> =
            store.observeAll().onStart { started.incrementAndGet(); running.incrementAndGet() }.onCompletion { running.decrementAndGet() }
    }

    private val providers = InMemoryProviderRepository()
    private val counting = CountingProviders(providers)

    private fun directory(scope: kotlinx.coroutines.CoroutineScope?) = DefaultRegistryDirectory(
        InMemoryAgentRepository(), counting, InMemoryModelRepository(), InMemoryAiAppRepository(), InMemorySettingsRepository(), sharing = scope,
        stopAfterMs = STOP_AFTER_MS,
    )

    private companion object {
        /** Real time: the shared snapshot runs unconfined, outside the test's virtual clock. */
        const val STOP_AFTER_MS = 200L
    }

    @Test
    fun `screens observing at once share one snapshot and see every change`() = runTest {
        val directory = directory(backgroundScope)
        val seen = List(3) { CopyOnWriteArrayList<RegistrySnapshot>() }

        val jobs = seen.map { list -> backgroundScope.launch { directory.observe().collect { list += it } } }
        runCurrent()
        providers.upsert(Samples.provider(id = "prv_1"))
        runCurrent()

        assertEquals(1, counting.started.get(), "one set of flows for three observers")
        for (list in seen) assertEquals(listOf(0, 1), list.map { it.providers.size })
        jobs.forEach { it.cancelAndJoin() }
    }

    @Test
    fun `sharing stops a while after the last observer left, and a later observer never starts from an old snapshot`() = runTest {
        val directory = directory(backgroundScope)
        val first = backgroundScope.launch { directory.observe().collect { } }
        runCurrent()
        first.cancelAndJoin()
        runCurrent()
        assertEquals(1, counting.running.get(), "kept for a moment, in case the screen comes back")

        Thread.sleep(STOP_AFTER_MS + 800)
        runCurrent()
        assertEquals(0, counting.running.get())
        providers.upsert(Samples.provider(id = "prv_1").copy(status = ConnectionStatus.OFFLINE))

        assertEquals(ConnectionStatus.OFFLINE, directory.observe().first().providers.single().status)
    }

    @Test
    fun `a snapshot reads the stores afresh, so a caller sees its own change at once`() = runTest {
        val directory = directory(backgroundScope)
        val watcher = backgroundScope.launch { directory.observe().collect { } }
        runCurrent()
        providers.upsert(Samples.provider(id = "prv_1").copy(status = ConnectionStatus.ONLINE))

        assertEquals(ConnectionStatus.ONLINE, directory.snapshot().providers.single().status)
        watcher.cancelAndJoin()
    }

    @Test
    fun `without a sharing scope each observer has its own flows, as before`() = runTest {
        val directory = directory(null)
        repeat(2) { directory.observe().first() }

        assertEquals(2, counting.started.get())
    }
}
