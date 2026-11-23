package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.memory.MemoryChange
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import com.aicontrolcenter.memory.MemoryWorld.Companion.shared
import com.aicontrolcenter.memory.MemoryWorld.Companion.task
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryPermissionTest {
    private val world = MemoryWorld()
    private val service = world.service

    private suspend fun recallTitles(actor: com.aicontrolcenter.core.memory.MemoryActor, text: String) =
        service.recall(actor, RecallQuery(text, recordAccess = false)).map { it.record.title }

    @Test
    fun `the owner may read and write everything`() = runTest {
        val researcher = world.agent("agt_researcher")
        val space = world.space("Team")
        val access = service.access(world.owner)
        assertTrue(access.everything)
        val inPrivate = world.remember(world.owner, private(researcher), "Owner note in researcher memory")
        val inTask = world.remember(world.owner, task(researcher, "tsk_1"), "Owner note in task")
        val inSpace = world.remember(world.owner, shared(space), "Owner note in space")
        assertEquals(setOf(inPrivate.id, inTask.id, inSpace.id), service.recall(world.owner, RecallQuery("owner note")).map { it.record.id }.toSet())
    }

    @Test
    fun `private memory is the agent's own and only when enabled`() = runTest {
        val researcher = world.agent("agt_researcher")
        val writer = world.agent("agt_writer", private = false)
        assertEquals(setOf<MemoryScope>(private(researcher)), service.access(researcher).readable)
        assertEquals(emptySet(), service.access(writer).readable)

        val mine = world.remember(researcher, private(researcher), "Researcher secret")
        assertEquals(mine, service.get(researcher, mine.id))
        val refused = assertFailsWith<MemoryPermissionException> { world.remember(writer, private(writer), "Writer note") }
        assertTrue("private memory switched off" in refused.message!!, refused.message)
        assertTrue("memory settings" in refused.message!!)

        // Another agent's private memory is invisible and untouchable.
        val other = world.agent("agt_other")
        assertNull(service.get(other, mine.id))
        assertEquals(emptyList(), recallTitles(other, "researcher secret"))
        assertFailsWith<MemoryPermissionException> { world.remember(other, private(researcher), "Planted") }
        assertFailsWith<MemoryPermissionException> { service.update(other, mine.id, MemoryChange(title = "Changed")) }
        assertFailsWith<MemoryPermissionException> { service.forget(other, mine.id) }
        assertNotNull(world.repository.get(mine.id))
    }

    @Test
    fun `a read-only space can be read but not written, a write space can be written`() = runTest {
        val team = world.space("Team")
        val drafts = world.space("Drafts")
        val agent = world.agent("agt_a", read = setOf(team.id), write = setOf(drafts.id))
        val fact = world.remember(world.owner, shared(team), "Team holiday schedule")
        assertEquals(fact, service.get(agent, fact.id))
        assertEquals(listOf("Team holiday schedule"), recallTitles(agent, "holiday schedule"))

        val refused = assertFailsWith<MemoryPermissionException> { world.remember(agent, shared(team), "Agent edit") }
        assertTrue("may not write to the knowledge space \"Team\"" in refused.message!!, refused.message)
        assertFailsWith<MemoryPermissionException> { service.update(agent, fact.id, MemoryChange(content = "Edited")) }

        val written = world.remember(agent, shared(drafts), "Draft outline")
        assertEquals(shared(drafts), written.scope)
        // Write-only: it may add to Drafts but not read it back.
        assertNull(service.get(agent, written.id))
        assertEquals(setOf<MemoryScope>(private(agent), shared(team)), service.access(agent).readable)
        assertEquals(setOf<MemoryScope>(private(agent), shared(drafts)), service.access(agent).writable)
    }

    @Test
    fun `permissions follow the stored profile, not the actor's stale copy`() = runTest {
        val team = world.space("Team")
        val agent = world.agent("agt_a", read = setOf(team.id), write = setOf(team.id))
        val fact = world.remember(agent, shared(team), "Shared roadmap")
        world.changePolicy(agent.profile.id) { it.copy(readableSpaces = emptySet(), writableSpaces = emptySet()) }

        // `agent` still carries the old policy naming Team.
        assertTrue(team.id in agent.profile.memory.readableSpaces)
        assertNull(service.get(agent, fact.id))
        assertEquals(emptyList(), recallTitles(agent, "roadmap"))
        assertFailsWith<MemoryPermissionException> { world.remember(agent, shared(team), "More roadmap") }
        assertFalse(service.access(agent).canRead(shared(team)))
        assertEquals(emptyList(), service.observe(agent).first())
    }

    @Test
    fun `observe follows policy changes as they happen`() = runTest {
        val team = world.space("Team")
        val agent = world.agent("agt_a")
        world.remember(world.owner, shared(team), "Visible later")
        assertEquals(emptyList(), service.observe(agent).first().map { it.title })
        world.changePolicy(agent.profile.id) { it.copy(readableSpaces = setOf(team.id)) }
        assertEquals(listOf("Visible later"), service.observe(agent).first().map { it.title })
    }

    @Test
    fun `a disabled or deleted agent may use no memory at all`() = runTest {
        val team = world.space("Team")
        val agent = world.agent("agt_a", read = setOf(team.id), write = setOf(team.id))
        val own = world.remember(agent, private(agent), "Own memory")
        val work = world.remember(agent, task(agent, "tsk_1"), "Task memory")

        world.agents.upsert(world.agents.get(agent.profile.id)!!.copy(enabled = false))
        val access = service.access(agent)
        assertTrue(access.readable.isEmpty() && access.writable.isEmpty() && access.taskOwner == null)
        assertNull(service.get(agent, own.id))
        assertNull(service.get(agent, work.id))
        assertEquals(emptyList(), recallTitles(agent, "memory"))
        val off = assertFailsWith<MemoryPermissionException> { world.remember(agent, private(agent), "While off") }
        assertTrue("switched off" in off.message!!, off.message)

        world.agents.delete(agent.profile.id)
        val gone = assertFailsWith<MemoryPermissionException> { world.remember(agent, shared(team), "While gone") }
        assertTrue("no longer exists" in gone.message!!, gone.message)
        assertEquals(emptyList(), service.observe(agent).first())
    }

    @Test
    fun `task memory belongs to the agent doing the task`() = runTest {
        val a = world.agent("agt_a")
        val b = world.agent("agt_b")
        val note = world.remember(a, task(a, "tsk_1"), "Intermediate result")
        assertEquals(note, service.get(a, note.id))
        assertEquals(listOf("Intermediate result"), recallTitles(a, "intermediate result"))
        assertTrue(service.access(a).canWrite(task(a, "tsk_99")))

        assertNull(service.get(b, note.id))
        assertEquals(emptyList(), recallTitles(b, "intermediate result"))
        val refused = assertFailsWith<MemoryPermissionException> { world.remember(b, task(a, "tsk_1"), "Meddling") }
        assertTrue("task memory of another agent" in refused.message!!)
        // Even naming the scope explicitly does not help.
        assertEquals(emptyList(), service.recall(b, RecallQuery("intermediate", scopes = setOf(task(a, "tsk_1")))))
    }

    @Test
    fun `spaces named in a policy but missing are not granted`() = runTest {
        val agent = world.agent("agt_a", read = setOf(SpaceId("spc_ghost")), write = setOf(SpaceId("spc_ghost")))
        assertEquals(setOf<MemoryScope>(private(agent)), service.access(agent).readable)
        assertEquals(setOf<MemoryScope>(private(agent)), service.access(agent).writable)
    }

    @Test
    fun `refusals are logged as permission events with the same sentence`() = runTest {
        val agent = world.agent("agt_a", private = false)
        val refused = assertFailsWith<MemoryPermissionException> { world.remember(agent, private(agent), "Nope") }
        val event = world.events.last { it.type == ActivityType.PERMISSION }
        assertEquals(refused.message, event.detail)
        assertEquals(AgentId("agt_a"), event.agentId)
        assertFailsWith<MemoryPermissionException> { service.createSpace(agent, "Mine") }
    }
}
