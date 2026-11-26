package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TeamModel
import com.aicontrolcenter.app.testing.teamOnCloud
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Goal 2 ("set a daily limit"), rule 6, Q2 (WP10a): with a low limit an objective pauses instead of failing; the
 * Dashboard says so and leads to it; the owner raises the limit in Settings › Daily limits and resumes the objective,
 * which then finishes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class DailyLimitJourneyTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    @Test
    fun `a reached limit pauses the objective, and raising it lets the owner resume it to the end`() {
        val model = TeamModel()
        val robot = AppRobot(compose).given {
            teamOnCloud(model)
            settings.update { it.copy(maxModelCallsPerDay = 2) }
        }.launch().also { this.robot = it }

        robot.openSection("Tasks")
            .tap("New objective")
            .typeIn("Objective", OBJECTIVE)
            .scrollTo("Start objective")
            .tap("Start objective")
            .waitUntil { runBlocking { robot.app.tasks.observeObjectives().first().singleOrNull()?.status } == TaskStatus.PAUSED }
        val paused = runBlocking { robot.app.tasks.observeObjectives().first().single() }
        assertEquals(DailyLimits.PAUSED_TEXT, paused.statusDetail, "paused, not failed")
        assertEquals(2, runBlocking { robot.app.graph.todayUsage.now().use.calls }, "the limit stopped the third call before it was sent")
        val requestsAtPause = model.seen.size

        // The Dashboard says what happened and leads to the objective and to the limits.
        robot.back().openSection("Dashboard")
            .assertShown("Paused by today's limit: ${paused.title}")
            .assertShown("Today's limit is reached")
            .assertShown("Today's limit of 2 model answers is reached.")

        // Settings › Daily limits: raise the limit.
        robot.openSectionViaMore("Settings")
            .scrollTo("Change daily limits")
            .tap("Change daily limits")
            .assertShown("Model answers a day")
            .assertShown(DAILY_LIMIT_WORDS)
            .type("Model answers a day", "50")
            .tap("Save limits")
            .waitUntilShown("Saved. Paused objectives do not go on by themselves: open each one and tap Resume.")
        assertEquals(50, runBlocking { robot.app.settings.get().maxModelCallsPerDay })
        assertEquals(requestsAtPause, model.seen.size, "raising the limit starts nothing by itself")

        // Back on the Dashboard: the item opens the objective, where Resume finishes the work.
        robot.back()
            .openSection("Dashboard")
            .tapItemContaining("Paused by today's limit")
            .tap("Resume")
            .waitUntil { runBlocking { robot.app.tasks.observeObjectives().first().single().status } == TaskStatus.COMPLETED }
        robot.back().openSection("Dashboard").assertNotShown("Paused by today's limit")
    }

    private companion object {
        const val OBJECTIVE = "Research offline note apps, compare the best three, and write a recommendation"
        const val DAILY_LIMIT_WORDS = "Objectives pause instead of failing. When a limit is reached, running objectives pause."
    }
}
