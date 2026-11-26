package com.aicontrolcenter.app.ui.routines

import com.aicontrolcenter.app.routines.ExactAlarmAccess
import com.aicontrolcenter.app.routines.ExactAlarmState
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.makeRoutine
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.routines.RoutineRun
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.framework.routines.RoutineRunner
import com.aicontrolcenter.framework.routines.WaitingRoutine
import com.aicontrolcenter.framework.routines.WaitingRoutines
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Routines list (WP10c; Appendix A): each routine's schedule, next time, status and screens; the master switch it
 * reads (and never writes); the runs waiting for a tap; the cap of 10; "Alarms and reminders" read again on return.
 */
class RoutinesViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private class FakeWaiting(runs: List<WaitingRoutine> = emptyList()) : WaitingRoutines {
        val waiting = MutableStateFlow(runs)
        override fun observeWaiting(): Flow<List<WaitingRoutine>> = waiting
        override suspend fun catchUp() {}
        override suspend fun startWaiting(routineId: RoutineId, runId: RoutineRunId): OwnerResult<RoutineRun> = OwnerResult.Refused(RoutineRunner.NOT_WAITING)
        override suspend fun dismissWaiting(routineId: RoutineId, runId: RoutineRunId): Boolean = false
    }

    private fun TestScope.list(
        app: TestApp,
        waiting: WaitingRoutines = app.graph.waitingRoutines,
        exact: ExactAlarmAccess = ExactAlarmAccess.Fixed(ExactAlarmState.ALWAYS),
    ) = RoutinesViewModel(app.graph.ownerRoutines!!, waiting, app.graph.settingsView, app.clock, app.graph.zone, exact).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `each routine shows its schedule, its next time once routines are on, its status and whether it may use screens`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.makeRoutine("Morning news", schedule = RoutineSchedule.Daily(7 * 60))
        app.makeRoutine(
            "Inbox check",
            schedule = RoutineSchedule.EveryHours(3, 6 * 60, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)),
            allowScreenActions = true,
        )
        val viewModel = list(app)
        var state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.masterOn, "routines are off after installing")
        assertEquals(listOf("Morning news", "Inbox check"), state.routines.map { it.title })
        assertEquals(listOf("Every day at 07:00", "Every 3 hours from 06:00, on weekdays"), state.routines.map { it.schedule })
        assertEquals(List(2) { "Does not run while routines are off in Settings." }, state.routines.map { it.next })
        assertEquals(listOf("No screens", "May use other apps' screens"), state.routines.map { it.screens })
        assertEquals(listOf("On", "On"), state.routines.map { it.status })
        assertEquals("2 of 10 routines", state.count)

        app.settings.update { it.copy(routinesEnabled = true) }
        state = viewModel.uiState.value
        assertTrue(state.masterOn)
        // 14 November 2026 is a Saturday: the daily one runs tomorrow, the weekday one on Monday.
        assertEquals(listOf("Next: tomorrow at 07:00", "Next: Monday 16 Nov at 06:00"), state.routines.map { it.next })
    }

    @Test
    fun `a routine that paused itself says why and how to go on, and failures in a row are counted`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val paused = app.makeRoutine("Morning news")
        val failing = app.makeRoutine("Evening digest")
        app.routineStore.update(paused.id) { it.copy(enabled = false, nextRunAt = null, consecutiveFailures = 3, pausedNote = RoutineLimits.PAUSED_AFTER_FAILURES) }
        app.routineStore.update(failing.id) { it.copy(consecutiveFailures = 1) }
        val rows = list(app).uiState.value.routines
        assertEquals("Paused", rows[0].status)
        assertEquals(StatusTone.ATTENTION, rows[0].tone)
        assertEquals(RoutineLimits.PAUSED_AFTER_FAILURES, rows[0].pausedNote)
        assertNull(rows[0].next, "a paused routine has no next time")
        assertNull(rows[0].failures, "the pause note says it")
        assertEquals("1 failed run in a row. After 3 in a row it switches itself off.", rows[1].failures)
        assertNull(rows[1].pausedNote)
    }

    @Test
    fun `a routine switched off by the owner says Off and has no next time`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val routine = app.makeRoutine()
        app.graph.ownerRoutines!!.setEnabled(routine.id, false)
        val row = list(app).uiState.value.routines.single()
        assertEquals("Off", row.status)
        assertEquals(StatusTone.DISABLED, row.tone)
        assertNull(row.next)
        assertNull(row.pausedNote)
    }

    @Test
    fun `at ten routines no more can be made`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = list(app)
        assertTrue(viewModel.uiState.value.canAdd)
        repeat(RoutineLimits.MAX_ROUTINES) { app.makeRoutine("Routine ${it + 1}") }
        val state = viewModel.uiState.value
        assertFalse(state.canAdd)
        assertEquals("10 of 10 routines", state.count)
    }

    @Test
    fun `the runs waiting for a tap are counted, in words`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val waiting = FakeWaiting()
        val viewModel = list(app, waiting)
        assertNull(viewModel.uiState.value.waiting)
        fun run(n: Int) = WaitingRoutine(RoutineId("rtn_$n"), RoutineRunId("rrn_$n"), "Routine $n", "Do it.", TestApp.START_TIME, allowScreenActions = false)
        waiting.waiting.value = listOf(run(1))
        assertEquals("1 routine is waiting for you to tap Start.", viewModel.uiState.value.waiting)
        waiting.waiting.value = listOf(run(1), run(2))
        assertEquals("2 routines are waiting for you to tap Start.", viewModel.uiState.value.waiting)
    }

    @Test
    fun `Alarms and reminders is read when the list opens and again when the owner comes back`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val exact = ExactAlarmAccess.Fixed(ExactAlarmState.NOT_ALLOWED)
        val viewModel = list(app, exact = exact)
        assertEquals(ExactAlarmState.NOT_ALLOWED, viewModel.uiState.value.exactAlarms)
        exact.current = ExactAlarmState.ALLOWED
        assertEquals(ExactAlarmState.NOT_ALLOWED, viewModel.uiState.value.exactAlarms, "read once, not polled")
        viewModel.refreshExactAlarms()
        assertEquals(ExactAlarmState.ALLOWED, viewModel.uiState.value.exactAlarms)
    }

    @Test
    fun `a phone without Android's page gets a plain sentence`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = list(app)
        viewModel.couldNotOpenAlarmSettings()
        assertEquals(RoutinesViewModel.NO_ALARM_PAGE, viewModel.uiState.value.message?.text)
        viewModel.messageShown()
        assertNull(viewModel.uiState.value.message)
    }
}
