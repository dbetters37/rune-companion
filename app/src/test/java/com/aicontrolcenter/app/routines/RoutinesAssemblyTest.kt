package com.aicontrolcenter.app.routines

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.di.wiring.RoutinesAssembly
import com.aicontrolcenter.app.di.wiring.RoutinesPorts
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.teamOnSimulator
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineDraft
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.framework.routines.RoutineAlarmPort
import com.aicontrolcenter.framework.routines.RoutineNotices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP17's assembly over the real composition root (in-memory ports): Android's alarm and receivers follow the stored
 * routines and the master switch; a due routine starts its objective through the real orchestrator with exactly the
 * routine's options; and nothing runs, and no receiver is enabled, while the owner has no routine or the switch is off.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoutinesAssemblyTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private class RecordingAlarms : RoutineAlarmPort {
        val armed = CopyOnWriteArrayList<Long>()
        var cancels = 0
        var receivers: Boolean? = null

        override fun arm(atMs: Long) {
            armed += atMs
        }

        override fun cancel() {
            cancels++
        }

        override fun receiversEnabled(enabled: Boolean) {
            receivers = enabled
        }
    }

    private class RecordingNotices : RoutineNotices {
        val waiting = CopyOnWriteArrayList<String>()
        var cleared = 0

        override fun waitingForTap(routineTitle: String) {
            waiting += routineTitle
        }

        override fun noneWaiting() {
            cleared++
        }
    }

    private class World(val app: TestApp, val alarms: RecordingAlarms, val notices: RecordingNotices, val assembly: RoutinesAssembly)

    private fun TestScope.world(scope: CoroutineScope = backgroundScope): World {
        val app = TestApp(scope)
        val alarms = RecordingAlarms()
        val notices = RecordingNotices()
        val ports = app.ports.copy(routines = RoutinesPorts(app.routineStore, app.pickUpRuleStore, alarms, notices, zone = { ZoneOffset.UTC }))
        val assembly = RoutinesAssembly(ports, app.graph.settingsView, app.graph.orchestrator, app.graph.memoryService, app.graph.activityRecorder, scope)
        runCurrent()
        return World(app, alarms, notices, assembly)
    }

    private fun draft(screens: Boolean = false) = RoutineDraft(
        title = "Morning news", objectiveText = "Summarise the news in five lines.", options = ObjectiveOptions(reviewResults = false),
        schedule = RoutineSchedule.Daily(7 * 60), approvalTimeoutMinutes = 45, allowScreenActions = screens,
    )

    @Test
    fun `both switches are off after installing, and nothing is armed or enabled while there is no routine`() = runTest(mainRule.dispatcher) {
        val w = world()
        val settings = w.app.graph.settingsView.get()
        assertFalse(settings.routinesEnabled)
        assertFalse(settings.folderPickUpEnabled)
        assertEquals(false, w.alarms.receivers)
        assertTrue(w.alarms.armed.isEmpty())
        assertNotNull(w.assembly.ownerRoutines)
        assertNotNull(w.assembly.ownerPickUpRules)
        w.assembly.runDue()
        assertTrue(w.app.tasks.withStatus(com.aicontrolcenter.core.tasks.TaskStatus.entries.toSet()).isEmpty())
    }

    @Test
    fun `the alarm follows the routines and the master switch, and the receivers exist only with a routine`() = runTest(mainRule.dispatcher) {
        val w = world()
        val owner = w.assembly.ownerRoutines!!
        val routine = assertIs<OwnerResult.Done<Routine>>(owner.create(draft())).value
        runCurrent()
        assertEquals(true, w.alarms.receivers, "a routine exists: the receivers may run")
        assertTrue(w.alarms.armed.isEmpty(), "the master switch is off: no alarm")
        w.app.settings.update { it.copy(routinesEnabled = true) }
        runCurrent()
        assertEquals(routine.nextRunAt, w.alarms.armed.last(), "the alarm is at its next due time")
        owner.setEnabled(routine.id, false)
        runCurrent()
        val cancels = w.alarms.cancels
        assertTrue(cancels > 0, "no routine is on: no alarm")
        owner.delete(routine.id)
        runCurrent()
        assertEquals(false, w.alarms.receivers, "the last routine is gone: the receivers are disabled again")
    }

    @Test
    fun `switching routines on runs nothing that was due while they were off`() = runTest(mainRule.dispatcher) {
        val w = world()
        val routine = assertIs<OwnerResult.Done<Routine>>(w.assembly.ownerRoutines!!.create(draft())).value
        runCurrent()
        // Days pass with the master switch off; then the owner switches routines on.
        w.app.clock.current = routine.nextRunAt!! + 3 * 24 * 3_600_000L
        w.app.settings.update { it.copy(routinesEnabled = true) }
        runCurrent()
        val moved = w.app.routineStore.get(routine.id)!!
        assertTrue(moved.nextRunAt!! > w.app.clock.current, "due next at its first time from now")
        assertEquals(moved.nextRunAt, w.alarms.armed.last())
        w.assembly.runDue()
        assertEquals(emptyList(), w.app.routineStore.runs(routine.id, 10))
    }

    @Test
    fun `a due routine starts its objective through the orchestrator with exactly the routine's options`() = runTest(mainRule.dispatcher) {
        val w = world()
        w.app.teamOnSimulator()
        w.app.settings.update { it.copy(routinesEnabled = true) }
        val routine = assertIs<OwnerResult.Done<Routine>>(w.assembly.ownerRoutines!!.create(draft())).value
        w.app.clock.current = routine.nextRunAt!!
        w.assembly.runDue()
        val run = w.app.routineStore.runs(routine.id, 10).single()
        assertEquals(RoutineRunOutcome.STARTED, run.outcome)
        val objective = w.app.tasks.get(run.objectiveId!!)!!
        assertEquals(TaskKind.OBJECTIVE, objective.kind)
        assertEquals(routine.objectiveOptions(), objective.options, "stored exactly as Routine.objectiveOptions()")
        assertEquals(routine.id, objective.options!!.routineId)
        assertFalse(objective.options!!.screenActionsAllowed)
        assertEquals(45, objective.options!!.approvalTimeoutMinutes)
        assertEquals(routine.objectiveText, objective.instructions)
        assertTrue(w.notices.waiting.isEmpty())
    }

    @Test
    fun `when the owner opens the app, a due time that passed waits for a tap, and the waiting notice goes once nothing waits`() = runTest(mainRule.dispatcher) {
        val w = world()
        w.app.settings.update { it.copy(routinesEnabled = true) }
        val routine = assertIs<OwnerResult.Done<Routine>>(w.assembly.ownerRoutines!!.create(draft())).value
        w.app.clock.current = routine.nextRunAt!! + 10 * 60_000L
        w.assembly.catchUp()
        runCurrent()
        val waiting = w.app.routineStore.runs(routine.id, 10).single()
        assertEquals(RoutineRunOutcome.WAITING_FOR_TAP, waiting.outcome)
        assertNull(waiting.objectiveId, "nothing started without the tap")
        val cleared = w.notices.cleared
        assertTrue(w.assembly.waitingRoutines.dismissWaiting(routine.id, waiting.id))
        runCurrent()
        assertTrue(w.notices.cleared > cleared, "nothing waits: the notice is taken away")
    }
}
