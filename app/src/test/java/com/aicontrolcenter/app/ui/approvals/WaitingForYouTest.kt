package com.aicontrolcenter.app.ui.approvals

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.sampleQuestion
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.app.ui.theme.StatusTone
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "Waiting for you" list and the Approval screen's Phase 6 parts (WP11a): questions listed with requests and routed
 * to their own screen (rule 13), the list's words, and D9's "What happened" after an approval.
 */
class WaitingForYouTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test
    fun `the list holds questions beside requests, told apart`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        val researcher = app.agentNamed("Researcher").profile.id
        app.raise(sampleApproval(researcher, id = "req_a", createdAt = app.clock.current, minutes = 240))
        app.raise(sampleQuestion(researcher, id = "req_q", createdAt = app.clock.current + 1))
        val list = ApprovalsViewModel(app.graph.ownerRequests, app.graph.registryDirectory, app.graph.capabilities::info, app.clock, ZoneOffset.UTC, flowOf(Unit))
            .also { it.uiState.activeIn(backgroundScope) }
        val requests = list.uiState.value.requests
        assertEquals(listOf("req_a", "req_q"), requests.map { it.id.value }, "oldest first")
        val question = requests.single { it.isQuestion }
        assertEquals("Question for you", question.title)
        assertEquals("Which city should the report cover?", question.question)
        assertTrue(question.choices.isEmpty(), "never an approval's answers")
        assertNull(requests.single { !it.isQuestion }.question)
        assertEquals("Waiting for you · 1 request and 1 question, oldest first", waitingSummary(1, 1))
        assertEquals("Waiting for you · 2 requests, oldest first", waitingSummary(2, 0))
        assertEquals("Waiting for you · 1 question, oldest first", waitingSummary(0, 1))
        assertTrue(OwnerRequestKind.QUESTION.icon != OwnerRequestKind.APPROVAL.icon, "a question never wears an approval's glyph")
    }

    @Test
    fun `an Approval screen opened for a question shows no approval`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        val researcher = app.agentNamed("Researcher").profile.id
        app.raise(sampleQuestion(researcher, id = "req_q", createdAt = app.clock.current))
        val screen = ApprovalViewModel(
            RequestId("req_q"), app.graph.ownerRequests, app.graph.ownerGrants, app.graph.registryDirectory, app.graph.capabilities::info,
            app.clock, ZoneOffset.UTC, flowOf(Unit),
        ).also { it.uiState.activeIn(backgroundScope) }
        val request = assertNotNull(screen.uiState.value.request)
        assertTrue(request.isQuestion, "the route hands it to the question screen")
        assertTrue(request.choices.isEmpty())
        assertTrue(request.consequences.isEmpty())
    }

    private fun call(id: String, outcome: String, requestId: String, detail: String = "Delete 3 files in Documents") = ActivityEvent(
        id = EventId(id), at = TestApp.START_TIME, type = ActivityType.TOOL_CALL, agentId = AgentId("agt"), title = "Tool used: Delete files",
        detail = detail,
        data = buildJsonObject {
            put("outcome", outcome)
            putJsonArray("requestIds") { add(requestId) }
        },
    )

    @Test
    fun `D9 - what an approved call did comes from its log entry`() {
        val approved = sampleApproval(AgentId("agt"), id = "req_x").let {
            it.copy(status = OwnerRequestStatus.APPROVED, decidedAt = it.createdAt + 1_000)
        }
        val now = approved.createdAt + 2_000
        assertEquals(WhatHappenedUi("Done.", StatusTone.READY), whatHappened(approved, listOf(call("e1", "OK", "req_x")), now))
        assertEquals(
            "It failed: disk full",
            whatHappened(approved, listOf(call("e1", "FAILED", "req_x", detail = "Delete 3 files in Documents — disk full")), now)?.text,
        )
        assertEquals(
            "Not run: the folder changed since you approved",
            whatHappened(approved, listOf(call("e1", "DENIED", "req_x", detail = "Delete 3 files — the folder changed since you approved")), now)?.text,
        )
        assertTrue(whatHappened(approved, listOf(call("e1", "OK", "req_other")), now)!!.text.startsWith("Still running"), "another request's call is not this one")
        assertTrue(whatHappened(approved, emptyList(), now + 2 * 60 * 60_000L)!!.text.startsWith("Not in the activity log"))
        assertNull(whatHappened(approved.copy(status = OwnerRequestStatus.DENIED, decisionScope = null), emptyList(), now), "nothing ran after a refusal")
    }

    @Test
    fun `D9 - the Approval screen of an approved call says what happened`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        val researcher = app.agentNamed("Researcher").profile.id
        app.raise(sampleApproval(researcher, id = "req_x", createdAt = app.clock.current, minutes = 240))
        val screen = ApprovalViewModel(
            RequestId("req_x"), app.graph.ownerRequests, app.graph.ownerGrants, app.graph.registryDirectory, app.graph.capabilities::info,
            app.clock, ZoneOffset.UTC, flowOf(Unit), activityLog = app.graph.activityLog,
        ).also { it.uiState.activeIn(backgroundScope) }
        assertNull(screen.uiState.value.whatHappened, "nothing to say while it waits")
        screen.choose(RequestChoice.APPROVE)
        assertTrue(screen.uiState.value.whatHappened!!.text.startsWith("Still running"))
        app.activityLog.record(call("e_done", "OK", "req_x").copy(agentId = researcher, at = app.clock.current))
        assertEquals("Done.", screen.uiState.value.whatHappened?.text)
    }
}
