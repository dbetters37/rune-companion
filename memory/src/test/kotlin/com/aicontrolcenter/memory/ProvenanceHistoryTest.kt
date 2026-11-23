package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.MemoryChange
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceAction
import com.aicontrolcenter.core.memory.ProvenanceEvent
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import com.aicontrolcenter.memory.MemoryWorld.Companion.task
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/** Provenance says who first wrote a memory and is never changed; what happened later is appended to its history. */
class ProvenanceHistoryTest {
    private val world = MemoryWorld()
    private val service = world.service

    @Test
    fun `a copy the owner shares keeps the author and source of the original, also after the author is gone`() = runTest {
        val team = world.space("Team")
        val a = world.agent("agt_a")
        val fromTool = service.remember(
            a,
            MemoryDraft(
                scope = private(a), kind = MemoryKind.FINDING, title = "Fare", content = "Return fare is 120 EUR.",
                provenance = Provenance(
                    ProvenanceOrigin.TOOL, conversationId = ConversationId("cnv_1"), modelName = "model-x",
                    source = "https://airline.example/fares", recordedAt = 0,
                ),
            ),
        )
        world.clock.advance(50)
        val copy = service.share(world.owner, fromTool.id, team.id)

        assertEquals(fromTool.provenance.copy(derivedFrom = listOf(fromTool.id)), copy.provenance)
        assertEquals(
            listOf(ProvenanceEvent(ProvenanceAction.SHARED_FROM, ProvenanceOrigin.OWNER, null, world.clock.current, fromTool.id, "Copied revision 1")),
            copy.history,
        )

        // The author and its private original go; the copy still says who wrote it and where it came from.
        world.agents.delete(a.profile.id)
        service.forgetAgent(a.profile.id)
        val kept = world.repository.get(copy.id)!!
        assertEquals(Triple(ProvenanceOrigin.TOOL, AgentId("agt_a"), "https://airline.example/fares"), Triple(kept.provenance.origin, kept.provenance.agentId, kept.provenance.source))
        assertEquals(fromTool.provenance.recordedAt, kept.provenance.recordedAt)
    }

    @Test
    fun `a note the owner put into an agent's task memory stays the owner's when the agent promotes it`() = runTest {
        val b = world.agent("agt_b")
        val note = service.remember(world.owner, world.draft(task(b, "tsk_1"), "Budget", "Keep it under 500.", importance = 0.9).copy(provenance = Provenance(ProvenanceOrigin.OWNER, recordedAt = 0)))
        world.clock.advance(10)
        val promoted = service.promoteTaskMemory(b, TaskId("tsk_1"), b.profile.id).single()

        assertEquals(ProvenanceOrigin.OWNER, promoted.provenance.origin)
        assertEquals(null, promoted.provenance.agentId, "the agent did not write it")
        assertEquals(listOf(note.id), promoted.provenance.derivedFrom)
        assertEquals(
            ProvenanceEvent(ProvenanceAction.PROMOTED_FROM, ProvenanceOrigin.AGENT, b.profile.id, world.clock.current, note.id, "Kept from task tsk_1"),
            promoted.history.single(),
        )
    }

    @Test
    fun `every edit is appended with who made it and when, and earlier entries are never rewritten`() = runTest {
        val c = world.agent("agt_c")
        val memory = world.remember(c, private(c), "Plan", "Draft one")
        world.clock.advance(10)
        val byOwner = service.update(world.owner, memory.id, MemoryChange(content = "Draft two", importance = 0.9))
        world.clock.advance(10)
        val byAgent = service.update(c, memory.id, MemoryChange(title = "Plan B"))

        assertEquals(memory.provenance, byAgent.provenance, "provenance describes how the text was first written")
        assertEquals(
            listOf(
                ProvenanceEvent(ProvenanceAction.EDITED, ProvenanceOrigin.OWNER, null, memory.createdAt + 10, note = "content, importance changed"),
                ProvenanceEvent(ProvenanceAction.EDITED, ProvenanceOrigin.AGENT, c.profile.id, memory.createdAt + 20, note = "title changed"),
            ),
            byAgent.history,
        )
        assertEquals(byOwner.history, byAgent.history.take(1))
        assertEquals(byAgent, world.repository.get(memory.id))
        // A change that changes nothing adds nothing.
        assertEquals(byAgent, service.update(c, memory.id, MemoryChange(title = "Plan B")))
    }

    @Test
    fun `a copy of an edited memory carries the edits, and a copy of a copy names both originals`() = runTest {
        val team = world.space("Team")
        val other = world.space("Other")
        val d = world.agent("agt_d", read = setOf(team.id, other.id), write = setOf(team.id, other.id))
        val memory = world.remember(d, private(d), "Rule", "Ship on Fridays")
        world.clock.advance(5)
        val edited = service.update(world.owner, memory.id, MemoryChange(content = "Never ship on Fridays"))
        val first = service.share(d, memory.id, team.id)
        val second = service.share(d, first.id, other.id)

        assertEquals(edited.history + first.history.last(), first.history)
        assertEquals(first.history + second.history.last(), second.history)
        assertEquals(listOf(memory.id, first.id), second.provenance.derivedFrom)
        assertEquals(memory.provenance.origin to memory.provenance.agentId, second.provenance.origin to second.provenance.agentId)
    }
}
