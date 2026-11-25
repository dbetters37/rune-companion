package com.aicontrolcenter.framework.routines

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineDraft
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.operator.BrokerFixture
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryRoutineRepository
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Security review of wave 1, F12: a routine's run starts its objective with `Routine.objectiveOptions()`, and the
 * permission broker applies them to every call of that objective: without the owner's tick, no screen tool is even
 * asked for; the routine's approval time-out is the requests' time-out.
 */
class RoutineRunReachesBrokerTest {
    private val utc = ZoneId.of("UTC")

    /** Runs [draft] as a routine due now, its objective stored as the fixture's objective (the calls' root). */
    private suspend fun TestScope.ranRoutine(draft: RoutineDraft): BrokerFixture {
        val f = BrokerFixture(this).setUp()
        f.runningTask()
        val clock = FakeClock(0)
        val store = InMemoryRoutineRepository()
        val ids = SequentialIdGenerator()
        val log = ActivityRecorder(InMemoryActivityLog(), ids, clock)
        val routine = assertIs<OwnerResult.Done<Routine>>(DefaultOwnerRoutines(store, log, clock, ids) { utc }.create(draft)).value
        val runner = RoutineRunner(
            routines = store, tasks = f.tasks, orchestrator = FakeRoutineOrchestrator(f.tasks, clock) { TaskId("tsk_root") },
            settings = InMemorySettingsRepository(FrameworkSettings(routinesEnabled = true, directorAgentId = AgentId("agt_director"))),
            dailyLimits = { null }, localOnly = { false }, gate = LocalMaintenanceGate(), activity = log, clock = RoutineClock(clock) { utc }, ids = ids,
        )
        clock.current = routine.nextRunAt!!
        assertEquals(RoutineRunOutcome.STARTED, runner.runDue().single().outcome)
        return f
    }

    private fun draft(screens: Boolean) = RoutineDraft(
        title = "Tidy the shop app", objectiveText = "Tidy the notes.", schedule = RoutineSchedule.Daily(7 * 60),
        approvalTimeoutMinutes = 90, allowScreenActions = screens,
    )

    @Test
    fun `a routine without the owner's tick may not use screens, and its requests wait as long as the routine says`() = runTest {
        val f = ranRoutine(draft(screens = false))
        val denied = assertIs<Authorization.Denied>(f.broker.authorize(f.tap, f.assessment(RiskLevel.MODIFYING, constraint = f.inNotes), f.context()))
        assertEquals(ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED, denied.reason)
        assertTrue(f.allRequests().isEmpty(), "nothing is put to the owner")
        // Any other permission is asked as for an objective the owner started, with the routine's time-out.
        val write = f.start(f.writeFolder, f.assessment(RiskLevel.MODIFYING))
        val request = f.pending()
        assertEquals(OperatorCapabilities.FOLDERS_WRITE, request.capability)
        assertEquals(90 * BrokerFixture.MINUTE, request.expiresAt - request.createdAt)
        write.cancel()
    }

    @Test
    fun `a routine the owner ticked asks for screens as an objective started by hand does`() = runTest {
        val f = ranRoutine(draft(screens = true))
        val call = f.start(f.tap, f.assessment(RiskLevel.MODIFYING, constraint = f.inNotes))
        assertEquals(OperatorCapabilities.SCREEN_OPERATE, f.pending().capability)
        call.cancel()
    }
}
