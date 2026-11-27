package com.aicontrolcenter.app.journeys

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TeamModel
import com.aicontrolcenter.app.testing.teamOnCloud
import com.aicontrolcenter.app.ui.tasks.costLineOf
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.core.usage.WorkKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * INT3-1: an objective's "model answers" are counted as today's limits count them (DailyUse.counted). A call the daily
 * limit refused before sending anything, and a call that ended on "slow down", are not answers, and the privacy line
 * counts only requests that left the phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class CountedAnswersJourneyTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun texts(): List<String> =
        compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }

    @Test
    fun `limit 2, the objective pauses, Resume pauses it again, and the header says 2 model answers with no In all`() {
        val model = TeamModel()
        val robot = AppRobot(compose).given {
            teamOnCloud(model)
            settings.update { it.copy(maxModelCallsPerDay = 2) }
        }.launch().also { this.robot = it }
        val app = robot.app
        val objective = runBlocking { app.graph.orchestrator.startObjective("Research offline note apps, compare the best three, and write a recommendation") }
        robot.waitUntil { runBlocking { app.tasks.get(objective.id)?.status } == TaskStatus.PAUSED }
        app.graph.requestObjective(objective.id)
        robot.waitUntilShown("Resume").tap("Resume")
        robot.waitUntil { runBlocking { app.tasks.get(objective.id)?.status } == TaskStatus.PAUSED }
        compose.waitForIdle()

        val today = runBlocking { app.graph.todayUsage.now().use.calls }
        assertEquals(2, today)
        assertEquals(2, model.seen.size, "two requests left the phone")
        val work = runBlocking { app.usage.workUsage(WorkKind.OBJECTIVE, objective.id.value) }
        assertEquals(today, work.sumOf { it.calls }, "the objective's answers are the answers today's limits counted")
        robot.waitUntil { texts().any { "2 model answers" in it } }
        val shown = texts()
        assertTrue(shown.none { "3 model answers" in it || "4 model answers" in it }, "$shown")
        assertTrue(shown.none { "In all" in it }, "no claim that answers came from off the phone beyond the log: $shown")
    }

    @Test
    fun `a call ended by slow down is not a model answer on the objective line`() {
        val robot = AppRobot(compose).launch().also { this.robot = it }
        val root = TaskId("obj_slow")
        runBlocking {
            robot.app.usage.record(
                ModelCallSample(
                    at = robot.app.clock.current, modelId = ModelId("m_cloud"), modelLabel = "Cloud model", providerId = ProviderId("p_cloud"),
                    providerLabel = "Cloud", locality = Locality.CLOUD, taskId = TaskId("tsk_slow"), rootTaskId = root,
                    source = UsageSource.OBJECTIVE, outcome = CallOutcome.FAILED, errorClass = ErrorClass.RATE_LIMITED,
                    inputTokens = null, outputTokens = null, answerMs = null, rateLimitedWaits = 1,
                ),
            )
        }
        assertEquals(0, runBlocking { robot.app.graph.todayUsage.now().use.calls })
        val rows = runBlocking { robot.app.usage.workUsage(WorkKind.OBJECTIVE, root.value) }
        assertEquals(0, rows.sumOf { it.calls })
        assertNull(costLineOf(rows, emptyMap()), "nothing answered and nothing was used: no cost line")
    }
}
