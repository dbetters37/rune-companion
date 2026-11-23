package com.aicontrolcenter.memory

import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.GraphRequest
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.memory.MemoryWorld.Companion.shared
import com.aicontrolcenter.memory.MemoryWorld.Companion.task
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What recall and the graph read: requested scopes and kinds are selected by storage, however
 * old their memories are, and the candidate bounds hold for the whole read, all parts together.
 */
class CandidateReadsTest {
    private lateinit var spy: SpyRepository

    private fun world(config: MemoryConfig = MemoryConfig()) = MemoryWorld(config = config, wrap = { inner -> SpyRepository(inner).also { spy = it } })

    private fun record(id: String, scope: MemoryScope, at: Long, content: String, kind: MemoryKind = MemoryKind.NOTE) = MemoryRecord(
        id = MemoryId(id), scope = scope, kind = kind,
        tier = if (scope is MemoryScope.Task) MemoryTier.SHORT_TERM else MemoryTier.LONG_TERM,
        title = "Note $id", content = content, provenance = Provenance(ProvenanceOrigin.AGENT, recordedAt = at),
        createdAt = at, updatedAt = at, expiresAt = if (scope is MemoryScope.Task) at + MemoryConfig.DAY_MS else null,
    )

    @Test
    fun `every memory of an old task is recallable when that task is asked for`() = runTest {
        // The verifier's case: 20,000 task memories over 1,200 tasks; the oldest task's memories
        // are spread over the whole range, 12 of its 16 older than the newest 5,000 of the agent.
        val w = world()
        val a = w.agent("agt_a")
        val now = w.clock.current
        (0 until 20_000).forEach { w.repository.upsert(record("mem_t$it", task(a, "tsk_${it % 1_200}"), now - it, "travel task $it")) }
        spy.calls.clear()
        spy.filters.clear()

        val oldest = task(a, "tsk_1199")
        val hits = w.service.recall(w.owner, RecallQuery("travel", scopes = setOf(oldest), limit = 20, recordAccess = false))
        assertEquals((0 until 16).map { "mem_t${1_199 + it * 1_200}" }.toSet(), hits.map { it.record.id.value }.toSet())
        assertTrue(spy.filters.any { oldest in it.scopes.orEmpty() }, "the task is read through its scope")
        assertFalse(spy.calls.any { it.startsWith("taskMemoryOf") }, "${spy.calls}")
    }

    @Test
    fun `a kinds filter is applied by storage, before the candidate limit`() = runTest {
        val w = world(MemoryConfig(candidateLimit = 50))
        val a = w.agent("agt_a")
        val now = w.clock.current
        val scope = task(a, "tsk_move")
        (1..100).forEach { w.repository.upsert(record("mem_n$it", scope, now - it, "Moving note $it")) }
        (1..3).forEach { w.repository.upsert(record("mem_d$it", scope, now - 1_000 - it, "Moving decision $it", kind = MemoryKind.DECISION)) }

        val query = RecallQuery("moving", scopes = setOf(scope), kinds = setOf(MemoryKind.DECISION), recordAccess = false)
        assertEquals(setOf("mem_d1", "mem_d2", "mem_d3"), w.service.recall(w.owner, query).map { it.record.id.value }.toSet())
        // The agent asking for its own task gets the same.
        assertEquals(setOf("mem_d1", "mem_d2", "mem_d3"), w.service.recall(a, query).map { it.record.id.value }.toSet())
        // They are candidates in their own right, not rescued by the text search.
        val who = AccessResolver(w.agents, spy).resolve(a)
        val collected = MemoryReader(spy, MemoryConfig(candidateLimit = 50))
            .collect(who, MemoryFilter(kinds = setOf(MemoryKind.DECISION), excludeExpiredAt = now), setOf(scope), emptySet(), 50)
        assertEquals(listOf("mem_d1", "mem_d2", "mem_d3"), collected.records.map { it.id.value })
        assertFalse(collected.bounded)
    }

    @Test
    fun `the text search reaches an older memory of a requested task`() = runTest {
        val w = world(MemoryConfig(candidateLimit = 5))
        val a = w.agent("agt_a")
        val now = w.clock.current
        val scope = task(a, "tsk_trip")
        (1..10).forEach { w.repository.upsert(record("mem_n$it", scope, now - it, "Packing list item $it")) }
        w.repository.upsert(record("mem_old", scope, now - 1_000, "The zebra crossing is next to the hotel"))

        val hits = w.service.recall(w.owner, RecallQuery("zebra crossing", scopes = setOf(scope), recordAccess = false))
        assertEquals(listOf("mem_old"), hits.map { it.record.id.value })
    }

    @Test
    fun `thousands of requested task scopes are read in chunks well under the SQL variable limit`() = runTest {
        val w = world(MemoryConfig(candidateLimit = 300))
        val a = w.agent("agt_a")
        val now = w.clock.current
        (1..1_200).forEach { w.repository.upsert(record("mem_t$it", task(a, "tsk_$it"), now - it, "Step $it of the move")) }
        spy.filters.clear()

        val requested = (1..1_200).map { task(a, "tsk_$it") }.toSet()
        w.service.recall(w.owner, RecallQuery("move step", scopes = requested, recordAccess = false))
        val withTasks = spy.filters.filter { f -> f.scopes.orEmpty().any { it is MemoryScope.Task } }
        assertTrue(withTasks.isNotEmpty())
        assertTrue(withTasks.all { it.scopes!!.size <= MemoryReader.SCOPE_CHUNK }, "${withTasks.map { it.scopes!!.size }}")
        assertEquals(requested, withTasks.flatMap { it.scopes!! }.toSet())
    }

    @Test
    fun `the candidate limit holds for the whole read, not for each part`() = runTest {
        // The verifier's owner graph: a space and the task memory of six agents, all interleaved in time.
        val limit = 1_000
        val w = world(MemoryConfig(candidateLimit = limit))
        val team = w.space("Big")
        val agents = (listOf("agt_a") + (1..5).map { "agt_o$it" }).map { w.agent(it) }
        val now = w.clock.current
        (0 until 3_000).forEach { w.repository.upsert(record("mem_s$it", shared(team), now - it, "travel note $it")) }
        agents.forEach { agent ->
            (0 until 1_500).forEach { w.repository.upsert(record("mem_${agent.profile.id.value}_$it", MemoryScope.Task(TaskId("tsk_$it"), agent.profile.id), now - it, "travel task $it")) }
        }
        spy.loaded.clear()

        val graph = w.service.graph(w.owner, GraphRequest(spaceIds = setOf(team.id), includeTaskMemory = true))
        assertEquals(500, graph.nodes.size)
        assertTrue(graph.truncated)
        val total = spy.loaded.sumOf { it.second }
        // A part read again replaces its earlier page, so what is held at most is the latest page of each part.
        val held = spy.loaded.groupBy { it.first }.values.sumOf { it.last().second }
        assertTrue(held <= limit + MemoryReader.FIRST_PAGE, "held $held rows at once: ${spy.loaded}")
        assertTrue(total <= 2 * limit, "read $total rows: ${spy.loaded}")

        // And the candidates are exactly the newest `limit` of all parts together.
        val reader = MemoryReader(spy, MemoryConfig(candidateLimit = limit))
        val who = AccessResolver(w.agents, spy).resolve(w.owner)
        val scopes = agents.map { MemoryScope.Agent(it.profile.id) }.toSet<MemoryScope>() + shared(team)
        val collected = reader.collect(who, MemoryFilter(excludeExpiredAt = now), scopes, agents.map { it.profile.id }.toSet(), limit)
        val newest = w.repository.list(MemoryFilter(excludeExpiredAt = now), limit)
        assertEquals(newest.map { it.id }, collected.records.map { it.id })
        assertTrue(collected.bounded)
    }

    @Test
    fun `the text budget holds for the whole read, not for each part`() = runTest {
        val budget = 100_000L
        val w = world(MemoryConfig(candidateTextBudget = budget))
        val agents = (1..4).map { w.agent("agt_$it") }
        val now = w.clock.current
        agents.forEach { agent ->
            (0 until 600).forEach { w.repository.upsert(record("mem_${agent.profile.id.value}_$it", task(agent, "tsk_$it"), now - it, "x".repeat(990))) }
        }
        spy.loaded.clear()
        spy.textLoaded = 0

        val graph = w.service.graph(w.owner, GraphRequest(spaceIds = emptySet(), includeTaskMemory = true))
        assertTrue(graph.truncated)
        assertTrue(graph.nodes.size in 90..100, "${graph.nodes.size} nodes")
        // One first page shared by the five parts (private memory and four agents' tasks), not one each.
        assertTrue(spy.loaded.sumOf { it.second } <= MemoryReader.FIRST_PAGE, "${spy.loaded}")
        assertTrue(spy.textLoaded <= budget + MemoryReader.FIRST_PAGE * 1_000L, "${spy.textLoaded} characters read")
    }
}
