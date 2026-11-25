package com.aicontrolcenter.app.ui.tasks

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.aicontrolcenter.app.testing.GatedLlmClient
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TeamModel
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.letTheTeamWork
import com.aicontrolcenter.app.testing.serveModel
import com.aicontrolcenter.app.testing.teamOn
import com.aicontrolcenter.app.testing.teamOnSimulator
import com.aicontrolcenter.app.ui.chat.TranscriptItem
import com.aicontrolcenter.app.work.InMemoryNotificationAccess
import com.aicontrolcenter.app.work.NotificationAccess
import com.aicontrolcenter.app.work.ObjectiveProgress
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.memory.MemorySeeder
import com.aicontrolcenter.testkit.ScriptStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Tasks screens' view models over a real AppGraph: objectives started, watched, controlled and retried. */
class TasksViewModelsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.newObjective(app: TestApp, notifications: NotificationAccess = app.notifications) =
        NewObjectiveViewModel(app.graph.orchestrator, app.graph.registryDirectory, app.graph.memoryService, notifications, app.graph::awaitStartup)
            .also { it.uiState.activeIn(backgroundScope) }

    private fun TestScope.objectives(app: TestApp) =
        ObjectivesViewModel(app.graph.tasks, app.graph.registryDirectory, app.graph.orchestrator, app.graph.notifications, app.clock, ZoneOffset.UTC)
            .also { it.uiState.activeIn(backgroundScope) }

    private fun TestScope.objective(app: TestApp, id: TaskId) = ObjectiveViewModel(
        id, app.graph.tasks, app.graph.messages, app.graph.registryDirectory, app.graph.orchestrator, app.graph.activityLog, app.graph.settings,
        app.clock, ZoneOffset.UTC, ticker = flowOf(Unit),
    ).also { it.uiState.activeIn(backgroundScope) }

    private fun TestScope.task(app: TestApp, id: TaskId) = TaskDetailViewModel(
        id, app.graph.tasks, app.graph.messages, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry, app.graph.orchestrator,
        app.graph.activityMonitor, app.clock, ZoneOffset.UTC,
    ).also { it.uiState.activeIn(backgroundScope) }

    private fun NewObjectiveViewModel.give(text: String): TaskId {
        setText(text)
        start()
        return assertNotNull(uiState.value.startedId, uiState.value.message?.text ?: "the objective did not start")
    }

    @Test
    fun `an objective started on the simulator runs through a reviewed plan to a final document`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnSimulator()
        val form = newObjective(app)
        val ready = form.uiState.value
        assertEquals("Director", ready.director?.name)
        assertTrue(ready.canStart)
        assertEquals(MemorySeeder.TEAM_SPACE_NAME, ready.knowledgeSpace, "the Director may write the starter space")
        assertFalse(ready.team.leavesDevice, "the simulator runs on this phone")
        assertTrue(ready.team.models.single().isSimulator)

        form.setSaveKnowledge(true)
        val id = form.give(OBJECTIVE)
        val list = objectives(app)
        letTheTeamWork()

        val state = objective(app, id).uiState.value
        val header = assertNotNull(state.header)
        assertEquals("Completed", header.status.text)
        assertTrue(state.document.orEmpty().contains("Simulated document"), state.document)
        assertEquals(ObjectiveProgress(5, 5), header.progress, "four planned tasks and the final result")
        assertNotNull(header.interpretation, "the Director's reading of the objective is shown")
        assertTrue(header.canDelete && !header.canPause && !header.canCancel)
        assertEquals(listOf(1, 2, 3, 4, null), state.plan.filter { it.depth == 0 }.map { it.number })
        val revised = state.plan.first { it.number == 2 }
        assertEquals(1, revised.revisionRound)
        assertEquals("Approved", revised.verdict?.status?.text)
        assertEquals(2, revised.verdict?.rounds)
        assertTrue(state.plan.any { it.kind == TaskKind.REVIEW && it.depth == 1 }, "reviews hang under the task they check")
        assertEquals(listOf(1), state.plan.first { it.number == 2 }.dependsOn)
        val first = state.messages.first()
        assertEquals(MessageType.ASSIGNMENT, first.type)
        assertEquals(ParticipantUi.Owner, first.from)
        assertTrue(state.messages.any { it.type == MessageType.REVIEW_RESULT })
        assertEquals(0, state.egress.transfers, "nothing left the phone")
        assertTrue(state.log.isNotEmpty())

        val after = list.uiState.value
        assertEquals(0 to 1, after.activeCount to after.finishedCount)
        list.setFilter(ObjectiveFilter.FINISHED)
        assertEquals(ObjectiveProgress(5, 5), list.uiState.value.rows.single().progress)

        // A task, with the agent's transcript: the brief, then the work.
        val researched = task(app, state.plan.first { it.number == 1 }.id).uiState.value
        val detail = assertNotNull(researched.task)
        assertEquals("Completed", detail.status.text)
        assertTrue(detail.result.orEmpty().contains("simulated"), detail.result)
        assertTrue(assertIs<TranscriptItem.Owner>(researched.transcript.first()).text.contains("[aicc-protocol task/1]"))
        assertTrue(researched.transcript.any { it is TranscriptItem.Agent })
        assertTrue(researched.messages.any { it.type == MessageType.ASSIGNMENT })
    }

    @Test
    fun `the owner pauses, resumes, cancels and deletes an objective`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        // The Director's planning never returns, so the objective stays at work until the owner acts.
        val model = app.serveModel(GatedLlmClient(listOf("never")))
        for (agent in app.agents.getAll()) app.graph.agentManager.assignModel(agent.id, model.id)
        val id = newObjective(app).give(OBJECTIVE)
        val screen = objective(app, id)
        val list = objectives(app)
        letTheTeamWork(1_000)
        assertTrue(screen.uiState.value.header!!.canPause)
        assertTrue(screen.uiState.value.header!!.isLive)
        val row = list.uiState.value.rows.single()
        assertEquals(id, row.id)
        assertTrue(row.isLive && row.progress.isPlanning)

        screen.pause()
        letTheTeamWork(1_000)
        val paused = screen.uiState.value.header!!
        assertEquals("Paused", paused.status.text)
        assertTrue(paused.canResume && !paused.isLive && !paused.canPause)
        assertTrue(app.graph.orchestrator.running.value.isEmpty())

        screen.resume()
        assertEquals("Running", screen.uiState.value.header!!.status.text)
        assertEquals(setOf(id), app.graph.orchestrator.running.value)

        screen.cancel()
        letTheTeamWork(1_000)
        val cancelled = screen.uiState.value
        assertEquals("Cancelled", cancelled.header!!.status.text)
        assertTrue(cancelled.header!!.canDelete && !cancelled.header!!.canResume)
        assertEquals("Cancelled. What the team produced is kept.", cancelled.message?.text)
        assertTrue(cancelled.documentNote.startsWith("No final document"), cancelled.documentNote)

        screen.delete()
        assertTrue(screen.uiState.value.deleted)
        assertNull(app.tasks.get(id))
        assertTrue(app.messages.list(id).isEmpty())
    }

    @Test
    fun `Resume waits for the start-up recovery, so restarted work never races it`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        val model = app.serveModel(GatedLlmClient(listOf("never")))
        for (agent in app.agents.getAll()) app.graph.agentManager.assignModel(agent.id, model.id)
        val id = newObjective(app).give(OBJECTIVE)
        letTheTeamWork(1_000)
        app.graph.orchestrator.pause(id)
        letTheTeamWork(1_000)
        // A screen restored after the app was killed: start-up has not finished its recovery yet.
        val startUp = CompletableDeferred<Unit>()
        val screen = ObjectiveViewModel(
            id, app.graph.tasks, app.graph.messages, app.graph.registryDirectory, app.graph.orchestrator, app.graph.activityLog, app.graph.settings,
            app.clock, ZoneOffset.UTC, ticker = flowOf(Unit), awaitStartup = { startUp.await() },
        ).also { it.uiState.activeIn(backgroundScope) }

        screen.resume()
        letTheTeamWork(1_000)
        assertEquals("Paused", screen.uiState.value.header!!.status.text, "nothing restarts before the recovery")
        assertTrue(app.graph.orchestrator.running.value.isEmpty())

        startUp.complete(Unit)
        letTheTeamWork(1_000)
        assertEquals("Running", screen.uiState.value.header!!.status.text)
        app.graph.orchestrator.cancel(id)
        letTheTeamWork(1_000)
    }

    @Test
    fun `a failed task is run again and the objective's document is rewritten`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope, FrameworkSettings(maxTaskAttempts = 1))
        val model = TeamModel()
        var failing = true
        model.work = { if (failing) ScriptStep.Fail(LlmException.InvalidRequest("The server rejected the request.", 400)) else ScriptStep.Reply("Seven apps work offline.") }
        model.synthesis = { ScriptStep.Reply(if (failing) "# Result\nThe research could not be done." else "# Result\nSeven apps work offline.") }
        app.teamOn(model)
        val id = newObjective(app).give(OBJECTIVE)
        letTheTeamWork()

        val screen = objective(app, id)
        val failedRow = screen.uiState.value.plan.single { it.number == 1 }
        assertEquals("Failed", failedRow.status.text)
        val detail = task(app, failedRow.id)
        assertTrue(detail.uiState.value.task!!.canRetry)

        failing = false
        detail.retry()
        assertEquals("Queued to run again.", detail.uiState.value.message?.text)
        letTheTeamWork()

        val done = detail.uiState.value.task!!
        assertEquals("Completed", done.status.text)
        assertEquals(2, done.attempt)
        assertEquals("Seven apps work offline.", done.result)
        val after = screen.uiState.value
        assertEquals("Completed", after.header!!.status.text)
        assertTrue(after.document.orEmpty().contains("Seven apps work offline."), after.document)
        assertEquals(2, after.plan.single { it.number == 1 }.attempt)
    }

    @Test
    fun `Start takes the objective exactly as the field holds it, then empties the field`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnSimulator()
        val form = newObjective(app)
        // Typed straight into the field: nothing else in the form has seen it yet.
        form.objective.setTextAndPlaceCursorAtEnd("  Compare three note apps, offline first.  ")
        form.start()
        val id = assertNotNull(form.uiState.value.startedId, form.uiState.value.message?.text)
        assertEquals("Compare three note apps, offline first.", assertNotNull(app.tasks.get(id)).instructions)
        assertEquals("", form.objective.text.toString(), "the form is empty for the next objective")
    }

    @Test
    fun `a new objective needs words, a sensible length and a Director that can run`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnSimulator()
        val form = newObjective(app)

        form.start()
        assertEquals("Describe what the team should achieve.", form.uiState.value.textError(form.objective.text))
        assertNull(form.uiState.value.startedId)
        form.setText("x".repeat(NewObjectiveViewModel.MAX_LENGTH + 1))
        assertEquals("Keep it to 4,000 characters or fewer (now 4,001).", form.uiState.value.textError(form.objective.text))
        form.setText("Compare three note apps")
        assertNull(form.uiState.value.textError(form.objective.text))

        val director = app.director().profile.id
        app.graph.agentManager.setEnabled(director, false)
        val off = form.uiState.value
        assertFalse(off.canStart)
        assertEquals("The Director, Director, is switched off. Switch it on to give it an objective.", off.director?.problem)
        form.start()
        assertNull(form.uiState.value.startedId)
        assertTrue(app.tasks.observeObjectivesOnce().isEmpty(), "nothing was stored")

        app.graph.agentManager.setEnabled(director, true)
        app.settings.update { it.copy(directorAgentId = null) }
        assertNull(form.uiState.value.director)
        assertFalse(form.uiState.value.canStart)
    }

    @Test
    fun `refused notifications are asked for once, the objective starts anyway, and the list says they are off`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnSimulator()
        val access = InMemoryNotificationAccess(enabled = false, askable = true)
        val form = newObjective(app, access)
        form.setText(OBJECTIVE)
        form.start()
        assertTrue(form.uiState.value.explainNotifications, "explained before Android asks")
        assertNull(form.uiState.value.startedId)

        form.notificationsExplained()
        form.notificationsAnswered()
        assertEquals(1, access.timesAsked)
        assertNotNull(form.uiState.value.startedId, "the work runs without notifications")
        assertFalse(access.shouldAsk(), "never asked again")

        app.notifications.set(false)
        assertTrue(objectives(app).uiState.value.notificationsOff)
        app.notifications.set(true)
        assertFalse(objectives(app).uiState.value.notificationsOff)
    }

    private suspend fun com.aicontrolcenter.core.tasks.TaskRepository.observeObjectivesOnce() = observeObjectives().first()

    private companion object {
        const val OBJECTIVE = "Research this subject, compare available solutions, develop a plan, and organize the results into a document"
    }
}
