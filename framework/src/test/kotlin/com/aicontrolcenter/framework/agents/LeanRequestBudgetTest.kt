package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.framework.orchestration.TeamFixture
import com.aicontrolcenter.framework.orchestration.TeamTools
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rule 28 (Phase 6, WP15; the owner's cost request #43): the first request of every starter agent
 * carries at most 3,000 estimated tokens of tool definitions and a system prompt of at most 1,500,
 * in a chat and in an objective's task (with the team tools), with every tool the app registers.
 * The Operator's first request before and after lean requests is printed into the test report.
 */
class LeanRequestBudgetTest {
    /** The starter team over every tool the app registers (the team tools included); one scripted model that answers at once. */
    private class Team(test: TestScope) {
        val fixture = TeamFixture(test)
        val world = fixture.world
        val client = ScriptedLlmClient.replies("Ready.")

        suspend fun setUp(): Team {
            registerOperatorTools(world)
            world.agentManager.ensureDefaults()
            return this
        }

        suspend fun starters(): List<AgentProfile> = world.agents.getAll().filter { it.templateId in AgentTemplates.starterTeam }

        /** The first request [agent] sends: in a chat, or in an objective's task with the team tools. */
        suspend fun firstRequest(agent: AgentProfile, inTask: Boolean): ChatRequest {
            val model = world.serve(client)
            val before = client.requests.size
            val extras = if (inTask) setOf(TeamTools.ASK_ID, TeamTools.DELEGATE_ID) else emptySet()
            world.runner.run(AgentRunRequest(agent, model.id, listOf(ChatMessage.user("Hello.")), extraToolIds = extras))
            return client.requests[before]
        }
    }

    @Test
    fun `every starter agent's first request keeps within the budgets of rule 28, in a chat and in a task`() = runTest {
        val team = Team(this).setUp()
        val starters = team.starters()
        assertEquals(AgentTemplates.starterTeam.size, starters.size)
        for (agent in starters) {
            for (inTask in listOf(false, true)) {
                val first = team.firstRequest(agent, inTask)
                val tools = toolTokens(first.tools)
                val system = textTokens(systemOf(first))
                val where = if (inTask) "in a task" else "in a chat"
                println("RULE 28 ${agent.name} $where: ${first.tools.size} tools, $tools tokens of tool definitions; system prompt $system tokens")
                assertTrue(tools <= TOOL_BUDGET, "${agent.name} $where offers $tools tokens of tool definitions (budget $TOOL_BUDGET): ${namesIn(first)}")
                assertTrue(system <= SYSTEM_BUDGET, "${agent.name} $where has a system prompt of $system tokens (budget $SYSTEM_BUDGET)")
            }
        }
    }

    @Test
    fun `the Operator's first request before and after lean requests`() = runTest {
        val team = Team(this).setUp()
        val operator = team.starters().single { it.templateId == AgentTemplates.OPERATOR }
        // Before (0.5.2): every enabled tool on every call, and a system prompt naming them all.
        val enabled = operator.toolIds.mapNotNull { team.world.toolRegistry.byId(it)?.spec }.sortedBy { it.functionName }
        val beforeTools = toolTokens(enabled.map(ToolMenu::definition))
        val beforeSystem = textTokens(AgentPromptComposer().compose(operator, enabled))

        val first = team.firstRequest(operator, inTask = false)
        val afterTools = toolTokens(first.tools)
        val afterSystem = textTokens(systemOf(first))
        println(
            "RULE 28 Operator first request BEFORE: ${enabled.size} tools, $beforeTools tokens of tool definitions, system prompt $beforeSystem tokens, " +
                "${beforeTools + beforeSystem} in all",
        )
        println(
            "RULE 28 Operator first request AFTER:  ${first.tools.size} tools (${namesIn(first).joinToString()}), $afterTools tokens of tool definitions, " +
                "system prompt $afterSystem tokens, ${afterTools + afterSystem} in all",
        )
        assertEquals(49, enabled.size, "the Operator template's tools")
        assertTrue(beforeTools > ToolMenu.THRESHOLD_TOKENS, "the Operator's whole catalogue is over the menu threshold: $beforeTools")
        assertEquals(listOf("clock_now", "memory_search", ToolMenu.FUNCTION_NAME), namesIn(first))
        assertTrue(afterTools * 4 < beforeTools, "at least four times smaller: $afterTools against $beforeTools")
        assertTrue(afterSystem <= beforeSystem, "the system prompt does not grow: $afterSystem against $beforeSystem")
    }

    @Test
    fun `agents under the threshold are offered every tool at once, as before, with today's system prompt`() = runTest {
        val team = Team(this).setUp()
        for (agent in team.starters().filter { it.templateId != AgentTemplates.OPERATOR }) {
            val first = team.firstRequest(agent, inTask = true)
            val enabled = (agent.toolIds + TeamTools.ASK_ID + TeamTools.DELEGATE_ID)
                .mapNotNull { team.world.toolRegistry.byId(it)?.spec }.sortedBy { it.functionName }
            assertTrue(toolTokens(enabled.map(ToolMenu::definition)) <= ToolMenu.THRESHOLD_TOKENS, agent.name)
            assertEquals(enabled.map { it.functionName }, namesIn(first), "${agent.name}: every tool, sorted, no menu")
            assertTrue(ToolMenu.FUNCTION_NAME !in namesIn(first))
            assertEquals(AgentPromptComposer().compose(agent, enabled), systemOf(first), "${agent.name}: the prompt is unchanged")
        }
    }

    private companion object {
        /** Rule 28: estimated tokens of tool definitions in a starter agent's first request. */
        const val TOOL_BUDGET = 3_000

        /** Rule 28: estimated tokens of a starter agent's system prompt. */
        const val SYSTEM_BUDGET = 1_500
    }
}
