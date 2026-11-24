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
        ApprovalViewModel(RequestId(id), app.graph.ownerRequests, app.graph.ownerGrants, app.graph.registryDirectory, app.graph.capabilities::info, app.clock, ZoneOffset.UTC, flowOf(Unit))
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

    @Test
    fun `B4 - a running task whose agent only asked for a permission shows when it times out, not a paused limit`() = runTest(mainRule.dispatcher) {
        // The verifier's N2: permissions.request never waits and pauses nothing, so the task runs on against its limit.
        val app = TestApp(backgroundScope, com.aicontrolcenter.core.settings.FrameworkSettings(operatorEnabled = true, approvalTimeoutMinutes = 100_000)).started()
        app.populateWithObjectives()
        val task = assertNotNull(
            app.tasks.update(SampleObjectives.COMPARE) {
                it.copy(status = TaskStatus.RUNNING, startedAt = app.clock.current, deadlineAt = app.clock.current + 15 * 60_000L, finishedAt = null)
            },
        )
        val agent = assertNotNull(app.agents.get(task.assignee!!))
        val asked = app.graph.toolExecutor.execute(
            com.aicontrolcenter.core.llm.ToolCallRequest(
                "c1", "permissions_request",
                kotlinx.serialization.json.buildJsonObject {
                    put("capability", kotlinx.serialization.json.JsonPrimitive("web.read"))
                    put("reason", kotlinx.serialization.json.JsonPrimitive("To read reviews."))
                },
            ),
            com.aicontrolcenter.core.tools.ToolContext(agent.copy(toolIds = agent.toolIds + ToolId("permissions.request")), task.id, task.rootId),
        )
        assertTrue(asked.outcome.ok, asked.outcome.content)
        val ask = app.graph.ownerRequests.pending.first().single()
        assertNull(ask.toolId, "the agent's own ask: no call waits for it")
        assertEquals(task.id, ask.taskId)
        val detail = TaskDetailViewModel(
            task.id, app.graph.tasks, app.graph.messages, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry,
            app.graph.orchestrator, app.graph.activityMonitor, app.clock, ZoneOffset.UTC,
            requests = app.graph.ownerRequests.pending, capabilityInfo = app.graph.capabilities::info,
        ).also { it.uiState.activeIn(backgroundScope) }

        val running = assertNotNull(detail.uiState.value.task)
        assertEquals(TaskStatus.RUNNING, app.tasks.get(task.id)?.status)
        assertTrue("times out at" in running.timing, running.timing)
        assertFalse("paused" in running.timing, running.timing)
        assertEquals(listOf(ask.id), detail.uiState.value.waitingFor.map { it.id }, "the ask is still shown with the task, for the owner to answer")

        // A request a call waits for does not pause a task that is not waiting either: only a waiting task's limit is paused.
        app.raise(samplePermission(agent.id, id = "req_call", taskId = task.id, rootId = task.rootId, createdAt = app.clock.current, minutes = 100_000))
        assertTrue("times out at" in assertNotNull(detail.uiState.value.task).timing, "RUNNING: the limit runs")
        // While the task waits for a teammate, only a request a call waits for pauses it; the agent's own ask does not.
        app.graph.ownerRequests.deny(RequestId("req_call"))
        app.tasks.update(task.id) { it.copy(status = TaskStatus.WAITING_FOR_AGENT) }
        assertTrue("times out at" in assertNotNull(detail.uiState.value.task).timing, "an ask alone pauses nothing")
        app.raise(samplePermission(agent.id, id = "req_helper", taskId = task.id, rootId = task.rootId, createdAt = app.clock.current, minutes = 100_000))
        assertTrue("paused while it waits for you" in assertNotNull(detail.uiState.value.task).timing, "the helper's call waits for the owner (INT-10)")
        // And a task the broker marked as waiting for the owner is paused, as before.
        app.tasks.update(task.id) { it.copy(status = TaskStatus.WAITING_FOR_OWNER) }
        assertTrue("paused while it waits for you" in assertNotNull(detail.uiState.value.task).timing)
    }

    @Test
    fun `U2 - a task waiting for a task it delegated, which waits for the owner, shows its limit as paused`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope, com.aicontrolcenter.core.settings.FrameworkSettings(operatorEnabled = true, approvalTimeoutMinutes = 100_000)).started()
        app.populateWithObjectives()
        val at = app.clock.current
        val task = assertNotNull(
            app.tasks.update(SampleObjectives.COMPARE) { it.copy(status = TaskStatus.WAITING_FOR_AGENT, startedAt = at, deadlineAt = at + 15 * 60_000L, finishedAt = null) },
        )
        val agent = task.assignee!!
        // The task it delegated: its own child, at work.
        val delegated = task.copy(
            id = com.aicontrolcenter.core.TaskId("tsk_delegated"), parentId = task.id, title = "Check the prices", status = TaskStatus.RUNNING,
            delegationChain = listOf(agent), dependsOn = emptyList(), conversationId = null, result = null, reviews = emptyList(),
        )
        app.tasks.upsert(delegated)
        val detail = TaskDetailViewModel(
            task.id, app.graph.tasks, app.graph.messages, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry,
            app.graph.orchestrator, app.graph.activityMonitor, app.clock, ZoneOffset.UTC,
            requests = app.graph.ownerRequests.pending, capabilityInfo = app.graph.capabilities::info,
        ).also { it.uiState.activeIn(backgroundScope) }
        fun timing() = assertNotNull(detail.uiState.value.task).timing
        assertEquals("Waiting for a teammate", assertNotNull(detail.uiState.value.task).status.text)
        assertTrue("times out at" in timing(), "the delegated task is at work, so the limit runs: ${timing()}")

        // A call of the delegated task waits for the owner: the broker marks that task, and the request names it, not this one.
        app.tasks.update(delegated.id) { it.copy(status = TaskStatus.WAITING_FOR_OWNER) }
        app.raise(samplePermission(agent, id = "req_delegated", taskId = delegated.id, rootId = task.rootId, createdAt = app.clock.current, minutes = 100_000))
        assertTrue("paused while it waits for you" in timing(), timing())
        assertFalse("times out at" in timing(), timing())

        // Two delegations down: the delegated task waits for one it delegated in turn, and that one waits for the owner.
        app.graph.ownerRequests.deny(RequestId("req_delegated"))
        val deeper = delegated.copy(id = com.aicontrolcenter.core.TaskId("tsk_deeper"), parentId = delegated.id, status = TaskStatus.WAITING_FOR_OWNER, delegationChain = listOf(agent, agent))
        app.tasks.upsert(deeper)
        app.tasks.update(delegated.id) { it.copy(status = TaskStatus.WAITING_FOR_AGENT) }
        assertTrue("paused while it waits for you" in timing(), timing())

        // The answer came and the delegated work goes on: the limit runs again.
        app.tasks.update(deeper.id) { it.copy(status = TaskStatus.RUNNING) }
        assertTrue("times out at" in timing(), timing())
        // A waiting task that is not under the one this task waits for pauses nothing here.
        app.tasks.update(deeper.id) { it.copy(status = TaskStatus.WAITING_FOR_OWNER, parentId = task.rootId) }
        assertTrue("times out at" in timing(), timing())
        // And a task that is itself at work runs against its limit, whatever waits below it.
        app.tasks.update(deeper.id) { it.copy(parentId = delegated.id) }
        assertTrue("paused while it waits for you" in timing(), timing())
        app.tasks.update(task.id) { it.copy(status = TaskStatus.RUNNING) }
        assertTrue("times out at" in timing(), timing())
    }

    @Test
    fun `U1 - the refused request's own screen stops saying its calls are refused once the owner gave the permission directly`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope).started()
        val operator = app.operatorId()
        val folder = CapabilityConstraint.Folder(com.aicontrolcenter.core.FolderId("fld_documents"))
        app.raise(
            samplePermission(operator, agentName = "Operator", id = "req_folder", createdAt = app.clock.current, minutes = 240)
                .copy(capability = OperatorCapabilities.FOLDERS_WRITE, constraint = folder, toolId = ToolId("folders.write"), targetLabel = "Documents", dataGoesTo = null),
        )
        val vm = approval(app, "req_folder")
        val refusedAt = app.clock.current
        vm.choose(RequestChoice.DENY)
        app.clock.current = refusedAt + 60_000
        vm.messageShown()
        val held = assertNotNull(assertNotNull(vm.uiState.value.request).askAgain)
        assertTrue(held.canLetAskAgain && "refused without asking you" in held.text, held.text)

        // Tools > Folders: the owner switches the agent on for the folder.
        app.clock.current = refusedAt + 120_000
        val given = app.graph.ownerGrants.grant(operator, OperatorCapabilities.FOLDERS_WRITE, folder)
        assertTrue(given is com.aicontrolcenter.core.operator.OwnerResult.Done, given.toString())
        val shown = assertNotNull(vm.uiState.value.request)
        assertNull(shown.askAgain, "its calls run now, so nothing says they are refused and there is nothing to let it ask for")
        assertEquals("Denied", shown.status.text)
    }

    @Test
    fun `B2 - on the refused request's own screen the owner lets the agent ask again`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope).started()
        val operator = app.operatorId()
        app.raise(samplePermission(operator, agentName = "Operator", id = "req_refused", createdAt = app.clock.current, minutes = 240))
        app.raise(sampleApproval(operator, id = "req_approval", createdAt = app.clock.current, minutes = 240))
        val vm = approval(app, "req_refused")
        assertNull(assertNotNull(vm.uiState.value.request).askAgain, "while it waits there is nothing to open")

        // The test clock jumps ahead on every read: kept just after the refusal, so the ten minutes are not over.
        val refusedAt = app.clock.current
        vm.choose(RequestChoice.DENY)
        app.clock.current = refusedAt + 60_000
        vm.messageShown()
        val refused = assertNotNull(vm.uiState.value.request)
        val held = assertNotNull(refused.askAgain, "a refused permission says how long its agent is held, and offers to end that")
        assertTrue(held.canLetAskAgain)
        assertTrue(held.text.startsWith("Operator cannot ask you for this permission again until "), held.text)
        assertNotNull(app.ownerRequestStore.lastRefusal(operator, OperatorCapabilities.WEB_READ, CapabilityConstraint.None, 0))

        vm.letAskAgain()
        assertEquals("Operator can ask again. Its next call or request for this permission is put to you.", vm.uiState.value.message?.text)
        val opened = assertNotNull(assertNotNull(vm.uiState.value.request).askAgain)
        assertFalse(opened.canLetAskAgain, "done once, nothing more to tap")
        assertTrue(opened.text.startsWith("You let Operator ask again ("), opened.text)
        assertEquals("Denied", vm.uiState.value.request?.status?.text, "the refusal stays in the history as a refusal")
        assertNull(app.ownerRequestStore.lastRefusal(operator, OperatorCapabilities.WEB_READ, CapabilityConstraint.None, 0), "the wait is over in the store the broker reads")
        assertNotNull(app.ownerRequestStore.get(RequestId("req_refused"))?.askAgainAt)
        assertTrue(app.permissions.observeAll().first().isEmpty(), "nothing was given")
        assertEquals(1, app.activityLog.recent().count { it.title.startsWith("Owner let the agent ask again") })

        // A denied approval holds nothing back: its screen offers nothing, and the port refuses.
        val approvalVm = approval(app, "req_approval")
        approvalVm.choose(RequestChoice.DENY)
        assertNull(assertNotNull(approvalVm.uiState.value.request).askAgain)
        approvalVm.letAskAgain()
        assertEquals("Only a permission you refused keeps an agent from asking again.", approvalVm.uiState.value.message?.text)
    }
}
