package com.aicontrolcenter.app.di.wiring

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.serveModel
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.framework.agents.AgentPromptComposer
import com.aicontrolcenter.framework.agents.AgentRunRequest
import com.aicontrolcenter.framework.agents.AgentTemplates
import com.aicontrolcenter.framework.agents.RunEnd
import com.aicontrolcenter.framework.agents.ToolMenu
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lean requests as the app wires them (Phase 6, WP15, rule 28): with every tool the app itself
 * registers, each starter agent's first request keeps within the budgets, and the Operator is
 * offered the menu. This test reads the app's real tool registry, so a tool added later (the
 * question to the owner, a new operator tool) is counted as soon as it is registered.
 */
class AgentsWiringTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test
    fun `with the app's own tools every starter agent's first request keeps within rule 28, and the Operator gets the menu`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        val client = ScriptedLlmClient.replies("Ready.")
        val model = app.serveModel(client)
        val runner = app.graph.agentsAssembly.agentRunner
        val starters = app.snapshot().agents.map { it.profile }.filter { it.templateId in AgentTemplates.starterTeam }
        assertEquals(AgentTemplates.starterTeam.size, starters.size)

        for (agent in starters) {
            val run = runner.run(AgentRunRequest(agent, model.id, listOf(ChatMessage.user("Hello."))))
            assertEquals(RunEnd.COMPLETED, run.end, run.notice)
            val first = client.requests.last()
            val tools = ToolMenu.estimatedTokens(first.tools)
            val system = ToolMenu.estimatedTokens(first.messages.first { it.role == ChatRole.SYSTEM }.text)
            println("RULE 28 (app) ${agent.name}: ${first.tools.size} tools, $tools tokens of tool definitions; system prompt $system tokens")
            assertTrue(tools <= 3_000, "${agent.name}: $tools tokens of tool definitions: ${first.tools.map { it.name }}")
            assertTrue(system <= 1_500, "${agent.name}: a system prompt of $system tokens")

            val enabled = agent.toolIds.mapNotNull { app.graph.toolRegistry.byId(it)?.spec }.sortedBy { it.functionName }
            val whole = ToolMenu.estimatedTokens(enabled.map(ToolMenu::definition))
            if (agent.templateId == AgentTemplates.OPERATOR) {
                assertTrue(whole > ToolMenu.THRESHOLD_TOKENS, "the Operator's tools as the app registers them: $whole")
                assertEquals(listOf("clock_now", "memory_search", ToolMenu.FUNCTION_NAME), first.tools.map { it.name })
            } else {
                assertEquals(enabled.map { it.functionName }, first.tools.map { it.name }, "${agent.name}: every tool at once, as before")
                assertEquals(AgentPromptComposer().compose(agent, enabled), first.messages.first().text)
            }
        }
    }
}
