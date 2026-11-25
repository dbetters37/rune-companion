package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.health.SafeStartReason
import com.aicontrolcenter.app.health.SafeStartState
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineDraft
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.framework.routines.RoutineRunner
import com.aicontrolcenter.testkit.contract.UsageSamples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Wave 3, part 3a (WP8): what the composition root now hands the owner's screens, and the two routine rules it adds:
 * the owner opening the app turns passed due times into runs waiting for a tap (WP17's catch-up), and in safe start a
 * routine due on time waits for the tap instead of starting from its alarm (rule 10).
 */
class Wave3WiringTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun draft() = RoutineDraft(
        title = "Morning news", objectiveText = "Summarise the news in five lines.", options = ObjectiveOptions(reviewResults = false),
        schedule = RoutineSchedule.Daily(7 * 60), approvalTimeoutMinutes = null, allowScreenActions = false,
    )

    private suspend fun TestScope.routineOn(app: TestApp): Routine {
        app.settings.update { it.copy(routinesEnabled = true) }
        runCurrent()
        return assertIs<OwnerResult.Done<Routine>>(app.graph.ownerRoutines!!.create(draft())).value.also { runCurrent() }
    }

    @Test
    fun `the screens' read ports are the ones the stores and wave-2 packages provide`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val graph = app.graph
        assertSame(app.safeStart, graph.safeStart)
        assertSame(graph.routinesAssembly.waitingRoutines, graph.waitingRoutines)
        assertNotNull(graph.pickUpImports)
        assertEquals(emptyList(), graph.pickUpImports!!.observeImports(PickUpRuleId("pkr_none"), 10).first())
        assertNull(graph.screenSwitchedOffSentence.value, "nothing to explain until Android switched screen control off")
        assertNull(graph.ownerBackup, "the test app has no backup file")
        // One owner zone for "today": the graph reads the test app's, which the usage store reads too.
        assertEquals(ZoneOffset.UTC, graph.zone())
        app.zone = ZoneId.of("Europe/Berlin")
        assertEquals(ZoneId.of("Europe/Berlin"), graph.zone())
        // Today's use counts what the limiter counts.
        app.usage.record(UsageSamples.call(app.clock.current))
        assertEquals(1, graph.todayUsage.now().use.calls)
        assertEquals(app.settings.get().dailyLimits, graph.todayUsage.now().limits)
    }

    @Test
    fun `the owner opening the app turns a passed due time into a run waiting for a tap, and starts nothing`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val routine = routineOn(app)
        app.clock.current = routine.nextRunAt!! + 5 * 60_000
        app.graph.onAppForegrounded()
        runCurrent()
        val run = app.routineStore.runs(routine.id, 10).single()
        assertEquals(RoutineRunOutcome.WAITING_FOR_TAP, run.outcome)
        assertEquals(run.id, app.graph.waitingRoutines.observeWaiting().first().single().runId)
        assertTrue(app.tasks.withStatus(com.aicontrolcenter.core.tasks.TaskStatus.entries.toSet()).isEmpty(), "nothing started")
    }

    @Test
    fun `in safe start a routine due on time waits for the owner's tap`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val routine = routineOn(app)
        app.safeStart.state.value = SafeStartState(on = true, reason = SafeStartReason.CRASHES)
        app.clock.current = routine.nextRunAt!! + 60_000
        app.graph.routinesAssembly.runDue()
        runCurrent()
        val run = app.routineStore.runs(routine.id, 10).single()
        assertEquals(RoutineRunOutcome.WAITING_FOR_TAP, run.outcome)
        assertEquals(RoutineRunner.SAFE_START_NOTE, run.note)
        assertTrue(app.tasks.withStatus(com.aicontrolcenter.core.tasks.TaskStatus.entries.toSet()).isEmpty(), "nothing started by itself")
    }
}
