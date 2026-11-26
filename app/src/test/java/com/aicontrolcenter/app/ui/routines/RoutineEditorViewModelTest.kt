package com.aicontrolcenter.app.ui.routines

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.makeRoutine
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.routines.RoutineRun
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.framework.routines.DefaultOwnerRoutines
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The routine editor (WP10c; Appendix A, F12): the fields are saved exactly as they are when Save is tapped (rule 27), the
 * schedule in the owner's local time with the clock changes said, "May use other apps' screens" off by default and asked
 * once more, the refusals of the owner's port shown as they are, the run history in words, and "Switch it on again".
 */
class RoutineEditorViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.editor(app: TestApp, id: RoutineId? = null, zone: () -> ZoneId = app.graph.zone) =
        RoutineEditorViewModel(id, app.graph.ownerRoutines!!, app.clock, zone).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `a new routine starts daily at seven on every day, reviewed, without screens, and is saved exactly as typed`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = editor(app)
        var state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.isNew)
        assertEquals(ScheduleKind.DAILY, state.kind)
        assertEquals("Every day at 07:00", state.scheduleText)
        assertEquals("With this schedule it next runs tomorrow at 07:00.", state.preview)
        assertFalse(state.allowScreenActions, "screens are off by default (F12)")
        assertNull(state.approvalTimeoutMinutes)
        assertTrue(state.reviewResults)
        assertNull(state.clockChange, "UTC has no clock changes")

        viewModel.title.setTextAndPlaceCursorAtEnd("Morning news")
        viewModel.objective.setTextAndPlaceCursorAtEnd("Summarise the news about electric cars in five lines.")
        viewModel.setHour(7)
        viewModel.setMinute(30)
        viewModel.setDays(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        viewModel.setApprovalTimeout(120)
        viewModel.setReviewResults(false)
        assertEquals("On weekdays at 07:30", viewModel.uiState.value.scheduleText)
        viewModel.save()

        state = viewModel.uiState.value
        val saved = app.routineStore.getAll().single()
        assertEquals(saved.id, state.saved, "the screen goes back once it is saved")
        assertEquals("Morning news", saved.title)
        assertEquals("Summarise the news about electric cars in five lines.", saved.objectiveText)
        assertEquals(RoutineSchedule.Daily(7 * 60 + 30, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)), saved.schedule)
        assertEquals(120, saved.approvalTimeoutMinutes)
        assertFalse(saved.allowScreenActions)
        assertFalse(saved.options.reviewResults)
        assertTrue(saved.enabled, "a new routine is on (it runs only while the master switch is on)")
        assertFalse(saved.objectiveOptions().screenActionsAllowed, "the run's options carry the routine's no-screens choice")
        viewModel.savedHandled()
        assertNull(viewModel.uiState.value.saved)
    }

    @Test
    fun `each field says what is missing and nothing is saved`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = editor(app)
        viewModel.setDays(emptySet())
        viewModel.save()
        val state = viewModel.uiState.value
        assertEquals("Give the routine a name.", state.titleError)
        assertEquals("Write what the team should do.", state.objectiveError)
        assertEquals("Choose at least one day.", state.daysError)
        assertEquals("Choose at least one day.", state.scheduleText)
        assertNull(state.preview)
        assertNull(state.saved)
        assertTrue(app.routineStore.getAll().isEmpty())
        viewModel.toggleDay(DayOfWeek.SUNDAY)
        assertNull(viewModel.uiState.value.daysError)
        assertEquals("On Sundays at 07:00", viewModel.uiState.value.scheduleText)
    }

    @Test
    fun `May use other apps' screens is asked once more before it is on, and off at once`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = editor(app)
        viewModel.requestScreenActions(true)
        assertTrue(viewModel.uiState.value.confirmScreens)
        assertFalse(viewModel.uiState.value.allowScreenActions, "not before the owner confirms")
        viewModel.cancelScreenActions()
        assertFalse(viewModel.uiState.value.confirmScreens)
        assertFalse(viewModel.uiState.value.allowScreenActions)
        viewModel.requestScreenActions(true)
        viewModel.confirmScreenActions()
        assertTrue(viewModel.uiState.value.allowScreenActions)
        viewModel.title.setTextAndPlaceCursorAtEnd("Read my inbox")
        viewModel.objective.setTextAndPlaceCursorAtEnd("Read the newest mail in the mail app and summarise it.")
        viewModel.save()
        assertTrue(app.routineStore.getAll().single().allowScreenActions)
        viewModel.requestScreenActions(false)
        assertFalse(viewModel.uiState.value.allowScreenActions)
        assertFalse(viewModel.uiState.value.confirmScreens, "switching off is not asked")
    }

    @Test
    fun `every few hours lists the times of a day and saves the schedule`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = editor(app)
        viewModel.setKind(ScheduleKind.EVERY_HOURS)
        viewModel.setEveryHours(5)
        viewModel.setHour(6)
        viewModel.setMinute(0)
        var state = viewModel.uiState.value
        assertEquals("Every 5 hours from 06:00, every day", state.scheduleText)
        assertEquals("At 06:00, 11:00, 16:00 and 21:00.", state.timesText)
        assertEquals("With this schedule it next runs today at 16:00.", state.preview)
        viewModel.setEveryHours(99)
        assertEquals(RoutineLimits.MAX_HOURS_APART, viewModel.uiState.value.everyHours, "at most every 12 hours")
        viewModel.setEveryHours(0)
        assertEquals(RoutineLimits.MIN_HOURS_APART, viewModel.uiState.value.everyHours, "runs are at least an hour apart")
        viewModel.setEveryHours(5)
        viewModel.title.setTextAndPlaceCursorAtEnd("Inbox check")
        viewModel.objective.setTextAndPlaceCursorAtEnd("Look for new mail.")
        viewModel.save()
        assertEquals(RoutineSchedule.EveryHours(5, 6 * 60), app.routineStore.getAll().single().schedule)
        state = viewModel.uiState.value
        assertNotNull(state.saved)
    }

    @Test
    fun `the night the clocks go forward or back is explained for the schedule's time`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val berlin = ZoneId.of("Europe/Berlin")
        val viewModel = editor(app, zone = { berlin })
        viewModel.setHour(2)
        viewModel.setMinute(30)
        assertEquals(
            "On Sunday 28 March the clocks go forward: 02:30 does not happen that night, so it runs at 03:30.",
            viewModel.uiState.value.clockChange,
        )
        viewModel.setHour(7)
        assertNull(viewModel.uiState.value.clockChange, "07:30 is not touched by the change")
    }

    @Test
    fun `an existing routine is filled once, changed exactly, and its history says each outcome in words`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val routine = app.makeRoutine("Morning news", approvalTimeoutMinutes = 90)
        val start = TestApp.START_TIME
        app.routineStore.appendRun(RoutineRun(RoutineRunId("rrn_1"), routine.id, start - 3 * 86_400_000L, RoutineRunOutcome.MISSED, note = "It waited for your tap and was not started."))
        app.routineStore.appendRun(RoutineRun(RoutineRunId("rrn_2"), routine.id, start - 2 * 86_400_000L, RoutineRunOutcome.SKIPPED_DAILY_LIMIT))
        app.routineStore.appendRun(
            RoutineRun(RoutineRunId("rrn_3"), routine.id, start - 86_400_000L, RoutineRunOutcome.STARTED, startedAt = start - 86_400_000L + 1_000, objectiveId = TaskId("tsk_obj")),
        )
        val viewModel = editor(app, routine.id)
        var state = viewModel.uiState.value
        assertFalse(state.isNew)
        assertEquals("Morning news", viewModel.title.text.toString())
        assertEquals("Summarise the news about electric cars in five lines.", viewModel.objective.text.toString())
        assertEquals(90, state.approvalTimeoutMinutes)
        assertEquals(listOf(null, 15, 30, 60, 90, 120, 240), state.timeoutChoices, "the routine's own 90 minutes is offered too")
        assertEquals("90 minutes", RoutineEditorViewModel.timeoutLabel(90))
        assertEquals(listOf("Started", "Skipped: today's limit", "Missed"), state.runs.map { it.outcome })
        assertEquals(listOf(StatusTone.READY, StatusTone.ATTENTION, StatusTone.DISABLED), state.runs.map { it.tone })
        assertEquals(TaskId("tsk_obj"), state.runs[0].objectiveId, "a started run links to its objective")
        assertEquals("Yesterday at 12:00", state.runs[0].due)
        assertEquals("It waited for your tap and was not started.", state.runs[2].note)
        assertTrue(state.enabled)

        // Save without a change goes back without changing anything (the failures in a row are not started afresh).
        val before = app.routineStore.get(routine.id)!!
        viewModel.save()
        assertEquals(routine.id, viewModel.uiState.value.saved)
        assertEquals(before.updatedAt, app.routineStore.get(routine.id)!!.updatedAt)
        viewModel.savedHandled()
        viewModel.title.setTextAndPlaceCursorAtEnd("Morning headlines")
        viewModel.save()
        assertEquals("Morning headlines", app.routineStore.get(routine.id)!!.title)
        assertEquals(routine.id, viewModel.uiState.value.saved)
        // A change elsewhere does not overwrite what the owner is typing.
        viewModel.title.setTextAndPlaceCursorAtEnd("Morning headlines, short")
        app.routineStore.update(routine.id) { it.copy(consecutiveFailures = 2) }
        state = viewModel.uiState.value
        assertEquals("Morning headlines, short", viewModel.title.text.toString())
        assertEquals("2 failed runs in a row. After 3 in a row it switches itself off.", state.failures)
    }

    @Test
    fun `a paused routine shows its note, and Switch it on again clears it`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val routine = app.makeRoutine()
        app.routineStore.update(routine.id) { it.copy(enabled = false, nextRunAt = null, consecutiveFailures = 3, pausedNote = RoutineLimits.PAUSED_AFTER_FAILURES) }
        val viewModel = editor(app, routine.id)
        assertEquals(RoutineLimits.PAUSED_AFTER_FAILURES, viewModel.uiState.value.pausedNote)
        viewModel.setEnabled(true)
        val stored = app.routineStore.get(routine.id)!!
        assertTrue(stored.enabled)
        assertNull(stored.pausedNote)
        assertEquals(0, stored.consecutiveFailures)
        val state = viewModel.uiState.value
        assertNull(state.pausedNote)
        assertEquals("Switched on.", state.message?.text)
        assertEquals("Next: tomorrow at 07:00", state.next)
        viewModel.setEnabled(false)
        assertFalse(app.routineStore.get(routine.id)!!.enabled)
        assertNull(viewModel.uiState.value.next)
    }

    @Test
    fun `the eleventh routine is refused with the port's own sentence`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        repeat(RoutineLimits.MAX_ROUTINES) { app.makeRoutine("Routine ${it + 1}") }
        val viewModel = editor(app)
        viewModel.title.setTextAndPlaceCursorAtEnd("One more")
        viewModel.objective.setTextAndPlaceCursorAtEnd("Do one more thing.")
        viewModel.save()
        val state = viewModel.uiState.value
        assertEquals(DefaultOwnerRoutines.TOO_MANY, state.problem)
        assertNull(state.saved)
        assertFalse(state.isSaving)
        assertEquals(RoutineLimits.MAX_ROUTINES, app.routineStore.getAll().size)
    }

    @Test
    fun `delete asks first, then the routine and its history are gone`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val routine = app.makeRoutine()
        val viewModel = editor(app, routine.id)
        viewModel.askDelete()
        assertTrue(viewModel.uiState.value.confirmDelete)
        viewModel.cancelDelete()
        assertFalse(viewModel.uiState.value.confirmDelete)
        assertNotNull(app.routineStore.get(routine.id))
        viewModel.askDelete()
        viewModel.confirmDelete()
        assertNull(app.routineStore.get(routine.id))
        val state = viewModel.uiState.value
        assertTrue(state.deleted)
        assertFalse(state.missing, "the screen closes, it does not say \"gone\"")
    }

    @Test
    fun `a routine deleted elsewhere says so`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val routine = app.makeRoutine()
        val viewModel = editor(app, routine.id)
        assertFalse(viewModel.uiState.value.missing)
        app.graph.ownerRoutines!!.delete(routine.id)
        assertTrue(viewModel.uiState.value.missing)
    }
}
