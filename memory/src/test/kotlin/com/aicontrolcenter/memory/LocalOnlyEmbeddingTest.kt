package com.aicontrolcenter.memory

import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.memory.Embedder
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.registry.Locality
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Switching local-only mode on stops memory text from leaving the device at once, also in a maintenance pass already running. */
class LocalOnlyEmbeddingTest {
    /** An off-device embedder; [onCall] runs while a batch is "in flight". */
    private class CloudEmbedder(var onCall: suspend (Int) -> Unit = {}) : Embedder {
        private val delegate = HashingEmbedder()
        override val id = "cloud-embed-384"
        override val dimensions = delegate.dimensions
        override val locality = Locality.CLOUD
        val sent = mutableListOf<Int>()

        override suspend fun embed(texts: List<String>): List<FloatArray> {
            sent += texts.size
            onCall(sent.size)
            return delegate.embed(texts)
        }
    }

    private suspend fun MemoryWorld.privateNotes(count: Int) {
        val agent = agent("agt_a")
        val now = clock.current
        repeat(count) {
            repository.upsert(
                MemoryRecord(
                    MemoryId("mem_$it"), MemoryScope.Agent(agent.profile.id), MemoryKind.NOTE, MemoryTier.LONG_TERM, "Note $it", "private text $it",
                    provenance = Provenance(ProvenanceOrigin.AGENT, recordedAt = now), createdAt = now, updatedAt = now,
                ),
            )
        }
    }

    @Test
    fun `local-only mode switched on during a maintenance pass stops it before the next batch`() = runTest {
        val embedder = CloudEmbedder()
        val world = MemoryWorld(embedder)
        world.privateNotes(300)
        embedder.onCall = { call -> if (call == 1) world.settings.update { it.copy(localOnlyMode = true) } }

        val report = world.service.maintain()
        assertEquals(listOf(MemoryConfig().embeddingBatchSize), embedder.sent, "only the batch already in flight was sent")
        assertEquals(MemoryConfig().embeddingBatchSize, report.embeddingsComputed)
        assertEquals(1, world.events.count { it.type == ActivityType.DATA_EGRESS })
        val stop = world.events.single { it.title == "Memory embedding stopped" }
        assertEquals(Severity.WARNING, stop.severity)
        assertTrue("local-only mode" in stop.detail.lowercase(), stop.detail)
    }

    @Test
    fun `the embedder is checked again immediately before each call, after the batch was read`() = runTest {
        lateinit var spy: SpyRepository
        val embedder = CloudEmbedder()
        val world = MemoryWorld(embedder, wrap = { SpyRepository(it).also { s -> spy = s } })
        world.privateNotes(10)
        // Switched on after maintenance decided to go ahead and while it reads the batch's text.
        spy.beforeGetAll = { world.settings.update { it.copy(localOnlyMode = true) } }

        assertEquals(0, world.service.maintain().embeddingsComputed)
        assertEquals(emptyList(), embedder.sent)
        assertEquals(0, world.events.count { it.type == ActivityType.DATA_EGRESS })
        assertTrue(world.events.any { it.title == "Memory embedding stopped" })
    }
}
