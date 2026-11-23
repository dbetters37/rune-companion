package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.memory.GraphRequest
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryChange
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import com.aicontrolcenter.memory.MemoryWorld.Companion.shared
import com.aicontrolcenter.memory.MemoryWorld.Companion.task
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recall, the graph, maintenance and forgetting an agent read only what they need: never the
 * whole store, never another agent's task memory, never one SQL condition per task.
 */
class BoundedReadsTest {
    private lateinit var spy: SpyRepository

    private fun world(config: MemoryConfig = MemoryConfig(), embedder: com.aicontrolcenter.core.memory.Embedder = HashingEmbedder()) =
        MemoryWorld(embedder, config, wrap = { inner -> SpyRepository(inner).also { spy = it } })

    private fun record(id: String, scope: MemoryScope, at: Long, title: String = "Note $id", content: String = "Text of $id") = MemoryRecord(
        id = MemoryId(id), scope = scope, kind = MemoryKind.NOTE,
        tier = if (scope is MemoryScope.Task) MemoryTier.SHORT_TERM else MemoryTier.LONG_TERM,
        title = title, content = content, provenance = Provenance(ProvenanceOrigin.AGENT, recordedAt = at),
        createdAt = at, updatedAt = at, expiresAt = if (scope is MemoryScope.Task) at + MemoryConfig.DAY_MS else null,
    )

    @Test
    fun `an agent's recall reads its own task memory only, never everyone's short-term memory`() = runTest {
        val w = world()
        val a = w.agent("agt_a")
        val b = w.agent("agt_b")
        val now = w.clock.current
        (1..2_000).forEach { w.repository.upsert(record("mem_b$it", task(b, "tsk_b$it"), now - it, content = "Secret travel plan $it")) }
        w.repository.upsert(record("mem_a1", task(a, "tsk_a"), now, content = "My travel plan"))
        spy.calls.clear()

        val hits = w.service.recall(a, RecallQuery("travel plan"))
        assertEquals(listOf("mem_a1"), hits.map { it.record.id.value })
        assertTrue("taskMemoryOf(agt_a)" in spy.calls, "${spy.calls}")
        assertFalse(spy.calls.any { it == "taskMemoryOf(agt_b)" })
        assertEquals(1, spy.loaded.sumOf { it.second }, "records loaded: ${spy.loaded}")
    }

    @Test
    fun `task scopes nobody asked for never become query conditions, and vectors are loaded for the candidates only`() = runTest {
        val w = world(MemoryConfig(candidateLimit = 300))
        val team = w.space("Team")
        val a = w.agent("agt_a", read = setOf(team.id))
        val now = w.clock.current
        (1..1_200).forEach { w.repository.upsert(record("mem_t$it", task(a, "tsk_$it"), now - it, content = "Step $it of the move")) }
        (1..400).forEach { w.repository.upsert(record("mem_s$it", shared(team), now - 2_000 - it, content = "Team fact $it")) }
        w.repository.list().forEach { w.repository.putEmbedding(it.id, HashingEmbedder.ID, HashingEmbedder().vectorOf(it.content)) }
        spy.calls.clear()
        spy.filters.clear()

        w.service.recall(a, RecallQuery("move step", recordAccess = false))
        w.service.graph(a, GraphRequest(includeTaskMemory = true))
        assertFalse(spy.sawTaskScopeInAFilter(), "a filter named task scopes nobody asked for")
        // Task scopes asked for by name are read as asked, a chunk at a time (see CandidateReadsTest).
        w.service.recall(a, RecallQuery("move step", scopes = (1..1_200).map { task(a, "tsk_$it") }.toSet(), recordAccess = false))
        assertTrue(spy.filters.all { it.scopes.orEmpty().size <= MemoryReader.SCOPE_CHUNK })

        assertFalse(spy.calls.any { it.startsWith("embeddings(") }, "${spy.calls}")
        val vectorRequests = spy.calls.filter { it.startsWith("embeddingsOf(") }.map { it.removePrefix("embeddingsOf(").removeSuffix(")").toInt() }
        assertTrue(vectorRequests.isNotEmpty() && vectorRequests.all { it <= MemoryRecall.VECTOR_CHUNK }, "$vectorRequests")
        assertTrue(vectorRequests.sum() <= 2 * (300 + MemoryRecall.TEXT_SEARCH_EXTRA), "loaded ${vectorRequests.sum()} vectors for two recalls")
    }

    @Test
    fun `the graph fetches the links of the drawn nodes only`() = runTest {
        val w = world()
        val agent = w.agent("agt_a")
        val records = (1..50).map { w.remember(agent, private(agent), "Fact $it", importance = it / 50.0) }
        records.zipWithNext().forEach { (x, y) -> w.service.link(agent, x.id, y.id, LinkRelation.RELATES_TO) }
        spy.calls.clear()

        val graph = w.service.graph(agent, GraphRequest(maxNodes = 10))
        assertEquals(10, graph.nodes.size)
        assertTrue("linksAmong(10)" in spy.calls, "${spy.calls}")
        assertFalse("links" in spy.calls)
        assertTrue(graph.truncated)
        val drawn = graph.nodes.map { it.id }.toSet()
        assertTrue(graph.edges.all { it.source in drawn && it.target in drawn })
        assertEquals(9, graph.edges.size, "the ten most important are consecutive, so nine of their links are drawn")
    }

    @Test
    fun `a stale vector left by an earlier run is found without reading every vector`() = runTest {
        val embedder = RecordingEmbedder(locality = Locality.ON_DEVICE)
        val w = world(embedder = embedder)
        val agent = w.agent("agt_a")
        val memories = (1..20).map { w.remember(agent, private(agent), "Fact $it") }
        embedder.failing = true
        w.service.update(agent, memories[3].id, MemoryChange(content = "Rewritten while the embedder failed"))
        embedder.failing = false

        // A new process: a fresh engine over the same storage.
        val restarted = DefaultMemoryService(
            w.store, w.agents, w.settings, embedder, ActivityRecorder(w.activityLog, w.ids, w.clock), w.ids, w.clock,
        )
        spy.calls.clear()
        assertEquals(1, restarted.maintain().embeddingsComputed)
        assertEquals(listOf("embeddings(${embedder.id}${MemoryEmbeddings.STALE_SUFFIX})"), spy.calls.filter { it.startsWith("embeddings(") })
        val vector = w.repository.embeddingsOf(embedder.id, listOf(memories[3].id))[memories[3].id]
        assertNotNull(vector)
        assertEquals(HashingEmbedder.DIMENSIONS, vector.size)
        assertEquals("Rewritten while the embedder failed", embedder.calls.last().single().lines()[1])
        // Done markers are not picked up again.
        assertEquals(0, DefaultMemoryService(w.store, w.agents, w.settings, embedder, ActivityRecorder(w.activityLog, w.ids, w.clock), w.ids, w.clock).maintain().embeddingsComputed)
    }

    @Test
    fun `forgetting an agent deletes its memory in one step without loading the store`() = runTest {
        val w = world()
        val a = w.agent("agt_a")
        val b = w.agent("agt_b")
        val now = w.clock.current
        (1..500).forEach { w.repository.upsert(record("mem_b$it", private(b), now - it)) }
        w.repository.upsert(record("mem_a0", private(a), now))
        w.repository.upsert(record("mem_a1", task(a, "tsk_1"), now))
        w.repository.upsert(record("mem_a2", task(a, "tsk_2"), now))
        spy.calls.clear()

        w.agents.delete(a.profile.id)
        assertEquals(3, w.service.forgetAgent(a.profile.id))
        assertEquals(listOf("deleteAgentMemory(agt_a)"), spy.calls)
        assertEquals(500, w.repository.list().size)
    }

    @Test
    fun `maintenance removes memory of agents that no longer exist`() = runTest {
        val w = world()
        val gone = w.agent("agt_gone")
        val stays = w.agent("agt_stays")
        val now = w.clock.current
        w.repository.upsert(record("mem_g0", private(gone), now, content = "secret of the removed agent"))
        w.repository.upsert(record("mem_g1", MemoryScope.Task(TaskId("tsk_g"), gone.profile.id), now).copy(expiresAt = Long.MAX_VALUE))
        w.repository.upsert(record("mem_s0", private(stays), now))
        // The agent is deleted, but its memory clean-up never ran (the process died in between).
        w.agents.delete(gone.profile.id)

        w.service.maintain()
        assertNull(w.repository.get(MemoryId("mem_g0")))
        assertNull(w.repository.get(MemoryId("mem_g1")))
        assertNotNull(w.repository.get(MemoryId("mem_s0")))
        val event = w.events.single { it.title == "Memory of removed agents deleted" }
        assertTrue("2 memories" in event.detail, event.detail)
        assertTrue(w.events.none { "secret" in it.detail || "secret" in it.title })
        assertEquals(setOf(stays.profile.id), w.repository.agentsWithMemory())
    }

    @Test
    fun `maintenance deletes nothing when no agent at all can be read`() = runTest {
        val w = world()
        val agent = w.agent("agt_a")
        w.repository.upsert(record("mem_a", private(agent), w.clock.current))
        w.agents.delete(agent.profile.id)
        w.service.maintain()
        assertEquals(setOf(AgentId("agt_a")), w.repository.agentsWithMemory())
    }
}
