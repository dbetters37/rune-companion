package com.aicontrolcenter.app.ui.tasks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.DAY_MS
import com.aicontrolcenter.app.testing.TeamModel
import com.aicontrolcenter.app.testing.teamOn
import com.aicontrolcenter.app.testing.teamOnCloud
import com.aicontrolcenter.app.ui.settings.SettingsViewModel
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.tasks.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * INT3-2: an objective that sent requests to a cloud model never says "Nothing has left this phone", also once the log
 * kept its usual 30 days and the owner cleared the usage history or the log. When the app cannot know any more, it says
 * so plainly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ObjectivePrivacyRecordsTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun privacyLines(): List<String> =
        compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
            .filter { "this phone" in it || "can no longer be shown" in it }

    private fun cloudObjective(): Pair<AppRobot, com.aicontrolcenter.core.TaskId> {
        val model = TeamModel()
        val robot = AppRobot(compose).given { teamOnCloud(model) }.launch().also { this.robot = it }
        val app = robot.app
        val objective = runBlocking { app.graph.orchestrator.startObjective("Research offline note apps, compare the best three, and write a recommendation") }
        robot.waitUntil(30_000) { runBlocking { app.tasks.get(objective.id)?.status } == TaskStatus.COMPLETED }
        assertTrue(model.seen.isNotEmpty(), "the cloud model was asked")
        return robot to objective.id
    }

    @Test
    fun `after the log kept its 30 days and the usage history was cleared, the line says the records were cleared`() {
        val (robot, objective) = cloudObjective()
        val app = robot.app
        app.clock.current += 31 * DAY_MS
        runBlocking {
            app.activityLog.prune(app.clock.current - 30 * DAY_MS)
            app.graph.ownerUsage.clearHistory()
        }
        app.graph.requestObjective(objective)
        robot.waitUntilShown("Team view")
        val lines = privacyLines()
        assertTrue(lines.none { it.startsWith("Nothing has left this phone") }, "never a false privacy claim: $lines")
        assertTrue(EgressTexts.RECORDS_CLEARED in lines, "says plainly it can no longer be shown: $lines")
    }

    @Test
    fun `after the owner cleared the log and the usage history, the line says the records were cleared`() {
        val (robot, objective) = cloudObjective()
        val app = robot.app
        app.clock.current += 2 * DAY_MS
        runBlocking {
            app.activityLog.clear()
            app.activityLog.record(
                ActivityEvent(
                    id = EventId("evt_cleared"), at = app.clock.current, type = ActivityType.USER_ACTION,
                    title = SettingsViewModel.LOG_CLEARED, detail = "All earlier events were deleted at your request.",
                ),
            )
            app.graph.ownerUsage.clearHistory()
        }
        app.graph.requestObjective(objective)
        robot.waitUntilShown("Team view")
        val lines = privacyLines()
        assertTrue(lines.none { it.startsWith("Nothing has left this phone") }, "never a false privacy claim: $lines")
        assertTrue(EgressTexts.RECORDS_CLEARED in lines, "$lines")
    }

    /** The owner sets the log to 7 days (the app prunes at once), then to [laterDays], then clears the usage history. */
    private fun shortenedThenLengthened(laterDays: Int) {
        val (robot, objective) = cloudObjective()
        val app = robot.app
        app.clock.current += 10 * DAY_MS
        runBlocking {
            app.settings.update { it.copy(activityRetentionDays = 7) }
            app.activityLog.prune(app.clock.current - 7 * DAY_MS)
            app.settings.update { it.copy(activityRetentionDays = laterDays) }
            app.graph.ownerUsage.clearHistory()
        }
        app.graph.requestObjective(objective)
        robot.waitUntilShown("Team view")
        val lines = privacyLines()
        assertTrue(lines.none { it.startsWith("Nothing has left this phone") }, "never a false privacy claim: $lines")
        assertTrue(EgressTexts.RECORDS_CLEARED in lines, "$lines")
    }

    @Test
    fun `after the log was set to 7 days and back to 30, and the usage history was cleared, the line says the records were cleared`() =
        shortenedThenLengthened(laterDays = 30)

    @Test
    fun `after the log was set to 7 days and then to keep everything, and the usage history was cleared, the line says so too`() =
        shortenedThenLengthened(laterDays = 0)

    @Test
    fun `an objective whose records are whole still says plainly that nothing left the phone`() {
        val model = TeamModel()
        val robot = AppRobot(compose).given { teamOn(model) }.launch().also { this.robot = it }
        val app = robot.app
        val objective = runBlocking { app.graph.orchestrator.startObjective("Research offline note apps, compare the best three, and write a recommendation") }
        robot.waitUntil(30_000) { runBlocking { app.tasks.get(objective.id)?.status } == TaskStatus.COMPLETED }
        app.clock.current += 40 * DAY_MS
        runBlocking { app.settings.update { it.copy(activityRetentionDays = 0) } }
        app.graph.requestObjective(objective.id)
        robot.waitUntilShown("Team view")
        val lines = privacyLines()
        assertTrue("Nothing has left this phone for this objective." in lines, "$lines")
    }

    @Test
    fun `the plain words for each case`() {
        assertEquals("Nothing has left this phone for this objective.", egressWords(EgressUi()))
        assertEquals(EgressTexts.RECORDS_CLEARED, egressWords(EgressUi(recordsCleared = true)))
        assertEquals("Older records were cleared, so this can no longer be shown.", EgressTexts.RECORDS_CLEARED)
    }
}
