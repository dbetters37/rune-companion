package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentDraft
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.toDraft
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.memory.MemoryTools
import com.aicontrolcenter.framework.operator.PermissionRequestTool
import com.aicontrolcenter.framework.tools.BuiltInTools
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Version 4 of the starter content: the Operator's phone tools and `permissions.request` for every template agent. */
class OperatorSeedTest {
    private val world = TestWorld()
    private val manager = world.agentManager

    private suspend fun byName(name: String): AgentProfile = world.agents.getAll().single { it.name == name }

    /** The starter team as a version-3 install has it: information and memory tools, nothing of Phase 5. */
    private suspend fun installAtVersion3(skip: Set<String> = emptySet()) {
        for (templateId in AgentTemplates.starterTeam - skip) {
            val draft = AgentTemplates.byId(templateId)!!.draft
            manager.create(draft.copy(toolIds = BuiltInTools.defaultToolIds + MemoryTools.defaultToolIds))
        }
        val director = world.agents.getAll().first { it.kind == AgentKind.DIRECTOR }
        world.settings.update { it.copy(seedVersion = 3, directorAgentId = director.id) }
    }

    @Test
    fun `the templates switch the operator tools on for the Operator only, and asking for a permission for everyone`() {
        val operatorTools = OperatorTools.all.map { it.id }.toSet() - PermissionRequestTool.ID
        assertEquals(operatorTools, AgentTemplates.operatorToolIds)
        assertEquals(44, operatorTools.size)
        for (template in AgentTemplates.all) {
            assertTrue(PermissionRequestTool.ID in template.draft.toolIds, template.id)
            if (template.id == AgentTemplates.OPERATOR) {
                assertTrue(template.draft.toolIds.containsAll(operatorTools), template.id)
            } else {
                assertTrue(template.draft.toolIds.none { it in operatorTools }, "${template.id} has no phone tools")
            }
        }
    }

    @Test
    fun `a new install has the Operator's phone tools switched on and every agent able to ask`() = runTest {
        manager.ensureDefaults()

        assertEquals(4, DefaultAgentManager.SEED_VERSION)
        assertEquals(DefaultAgentManager.SEED_VERSION, world.settings.get().seedVersion)
        val team = world.agents.getAll()
        assertTrue(team.all { PermissionRequestTool.ID in it.toolIds })
        assertTrue(byName("Operator").toolIds.containsAll(AgentTemplates.operatorToolIds))
        assertTrue(team.filter { it.name != "Operator" }.all { agent -> agent.toolIds.none { it in AgentTemplates.operatorToolIds } })
        assertTrue(world.log.all.none { it.title == "Phone operator tools switched on" }, "a new install has nothing to upgrade")
    }

    @Test
    fun `an install at version 3 gets exactly the new tools and keeps every other choice of the owner`() = runTest {
        installAtVersion3(skip = setOf(AgentTemplates.WRITER))
        // The owner switched the Operator's memory saving off and gave it a tool of their choosing.
        val operator = byName("Operator")
        manager.update(operator.id, operator.toDraft().copy(toolIds = operator.toolIds - MemoryTools.SAVE_ID + ToolId("team.list_agents")))
        val handMade = manager.create(AgentDraft(name = "Mine", toolIds = setOf(ToolId("clock.now"))))
        val grantsBefore = world.grants.observeAll().first()

        manager.ensureDefaults()

        assertEquals(DefaultAgentManager.SEED_VERSION, world.settings.get().seedVersion)
        assertFalse(world.agents.getAll().any { it.name == "Writer" }, "a deleted starter agent is not recreated")
        val upgraded = byName("Operator").toolIds
        assertTrue(upgraded.containsAll(AgentTemplates.operatorToolIds))
        assertFalse(MemoryTools.SAVE_ID in upgraded, "the memory tool the owner switched off stays off")
        assertTrue(ToolId("team.list_agents") in upgraded)
        assertEquals(
            BuiltInTools.defaultToolIds + MemoryTools.defaultToolIds + AgentTemplates.askToolIds, byName("Researcher").toolIds,
            "other agents only gain asking for a permission",
        )
        assertEquals(setOf(ToolId("clock.now")), world.agents.get(handMade.id)!!.toolIds, "an agent not made from a template is left alone")
        val event = world.log.all.single { it.title == "Phone operator tools switched on" }
        assertTrue("5 agents" in event.detail && "The Operator also has the phone tools" in event.detail, event.detail)
        assertTrue("gives no permission" in event.detail, event.detail)
        assertTrue(world.log.all.none { it.title == "Memory tools switched on" || it.title == "Information tools switched on" }, "earlier steps do not run again")
        assertEquals(grantsBefore, world.grants.observeAll().first(), "switching tools on grants nothing")
        assertTrue(grantsBefore.isEmpty())

        // Once only: a phone tool the owner switches off afterwards stays off.
        val after = byName("Operator")
        manager.update(after.id, after.toDraft().copy(toolIds = after.toolIds - ToolId("scripts.run_shell")))
        manager.ensureDefaults()
        assertFalse(ToolId("scripts.run_shell") in byName("Operator").toolIds)
        assertEquals(1, world.log.all.count { it.title == "Phone operator tools switched on" })
    }

    @Test
    fun `an install at version 1 reaches the same tools through every step in order`() = runTest {
        for (templateId in AgentTemplates.starterTeam) manager.create(AgentTemplates.byId(templateId)!!.draft.copy(toolIds = emptySet()))
        world.settings.update { it.copy(seedVersion = 1) }

        manager.ensureDefaults()

        for (agent in world.agents.getAll()) {
            assertEquals(AgentTemplates.byId(agent.templateId!!)!!.draft.toolIds, agent.toolIds, agent.name)
        }
        assertTrue(world.log.all.any { it.title == "Information tools switched on" })
        assertTrue(world.log.all.any { it.title == "Memory tools switched on" })
        assertTrue(world.log.all.any { it.title == "Phone operator tools switched on" })
        val information = world.log.all.single { it.title == "Information tools switched on" }
        assertTrue("only read information" in information.detail, "the information step still gives only read-only tools")
    }
}
