package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.testkit.FakeScreenOperator
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review 5, INT-6: the end of one run of an agent must not end the "operating" indicator that
 * another run of the same agent needs, and a run that never touched the screen ends nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunnerScreenSessionTest {
    private val world = TestWorld()
    private val screen = FakeScreenOperator()

    /** Stands in for a screen tool: what matters here is its "screen." id. */
    private val screenTool = object : Tool {
        override val spec = ToolSpec(
            ToolId("screen.read"), "screen_read", "Read the screen", "Reads the screen.",
            buildJsonObject { put("type", "object") }, ToolCategory.ACCESSIBILITY, Capabilities.BASIC_INFO, RiskLevel.SAFE,
        )

        override suspend fun execute(arguments: JsonObject, context: ToolContext) = ToolOutcome.success("1. Button \"New note\"")
    }

    private val runner = AgentRunner(
        world.gateway, world.toolRegistry, world.executor, world.agents, world.settings, world.monitor, world.clock,
        timeSource = world.time, screen = screen,
    )

    @Test
    fun `a chat run ending while a task run of the same agent operates an app leaves the indicator on`() = runTest {
        world.toolRegistry.register(screenTool)
        val taskMayFinish = CompletableDeferred<Unit>()
        val client = ScriptedLlmClient { request, _ ->
            val asked = request.messages.first { it.role == com.aicontrolcenter.core.llm.ChatRole.USER }.text
            val answered = request.messages.any { it.toolResult != null }
            when {
                asked == "Tidy my notes" && !answered -> ScriptStep.Calls(listOf(ScriptedLlmClient.call("c1", "screen_read")))
                asked == "Tidy my notes" -> {
                    taskMayFinish.await()
                    ScriptStep.Reply("Tidied.")
                }
                else -> ScriptStep.Reply("Noon.")
            }
        }
        val model = world.serve(client)
        val agent = world.agent(model, setOf(ToolId("screen.read")), name = "Operator")
        val task = async { runner.run(AgentRunRequest(agent, model.id, listOf(ChatMessage.user("Tidy my notes")))) }
        runCurrent()
        // Meanwhile the owner asks the same agent something in a chat; that run never uses the screen.
        val chat = runner.run(AgentRunRequest(agent, model.id, listOf(ChatMessage.user("What time is it?"))))
        assertEquals(RunEnd.COMPLETED, chat.end)
        assertTrue(screen.endedSessions.isEmpty(), "the chat run ended the task run's indicator: ${screen.endedSessions}")
        taskMayFinish.complete(Unit)
        assertEquals(RunEnd.COMPLETED, task.await().end)
        assertEquals(listOf("Operator"), screen.endedSessions, "the run that operated the app ends its indicator")
    }

    @Test
    fun `a run that used no screen tool ends no indicator`() = runTest {
        val model = world.serve(ScriptedLlmClient.replies("Noon."))
        val agent = world.agent(model, emptySet(), name = "Operator")
        runner.run(AgentRunRequest(agent, model.id, listOf(ChatMessage.user("What time is it?"))))
        assertTrue(screen.endedSessions.isEmpty(), screen.endedSessions.toString())
    }
}
