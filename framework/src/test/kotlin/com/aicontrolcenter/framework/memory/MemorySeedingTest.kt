package com.aicontrolcenter.framework.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.agents.AgentDraft
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.toDraft
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.agents.AgentTemplates
import com.aicontrolcenter.framework.agents.DefaultAgentManager
import com.aicontrolcenter.framework.tools.BuiltInTools
import com.aicontrolcenter.framework.tools.ClockTool
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Version 3 of the starter content (memory tools), the memory seed (Team knowledge) and clean-up on deletion. */
class MemorySeedingTest {
    private val world = TestWorld()
    private val manager = world.agentManager
    private val seeder = world.memorySeeder

    private suspend fun spaces() = world.memory.observeSpaces().first()

    private suspend fun byName(name: String): AgentProfile = world.agents.getAll().single { it.name == name }

    /** What an install at [version] looks like: the starter team with the template tools of that version, minus [skip]. */
    private suspend fun installAt(version: Int, skip: Set<String> = emptySet()) {
        for (templateId in AgentTemplates.starterTeam - skip) {
            val draft = AgentTemplates.byId(templateId)!!.draft
            val tools = if (version >= 2) BuiltInTools.defaultToolIds else emptySet()
            manager.create(draft.copy(toolIds = tools))
        }
        val director = world.agents.getAll().first { it.kind == AgentKind.DIRECTOR }
        world.settings.update { it.copy(seedVersion = version, directorAgentId = director.id) }
    }

    // ---- starter content, version 3 ----

    @Test
    fun `every template gets memory search and saving, but not sharing`() {
        for (template in AgentTemplates.all) {
            assertTrue(template.draft.toolIds.containsAll(MemoryTools.defaultToolIds), template.id)
            assertTrue(template.draft.toolIds.containsAll(BuiltInTools.defaultToolIds), template.id)
            assertFalse(MemoryTools.SHARE_ID in template.draft.toolIds, template.id)
        }
    }

    @Test
    fun `a new install starts with the memory tools and the team space, readable by all and writable by the Director only`() = runTest {
        manager.ensureDefaults()
        seeder.ensureDefaults()

        assertEquals(3, DefaultAgentManager.SEED_VERSION)
        val team = world.agents.getAll()
        assertEquals(6, team.size)
        assertTrue(team.all { it.toolIds.containsAll(MemoryTools.defaultToolIds) })
        val space = spaces().single()
        assertEquals(MemorySeeder.TEAM_SPACE_NAME, space.name)
        assertTrue(team.all { space.id in it.memory.readableSpaces }, "every starter agent may read the team space")
        assertEquals(listOf("Director"), team.filter { space.id in it.memory.writableSpaces }.map { it.name })
        assertEquals(MemorySeeder.MEMORY_SEED_VERSION, world.settings.get().memorySeedVersion)
        val event = world.events(ActivityType.MEMORY).single()
        assertEquals("Shared space created: Team knowledge", event.title)
        assertTrue("6 starter agents can read it. Only Director can add to it." in event.detail, event.detail)
        assertTrue(world.memory.access(MemoryActor.Agent(byName("Director"))).canWrite(MemoryScope.Shared(space.id)))
        assertFalse(world.memory.access(MemoryActor.Agent(byName("Writer"))).canWrite(MemoryScope.Shared(space.id)))

        val events = world.log.all.size
        seeder.ensureDefaults()
        manager.ensureDefaults()
        assertEquals(1, spaces().size)
        assertEquals(events, world.log.all.size, "both run once")
    }

    @Test
    fun `an install at version 2 gets exactly the memory tools, keeping the owner's other tool choices`() = runTest {
        installAt(version = 2, skip = setOf(AgentTemplates.WRITER))
        val researcher = byName("Researcher")
        manager.update(researcher.id, researcher.toDraft().copy(toolIds = researcher.toolIds - ClockTool.ID))
        val coder = manager.create(AgentTemplates.byId(AgentTemplates.CODER)!!.draft.copy(toolIds = setOf(ToolId("some.future_tool"))))
        val handMade = manager.create(AgentDraft(name = "Mine", toolIds = setOf(ClockTool.ID)))

        manager.ensureDefaults()

        assertFalse(world.agents.getAll().any { it.name == "Writer" }, "a deleted starter agent is not recreated")
        assertEquals(setOf(TEAM_LIST) + MemoryTools.defaultToolIds, byName("Researcher").toolIds, "the clock the owner removed stays removed")
        assertEquals(BuiltInTools.defaultToolIds + MemoryTools.defaultToolIds, byName("Analyst").toolIds)
        assertEquals(setOf(ToolId("some.future_tool")) + MemoryTools.defaultToolIds, world.agents.get(coder.id)!!.toolIds)
        assertEquals(setOf(ClockTool.ID), world.agents.get(handMade.id)!!.toolIds, "an agent not made from a template is left alone")
        assertEquals(DefaultAgentManager.SEED_VERSION, world.settings.get().seedVersion)
        val event = world.log.all.single { it.title == "Memory tools switched on" }
        assertTrue("6 agents" in event.detail, event.detail)
        assertTrue(world.log.all.none { it.title == "Information tools switched on" }, "the version-2 step does not run again")

        // Once only: a memory tool the owner switches off afterwards stays off.
        val analyst = byName("Analyst")
        manager.update(analyst.id, analyst.toDraft().copy(toolIds = analyst.toolIds - MemoryTools.SAVE_ID))
        manager.ensureDefaults()
        assertFalse(MemoryTools.SAVE_ID in byName("Analyst").toolIds)
        assertEquals(1, world.log.all.count { it.title == "Memory tools switched on" })
    }

    @Test
    fun `an install at version 1 gets the information tools and the memory tools, each in its own step`() = runTest {
        installAt(version = 1)

        manager.ensureDefaults()

        val researcher = byName("Researcher")
        assertEquals(AgentTemplates.byId(AgentTemplates.RESEARCHER)!!.draft.toolIds, researcher.toolIds)
        assertEquals(BuiltInTools.defaultToolIds + MemoryTools.defaultToolIds, researcher.toolIds)
        assertTrue(world.log.all.any { it.title == "Information tools switched on" })
        assertTrue(world.log.all.any { it.title == "Memory tools switched on" })
    }

    // ---- the memory seed ----

    @Test
    fun `an upgraded install gets the team space for its starter agents only, and the designated Director writes`() = runTest {
        installAt(version = 2)
        val mine = manager.create(AgentDraft(name = "Mine"))
        val secondDirector = manager.createFromTemplate(AgentTemplates.DIRECTOR, "Chief")
        manager.designateDirector(secondDirector.id)
        manager.ensureDefaults()

        seeder.ensureDefaults()

        val space = spaces().single()
        assertTrue(space.id !in world.agents.get(mine.id)!!.memory.readableSpaces, "the owner's own agents are left alone")
        assertEquals(listOf("Chief"), world.agents.getAll().filter { space.id in it.memory.writableSpaces }.map { it.name })
        assertEquals(7, world.agents.getAll().count { space.id in it.memory.readableSpaces })
        assertTrue(world.log.all.any { it.title == "Agent updated" && "memory policy" in it.detail }, "access is given as an ordinary, recorded edit")
    }

    @Test
    fun `a team space the owner deleted is not created again`() = runTest {
        manager.ensureDefaults()
        seeder.ensureDefaults()
        val space = spaces().single()

        world.memory.deleteSpace(MemoryActor.Owner, space.id)
        seeder.ensureDefaults()

        assertTrue(spaces().isEmpty())
        assertTrue(world.agents.getAll().none { space.id in it.memory.readableSpaces })
        assertEquals(1, world.events(ActivityType.MEMORY).size)
    }

    @Test
    fun `access the owner took away is not given back`() = runTest {
        manager.ensureDefaults()
        seeder.ensureDefaults()
        val space = spaces().single()
        val writer = byName("Writer")
        manager.update(writer.id, writer.toDraft().copy(memory = writer.memory.copy(readableSpaces = emptySet())))

        seeder.ensureDefaults()

        assertFalse(space.id in byName("Writer").memory.readableSpaces)
    }

    @Test
    fun `the memory seed waits for the starter team, and resumes with the space an interrupted run made`() = runTest {
        seeder.ensureDefaults()
        assertTrue(spaces().isEmpty(), "before the starter content is applied nothing happens")
        assertEquals(0, world.settings.get().memorySeedVersion)

        manager.ensureDefaults()
        val earlier = world.memory.createSpace(MemoryActor.Owner, "team knowledge")
        seeder.ensureDefaults()

        assertEquals(listOf(earlier.id), spaces().map { it.id }, "no second space")
        assertTrue(world.agents.getAll().all { earlier.id in it.memory.readableSpaces })
    }

    // ---- deletion ----

    @Test
    fun `deleting an agent deletes its private and task memory, and keeps what it shared`() = runTest {
        val agent = world.agent(model = null)
        val other = world.agent(model = null, name = "Other")
        val team = world.memory.createSpace(MemoryActor.Owner, "Team knowledge")
        world.memory.seed(MemoryScope.Agent(agent.id), "Private", "Mine.")
        world.memory.seed(MemoryScope.Task(TaskId("tsk_1"), agent.id), "Step", "Working note.")
        val shared = world.memory.seed(MemoryScope.Shared(team.id), "Shared", "For all.")
        val others = world.memory.seed(MemoryScope.Agent(other.id), "Theirs", "Not mine.")

        manager.delete(agent.id)

        assertEquals(listOf(agent.id), world.memory.forgottenAgents)
        assertEquals(setOf(shared.id, others.id), world.memory.repository.list().map { it.id }.toSet())
        assertTrue(world.log.all.none { it.type == ActivityType.ERROR })
    }

    @Test
    fun `a failing memory clean-up is logged and does not stop the deletion`() = runTest {
        val failing = object : MemoryService by world.memory {
            override suspend fun forgetAgent(agentId: AgentId): Int = throw IllegalStateException("store closed")
        }
        val agent = world.agent(model = null)
        val manager = DefaultAgentManager(
            world.agents, world.settings, world.activity, world.ids, world.clock, deletionListeners = listOf(AgentMemoryCleanup(failing)),
        )

        manager.delete(agent.id)

        assertTrue(world.agents.get(agent.id) == null)
        assertEquals("Clean-up after deleting an agent failed", world.log.all.single { it.type == ActivityType.ERROR }.title)
    }

    private companion object {
        val TEAM_LIST = ToolId("team.list_agents")
    }
}
