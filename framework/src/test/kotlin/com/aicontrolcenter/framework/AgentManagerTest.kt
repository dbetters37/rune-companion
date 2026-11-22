package com.aicontrolcenter.framework

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.agents.AgentDraft
import com.aicontrolcenter.core.agents.AgentField
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentOperationException
import com.aicontrolcenter.core.agents.AgentValidationException
import com.aicontrolcenter.core.agents.DelegationPolicy
import com.aicontrolcenter.core.agents.GenerationSettings
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.agents.toDraft
import com.aicontrolcenter.framework.agents.AgentTemplates
import com.aicontrolcenter.framework.agents.DefaultAgentManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentManagerTest {
    private val world = TestWorld()
    private val manager = world.agentManager

    @Test
    fun `first run seeds the starter team and designates the director exactly once`() = runTest {
        manager.ensureDefaults()
        val team = world.agents.getAll()
        assertEquals(listOf("Director", "Researcher", "Analyst", "Writer", "Reviewer", "Operator"), team.map { it.name })
        val director = team.single { it.kind == AgentKind.DIRECTOR }
        assertEquals(director.id, world.settings.get().directorAgentId)
        assertEquals(DefaultAgentManager.SEED_VERSION, world.settings.get().seedVersion)
        assertTrue(team.all { it.modelId == null && it.systemInstructions.isNotBlank() || it.templateId == AgentTemplates.BLANK })

        manager.delete(team.first { it.name == "Writer" }.id)
        manager.ensureDefaults()
        assertEquals(5, world.agents.getAll().size, "seeding must not run again or resurrect deleted agents")
    }

    @Test
    fun `seeding leaves an existing team alone but still designates its director`() = runTest {
        val mine = manager.create(AgentDraft(name = "Boss", kind = AgentKind.DIRECTOR))
        manager.ensureDefaults()
        assertEquals(listOf("Boss"), world.agents.getAll().map { it.name })
        assertEquals(mine.id, world.settings.get().directorAgentId)
    }

    @Test
    fun `create normalises the draft and records activity`() = runTest {
        val agent = manager.create(
            AgentDraft(
                name = "  Scout  ", description = " finds things ", skills = setOf(" Research ", "", "CODING"),
                responsibilities = listOf(" one ", " "), modelId = ModelId("m1"),
                fallbackModelIds = listOf(ModelId("m1"), ModelId("m2"), ModelId("m2")),
            ),
        )
        assertEquals("Scout", agent.name)
        assertEquals("finds things", agent.description)
        assertEquals(setOf("research", "coding"), agent.skills)
        assertEquals(listOf("one"), agent.responsibilities)
        assertEquals(listOf(ModelId("m2")), agent.fallbackModelIds)
        assertEquals(agent, world.agents.get(agent.id))
        assertEquals(agent.createdAt, agent.updatedAt)
        assertEquals(listOf(ActivityType.REGISTRY), world.log.all.map { it.type })
    }

    @Test
    fun `validation reports every problem with its field`() = runTest {
        manager.create(AgentDraft(name = "Taken"))
        fun fields(draft: AgentDraft, issues: List<com.aicontrolcenter.core.agents.ValidationIssue>) = issues.map { it.field }
        assertEquals(listOf(AgentField.NAME), manager.validate(AgentDraft(name = "  ")).map { it.field })
        assertEquals(listOf(AgentField.NAME), manager.validate(AgentDraft(name = "x".repeat(41))).map { it.field })
        assertEquals(listOf(AgentField.NAME), manager.validate(AgentDraft(name = " taken ")).map { it.field })
        assertTrue(manager.validate(AgentDraft(name = "x".repeat(40))).isEmpty())
        val bad = AgentDraft(
            name = "Ok", generation = GenerationSettings(temperature = 2.5, maxOutputTokens = 0),
            memory = MemoryPolicy(recallLimit = 51), delegation = DelegationPolicy(maxDepth = -1),
        )
        assertEquals(
            listOf(AgentField.GENERATION, AgentField.GENERATION, AgentField.MEMORY, AgentField.DELEGATION),
            manager.validate(bad).map { it.field },
        )
        assertEquals(listOf(AgentField.GENERATION), manager.validate(AgentDraft(name = "N", generation = GenerationSettings(temperature = Double.NaN))).map { it.field })
        val error = assertFailsWith<AgentValidationException> { manager.create(bad) }
        assertEquals(4, error.issues.size)
        assertEquals(1, world.agents.getAll().size, "an invalid draft must not be saved")
    }

    @Test
    fun `update keeps identity and creation time, and may keep its own name`() = runTest {
        val agent = manager.create(AgentDraft(name = "Scout"))
        val updated = manager.update(agent.id, agent.toDraft().copy(description = "new", toolIds = setOf(ToolId("t"))))
        assertEquals(agent.id, updated.id)
        assertEquals(agent.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt > agent.updatedAt)
        assertEquals("new", world.agents.get(agent.id)?.description)
        assertEquals("Scout: description, tools", world.log.all.last().detail)

        manager.create(AgentDraft(name = "Other"))
        assertFailsWith<AgentValidationException> { manager.update(agent.id, agent.toDraft().copy(name = "other")) }
        assertFailsWith<AgentOperationException> { manager.update(AgentId("agt_missing"), AgentDraft(name = "Ghost")) }
    }

    @Test
    fun `duplicate copies configuration under a unique name with no history`() = runTest {
        val source = manager.create(
            AgentDraft(
                name = "Scout", kind = AgentKind.REVIEWER, systemInstructions = "Be careful.", skills = setOf("review"),
                modelId = ModelId("m1"), toolIds = setOf(ToolId("t")), delegation = DelegationPolicy(canDelegate = true),
            ),
        )
        world.agents.updateStats(source.id) { it.copy(tasksCompleted = 9) }
        val first = manager.duplicate(source.id)
        val second = manager.duplicate(source.id)
        val named = manager.duplicate(source.id, "  Sentinel ")
        assertEquals(listOf("Scout copy", "Scout copy 2", "Sentinel"), listOf(first.name, second.name, named.name))
        assertNotEquals(source.id, first.id)
        assertEquals(source.toDraft().copy(name = first.name, colorIndex = first.colorIndex), first.toDraft())
        assertEquals(0, world.agents.getStats(first.id).tasksCompleted)
        assertEquals("scout 2", manager.duplicate(source.id, "scout").name, "a taken name is made unique, not rejected")
        assertFailsWith<AgentOperationException> { manager.duplicate(AgentId("agt_missing")) }
    }

    @Test
    fun `a duplicated director is not the designated director`() = runTest {
        manager.ensureDefaults()
        val director = world.agents.getAll().first { it.kind == AgentKind.DIRECTOR }
        val copy = manager.duplicate(director.id)
        assertEquals(AgentKind.DIRECTOR, copy.kind)
        assertEquals(director.id, world.settings.get().directorAgentId)
    }

    @Test
    fun `long names stay within the limit when made unique`() = runTest {
        val long = "N".repeat(40)
        val a = manager.create(AgentDraft(name = long))
        val b = manager.duplicate(a.id)
        val c = manager.createFromTemplate(AgentTemplates.BLANK, long)
        assertTrue(listOf(a, b, c).all { it.name.length <= 40 })
        assertEquals(3, setOf(a.name, b.name, c.name).size)
    }

    @Test
    fun `templates create distinctly named agents`() = runTest {
        val one = manager.createFromTemplate(AgentTemplates.RESEARCHER)
        val two = manager.createFromTemplate(AgentTemplates.RESEARCHER)
        val custom = manager.createFromTemplate(AgentTemplates.CODER, "Byte")
        assertEquals(listOf("Researcher", "Researcher 2", "Byte"), listOf(one.name, two.name, custom.name))
        assertEquals(AgentTemplates.CODER, custom.templateId)
        assertFailsWith<AgentOperationException> { manager.createFromTemplate("nope") }
        assertEquals(manager.templates.map { it.id }.toSet().size, manager.templates.size)
        val empty = TestWorld().agentManager
        assertTrue(manager.templates.all { empty.validate(it.draft).isEmpty() }, "every template must be valid on its own")
    }

    @Test
    fun `enable, disable and model assignment change only what they say`() = runTest {
        val agent = manager.create(AgentDraft(name = "Scout", fallbackModelIds = listOf(ModelId("m2"))))
        val events = world.log.all.size
        assertEquals(agent, manager.setEnabled(agent.id, true), "no-op must not rewrite the agent")
        assertEquals(events, world.log.all.size)
        val disabled = manager.setEnabled(agent.id, false)
        assertEquals(agent.copy(enabled = false, updatedAt = disabled.updatedAt), disabled)
        val assigned = manager.assignModel(agent.id, ModelId("m2"))
        assertEquals(ModelId("m2"), assigned.modelId)
        assertTrue(assigned.fallbackModelIds.isEmpty(), "the primary model must not also be a fallback")
        assertNull(manager.assignModel(agent.id, null).modelId)
        assertFailsWith<AgentOperationException> { manager.setEnabled(AgentId("agt_missing"), false) }
        assertFailsWith<AgentOperationException> { manager.assignModel(AgentId("agt_missing"), null) }
    }

    @Test
    fun `the designated director is protected until another is designated`() = runTest {
        manager.ensureDefaults()
        val director = world.agents.getAll().first { it.kind == AgentKind.DIRECTOR }
        val researcher = world.agents.getAll().first { it.name == "Researcher" }
        assertFailsWith<AgentOperationException> { manager.delete(director.id) }
        assertEquals(
            listOf(AgentField.KIND),
            manager.validate(director.toDraft().copy(kind = AgentKind.SPECIALIST), director.id).map { it.field },
        )
        assertFailsWith<AgentOperationException> { manager.designateDirector(researcher.id) }
        assertFailsWith<AgentOperationException> { manager.designateDirector(AgentId("agt_missing")) }

        val second = manager.duplicate(director.id, "Director B")
        manager.designateDirector(second.id)
        assertEquals(second.id, world.settings.get().directorAgentId)
        manager.delete(director.id)
        assertNull(world.agents.get(director.id))
        assertEquals(listOf("Director"), world.deleted)
        manager.delete(director.id) // deleting a missing agent is a no-op
        assertEquals(listOf("Director"), world.deleted)
    }

    @Test
    fun `concurrent creates never produce duplicate names`() = runTest {
        val created = (1..25).map { async { manager.createFromTemplate(AgentTemplates.ANALYST) } }.awaitAll()
        assertEquals(25, created.map { it.name.lowercase() }.toSet().size)
        assertEquals(25, created.map { it.id }.toSet().size)
    }
}
