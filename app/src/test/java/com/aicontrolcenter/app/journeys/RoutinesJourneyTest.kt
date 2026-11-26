package com.aicontrolcenter.app.journeys

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.makeRoutine
import com.aicontrolcenter.app.testing.teamOnSimulator
import com.aicontrolcenter.app.ui.routines.OBJECTIVE_LABEL
import com.aicontrolcenter.app.ui.routines.TITLE_LABEL
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Routines as the owner uses them (Q1, Appendix A, F12; WP10c): switched on in Settings after a second question, made in
 * the editor, and started only after the owner's Start and confirmation on the "Tap to start" step, which the app opens
 * by itself when a run waits; "Alarms and reminders" leads to Android's own page; a paused routine is switched on again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class RoutinesJourneyTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null
    private val application: Application get() = ApplicationProvider.getApplicationContext()

    private fun app(setUp: suspend TestApp.() -> Unit = {}): AppRobot =
        AppRobot(compose).given { teamOnSimulator(); setUp() }.launch().also { robot = it }

    @After
    fun stopApp() {
        robot?.close()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
    }

    private fun AppRobot.objectives(): List<TaskRecord> = runBlocking {
        app.tasks.observeObjectives().first()
    }

    private fun AppRobot.typeInto(label: String, text: String) = apply {
        compose.onNode(hasSetTextAction() and hasText(label)).performTextReplacement(text)
        compose.waitForIdle()
    }

    @Test
    fun `the owner switches routines on, makes one, and it starts only after Start and the confirmation`() {
        val robot = app().openSectionViaMore("Settings")
        // Settings › Routines and folder pick-up: off after installing; on only after the second question.
        robot.tap("Routines").assertShown("Switch routines on?")
        assertFalse(runBlocking { robot.app.settings.get().routinesEnabled }, "not before the owner confirms")
        robot.tapInDialog("Switch on")
        assertTrue(runBlocking { robot.app.settings.get().routinesEnabled })

        robot.tap("Open routines").assertShown("No routines yet").tap("New routine")
        robot.typeInto(TITLE_LABEL, "Morning news")
            .typeInto(OBJECTIVE_LABEL, "Summarise the news about electric cars in five lines.")
            .assertShown("Every day at 07:00")
            .tap("Save routine")
            .assertShown("Morning news")
            .assertShown("Every day at 07:00")
            .assertShown("No screens")
        val routine = runBlocking { robot.app.routineStore.getAll().single() }
        assertFalse(routine.allowScreenActions, "screens are off unless the owner ticks them (F12)")

        // The phone was asleep at 07:00: when the owner looks, the run waits for a tap and the app shows it by itself.
        val due = routine.nextRunAt!!
        robot.app.clock.current = due + 5 * 60_000L
        runBlocking { robot.app.graph.waitingRoutines.catchUp() }
        robot.waitUntilShown("Routine \"Morning news\" is waiting to start")
            .assertShown("Routines waiting to start")
            .assertShown("Nothing starts until you tap Start")
        assertTrue(robot.objectives().isEmpty(), "nothing started by itself")

        robot.app.clock.current = due + 6 * 60_000L
        robot.tap("Start").assertShown("Start \"Morning news\" now?")
        assertTrue(robot.objectives().isEmpty(), "Start only asks")
        robot.tapInDialog("Start now").waitUntilShown("Started: the team is working on \"Morning news\".")
        val started = robot.objectives().single()
        assertEquals(routine.id, started.options?.routineId)
        assertEquals(false, started.options?.screenActionsAllowed, "the run carries the routine's own choice")
        val run = runBlocking { robot.app.routineStore.runs(routine.id, 5) }.first()
        assertEquals(RoutineRunOutcome.STARTED, run.outcome)
        assertEquals(started.id, run.objectiveId)

        // The routine's history says it, with a way to the objective.
        robot.back().assertShown("Morning news").tapItemContaining("Every day at 07:00")
            .scrollTo("Started after you tapped Start.")
            .tap("Open the objective")
            .waitUntilShown("Started by routine \u201CMorning news\u201D")
    }

    @Test
    fun `the list leads to what waits, and Not now leaves the run out and starts nothing`() {
        val robot = app {
            settings.update { it.copy(routinesEnabled = true) }
            val routine = makeRoutine("Evening digest")
            clock.current = routine.nextRunAt!! + 60_000L
            graph.waitingRoutines.catchUp()
        }
        robot.openSectionViaMore("Tools").tap("Routines: objectives that start on a schedule")
            .assertShown("1 routine is waiting for you to tap Start.")
            .tap("See what is waiting")
            .waitUntilShown("Routine \"Evening digest\" is waiting to start")
        robot.tap("Not now").waitUntilShown("Nothing is waiting to start")
        assertTrue(robot.objectives().isEmpty())
        val run = runBlocking { robot.app.routineStore.runs(robot.app.routineStore.getAll().single().id, 5) }.single()
        assertEquals(RoutineRunOutcome.MISSED, run.outcome)
    }

    @Test
    fun `without Alarms and reminders the list says routines may start late and opens Android's page`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val robot = app { makeRoutine() }.openSectionViaMore("Settings").tap("Open routines")
            .scrollTo("Routines may start late")
            .assertShown("you get a \"Tap to start\" notification")
            .tap("Allow alarms and reminders")
        var started: android.content.Intent? = null
        robot.waitUntil { shadowOf(application).nextStartedActivity?.also { started = it } != null }
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, assertNotNull(started).action)
        assertEquals("package:${application.packageName}", started!!.dataString)

        // Back from Android's page with it allowed: the list reads it again.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        robot.back().tap("Open routines").scrollTo("Alarms and reminders: allowed")
            .assertShown("Routines start at their time")
    }

    @Test
    fun `a routine that paused itself after three failed runs is switched on again from its page`() {
        val robot = app {
            settings.update { it.copy(routinesEnabled = true) }
            val routine = makeRoutine("Morning news")
            routineStore.update(routine.id) { it.copy(enabled = false, nextRunAt = null, consecutiveFailures = 3, pausedNote = RoutineLimits.PAUSED_AFTER_FAILURES) }
        }.openSectionViaMore("Tools").tap("Routines: objectives that start on a schedule")
            .assertShown("Paused")
            .assertShown(RoutineLimits.PAUSED_AFTER_FAILURES)
            .tapItemContaining("Morning news")
            .tap("Switch it on again")
            .waitUntilShown("Switched on.")
        val routine = runBlocking { robot.app.routineStore.getAll().single() }
        assertTrue(routine.enabled)
        assertEquals(0, routine.consecutiveFailures)
        assertEquals(null, routine.pausedNote)
    }

    @Test
    fun `May use other apps' screens is off by default and needs a second yes`() {
        val robot = app { makeRoutine("Read my inbox") }.openSectionViaMore("Tools").tap("Routines: objectives that start on a schedule")
            .tapItemContaining("Read my inbox")
            .tap("May use other apps' screens")
            .assertShown("Let this routine use other apps' screens?")
            .tapInDialog("Cancel")
        assertFalse(runBlocking { robot.app.routineStore.getAll().single().allowScreenActions })
        robot.tap("May use other apps' screens").tapInDialog("Allow screens")
            .assertShown("Screen actions work only while the phone's screen is on and unlocked")
            .tap("Save changes")
        assertTrue(runBlocking { robot.app.routineStore.getAll().single().allowScreenActions })
    }
}
