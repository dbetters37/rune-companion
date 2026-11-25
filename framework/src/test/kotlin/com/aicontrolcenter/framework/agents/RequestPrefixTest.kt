package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rule 28's second half (Phase 6, WP15): the request prefix (the system prompt and the offered
 * tools) is byte-identical from call to call of one run until a family opens, and opening one
 * only appends, so a provider's prompt cache keeps matching. Checked on what the provider
 * receives, through the real gateway, over a 5-call run (in real time: the workspace tool works
 * on Dispatchers.IO).
 */
class RequestPrefixTest {
    private val world = TestWorld()

    @Test
    fun `the prefix stays byte-identical over a 5-call run, and opening a family only appends`() = runBlocking<Unit> {
        registerOperatorTools(world)
        world.settings.update { it.copy(operatorEnabled = true) }
        val agent = world.agents.get(world.agentManager.create(AgentTemplates.byId(AgentTemplates.OPERATOR)!!.draft).id)!!
        val client = ScriptedLlmClient { _, index ->
            when (index) {
                0 -> ScriptStep.Calls(listOf(ToolCallRequest("c1", "clock_now", json())))
                1 -> ScriptStep.Calls(listOf(ToolCallRequest("c2", "memory_search", json("query" to "plans"))))
                2 -> ScriptStep.Calls(listOf(ToolCallRequest("c3", ToolMenu.FUNCTION_NAME, family("workspace"))))
                3 -> ScriptStep.Calls(listOf(ToolCallRequest("c4", "workspace_list", json())))
                else -> ScriptStep.Reply("Nothing planned, and the workspace is empty.")
            }
        }
        val model = world.serve(client)

        val result = world.runner.run(AgentRunRequest(agent, model.id, listOf(ChatMessage.user("What did I plan?"))))

        assertEquals(RunEnd.COMPLETED, result.end, result.notice)
        val requests = client.requests
        assertEquals(5, requests.size)
        // The system prompt: one text for the whole run.
        assertEquals(1, requests.map { systemOf(it) }.distinct().size)
        assertEquals(ChatRole.SYSTEM, requests.first().messages.first().role)
        // The tools: identical until the family opens, then the earlier list followed by the family.
        val tools = requests.map { request -> request.tools.map { it.rendered() } }
        assertEquals(tools[0], tools[1])
        assertEquals(tools[1], tools[2])
        assertEquals(tools[2], tools[3].take(tools[2].size), "opening appends: the earlier definitions are untouched, in place")
        assertTrue(tools[3].size > tools[2].size)
        assertTrue(requests[3].tools.drop(tools[2].size).all { it.name.startsWith("workspace_") })
        assertEquals(tools[3], tools[4])
        // The conversation only grows: each request starts with the one before it.
        for (i in 1 until requests.size) {
            assertEquals(requests[i - 1].messages, requests[i].messages.take(requests[i - 1].messages.size), "call ${i + 1}")
        }
    }
}
