package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.MemoryValidationException
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import com.aicontrolcenter.memory.MemoryWorld.Companion.shared
import com.aicontrolcenter.memory.MemoryWorld.Companion.task
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareAndPromoteTest {
    private val world = MemoryWorld()
    private val service = world.service

    @Test
    fun `sharing copies into the space, links the copy to the original and keeps the original`() = runTest {
        val team = world.space("Team")
        val a = world.agent("agt_a", write = setOf(team.id))
        val b = world.agent("agt_b", read = setOf(team.id))
        val original = world.remember(a, private(a), "API rate limit", "The API allows 60 requests a minute.", importance = 0.8, tags = setOf("api"))
        world.clock.advance(100)

        val copy = service.share(a, original.id, team.id)
        assertNotEquals(original.id, copy.id)
        assertEquals(shared(team), copy.scope)
        assertEquals(listOf(original.content, original.title), listOf(copy.content, copy.title))
        assertEquals(original.tags, copy.tags)
        assertEquals(0.8, copy.importance)
        assertEquals(1, copy.revision)
        assertEquals(world.clock.current, copy.createdAt)
        assertEquals(ProvenanceOrigin.DERIVED, copy.provenance.origin)
        assertEquals(listOf(original.id), copy.provenance.derivedFrom)
        assertEquals(AgentId("agt_a"), copy.provenance.agentId)
        assertEquals(original, world.repository.get(original.id))
        val link = world.repository.linksOf(copy.id).single()
        assertEquals(Triple(copy.id, original.id, LinkRelation.SHARED_COPY_OF), Triple(link.from, link.to, link.relation))
        assertNotNull(world.repository.embeddings(HashingEmbedder.ID)[copy.id])

        // The other agent sees the copy but not the original; sharing again returns the same copy.
        assertEquals(listOf(copy.id), service.recall(b, RecallQuery("api rate limit")).map { it.record.id })
        assertNull(service.get(b, original.id))
        assertEquals(copy.id, service.share(a, original.id, team.id).id)
        assertEquals(2, world.repository.list().size)
        assertEquals("Memory shared: API rate limit", world.events.last { it.title.startsWith("Memory shared") }.title)
    }

    @Test
    fun `sharing needs read access to the original and write access to the space`() = runTest {
        val team = world.space("Team")
        val readOnly = world.agent("agt_r", read = setOf(team.id))
        val other = world.agent("agt_o", write = setOf(team.id))
        val mine = world.remember(readOnly, private(readOnly), "Mine")
        val refused = assertFailsWith<MemoryPermissionException> { service.share(readOnly, mine.id, team.id) }
        assertTrue("writable spaces" in refused.message!!)
        assertFailsWith<MemoryPermissionException> { service.share(other, mine.id, team.id) }
        assertFailsWith<MemoryValidationException> { service.share(world.owner, mine.id, SpaceId("spc_missing")) }
        assertEquals(1, world.repository.list().size)
    }

    @Test
    fun `sharing into the space the memory already lives in changes nothing`() = runTest {
        val team = world.space("Team")
        val record = world.remember(world.owner, shared(team), "Already here")
        assertEquals(record, service.share(world.owner, record.id, team.id))
        assertEquals(1, world.repository.list().size)
    }

    @Test
    fun `promotion keeps important task memory long-term and lets the rest expire`() = runTest {
        val agent = world.agent("agt_a")
        val scope = task(agent, "tsk_7")
        val key = world.remember(agent, scope, "Root cause", "The outage came from an expired certificate.", importance = 0.9)
        val minor = world.remember(agent, scope, "Tried restarting", "Restarting did not help.", importance = 0.3)
        world.clock.advance(1_000)

        val promoted = service.promoteTaskMemory(agent, TaskId("tsk_7"), agent.profile.id)
        val kept = promoted.single()
        assertEquals(MemoryScope.Agent(agent.profile.id), kept.scope)
        assertEquals(MemoryTier.LONG_TERM, kept.tier)
        assertNull(kept.expiresAt)
        assertEquals(key.content, kept.content)
        assertEquals(ProvenanceOrigin.SYSTEM, kept.provenance.origin)
        assertEquals(TaskId("tsk_7"), kept.provenance.taskId)
        assertEquals(listOf(key.id), kept.provenance.derivedFrom)

        // The promoted original expired at once, the minor one keeps its own expiry.
        assertEquals(world.clock.current, world.repository.get(key.id)!!.expiresAt)
        assertEquals(minor.expiresAt, world.repository.get(minor.id)!!.expiresAt)
        assertEquals(listOf(kept.id), service.recall(agent, RecallQuery("root cause outage")).map { it.record.id })
        assertEquals(emptyList(), service.promoteTaskMemory(agent, TaskId("tsk_7"), agent.profile.id))

        world.clock.advance(3 * MemoryConfig.DAY_MS)
        world.service.maintain()
        assertEquals(listOf(kept.id), world.repository.list().map { it.id })
    }

    @Test
    fun `promotion respects permissions and a switched-off private memory`() = runTest {
        val a = world.agent("agt_a")
        val b = world.agent("agt_b")
        world.remember(a, task(a, "tsk_1"), "Important", importance = 1.0)
        assertFailsWith<MemoryPermissionException> { service.promoteTaskMemory(b, TaskId("tsk_1"), a.profile.id) }
        assertFailsWith<MemoryValidationException> { service.promoteTaskMemory(a, TaskId("tsk_1"), a.profile.id, minImportance = 2.0) }

        world.changePolicy(a.profile.id) { it.copy(privateMemoryEnabled = false) }
        assertEquals(emptyList(), service.promoteTaskMemory(world.owner, TaskId("tsk_1"), a.profile.id))
        assertEquals(1, world.repository.list().size)
        assertTrue(world.events.last().title == "Task memory not kept")
    }
}
