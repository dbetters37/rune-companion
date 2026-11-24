package com.aicontrolcenter.app.ui.approvals

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.SampleObjectives
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.populateWithObjectives
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.samplePermission
import com.aicontrolcenter.app.ui.chat.ChatViewModel
import com.aicontrolcenter.app.ui.chat.TranscriptItem
import com.aicontrolcenter.app.ui.shell.ShellViewModel
import com.aicontrolcenter.app.ui.tasks.TaskDetailViewModel
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The approval screens' view models over a real AppGraph: what is listed, and every way an answer can go. */
class ApprovalViewModelsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.list(app: TestApp) =
        ApprovalsViewModel(app.graph.ownerRequests, app.graph.registryDirectory, app.graph.capabilities::info, app.clock, ZoneOffset.UTC, flowOf(Unit))
            .also { it.uiState.activeIn(backgroundScope) }

    private fun TestScope.approval(app: TestApp, id: String) =
        ApprovalViewModel(RequestId(id), app.graph.ownerRequests, app.graph.registryDirectory, app.graph.capabilities::info, app.clock, ZoneOffset.UTC, flowOf(Unit))
            .also { it.uiState.activeIn(backgroundScope) }

    private suspend fun TestApp.started(): TestApp = apply { graph.start().join() }

    private suspend fun TestApp.operatorId() = agentNamed("Operator").profile.id

    @Test
    fun `the pending list shows open requests oldest first, and drops each once answered or expired`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope).started()
        val now = app.clock.current
        app.raise(samplePermission(app.operatorId(), agentName = "Operator", id = "req_2", createdAt = now + 1_000))
        app.raise(sampleApproval(app.operatorId(), id = "req_1", createdAt = now))
        app.raise(sampleApproval(app.operatorId(), id = "req_old", createdAt = now - 60 * 60_000L, minutes = 5))
        val vm = list(app)

        assertEquals(listOf("req_1", "req_2"), vm.uiState.value.requests.map { it.id.value }, "oldest first, the expired one left out")
        assertTrue(vm.uiState.value.requests.all { it.isOpen })

        app.graph.ownerRequests.deny(RequestId("req_1"))
        assertEquals(listOf("req_2"), vm.uiState.value.requests.map { it.id.value })
    }

    private fun screenRequest(agent: com.aicontrolcenter.core.AgentId, kind: OwnerRequestKind, id: String, createdAt: Long) = OwnerRequest(
        id = RequestId(id), kind = kind, agentId = agent, toolId = ToolId("screen.tap"), capability = OperatorCapabilities.SCREEN_OPERATE,
        constraint = CapabilityConstraint.App("org.example.notes"),
        risk = if (kind == OwnerRequestKind.APPROVAL) RiskLevel.CONSEQUENTIAL else RiskLevel.MODIFYING,
        summary = "Tap Button \"Send\" in \"Notes\" (org.example.notes)", targetLabel = "Notes",
        details = listOf("On screen now (1 text):", "  TextView: \"Sent messages cannot be undone\""),
        createdAt = createdAt, expiresAt = createdAt + 30 * 60_000L,
    )

    /** Review 5, A1: the owner answers here, so after allowing a screen action this app steps aside for the app it operates. */
    @Test
    fun `approving a screen action steps aside so the operated app comes back`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope).started()
        app.raise(screenRequest(app.operatorId(), OwnerRequestKind.APPROVAL, "req_tap", app.clock.current))
        val vm = approval(app, "req_tap")
        val shown = assertNotNull(vm.uiState.value.request)
        assertEquals("May leave this phone through Notes", shown.dataGoesTo)
        assertTrue(shown.returnsToApp!!.contains("this app steps aside so Notes comes back"), shown.returnsToApp)
        // The other app's own words stay in the quoted preview; they are not lifted into this app's warning.
        assertFalse("Sent messages" in shown.hardToUndo.orEmpty(), shown.hardToUndo)

        vm.choose(RequestChoice.APPROVE)
        testScheduler.advanceUntilIdle()
        assertEquals(AfterAnswer.ReturnToApp("Notes"), vm.uiState.value.afterAnswer)
        vm.afterAnswerDone()
        assertNull(vm.uiState.value.afterAnswer)
    }

    @Test
    fun `allowing a screen permission opens the same call's approval when it follows, and denying stays here`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope).started()
        val operator = app.operatorId()
        app.raise(screenRequest(operator, OwnerRequestKind.PERMISSION, "req_ask", app.clock.current))
        val vm = approval(app, "req_ask")
        vm.choose(RequestChoice.ALLOW_ONCE)
        // The broker raises the call's approval right after the permission.
        app.raise(screenRequest(operator, OwnerRequestKind.APPROVAL, "req_then", app.clock.current))
        testScheduler.advanceUntilIdle()
        assertEquals(AfterAnswer.OpenRequest(RequestId("req_then")), vm.uiState.value.afterAnswer)

        // A permission with no approval after it: back to the app.
        app.raise(screenRequest(operator, OwnerRequestKind.PERMISSION, "req_read", app.clock.current))
        val read = approval(app, "req_read")
        read.choose(RequestChoice.ALLOW_ONCE)
        testScheduler.advanceUntilIdle()
        assertEquals(AfterAnswer.ReturnToApp("Notes"), read.uiState.value.afterAnswer)

        // Denied: nothing runs there, so the owner stays here.
        val denied = approval(app, "req_then")
        denied.choose(RequestChoice.DENY)
        testScheduler.advanceUntilIdle()
        assertNull(denied.uiState.value.afterAnswer)
    }

    @Test
    fun `allowing a permission once stores a grant for that one call and closes the request`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope).started()
        app.raise(samplePermission(app.operatorId(), agentName = "Operator", taskId = null, rootId = null))
        val vm = approval(app, "req_permission")
        val shown = assertNotNull(vm.uiState.value.request)
        assertEquals(listOf(RequestChoice.ALLOW_ONCE, RequestChoice.ALLOW_ALWAYS, RequestChoice.DENY), shown.choices)
        assertEquals("Read web pages", shown.permission)

        vm.choose(RequestChoice.ALLOW_ONCE)

        // Said honestly: the call is checked again before it runs (INT-2).
        assertEquals("Allowed just this once. The action is checked again before it runs.", vm.uiState.value.message?.text)
        val closed = assertNotNull(vm.uiState.value.request)
        assertEquals("Allowed just once", closed.status.text)
        assertEquals("Permission request", closed.title, "a closed request no longer says it needs the owner")
        assertTrue(closed.choices.isEmpty(), "an answered request offers nothing more")
        val stored = assertNotNull(app.ownerRequestStore.get(RequestId("req_permission")))
        assertEquals(OwnerRequestStatus.APPROVED, stored.status)
        assertEquals(GrantScope.ONCE, stored.decisionScope)
        assertEquals(GrantScope.ONCE, app.permissions.findByRequest(RequestId("req_permission"))?.scope)
        assertTrue(app.activityLog.recent().any { it.type == ActivityType.PERMISSION })
    }

    @Test
    fun `always is stored until revoked, and the note goes with the answer`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope).started()
        val operator = app.operatorId()
        app.raise(samplePermission(operator, agentName = "Operator"))
        val vm = approval(app, "req_permission")

        vm.setNote("  Only for reviews.  ")
        vm.choose(RequestChoice.ALLOW_ALWAYS)

        val stored = assertNotNull(app.ownerRequestStore.get(RequestId("req_permission")))
        assertEquals(GrantScope.ALWAYS, stored.decisionScope)
        assertEquals("Only for reviews.", stored.decisionNote)
        assertEquals("", vm.uiState.value.note, "the note is cleared once sent")
        assertEquals(listOf(GrantScope.ALWAYS), app.graph.ownerGrants.observeForAgent(operator).first().map { it.scope })
    }

    @Test
    fun `an approval is approved or denied, never granted for longer`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope).started()
        app.raise(sampleApproval(app.operatorId()))
        val vm = approval(app, "req_approval")
        assertEquals(listOf(RequestChoice.DENY, RequestChoice.APPROVE), vm.uiState.value.request?.choices, "the safe answer leads")

        // Not offered on screen; even if it were sent, the store refuses it and nothing changes.
        vm.choose(RequestChoice.ALLOW_ALWAYS)
        assertTrue("cannot be granted for longer" in vm.uiState.value.message!!.text, vm.uiState.value.message!!.text)
        assertEquals(OwnerRequestStatus.PENDING, app.ownerRequestStore.get(RequestId("req_approval"))?.status)
        assertTrue(app.permissions.observeAll().first().isEmpty())

        vm.setNote("Keep the invoices.")
        vm.choose(RequestChoice.DENY)
        assertEquals("Denied. The agent is told, with your note if you wrote one.", vm.uiState.value.message?.text)
        assertEquals("Denied", vm.uiState.value.request?.status?.text)
        assertEquals("Keep the invoices.", app.ownerRequestStore.get(RequestId("req_approval"))?.decisionNote)
        assertTrue(app.activityLog.recent().any { it.type == ActivityType.APPROVAL })
    }

    @Test
    fun `a second answer, a late answer and a missing request change nothing`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope).started()
        app.raise(sampleApproval(app.operatorId()))
        val vm = approval(app, "req_approval")
        app.graph.ownerRequests.approve(RequestId("req_approval"))

        vm.choose(RequestChoice.DENY)
        assertTrue(vm.uiState.value.message!!.text.startsWith("This request was already closed (approved)"), vm.uiState.value.message!!.text)
        assertEquals(OwnerRequestStatus.APPROVED, app.ownerRequestStore.get(RequestId("req_approval"))?.status)

        app.raise(sampleApproval(app.operatorId(), id = "req_late", createdAt = app.clock.current, minutes = 5))
        val late = approval(app, "req_late")
        app.clock.current += 10 * 60_000L
        late.choose(RequestChoice.APPROVE)
        assertEquals("Too late: the request expired, so the agent was refused. Nothing was allowed.", late.uiState.value.message?.text)
        assertEquals(OwnerRequestStatus.EXPIRED, app.ownerRequestStore.get(RequestId("req_late"))?.status)
        assertEquals("Expired", late.uiState.value.request?.status?.text)

        val missing = approval(app, "req_missing")
        assertTrue(missing.uiState.value.notFound)
        assertNull(missing.uiState.value.request)
        missing.choose(RequestChoice.APPROVE)
        assertEquals("This request no longer exists. Nothing changed.", missing.uiState.value.message?.text)
    }

    @Test
    fun `the shell counts what waits, the chat shows its requests inline and the task its own`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope).started()
        val operator = app.operatorId()
        val conversation = app.graph.agentChat.startConversation(operator, "Invoices")
        app.raise(sampleApproval(operator, conversationId = conversation.id))
        val shell = ShellViewModel(app.graph.registryDirectory, app.graph.ownerRequests.pending).also { it.uiState.activeIn(backgroundScope) }
        assertEquals(1, shell.uiState.value.pendingRequests)

        val chat = ChatViewModel(
            conversation.id, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry, app.graph.providerManager,
            requests = app.graph.ownerRequests.observeForConversation(conversation.id), capabilityInfo = app.graph.capabilities::info, clock = app.clock,
        ).also { it.uiState.activeIn(backgroundScope) }
        val inline = chat.uiState.value.items.filterIsInstance<TranscriptItem.Request>().single()
        assertEquals("req_approval", inline.request.id.value)
        assertTrue(inline.request.isOpen)

        app.graph.ownerRequests.approve(RequestId("req_approval"))
        assertEquals(0, shell.uiState.value.pendingRequests)
        assertEquals("Approved", chat.uiState.value.items.filterIsInstance<TranscriptItem.Request>().single().request.status.text, "it stays in the chat, answered")

        // A task waiting for the owner shows its request with a link, and says its time limit is paused.
        app.populateWithObjectives()
        val task = assertNotNull(app.tasks.update(SampleObjectives.COMPARE) { it.copy(status = TaskStatus.WAITING_FOR_OWNER) })
        app.raise(samplePermission(operator, id = "req_task", taskId = task.id, rootId = task.rootId, createdAt = app.clock.current))
        val detail = TaskDetailViewModel(
            task.id, app.graph.tasks, app.graph.messages, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry,
            app.graph.orchestrator, app.graph.activityMonitor, app.clock, ZoneOffset.UTC,
            requests = app.graph.ownerRequests.pending, capabilityInfo = app.graph.capabilities::info,
        ).also { it.uiState.activeIn(backgroundScope) }
        val shown = assertNotNull(detail.uiState.value.task)
        assertEquals("Waiting for you", shown.status.text)
        assertTrue("paused while it waits for you" in shown.timing, shown.timing)
        assertEquals(listOf("req_task"), detail.uiState.value.waitingFor.map { it.id.value })

        // INT-10: a task waiting for a teammate whose answer waits for the owner (the request names this task) says the same.
        app.tasks.update(task.id) { it.copy(status = TaskStatus.WAITING_FOR_AGENT) }
        val asking = assertNotNull(detail.uiState.value.task)
        assertTrue("paused while it waits for you" in asking.timing, asking.timing)
        assertTrue("times out at" !in asking.timing, asking.timing)
        app.graph.ownerRequests.deny(RequestId("req_task"))
        assertTrue("times out at" in assertNotNull(detail.uiState.value.task).timing, "with nothing open, the deadline shows again")
    }
}
