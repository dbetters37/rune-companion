package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.samplePermission
import com.aicontrolcenter.app.ui.approvals.ApprovalViewModel
import com.aicontrolcenter.app.ui.approvals.RequestChoice
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.operator.OperatorTaskHooks
import com.aicontrolcenter.framework.tools.ToolCallEnd
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The phone operator as the composition root wires it: requests withdrawn at start-up, the
 * expiry sweep, the broker in the executor with the master switch, and egress logging.
 */
class AppGraphOperatorTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private suspend fun TestApp.call(function: String, arguments: JsonObject) =
        graph.toolExecutor.execute(ToolCallRequest("call_$function", function, arguments), ToolContext(agentNamed("Operator").profile))

    private fun TestScope.started(settings: FrameworkSettings = FrameworkSettings(operatorEnabled = true)): TestApp = TestApp(backgroundScope, settings)

    @Test
    fun `requests left open when the app stopped are withdrawn at start-up, before anything runs`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.raise(sampleApproval(AgentId("agt_gone"), createdAt = app.clock.current, minutes = 240), waiting = false)
        // Raised by work the owner restarted before start-up finished: a live call waits for it (INT-4).
        app.raise(samplePermission(AgentId("agt_gone"), createdAt = app.clock.current, minutes = 240))

        app.graph.start().join()

        val request = assertNotNull(app.ownerRequestStore.get(RequestId("req_approval")))
        assertEquals(OwnerRequestStatus.CANCELLED, request.status)
        assertEquals(OperatorTaskHooks.STOPPED_NOTE, request.decisionNote)
        assertEquals(OwnerRequestStatus.PENDING, app.ownerRequestStore.get(RequestId("req_permission"))?.status, "a live call still waits for it")
        // INT-8: the withdrawal names its request.
        val logged = app.activityLog.recent().single { it.title.startsWith("Request withdrawn at start-up") }
        assertEquals("req_approval", logged.data?.get("requestId")?.jsonPrimitive?.content)
    }

    @Test
    fun `a request left by an earlier run cannot be approved before start-up recovery runs`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.raise(sampleApproval(AgentId("agt_operator"), createdAt = app.clock.current, minutes = 240), waiting = false)

        // Opened from its notification before start-up got to the recovery: it is closed, never offered.
        val vm = ApprovalViewModel(
            RequestId("req_approval"), app.graph.ownerRequests, app.graph.registryDirectory, app.graph.capabilities::info, app.clock,
            ZoneOffset.UTC, flowOf(Unit),
        )
        val state = vm.uiState.activeIn(backgroundScope)
        runCurrent()
        val shown = assertNotNull(state.value.request)
        assertTrue(shown.choices.isEmpty(), "no answer is offered for a request nothing waits for")
        assertEquals("No longer waiting", shown.status.text)
        assertEquals(OwnerRequestStatus.CANCELLED, app.ownerRequestStore.get(RequestId("req_approval"))?.status)

        // Even an answer sent anyway is not taken.
        vm.choose(RequestChoice.APPROVE)
        runCurrent()
        assertTrue(state.value.message!!.text.startsWith("Your answer was not used."), state.value.message!!.text)
        app.graph.start().join()
        assertEquals(OwnerRequestStatus.CANCELLED, app.ownerRequestStore.get(RequestId("req_approval"))?.status)
        assertTrue(app.activityLog.recent().none { it.title.startsWith("Owner approved") })
    }

    @Test
    fun `pressing Stop withdraws the questions to see or use a screen`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.graph.start().join()
        val now = app.clock.current
        val screenAsk = samplePermission(AgentId("agt_operator"), id = "req_screen", createdAt = now, minutes = 240).copy(
            capability = OperatorCapabilities.SCREEN_OPERATE, constraint = CapabilityConstraint.App("org.example.notes"), targetLabel = "Notes",
        )
        app.raise(screenAsk)
        app.raise(samplePermission(AgentId("agt_operator"), createdAt = now, minutes = 240))
        runCurrent()

        app.graph.screenControl.stop()
        runCurrent()

        val closed = assertNotNull(app.ownerRequestStore.get(RequestId("req_screen")))
        assertEquals(OwnerRequestStatus.CANCELLED, closed.status)
        assertEquals(OperatorTaskHooks.SCREEN_STOPPED_NOTE, closed.decisionNote)
        assertEquals(OwnerRequestStatus.PENDING, app.ownerRequestStore.get(RequestId("req_permission"))?.status, "only screen questions")
    }

    @Test
    fun `an agent's own ask that nobody answers is closed as expired by the sweep`() = runTest(mainRule.dispatcher) {
        val app = started(FrameworkSettings(operatorEnabled = true, approvalTimeoutMinutes = 5))
        app.graph.start().join()

        val asked = app.call("permissions_request", buildJsonObject { put("capability", "web.read"); put("reason", "To read reviews of note apps.") })
        assertEquals(ToolCallEnd.OK, asked.end, asked.outcome.content)
        val ask = app.ownerRequestStore.observePending().first().single()
        assertEquals(OwnerRequestKind.PERMISSION, ask.kind)
        assertEquals(null, ask.toolId, "an ask names no tool call")
        assertEquals("To read reviews of note apps.", ask.reason)

        // Nothing waits on an ask, so its expiry is the sweep's job.
        app.clock.current = ask.expiresAt + 1
        advanceTimeBy(AppStartup.REQUEST_SWEEP_INTERVAL_MS + 1)
        runCurrent()

        assertEquals(OwnerRequestStatus.EXPIRED, app.ownerRequestStore.get(ask.id)?.status)
        val swept = app.activityLog.recent().single { it.type == ActivityType.PERMISSION && it.title == "No answer in time: permission asked by Operator" }
        assertEquals(ask.id.value, swept.data?.get("requestId")?.jsonPrimitive?.content, "the sweep's entry names its request (INT-8)")
    }

    @Test
    fun `with the master switch off, a phone tool is refused before the owner is asked`() = runTest(mainRule.dispatcher) {
        val app = started(FrameworkSettings(operatorEnabled = false))
        app.graph.start().join()

        val refused = app.call("workspace_write", buildJsonObject { put("path", "a.md"); put("content", "x") })

        assertEquals(ToolCallEnd.DENIED, refused.end)
        assertTrue(app.ownerRequestStore.observeRecent(10).first().isEmpty(), "nobody was asked")
    }

    @Test
    fun `every request a page read sends off the phone is logged as egress`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.graph.start().join()
        val operator = app.agentNamed("Operator").profile.id
        assertIs<OwnerResult.Done<*>>(app.graph.ownerGrants.grant(operator, OperatorCapabilities.WEB_READ))
        app.web.page("https://example.org/news", "<html><body><p>Hello</p></body></html>")

        val read = app.call("web_read_page", buildJsonObject { put("url", "https://example.org/news") })
        runCurrent()

        assertEquals(ToolCallEnd.OK, read.end, read.outcome.content)
        val egress = app.activityLog.recent().filter { it.type == ActivityType.DATA_EGRESS }
        val sent = egress.single { it.title == "Web page requested from example.org" }
        assertEquals("example.org", sent.egress?.destinationHost)
        assertEquals(Locality.CLOUD, sent.egress?.locality)
        assertEquals(operator, sent.agentId, "the entry names the agent that read the page (INT-9)")

        // Inside a task, the entry names the task and the objective too.
        app.web.page("https://example.org/more", "<html><body><p>More</p></body></html>")
        val inTask = app.graph.toolExecutor.execute(
            ToolCallRequest("call_task", "web_read_page", buildJsonObject { put("url", "https://example.org/more") }),
            ToolContext(app.agentNamed("Operator").profile, TaskId("tsk_compare"), TaskId("tsk_root")),
        )
        runCurrent()
        assertEquals(ToolCallEnd.OK, inTask.end, inTask.outcome.content)
        val second = app.activityLog.recent().filter { it.type == ActivityType.DATA_EGRESS }.first { it.taskId != null }
        assertEquals(TaskId("tsk_compare"), second.taskId)
        assertEquals(TaskId("tsk_root"), second.rootTaskId)
    }
}
