package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolCategory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A routine's objective carries the routine's screen choice and approval time-out, and the broker
 * applies them (Phase 6, Appendix A; security review of wave 1, F12).
 */
class RoutineObjectiveBrokerTest {
    private suspend fun TestScope.fixture(options: ObjectiveOptions?): BrokerFixture {
        val f = BrokerFixture(this).setUp()
        f.tasks.upsert(
            TaskRecord(
                id = TaskId("tsk_root"), rootId = TaskId("tsk_root"), kind = TaskKind.OBJECTIVE, title = "Morning news", instructions = "Read the news.",
                requestedBy = TaskRequester.Owner, status = TaskStatus.RUNNING, createdAt = BrokerFixture.START, updatedAt = BrokerFixture.START, options = options,
            ),
        )
        f.runningTask()
        return f
    }

    private val readScreen = BrokerFixture.spec("screen.read", OperatorCapabilities.SCREEN_READ, RiskLevel.SAFE, category = ToolCategory.ACCESSIBILITY)

    @Test
    fun `a routine's objective without the owner's tick may not read or act on other apps' screens`() = runTest {
        val f = fixture(ObjectiveOptions(routineId = RoutineId("rtn_1"), screenActionsAllowed = false))
        f.agents.upsert(f.agent.copy(toolIds = f.agent.toolIds + readScreen.id))
        for (spec in listOf(f.tap, f.home, readScreen)) {
            val denied = assertIs<Authorization.Denied>(f.broker.authorize(spec, f.assessment(RiskLevel.MODIFYING, constraint = f.inNotes), f.context()))
            assertEquals(ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED, denied.reason)
        }
        assertTrue(f.allRequests().isEmpty(), "nothing is put to the owner")
        // Other tools of the same objective are decided as usual: a missing grant asks.
        val write = f.start(f.writeFolder, f.assessment(RiskLevel.MODIFYING))
        assertEquals(OperatorCapabilities.FOLDERS_WRITE, f.pending().capability)
        write.cancel()
    }

    @Test
    fun `options that name a routine but were not built by it, and an objective whose record is gone, allow no screens`() = runTest {
        // F12, fail closed: a wave-2 path that rebuilds options as ObjectiveOptions(routineId = ...) instead of copying them.
        assertEquals(false, ObjectiveOptions(routineId = RoutineId("rtn_1")).screenActionsAllowed)
        val rebuilt = fixture(ObjectiveOptions(routineId = RoutineId("rtn_1")))
        val denied = assertIs<Authorization.Denied>(rebuilt.broker.authorize(rebuilt.tap, rebuilt.assessment(RiskLevel.MODIFYING, constraint = rebuilt.inNotes), rebuilt.context()))
        assertEquals(ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED, denied.reason)
        // The objective's record cannot be read (deleted, or never stored): the call cannot learn the owner's choice, so no screens.
        val gone = fixture(ObjectiveOptions())
        val context = gone.context().copy(rootTaskId = TaskId("tsk_gone"))
        val refused = assertIs<Authorization.Denied>(gone.broker.authorize(gone.tap, gone.assessment(RiskLevel.MODIFYING, constraint = gone.inNotes), context))
        assertEquals(ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED, refused.reason)
        assertTrue(rebuilt.allRequests().isEmpty() && gone.allRequests().isEmpty(), "nothing is put to the owner")
    }

    @Test
    fun `objectives the owner starts by hand, ticked routines and chats keep screen tools`() = runTest {
        for (options in listOf(null, ObjectiveOptions(), ObjectiveOptions(routineId = RoutineId("rtn_1"), screenActionsAllowed = true))) {
            val f = fixture(options)
            val call = f.start(f.tap, f.assessment(RiskLevel.MODIFYING, constraint = f.inNotes))
            assertEquals(OperatorCapabilities.SCREEN_OPERATE, f.pending().capability, "asked as before ($options)")
            call.cancel()
        }
        val chat = fixture(ObjectiveOptions(screenActionsAllowed = false))
        val call = chat.start(chat.tap, chat.assessment(RiskLevel.MODIFYING, constraint = chat.inNotes), chat.context(task = null).copy(rootTaskId = null))
        assertEquals(OperatorCapabilities.SCREEN_OPERATE, chat.pending().capability, "a chat belongs to no objective")
        call.cancel()
    }

    @Test
    fun `a routine's approval time-out is the one its requests wait, within 1 to 240 minutes`() = runTest {
        for ((minutes, expected) in listOf(120 to 120L, null to 30L, 0 to 1L, 999 to 240L)) {
            val f = fixture(ObjectiveOptions(routineId = RoutineId("rtn_1"), approvalTimeoutMinutes = minutes))
            val call = f.start(f.writeFolder, f.assessment(RiskLevel.MODIFYING))
            val request = f.pending()
            assertEquals(expected * BrokerFixture.MINUTE, request.expiresAt - request.createdAt, "time-out $minutes")
            call.cancel()
        }
    }

    @Test
    fun `a routine starts its objective with its own screen choice and time-out`() {
        val routine = Routine(
            id = RoutineId("rtn_7"), title = "Morning news", objectiveText = "Read the news.", schedule = RoutineSchedule.Daily(8 * 60),
            approvalTimeoutMinutes = 90, createdAt = 1, updatedAt = 1,
        )
        assertEquals(ObjectiveOptions(routineId = RoutineId("rtn_7"), screenActionsAllowed = false, approvalTimeoutMinutes = 90), routine.objectiveOptions())
        assertEquals(true, routine.copy(allowScreenActions = true).objectiveOptions().screenActionsAllowed)
        assertEquals(true, ObjectiveOptions().screenActionsAllowed, "an objective started by hand")
    }
}
