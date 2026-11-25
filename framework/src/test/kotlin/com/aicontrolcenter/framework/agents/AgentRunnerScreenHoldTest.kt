package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentActivity
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.BrokerFixture
import com.aicontrolcenter.framework.operator.OperatorFixture
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.testkit.FakeControl
import com.aicontrolcenter.testkit.FakeScreen
import com.aicontrolcenter.testkit.FakeScreenOperator
import com.aicontrolcenter.testkit.ScreenCall
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D4 (Phase 6, WP15's side of rule 12): while a screen action waits for the owner, the runner
 * holds the agent's session in that app, through the owner's answer and the action's wait for
 * its app to come back, and releases it when the call ends. And (wave-1 security fix F12) a
 * routine's objective without the owner's tick never reaches the screen, menu or no menu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunnerScreenHoldTest {
    private val pkg = "com.example.messages"
    private val send = FakeControl(text = "Send", nodeKey = "send")
    private val messages = FakeScreen(pkg, "Sam", listOf(FakeControl(role = "TextView", text = "Chat with Sam", clickable = false, nodeKey = "who"), send))
    private val ownApp = FakeScreen(OperatorFixture.OWN, "Approval needed", listOf(FakeControl(text = "Approve")))

    /** The broker over in-memory stores and the real operator tools over a fake screen, run by a runner in virtual time. */
    private class Phone(val broker: BrokerFixture, val tools: OperatorFixture, val runner: AgentRunner, val monitor: DefaultAgentActivityMonitor) {
        val screen: FakeScreenOperator get() = tools.screen
        val agent: AgentProfile get() = broker.agent
    }

    private suspend fun TestScope.phone(client: ScriptedLlmClient, agentTools: Set<ToolId>, extraTools: List<Tool> = emptyList()): Phone {
        val b = BrokerFixture(this).setUp()
        b.agent = b.agent.copy(toolIds = agentTools)
        b.agents.upsert(b.agent)
        b.apps.upsert(OperatorApp(pkg, "Messages", 20, "cd".repeat(32), ScreenActionPolicy.ASK_EVERY_ACTION))
        val o = OperatorFixture(messages)
        o.authorise(pkg, "Messages", ScreenActionPolicy.ASK_EVERY_ACTION)
        val registry = DefaultToolRegistry().also { r -> (o.tools.values + extraTools).forEach(r::register) }
        val executor = ToolExecutor(registry, b.broker, b.activity, b.clock, timeSource = testTimeSource)
        val monitor = DefaultAgentActivityMonitor(b.clock)
        val runner = AgentRunner(
            ClientGateway(client), registry, executor, b.agents, b.settings, monitor, b.clock, timeSource = testTimeSource, screen = o.screen,
        )
        return Phone(b, o, runner, monitor)
    }

    private suspend fun grantScreens(b: BrokerFixture) {
        for ((id, capability) in listOf("grt_op" to OperatorCapabilities.SCREEN_OPERATE, "grt_read" to OperatorCapabilities.SCREEN_READ)) {
            b.grants.insert(PermissionGrant(GrantId(id), b.agent.id, capability, CapabilityConstraint.App(pkg), GrantScope.ALWAYS, grantedAt = 1))
        }
    }

    @Test
    fun `the session stays held through a 60 second approval and the app's return, and is released when the call ends`() = runTest {
        val client = ScriptedLlmClient { _, index ->
            if (index == 0) ScriptStep.Calls(listOf(ToolCallRequest("c1", "screen_tap", json("exact_text" to "Send")))) else ScriptStep.Reply("Sent.")
        }
        val p = phone(client, setOf(ToolId("screen.tap")))
        grantScreens(p.broker)
        val run = async { p.runner.run(AgentRunRequest(p.agent, MODEL, listOf(ChatMessage.user("Send it.")))) }
        runCurrent()

        val approval = p.broker.pending()
        assertEquals(OwnerRequestKind.APPROVAL, approval.kind)
        assertEquals(FakeScreenOperator.Hold("Operator", pkg), p.screen.holds[approval.id.value], "held from the moment it waits")

        // The owner takes a minute to answer: the banner stays, and the run shows the wait.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(setOf(approval.id.value), p.screen.holds.keys)
        assertEquals(AgentActivity.WAITING_FOR_APPROVAL, p.monitor.states.value[p.agent.id]?.activity)
        assertTrue(p.screen.releasedHolds.isEmpty())

        // The owner approves inside this app; Messages comes back in front 4 seconds later.
        p.screen.screen = ownApp
        p.broker.owner.approve(approval.id)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(setOf(approval.id.value), p.screen.holds.keys, "still held while the approved tap waits for its app")
        assertTrue(p.screen.performed.none { it is ScreenCall.Tap })
        p.screen.screen = messages

        val result = run.await()
        assertEquals(RunEnd.COMPLETED, result.end, result.notice)
        assertEquals("Send", p.screen.performed.filterIsInstance<ScreenCall.Tap>().single().element.text)
        assertTrue(p.screen.holds.isEmpty(), "released when the call ended")
        assertEquals(listOf(approval.id.value), p.screen.releasedHolds)
    }

    @Test
    fun `a call that is stopped while it waits releases its hold`() = runTest {
        val client = ScriptedLlmClient { _, _ -> ScriptStep.Calls(listOf(ToolCallRequest("c1", "screen_tap", json("exact_text" to "Send")))) }
        val p = phone(client, setOf(ToolId("screen.tap")))
        grantScreens(p.broker)
        val run = async { p.runner.run(AgentRunRequest(p.agent, MODEL, listOf(ChatMessage.user("Send it.")))) }
        runCurrent()
        val approval = p.broker.pending()
        assertTrue(approval.id.value in p.screen.holds)

        run.cancel()
        runCurrent()

        assertTrue(p.screen.holds.isEmpty())
        assertEquals(listOf(approval.id.value), p.screen.releasedHolds)
    }

    @Test
    fun `a request of a tool that is not a screen tool holds nothing`() = runTest {
        // Reads a web page: a permission without an app, raised by a tool that is not a screen tool.
        val fetch = object : Tool {
            override val spec = BrokerFixture.spec("test.fetch", OperatorCapabilities.WEB_READ, RiskLevel.SAFE, sendsDataOffDevice = true, category = ToolCategory.WEB)

            override suspend fun assess(arguments: JsonObject, context: ToolContext) = ActionAssessment(RiskLevel.SAFE, "Read example.com", dataGoesTo = "example.com")

            override suspend fun execute(arguments: JsonObject, context: ToolContext) = ToolOutcome.success("A page.")
        }
        val client = ScriptedLlmClient { _, index ->
            if (index == 0) ScriptStep.Calls(listOf(ToolCallRequest("c1", "test_fetch", json()))) else ScriptStep.Reply("Ok.")
        }
        val p = phone(client, setOf(fetch.spec.id), listOf(fetch))
        val run = async { p.runner.run(AgentRunRequest(p.agent, MODEL, listOf(ChatMessage.user("Read it.")))) }
        runCurrent()
        val permission = p.broker.pending()
        assertEquals(OperatorCapabilities.WEB_READ, permission.capability)
        assertTrue(p.screen.holds.isEmpty())
        p.broker.owner.deny(permission.id)
        assertEquals(RunEnd.COMPLETED, run.await().end)
        assertTrue(p.screen.releasedHolds.isEmpty())
    }

    @Test
    fun `a routine's objective without the owner's tick never reaches the screen, whether the menu opened the family or not`() = runTest {
        // The Operator's whole catalogue: a menu run. The model calls a screen tool before and after opening its family.
        val client = ScriptedLlmClient { _, index ->
            when (index) {
                0 -> ScriptStep.Calls(listOf(ToolCallRequest("c1", "screen_read", json())))
                1 -> ScriptStep.Calls(listOf(ToolCallRequest("c2", ToolMenu.FUNCTION_NAME, family("screen"))))
                2 -> ScriptStep.Calls(listOf(ToolCallRequest("c3", "screen_tap", json("exact_text" to "Send"))))
                else -> ScriptStep.Reply("I could not use the screen.")
            }
        }
        val p = phone(client, AgentTemplates.operatorToolIds)
        grantScreens(p.broker)
        p.broker.tasks.upsert(
            TaskRecord(
                id = TaskId("tsk_root"), rootId = TaskId("tsk_root"), kind = TaskKind.OBJECTIVE, title = "Morning news", instructions = "Read the news.",
                requestedBy = TaskRequester.Owner, status = TaskStatus.RUNNING, createdAt = BrokerFixture.START, updatedAt = BrokerFixture.START,
                options = ObjectiveOptions(routineId = RoutineId("rtn_1"), screenActionsAllowed = false),
            ),
        )
        p.broker.runningTask()

        val result = p.runner.run(
            AgentRunRequest(p.agent, MODEL, listOf(ChatMessage.user("Read the news.")), taskId = TaskId("tsk_work"), rootTaskId = TaskId("tsk_root")),
        )

        assertEquals(RunEnd.COMPLETED, result.end, result.notice)
        assertTrue(ToolMenu.FUNCTION_NAME in namesIn(client.requests.first()), "a menu run")
        assertEquals(ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED, resultOf(result.produced, "c1"))
        assertEquals(ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED, resultOf(result.produced, "c3"))
        assertTrue(p.screen.calls.isEmpty(), "the screen operator saw no call: ${p.screen.calls}")
        assertTrue(p.broker.allRequests().isEmpty(), "nothing was put to the owner")
        assertTrue(p.screen.holds.isEmpty())
    }

    private companion object {
        /** The runner hands every request to the scripted client, whatever the model. */
        val MODEL = ModelId("mdl_x")
    }
}
