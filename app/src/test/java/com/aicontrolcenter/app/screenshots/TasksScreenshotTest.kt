package com.aicontrolcenter.app.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.SampleObjectives
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.populateWithObjectives
import com.aicontrolcenter.app.testing.teamAtWork
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of every Tasks screen over realistic objectives (one in full swing, one finished with
 * its document, one paused, one interrupted), and of the dashboard while the team works.
 * Recorded into `build/outputs/roborazzi`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TasksScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    private fun app(dark: Boolean = true, populate: suspend TestApp.() -> Unit = { populateWithObjectives() }): AppRobot =
        AppRobot(compose).given(populate).launch(dark).also { robot = it }

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    private fun shootScreen(name: String) = captureScreenRoboImage("build/outputs/roborazzi/$name.png")

    private fun AppRobot.openNotes() = openSection("Tasks").tapItemContaining(SampleObjectives.NOTES_TITLE)

    // --- The list ---

    @Test
    fun objectives() {
        val robot = app().openSection("Tasks")
        shoot("p4_tasks_1_active")
        robot.tap("Finished · 1")
        shoot("p4_tasks_2_finished")
    }

    @Test
    fun objectivesNotificationsOff() {
        app { populateWithObjectives(); notifications.set(false) }.openSection("Tasks")
        shoot("p4_tasks_3_notifications_off")
    }

    @Test
    fun objectivesEmpty() {
        app { graph.start().join() }.openSection("Tasks")
        shoot("p4_tasks_4_empty")
    }

    // --- A new objective ---

    @Test
    fun newObjective() {
        val robot = app().openSection("Tasks").tapIcon("New objective")
        shoot("p4_new_1_empty")
        robot.typeIn("Objective", "Research note-taking apps that work fully offline, compare the best three for privacy and sync, and write a one-page recommendation.")
            .scrollTo("Where the work goes".uppercase())
        shoot("p4_new_2_where")
        robot.scrollTo("Start objective")
        shoot("p4_new_3_bottom")
    }

    @Test
    fun newObjectiveNotificationExplained() {
        val robot = app { populateWithObjectives(); notifications.set(false); notifications.askable = true }.openSection("Tasks").tapIcon("New objective")
        robot.typeIn("Objective", "Summarise the reviews of the three apps").scrollTo("Start objective").tap("Start objective")
        shootScreen("p4_new_4_notification_explained")
    }

    // --- One objective ---

    @Test
    fun objectivePlan() {
        val robot = app().openNotes()
        shoot("p4_objective_1_header")
        robot.tap("Plan").scrollListTo("Draft a plan to move existing notes")
        shoot("p4_objective_2_plan")
        robot.scrollListTo("LIMITS")
        shoot("p4_objective_3_limits")
    }

    @Test
    fun objectiveConversation() {
        val robot = app().openNotes().tap("Conversation · 14")
        shoot("p4_objective_4_conversation")
        robot.tap("Help · 2")
        shoot("p4_objective_5_conversation_help")
    }

    @Test
    fun objectiveLog() {
        app().openNotes().tap("Log")
        shoot("p4_objective_6_log")
    }

    @Test
    fun objectiveResult() {
        val robot = app().openSection("Tasks").tap("Finished · 1").tapItemContaining(SampleObjectives.LISBON_TITLE).tap("Result")
        shoot("p4_objective_7_document")
        robot.scrollListTo("Getting around")
        shoot("p4_objective_8_document_end")
    }

    @Test
    fun objectiveLight() {
        app(dark = false).openNotes()
        shoot("p4_objective_light_1_header")
    }

    @Test
    fun objectiveResultLight() {
        app(dark = false).openSection("Tasks").tap("Finished · 1").tapItemContaining(SampleObjectives.LISBON_TITLE).tap("Result")
        shoot("p4_objective_light_2_document")
    }

    @Test
    @Config(qualifiers = NARROW)
    fun objectiveNarrow() {
        val robot = app().openNotes()
        shoot("p4_objective_narrow_1_header")
        robot.tap("Plan").scrollListTo("Compare Obsidian")
        shoot("p4_objective_narrow_2_plan")
    }

    @Test
    fun objectiveCancelConfirmation() {
        app().openNotes().tap("Cancel")
        shootScreen("p4_objective_9_cancel")
    }

    // --- One task ---

    @Test
    fun taskDetail() {
        val robot = app().openNotes().tap("Plan").tapItemContaining("Find offline-first note apps")
        shoot("p4_task_1_top")
        robot.scrollListTo("RESULT")
        shoot("p4_task_2_result")
        robot.scrollListTo("Brief written by the app")
        shoot("p4_task_3_transcript")
        robot.scrollListTo("Analyst finished")
        shoot("p4_task_4_transcript_end")
    }

    @Test
    fun taskInRevision() {
        val robot = app().openNotes().tap("Plan").tapItemContaining(SampleObjectives.COMPARE_TITLE)
        shoot("p4_task_5_revising")
        robot.scrollListTo("REVIEWS")
        shoot("p4_task_6_reviews")
    }

    // --- The team at work, elsewhere in the app ---

    @Test
    fun dashboardWhileTheTeamWorks() {
        val robot = app { teamAtWork() }
        robot.waitUntilShown("Compare the three best apps")
        shoot("p4_dashboard_1_working")
        robot.scrollTo("Pinch to zoom")
        shoot("p4_dashboard_2_network")
    }

    @Test
    fun agentRecentTasks() {
        val robot = app().openSection("Agents").tapItemContaining("Investigates subjects")
        robot.scrollToEnd()
        shoot("p4_agent_recent_tasks")
    }
}
