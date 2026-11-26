package com.aicontrolcenter.app.routines

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.routines.RoutineRun
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.framework.routines.WaitingRoutine
import com.aicontrolcenter.framework.routines.WaitingRoutines
import com.aicontrolcenter.testkit.FakeClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The in-app "Routine '<title>' is waiting to start" banner (security review of wave 1, F12, second review): it shows a
 * run waiting for a tap, Start asks once more, and only the confirmation starts exactly that run; nothing starts without
 * the tap, Cancel starts nothing, and "Not now" puts the run aside.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class WaitingRoutinesBannerTest {
    @get:Rule val compose = createComposeRule()

    private class FakeWaiting : WaitingRoutines {
        val waiting = MutableStateFlow<List<WaitingRoutine>>(emptyList())
        val started = CopyOnWriteArrayList<Pair<RoutineId, RoutineRunId>>()
        val dismissed = CopyOnWriteArrayList<RoutineRunId>()
        var catchUps = 0

        override fun observeWaiting(): Flow<List<WaitingRoutine>> = waiting

        override suspend fun catchUp() {
            catchUps++
        }

        override suspend fun startWaiting(routineId: RoutineId, runId: RoutineRunId): OwnerResult<RoutineRun> {
            started += routineId to runId
            val run = waiting.value.single { it.runId == runId }
            waiting.update { list -> list.filterNot { it.runId == runId } }
            return OwnerResult.Done(RoutineRun(runId, routineId, run.dueAt, RoutineRunOutcome.STARTED, startedAt = run.dueAt + 1, objectiveId = TaskId("tsk_1")))
        }

        override suspend fun dismissWaiting(routineId: RoutineId, runId: RoutineRunId): Boolean {
            dismissed += runId
            waiting.update { list -> list.filterNot { it.runId == runId } }
            return true
        }
    }

    private val dueAt = 1_794_657_600_000L

    private fun run(n: Int, title: String) = WaitingRoutine(
        RoutineId("rtn_$n"), RoutineRunId("rrn_$n"), title, "Summarise the news in five lines.", dueAt, allowScreenActions = false,
    )

    @Test
    fun `the banner shows a waiting run, Start asks once more, and only the confirmation starts exactly that run`() {
        val fake = FakeWaiting()
        fake.waiting.value = listOf(run(1, "Morning news"), run(2, "Evening digest"))
        val viewModel = WaitingRoutinesViewModel(fake, FakeClock(dueAt + 600_000)) { ZoneOffset.UTC }
        compose.setContent { WaitingRoutinesBanner(viewModel) }
        compose.waitForIdle()
        assertEquals(1, fake.catchUps, "opening it looks for due times that passed")
        compose.onNodeWithText("Routine \"Morning news\" is waiting to start").assertExists()
        compose.onNodeWithText("Routine \"Evening digest\" is waiting to start").assertExists()
        assertTrue(fake.started.isEmpty(), "nothing starts without the tap")

        // Start on the second asks once more; Cancel starts nothing.
        compose.onAllNodesWithText("Start")[1].performClick()
        compose.onNodeWithText("Start \"Evening digest\" now?").assertExists()
        compose.onNodeWithText("It may not use other apps' screens", substring = true).assertExists()
        assertTrue(fake.started.isEmpty(), "the first tap only asks")
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodesWithText("Start \"Evening digest\" now?").assertCountEquals(0)
        assertTrue(fake.started.isEmpty())

        // Confirmed: exactly that run starts, once.
        compose.onAllNodesWithText("Start")[1].performClick()
        compose.onNodeWithText("Start now").performClick()
        compose.waitForIdle()
        assertEquals(listOf(RoutineId("rtn_2") to RoutineRunId("rrn_2")), fake.started.toList())
        compose.onNodeWithText("Started: the team is working on \"Evening digest\".").assertExists()
        compose.onAllNodesWithText("Routine \"Evening digest\" is waiting to start").assertCountEquals(0)

        // "Not now" puts the other aside without starting it.
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        assertEquals(listOf(RoutineRunId("rrn_1")), fake.dismissed.toList())
        assertEquals(1, fake.started.size)
        compose.onAllNodesWithText("Routine \"Morning news\" is waiting to start").assertCountEquals(0)
    }

    @Test
    fun `a refused start says why and the owner's tap started nothing else`() {
        val fake = object : WaitingRoutines by FakeWaiting() {
            val flow = MutableStateFlow(listOf(run(1, "Morning news")))
            override fun observeWaiting(): Flow<List<WaitingRoutine>> = flow

            override suspend fun startWaiting(routineId: RoutineId, runId: RoutineRunId): OwnerResult<RoutineRun> {
                flow.value = emptyList()
                return OwnerResult.Refused("Today's limit of 200 model answers is reached. It starts again at midnight, or you can raise it in Settings › Daily limits.")
            }
        }
        val viewModel = WaitingRoutinesViewModel(fake, FakeClock(dueAt)) { ZoneOffset.UTC }
        compose.setContent { WaitingRoutinesBanner(viewModel) }
        compose.onNodeWithText("Start").performClick()
        compose.onNodeWithText("Start now").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Today's limit of 200 model answers is reached", substring = true).assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.onAllNodesWithText("Today's limit of 200 model answers is reached", substring = true).assertCountEquals(0)
    }
}
