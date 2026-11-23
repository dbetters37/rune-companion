package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.LambdaTool
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.tools.ClockTool
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a request can change about one run: lower limits, framework-given tools, the delegation chain, JSON output, the label shown. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunOverridesTest {
    private val world = TestWorld()

    @Test
    fun `run limits lower the framework's limits for one run and can never raise them`() = runTest {
        world.settings.update { it.copy(maxModelCallsPerTask = 3) }
        val client = ScriptedLlmClient { _, index -> ScriptStep.Calls(listOf(ScriptedLlmClient.call("c$index", "clock_now"))) }
        val model = world.serve(client)
        val agent = world.agent(model, setOf(ClockTool.ID))
        val base = AgentRunRequest(agent, model.id, listOf(ChatMessage.user("Time?")))

        assertEquals(2, world.runner.run(base.copy(limits = RunLimits(maxModelCalls = 2))).modelCalls)
        assertEquals(3, world.runner.run(base.copy(limits = RunLimits(maxModelCalls = 100))).modelCalls)
        val noTools = world.runner.run(base.copy(limits = RunLimits(maxToolCalls = 0)))
        assertEquals(RunEnd.TOOL_CALL_LIMIT, noTools.end)
        assertEquals(0, noTools.toolCalls)
    }

    @Test
    fun `a shorter time limit ends the run when it passes`() = runTest {
        val client = ScriptedLlmClient { _, _ ->
            delay(10 * 60_000)
            ScriptStep.Reply("late")
        }
        val model = world.serve(client)
        val result = world.runner.run(AgentRunRequest(world.agent(model), model.id, listOf(ChatMessage.user("Hi")), limits = RunLimits(timeoutMs = 30_000)))
        assertEquals(RunEnd.TIMED_OUT, result.end)
        assertEquals("Stopped: the turn reached its time limit of 30 seconds.", result.notice)
        assertEquals(30_000, currentTime)
    }

    @Test
    fun `framework-given tools are offered and authorised, calls carry the delegation chain, and JSON output is asked for`() = runTest {
        val contexts = ArrayList<ToolContext>()
        val teamTool = world.register(
            object : com.aicontrolcenter.core.tools.Tool by LambdaTool("team_probe", body = { ToolOutcome.success("ok") }) {
                override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject, context: ToolContext): ToolOutcome {
                    contexts += context
                    return ToolOutcome.success("ok")
                }
            },
        )
        val client = ScriptedLlmClient { _, index ->
            if (index == 0) ScriptStep.Calls(listOf(ScriptedLlmClient.call("c1", teamTool.spec.functionName))) else ScriptStep.Reply("{}")
        }
        val model = world.serve(client, ModelCapabilities(toolCalling = true, jsonOutput = true))
        val agent = world.agent(model, tools = emptySet())
        val chain = listOf(AgentId("agt_lead"))
        val labels = ArrayList<String?>()
        val result = world.runner.run(
            AgentRunRequest(
                agent, model.id, listOf(ChatMessage.user("Probe")), delegationChain = chain, extraToolIds = setOf(teamTool.spec.id),
                jsonOutput = true, activityLabel = "Task: Probe",
            ),
            object : AgentRunListener {
                override suspend fun onModelCall(number: Int) {
                    labels += world.monitor.states.value[agent.id]?.detail
                }
            },
        )
        assertEquals(RunEnd.COMPLETED, result.end)
        assertEquals(listOf(teamTool.spec.functionName), client.requests.first().tools.map { it.name })
        assertTrue(client.requests.all { it.jsonOutput })
        assertEquals(chain, contexts.single().delegationChain)
        assertTrue(labels.all { it?.startsWith("Task: Probe") == true }, labels.toString())
        assertTrue(world.agents.get(agent.id)!!.toolIds.isEmpty(), "the agent's own tool list is unchanged")
    }
}
