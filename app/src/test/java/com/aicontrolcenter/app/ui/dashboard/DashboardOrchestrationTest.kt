package com.aicontrolcenter.app.ui.dashboard

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TeamModel
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.letTheTeamWork
import com.aicontrolcenter.app.testing.teamOn
import com.aicontrolcenter.app.ui.dashboard.network.EdgeKind
import com.aicontrolcenter.testkit.ScriptStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The dashboard while the team works on an objective: who is on which task, and the messages in the network. */
class DashboardOrchestrationTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test
    fun `an agent on an objective's task is listed with it, and the objective's messages travel through the network`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val model = TeamModel()
        val hold = CompletableDeferred<Unit>()
        model.plan = { ScriptStep.Reply(TeamModel.planJson(listOf(TeamModel.task("t1", "Research offline note apps")))) }
        model.work = {
            hold.await()
            ScriptStep.Reply("Seven apps work offline.")
        }
        app.teamOn(model)
        val viewModel = DashboardViewModel(
            app.graph.registryDirectory, app.graph.activityLog, app.graph.aiAppRegistry, app.clock, ZoneOffset.UTC,
            monitor = app.graph.activityMonitor, orchestrator = app.graph.orchestrator, tasks = app.graph.tasks, messages = app.graph.messages,
        ).also { it.uiState.activeIn(backgroundScope) }

        app.graph.orchestrator.startObjective("Research offline note apps and recommend one")
        letTheTeamWork(1_000)

        val state = viewModel.uiState.value
        val working = state.working.single { it.taskTitle == "Research offline note apps" }
        assertNotNull(working.taskId)
        assertEquals("Research offline note apps and recommend one", working.objectiveTitle)
        assertFalse(working.detail.startsWith("Task:"), "the step alone, not the title again: ${working.detail}")
        val assignment = state.network.pulses.first { it.messageId != null }
        assertEquals(app.director().profile.id.value, assignment.fromId, "the Director's assignment is on its way")
        assertEquals(working.agentId.value, assignment.toId)
        assertTrue(state.network.edges.none { it.kind == EdgeKind.EXCHANGE }, "only the reporting lines were used")

        hold.complete(Unit)
        letTheTeamWork()
        val after = viewModel.uiState.value
        assertEquals(emptyList(), after.working)
        assertEquals(emptyList(), after.network.pulses, "no objective runs: nothing travels")
    }
}
