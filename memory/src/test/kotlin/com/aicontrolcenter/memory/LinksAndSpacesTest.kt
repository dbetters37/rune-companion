package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.MemoryValidationException
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import com.aicontrolcenter.memory.MemoryWorld.Companion.shared
import com.aicontrolcenter.memory.MemoryWorld.Companion.task
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinksAndSpacesTest {
    private val world = MemoryWorld()
    private val service = world.service

    @Test
    fun `linking needs both ends readable and the source writable, and never duplicates`() = runTest {
        val team = world.space("Team")
        val agent = world.agent("agt_a", read = setOf(team.id))
        val mine = world.remember(agent, private(agent), "My summary")
        val theirs = world.remember(world.owner, shared(team), "Team source")

        val link = service.link(agent, mine.id, theirs.id, LinkRelation.DERIVED_FROM, 0.7)
        assertEquals(ProvenanceOrigin.AGENT, link.provenance.origin)
        assertEquals(AgentId("agt_a"), link.provenance.agentId)
        val again = service.link(agent, mine.id, theirs.id, LinkRelation.DERIVED_FROM, 0.4)
        assertEquals(link.id, again.id)
        assertEquals(0.4, world.repository.linksOf(mine.id).single().weight)
        assertEquals("Memories linked: My summary → Team source", world.events.last().title)

        // The space is read-only for the agent, so a link starting there is refused.
        assertFailsWith<MemoryPermissionException> { service.link(agent, theirs.id, mine.id, LinkRelation.SUPPORTS) }
        assertFailsWith<MemoryValidationException> { service.link(agent, mine.id, mine.id, LinkRelation.RELATES_TO) }
        assertFailsWith<MemoryValidationException> { service.link(agent, mine.id, theirs.id, LinkRelation.RELATES_TO, 1.2) }
        val stranger = world.agent("agt_s")
        val foreign = world.remember(stranger, private(stranger), "Stranger's")
        assertFailsWith<MemoryPermissionException> { service.link(agent, mine.id, foreign.id, LinkRelation.RELATES_TO) }
    }

    @Test
    fun `linksOf hides links whose other end is unreadable and unlink removes a link`() = runTest {
        val a = world.agent("agt_a")
        val b = world.agent("agt_b")
        val first = world.remember(world.owner, private(a), "First")
        val second = world.remember(world.owner, private(a), "Second")
        val elsewhere = world.remember(world.owner, private(b), "Elsewhere")
        val visible = service.link(world.owner, first.id, second.id, LinkRelation.RELATES_TO)
        service.link(world.owner, first.id, elsewhere.id, LinkRelation.REFERENCES)

        assertEquals(2, service.linksOf(world.owner, first.id).size)
        assertEquals(listOf(visible.id), service.linksOf(a, first.id).map { it.id })
        assertEquals(emptyList(), service.linksOf(b, first.id))

        assertFailsWith<MemoryPermissionException> { service.unlink(b, visible.id) }
        service.unlink(a, visible.id)
        service.unlink(a, MemoryLinkId("lnk_missing"))
        assertEquals(listOf(LinkRelation.REFERENCES), world.repository.linksOf(first.id).map { it.relation })
        service.forget(world.owner, elsewhere.id)
        assertEquals(emptyList(), world.repository.linksOf(first.id))
    }

    @Test
    fun `only the owner manages spaces and names stay unique`() = runTest {
        val agent = world.agent("agt_a")
        val team = world.space("Team")
        assertFailsWith<MemoryPermissionException> { service.createSpace(agent, "Agent space") }
        assertFailsWith<MemoryPermissionException> { service.updateSpace(agent, team.id, "Renamed", "") }
        assertFailsWith<MemoryPermissionException> { service.deleteSpace(agent, team.id) }
        assertFailsWith<MemoryValidationException> { service.createSpace(world.owner, " TEAM ") }
        assertFailsWith<MemoryValidationException> { service.createSpace(world.owner, "  ") }
        assertFailsWith<MemoryValidationException> { service.updateSpace(world.owner, SpaceId("spc_none"), "X", "") }

        world.clock.advance(10)
        val renamed = service.updateSpace(world.owner, team.id, "Team knowledge", "Everything the team knows")
        assertEquals("Team knowledge", renamed.name)
        assertEquals(world.clock.current, renamed.updatedAt)
        assertEquals(listOf(renamed), service.observeSpaces().first())
        assertEquals(team.createdAt, renamed.createdAt)
        service.updateSpace(world.owner, team.id, "team KNOWLEDGE", "Case change of its own name is fine")
    }

    @Test
    fun `deleting a space deletes its memories and removes it from every agent's policy`() = runTest {
        val team = world.space("Team")
        val other = world.space("Other")
        val reader = world.agent("agt_r", read = setOf(team.id, other.id))
        val writer = world.agent("agt_w", write = setOf(team.id))
        val bystander = world.agent("agt_b", read = setOf(other.id))
        world.remember(world.owner, shared(team), "One")
        world.remember(writer, shared(team), "Two")
        val kept = world.remember(world.owner, shared(other), "Kept")

        assertEquals(2, service.deleteSpace(world.owner, team.id))
        assertNull(world.repository.getSpace(team.id))
        assertEquals(listOf(kept.id), world.repository.list().map { it.id })
        assertEquals(setOf(other.id), world.agents.get(reader.profile.id)!!.memory.readableSpaces)
        assertEquals(emptySet(), world.agents.get(writer.profile.id)!!.memory.writableSpaces)
        assertEquals(1, world.agents.get(bystander.profile.id)!!.updatedAt, "untouched agents are not rewritten")
        assertEquals(0, service.deleteSpace(world.owner, team.id))
        assertTrue(world.events.any { it.title == "Knowledge space deleted: Team" && "2 memories" in it.detail })
    }

    @Test
    fun `forgetting an agent deletes its private and task memory but not what it shared`() = runTest {
        val team = world.space("Team")
        val agent = world.agent("agt_a", write = setOf(team.id), read = setOf(team.id))
        val other = world.agent("agt_o")
        val own = world.remember(agent, private(agent), "Own")
        world.remember(agent, task(agent, "tsk_1"), "Task one")
        world.remember(agent, task(agent, "tsk_2"), "Task two")
        val sharedCopy = service.share(agent, own.id, team.id)
        val othersMemory = world.remember(other, private(other), "Someone else")
        val othersTask = world.remember(other, task(other, "tsk_1"), "Same task id, other agent")

        assertEquals(3, service.forgetAgent(agent.profile.id))
        assertEquals(setOf(sharedCopy.id, othersMemory.id, othersTask.id), world.repository.list().map { it.id }.toSet())
        assertEquals(listOf(own.id), world.repository.get(sharedCopy.id)!!.provenance.derivedFrom)
        assertTrue(world.events.last().title.startsWith("Agent memory forgotten"))
    }

    @Test
    fun `maintenance deletes expired memories and computes missing embeddings in batches`() = runTest {
        val embedder = RecordingEmbedder(Locality.ON_DEVICE)
        val w = MemoryWorld(embedder, config = MemoryConfig(embeddingBatchSize = 2, maxEmbeddingsPerMaintenance = 3))
        val agent = w.agent("agt_a")
        embedder.failing = true
        repeat(5) { w.remember(agent, private(agent), "Note $it") }
        w.service.remember(agent, w.draft(private(agent), "Short", tier = MemoryTier.SHORT_TERM, ttlMs = 10))
        embedder.failing = false
        embedder.calls.clear()
        w.clock.advance(10)

        val first = w.service.maintain()
        assertEquals(1, first.expiredDeleted)
        assertEquals(3, first.embeddingsComputed)
        assertEquals(listOf(2, 1), embedder.calls.map { it.size })
        assertEquals(2, w.service.maintain().embeddingsComputed)
        assertEquals(0, w.service.maintain().embeddingsComputed)
        assertEquals(emptyList(), w.repository.missingEmbeddings(embedder.id))
        assertTrue(w.events.any { it.title == "Memory maintenance" })
    }

    @Test
    fun `task ids are scoped by agent`() = runTest {
        val a = world.agent("agt_a")
        val b = world.agent("agt_b")
        world.remember(a, task(a, "tsk_1"), "A's step", importance = 0.9)
        world.remember(b, task(b, "tsk_1"), "B's step", importance = 0.9)
        val promoted = service.promoteTaskMemory(a, TaskId("tsk_1"), a.profile.id)
        assertEquals(listOf("A's step"), promoted.map { it.title })
    }
}
