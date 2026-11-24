package com.aicontrolcenter.app.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.SampleObjectives
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.populateWithObjectives
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.samplePermission
import com.aicontrolcenter.core.tasks.TaskStatus
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
 * Pictures of the owner being asked: the waiting count in the shell (above the phone's bottom
 * bar, on the rail's Approvals item), the list, and the Approval screen for an action that is
 * hard to undo, whose file name hides a direction change (its answers, Deny leading, and the
 * second step before Approve), and for a permission asked inside a task (its answers and the
 * "Always allow…" step). PHONE, WIDE and NARROW, the answers in the light theme too. Also the
 * notification explanation and the "Notifications are off" warnings. Recorded into
 * `build/outputs/roborazzi`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ApprovalScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    private fun app(dark: Boolean = true): AppRobot = AppRobot(compose).given { waiting() }.launch(dark).also { robot = it }

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    private fun shootScreen(name: String) = captureScreenRoboImage("build/outputs/roborazzi/$name.png")

    /** The team at work on the notes objective, with two questions for the owner: one approval, one permission. */
    private suspend fun TestApp.waiting() {
        populateWithObjectives()
        val now = clock.current
        val operator = agentNamed("Operator").profile.id
        tasks.update(SampleObjectives.COMPARE) {
            // As the broker writes it, for every reader; the owner's screens say "your".
            it.copy(status = TaskStatus.WAITING_FOR_OWNER, statusDetail = "Waiting for the owner's permission: Read the web page “https://example.org/reviews/offline-notes”")
        }
        raise(sampleApproval(operator, createdAt = now, minutes = 240, delegationChain = listOf(director().profile.id), rootId = SampleObjectives.NOTES))
        raise(
            samplePermission(
                agentNamed("Analyst").profile.id, agentName = "Analyst", createdAt = now + 1, minutes = 240,
                taskId = SampleObjectives.COMPARE, rootId = SampleObjectives.NOTES,
                reason = "I need recent reviews to compare sync without an account. The apps' own pages do not say.",
            ),
        )
    }

    /** Scrolls to the end of the answers: the line under the last button. */
    private fun AppRobot.toAnswers() = scrollTo("If you do not answer within the time left")

    @Test
    fun phone() {
        val robot = app()
        shoot("p5_approvals_1_badge_phone")
        robot.tap("2 requests wait for your answer")
        shoot("p5_approvals_2_list_phone")
        robot.tapItemContaining("Delete 3 files")
        shoot("p5_approval_1_consequential_phone")
        robot.toAnswers()
        shoot("p5_approval_2_consequential_answer_phone")
        robot.tap("Approve: delete 3 files")
        shootScreen("p5_approval_2b_approve_confirm_phone")
        robot.tapInDialog("Back").back().tapItemContaining("Read the web page")
        shoot("p5_approval_3_permission_phone")
        robot.toAnswers()
        shoot("p5_approval_4_permission_answer_phone")
        robot.tap("Always allow…")
        shootScreen("p5_approval_5_always_confirm_phone")
    }

    /**
     * Review 5, A2: the explanation shown before Android's own notification question, with the
     * phone operator on and nothing asked yet, and the warning on the Approvals list and in
     * Tools while notifications are off.
     */
    @Test
    fun notificationsOff() {
        val robot = AppRobot(compose).given {
            waiting()
            settings.update { it.copy(operatorEnabled = true) }
            notifications.set(false)
            notifications.askableForOperator = true
        }.launch().also { robot = it }
        robot.waitUntilShown("Be told when an agent waits for you?")
        shootScreen("p5_notifications_1_explain_phone")
        robot.tapInDialog("Not now").tap("2 requests wait for your answer")
        shoot("p5_notifications_2_approvals_phone")
        robot.back().openSectionViaMore("Tools")
        shoot("p5_notifications_3_tools_phone")
    }

    @Test
    fun phoneLight() {
        val robot = app(dark = false).tap("2 requests wait for your answer").tapItemContaining("Delete 3 files")
        shoot("p5_approval_light_1_consequential_phone")
        robot.toAnswers()
        shoot("p5_approval_light_2_consequential_answer_phone")
    }

    @Test
    @Config(qualifiers = WIDE)
    fun wide() {
        val robot = app()
        shoot("p5_approvals_1_badge_wide")
        robot.openSection("Approvals")
        shoot("p5_approvals_2_list_wide")
        robot.tapItemContaining("Delete 3 files")
        shoot("p5_approval_1_consequential_wide")
    }

    @Test
    @Config(qualifiers = NARROW)
    fun narrow() {
        val robot = app()
        shoot("p5_approvals_1_badge_narrow")
        robot.tap("2 requests wait for your answer").tapItemContaining("Delete 3 files").toAnswers()
        shoot("p5_approval_2_consequential_answer_narrow")
        robot.back().tapItemContaining("Read the web page")
        shoot("p5_approval_3_permission_narrow")
        robot.toAnswers()
        shoot("p5_approval_4_permission_answer_narrow")
    }

    @Test
    fun waitingTaskAndObjective() {
        val robot = app().openSection("Tasks").tapItemContaining(SampleObjectives.NOTES_TITLE)
        shoot("p5_objective_waiting_for_you")
        // The plan is the first tab; its rows follow the requests.
        robot.scrollListTo(SampleObjectives.COMPARE_TITLE).tapItemContaining(SampleObjectives.COMPARE_TITLE)
        shoot("p5_task_waiting_for_you")
    }

    @Test
    @Config(qualifiers = WIDE)
    fun nothingWaiting() {
        AppRobot(compose).given { populateWithObjectives() }.launch().also { robot = it }.openSection("Approvals")
        shoot("p5_approvals_3_empty_wide")
    }
}
