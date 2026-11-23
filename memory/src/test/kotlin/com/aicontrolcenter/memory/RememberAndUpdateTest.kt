package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.memory.MemoryChange
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.MemoryValidationException
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import com.aicontrolcenter.memory.MemoryWorld.Companion.shared
import com.aicontrolcenter.memory.MemoryWorld.Companion.task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RememberAndUpdateTest {
    private val world = MemoryWorld()
    private val service = world.service
    private val day = MemoryConfig.DAY_MS

    @Test
    fun `remember stores a complete record with provenance, embedding and an event`() = runTest {
        val agent = world.agent("agt_a")
        val now = world.clock.current
        val record = service.remember(
            agent,
            world.draft(private(agent), "  Tea preference ", "The owner drinks green tea.", tags = setOf(" Drinks ", "TEA", "tea", "")),
        )
        assertEquals("Tea preference", record.title)
        assertEquals(setOf("drinks", "tea"), record.tags)
        assertEquals(1, record.revision)
        assertEquals(now, record.createdAt)
        assertEquals(now, record.provenance.recordedAt)
        assertEquals(AgentId("agt_a"), record.provenance.agentId)
        assertNull(record.expiresAt)
        assertEquals(record, world.repository.get(record.id))
        assertNotNull(world.repository.embeddings(HashingEmbedder.ID)[record.id])
        val event = world.events.last { it.type == ActivityType.MEMORY }
        assertEquals("Memory saved: Tea preference", event.title)
        assertEquals(AgentId("agt_a"), event.agentId)
        assertTrue("private memory of A" in event.detail, event.detail)
    }

    @Test
    fun `invalid drafts are refused and nothing is stored`() = runTest {
        val agent = world.agent("agt_a")
        val scope = private(agent)
        val bad = listOf(
            world.draft(scope, "   "),
            world.draft(scope, "Title", content = " "),
            world.draft(scope, "x".repeat(201)),
            world.draft(scope, "Title", content = "x".repeat(20_001)),
            world.draft(scope, "Title", tags = (1..21).map { "t$it" }.toSet()),
            world.draft(scope, "Title", tags = setOf("x".repeat(41))),
            world.draft(scope, "Title", importance = 1.5),
            world.draft(scope, "Title", importance = Double.NaN),
            world.draft(scope, "Title").copy(confidence = -0.1),
            world.draft(scope, "Title", tier = MemoryTier.SHORT_TERM, ttlMs = 0),
            world.draft(MemoryScope.Shared(SpaceId("spc_missing")), "Title"),
            world.draft(MemoryScope.Agent(AgentId("agt_nobody")), "Title"),
        )
        for (draft in bad) assertFailsWith<MemoryValidationException> { service.remember(world.owner, draft) }
        assertEquals(emptyList(), world.repository.list())
        // Exactly at the limits is fine.
        service.remember(agent, world.draft(scope, "x".repeat(200), "y".repeat(20_000), tags = (1..20).map { "t".repeat(40 - 2) + "$it".padStart(2, '0') }.toSet(), importance = 1.0))
    }

    @Test
    fun `short-term memories expire after the default or their own ttl, long-term never`() = runTest {
        val agent = world.agent("agt_a")
        val now = world.clock.current
        val short = service.remember(agent, world.draft(private(agent), "Parking spot", tier = MemoryTier.SHORT_TERM))
        val custom = service.remember(agent, world.draft(private(agent), "Oven on", tier = MemoryTier.SHORT_TERM, ttlMs = 60_000))
        val long = service.remember(agent, world.draft(private(agent), "Birthday", ttlMs = 60_000))
        assertEquals(now + 3 * day, short.expiresAt)
        assertEquals(now + 60_000, custom.expiresAt)
        assertNull(long.expiresAt)
    }

    @Test
    fun `task memory is always short-term`() = runTest {
        val agent = world.agent("agt_a")
        val record = service.remember(agent, world.draft(task(agent, "tsk_1"), "Step result", tier = MemoryTier.LONG_TERM))
        assertEquals(MemoryTier.SHORT_TERM, record.tier)
        assertEquals(world.clock.current + 3 * day, record.expiresAt)
        assertFailsWith<MemoryValidationException> { service.update(agent, record.id, MemoryChange(tier = MemoryTier.LONG_TERM)) }
    }

    @Test
    fun `an agent cannot claim the owner wrote its memory`() = runTest {
        val agent = world.agent("agt_a")
        val forged = world.draft(private(agent), "Claim").copy(provenance = Provenance(ProvenanceOrigin.OWNER, agentId = AgentId("agt_b"), recordedAt = 5))
        val record = service.remember(agent, forged)
        assertEquals(ProvenanceOrigin.AGENT, record.provenance.origin)
        assertEquals(AgentId("agt_a"), record.provenance.agentId)
        val ownerRecord = service.remember(world.owner, forged)
        assertEquals(ProvenanceOrigin.OWNER, ownerRecord.provenance.origin)
    }

    @Test
    fun `update changes only what is given, bumps the revision and re-embeds`() = runTest {
        val agent = world.agent("agt_a")
        val original = world.remember(agent, private(agent), "Car", "The car is blue.", importance = 0.4, tags = setOf("car"))
        val vectorBefore = world.repository.embeddings(HashingEmbedder.ID).getValue(original.id)
        world.clock.advance(1_000)

        val updated = service.update(agent, original.id, MemoryChange(content = "The car is red now.", importance = 0.9, kind = MemoryKind.EVENT))
        assertEquals(2, updated.revision)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(world.clock.current, updated.updatedAt)
        assertEquals("Car", updated.title)
        assertEquals(setOf("car"), updated.tags)
        assertEquals(MemoryKind.EVENT, updated.kind)
        assertEquals(0.9, updated.importance)
        assertEquals(original.provenance, updated.provenance)
        assertEquals(updated, world.repository.get(original.id))
        val vectorAfter = world.repository.embeddings(HashingEmbedder.ID).getValue(original.id)
        assertContentEquals(HashingEmbedder().vectorOf("Car\nThe car is red now.\ncar"), vectorAfter)
        assertFalse(vectorBefore.contentEquals(vectorAfter))
        assertEquals("Memory updated: Car", world.events.last().title)

        val third = service.update(agent, original.id, MemoryChange(title = "Vehicle"))
        assertEquals(3, third.revision)
    }

    @Test
    fun `concurrent updates never lose a revision`() = runTest {
        val agent = world.agent("agt_a")
        val record = world.remember(agent, private(agent), "Counter")
        withContext(Dispatchers.Default) {
            (1..40).map { i -> async { service.update(agent, record.id, MemoryChange(content = "Version $i")) } }.awaitAll()
        }
        assertEquals(41, world.repository.get(record.id)!!.revision)
        assertEquals(40, world.events.count { it.title == "Memory updated: Counter" })
    }

    @Test
    fun `an update that changes nothing keeps the revision and logs nothing`() = runTest {
        val agent = world.agent("agt_a")
        val original = world.remember(agent, private(agent), "Car")
        val eventsBefore = world.events.size
        val same = service.update(agent, original.id, MemoryChange(title = "Car", importance = original.importance))
                assertEquals(original, same)
        assertEquals(eventsBefore, world.events.size)
    }

    @Test
    fun `changing the tier sets or clears the expiry`() = runTest {
        val agent = world.agent("agt_a")
        val record = world.remember(agent, private(agent), "Wifi password hint")
        world.clock.advance(10)
        val short = service.update(agent, record.id, MemoryChange(tier = MemoryTier.SHORT_TERM))
        assertEquals(world.clock.current + 3 * day, short.expiresAt)
        val long = service.update(agent, record.id, MemoryChange(tier = MemoryTier.LONG_TERM))
        assertNull(long.expiresAt)
    }

    @Test
    fun `updating or forgetting a missing memory`() = runTest {
        assertFailsWith<MemoryValidationException> { service.update(world.owner, MemoryId("mem_none"), MemoryChange(title = "x")) }
        service.forget(world.owner, MemoryId("mem_none"))
        val agent = world.agent("agt_a")
        val record = world.remember(agent, private(agent), "Temp")
        service.forget(agent, record.id)
        assertNull(world.repository.get(record.id))
        assertEquals("Memory forgotten: Temp", world.events.last().title)
    }

    @Test
    fun `an embedder failure never loses the memory and maintenance fills the vector in`() = runTest {
        val embedder = RecordingEmbedder(Locality.ON_DEVICE).apply { failing = true }
        val w = MemoryWorld(embedder)
        val agent = w.agent("agt_a")
        val record = w.remember(agent, private(agent), "Kept anyway", "Survives a broken embedder.")
        assertEquals(record, w.repository.get(record.id))
        assertEquals(listOf(record.id), w.repository.missingEmbeddings(embedder.id))
        assertTrue(w.events.any { it.title == "Embedding failed" })

        embedder.failing = false
        val report = w.service.maintain()
        assertEquals(1, report.embeddingsComputed)
        assertEquals(emptyList(), w.repository.missingEmbeddings(embedder.id))
    }

    @Test
    fun `a failed re-embedding marks the old vector stale until maintenance replaces it`() = runTest {
        val embedder = RecordingEmbedder(Locality.ON_DEVICE)
        val w = MemoryWorld(embedder)
        val agent = w.agent("agt_a")
        val record = w.remember(agent, private(agent), "Address", "Lives on Elm Street.")
        embedder.failing = true
        w.service.update(agent, record.id, MemoryChange(content = "Moved to Oak Avenue."))
        assertEquals(0, w.repository.embeddings(embedder.id).getValue(record.id).size, "the outdated vector must not survive")

        embedder.failing = false
        assertEquals(1, w.service.maintain().embeddingsComputed)
        assertContentEquals(HashingEmbedder().vectorOf("Address\nMoved to Oak Avenue."), w.repository.embeddings(embedder.id).getValue(record.id))
        assertEquals(0, w.service.maintain().embeddingsComputed)
    }
}
