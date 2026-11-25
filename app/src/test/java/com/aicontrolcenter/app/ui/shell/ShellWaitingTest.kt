package com.aicontrolcenter.app.ui.shell

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.framework.routines.WaitingRoutine
import com.aicontrolcenter.testkit.contract.OperatorSamples
import com.aicontrolcenter.testkit.contract.QuestionSamples
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave 3 (WP8): the shell's "Waiting for you" counts approvals and agents' questions together and says which, and a
 * routine run waiting for the owner's tap opens its confirmation step once, never from an intent.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShellWaitingTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    @Test
    fun `the waiting line says how many requests and questions wait`() {
        assertEquals("1 request waits for your answer", waitingText(1))
        assertEquals("3 requests wait for your answer", waitingText(3))
        assertEquals("1 question waits for your answer", waitingText(1, questions = 1))
        assertEquals("2 questions wait for your answer", waitingText(2, questions = 2))
        assertEquals("2 requests and 1 question wait for your answer", waitingText(3, questions = 1))
        assertEquals("1 request and 1 question wait for your answer", waitingText(2, questions = 1))
    }

    @Test
    fun `the badge counts approvals and questions, and each waiting routine run is shown once`() = runTest(mainRule.dispatcher) {
        val app = com.aicontrolcenter.app.testing.TestApp(backgroundScope)
        val pending = MutableStateFlow<List<OwnerRequest>>(emptyList())
        val waiting = MutableStateFlow<List<WaitingRoutine>>(emptyList())
        val shell = ShellViewModel(app.graph.registryDirectory, pending, waiting)
        val state = shell.uiState.activeIn(backgroundScope)
        pending.value = listOf(
            OperatorSamples.request("req_a", kind = OwnerRequestKind.APPROVAL),
            OperatorSamples.request("req_p"),
            QuestionSamples.question("req_q"),
        )
        runCurrent()
        assertEquals(3, state.value.pendingRequests)
        assertEquals(1, state.value.pendingQuestions)
        assertNull(state.value.routineToShow)
        val run = WaitingRoutine(RoutineId("rtn_1"), RoutineRunId("run_1"), "Morning news", "Summarise the news.", 1_000, allowScreenActions = false)
        waiting.value = listOf(run)
        runCurrent()
        assertEquals(run.runId, state.value.routineToShow)
        shell.routineShown(run.runId)
        runCurrent()
        assertNull(state.value.routineToShow, "shown once")
        val second = run.copy(runId = RoutineRunId("run_2"))
        waiting.value = listOf(run, second)
        runCurrent()
        assertEquals(second.runId, state.value.routineToShow, "a new waiting run is shown too")
    }

    @Test
    fun `a waiting question shows in the strip with its own words`() {
        // As the questions tool raises one: a live call waits for it.
        val robot = AppRobot(compose).given { raise(QuestionSamples.question("req_q", at = clock.current, task = null)) }.launch()
        this.robot = robot
        robot.waitUntilShown("1 question waits for your answer")
        assertTrue(compose.onAllNodes(hasContentDescription("1 question waits for your answer. Review")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    @Config(qualifiers = "w900dp-h600dp-xhdpi")
    fun `the rail's item is called Waiting for you`() {
        val robot = AppRobot(compose).launch()
        this.robot = robot
        assertTrue(compose.onAllNodesWithText("Waiting for you").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasContentDescription("Waiting for you, nothing waits for your answer")).fetchSemanticsNodes().isNotEmpty())
    }
}
