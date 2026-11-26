package com.aicontrolcenter.app.screenshots

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.health.SafeStartReason
import com.aicontrolcenter.app.health.SafeStartState
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.populateWithModels
import com.aicontrolcenter.app.testing.recordSampleUsage
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.StandbyBucket
import com.aicontrolcenter.core.routines.RoutineDraft
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.testkit.Samples
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
 * Pictures of WP10a's screens (wave 3): Activity › Usage and cost, the log with its search and "Show older", Settings ›
 * Daily limits and Usage history, and the Dashboard's Today tile, Getting started, the phone's items, safe start and a
 * routine waiting for a tap. Dark, light, wide (900 dp), 360 dp and font scale 2.0. Review material, recorded into
 * `build/outputs/roborazzi/` (`ScreenshotTest`'s KDoc), not golden files.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class UsageScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun app(dark: Boolean = true, populate: suspend TestApp.() -> Unit = { withUsage() }): AppRobot =
        AppRobot(compose).given(populate).launch(dark).also { robot = it }

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/p6_wp10a_$name.png")

    private fun shootScreen(name: String) = captureScreenRoboImage("build/outputs/roborazzi/p6_wp10a_$name.png")

    private suspend fun TestApp.withUsage() {
        populateWithModels()
        recordSampleUsage()
    }

    private fun AppRobot.usageTab() = openSection("Activity").tap("Usage and cost")

    private fun scrollList(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher)
        compose.waitForIdle()
    }

    // --- Activity › Usage and cost ---

    @Test
    fun usageToday() {
        app().usageTab()
        shoot("usage_1_today")
        scrollList(hasText("Grok 4"))
        shoot("usage_2_models")
        scrollList(hasContentDescription("By agent", substring = true))
        shoot("usage_3_agents")
    }

    @Test
    fun usageTodayLight() {
        app(dark = false).usageTab()
        shoot("usage_today_light")
    }

    @Test
    fun usageMonthWithWaits() {
        val robot = app {
            withUsage()
            val agent = agents.getAll().first().id
            ownerRequestStore.create(com.aicontrolcenter.app.testing.sampleApproval(agent, id = "req_w1", createdAt = TestApp.START_TIME - 2 * 60_000L))
        }.usageTab()
        robot.tap("This month")
        shoot("usage_month")
    }

    @Test
    @Config(qualifiers = WIDE_WP10A)
    fun usageWide() {
        app().usageTab()
        shoot("usage_wide")
    }

    @Test
    @Config(qualifiers = NARROW_WP10A)
    fun usage360() {
        app().usageTab()
        shoot("usage_360")
    }

    @Test
    @Config(qualifiers = NARROW_WP10A, fontScale = 2.0f)
    fun usage360LargeFont() {
        app().usageTab()
        shoot("usage_360_font2")
    }

    @Test
    fun usageEmpty() {
        app(populate = { populateWithModels() }).usageTab()
        shoot("usage_empty")
    }

    // --- Activity › Log ---

    @Test
    fun logSearchAndOlder() {
        val robot = app {
            populateWithModels()
            for (i in 0 until 120) activityLog.record(Samples.event("old_$i", at = TestApp.START_TIME - (121 - i) * 60_000L, egress = i % 3 == 0))
            graph.activityRecorder.record(ActivityType.MODEL_CALL, "Model call", "Grok 4 via xAI: 6 messages, 2,400 tokens in, 700 out")
        }.openSection("Activity")
        shoot("log_1_newest")
        val search = compose.onNode(hasSetTextAction() and hasText("Search the log", substring = true))
        search.performTextInput("grok 4 via")
        compose.waitForIdle()
        shoot("log_2_search")
        search.performTextClearance()
        compose.waitForIdle()
        scrollList(hasText("Show older events"))
        shoot("log_3_show_older")
        robot.tap("Show older events")
        robot.given { graph.activityRecorder.record(ActivityType.USER_ACTION, "Objective started", "Compare note apps") }
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Show the newest"))
        compose.waitForIdle()
        shoot("log_4_held_with_newer")
    }

    @Test
    @Config(qualifiers = NARROW_WP10A)
    fun logAgentFilter360() {
        app().usageTab()
        scrollList(hasContentDescription("By agent", substring = true))
        compose.onAllNodes(hasContentDescription("Researcher", substring = true) and hasClickAction()).onFirst().performSemanticsActionClick()
        shoot("log_agent_filter_360")
    }

    // --- Settings ---

    @Test
    fun settingsSections() {
        val robot = app().openSectionViaMore("Settings")
        robot.scrollTo("Change daily limits")
        shoot("settings_1_daily_limits")
        robot.scrollTo("Clear usage history")
        shoot("settings_2_usage_history")
        robot.tap("Clear usage history")
        shootScreen("settings_3_clear_confirm")
    }

    @Test
    fun settingsSectionsLight() {
        app(dark = false).openSectionViaMore("Settings").scrollTo("Change daily limits")
        shoot("settings_daily_limits_light")
    }

    @Test
    fun dailyLimitsScreen() {
        val robot = app().openSectionViaMore("Settings").scrollTo("Change daily limits").tap("Change daily limits")
        shoot("daily_limits_1_top")
        robot.scrollTo("Limits stop work")
        shoot("daily_limits_2_bottom")
        val answers = compose.onNode(hasSetTextAction() and hasText("Model answers a day", substring = true))
        answers.performTextClearance()
        compose.onNode(hasSetTextAction() and hasText("Money a day (estimated)", substring = true)).performTextClearance()
        robot.tap("Save limits")
        robot.scrollTo("Model answers a day")
        shoot("daily_limits_3_errors")
    }

    @Test
    fun dailyLimitsLight() {
        app(dark = false).openSectionViaMore("Settings").scrollTo("Change daily limits").tap("Change daily limits")
        shoot("daily_limits_light")
    }

    @Test
    @Config(qualifiers = NARROW_WP10A)
    fun dailyLimitsReached360() {
        app {
            withUsage()
            settings.update { it.copy(maxModelCallsPerDay = 20) }
        }.openSectionViaMore("Settings").scrollTo("Change daily limits").tap("Change daily limits")
        shoot("daily_limits_reached_360")
    }

    @Test
    @Config(qualifiers = WIDE_WP10A)
    fun dailyLimitsWide() {
        app().openSection("Settings").scrollTo("Change daily limits").tap("Change daily limits")
        shoot("daily_limits_wide")
    }

    @Test
    @Config(qualifiers = NARROW_WP10A, fontScale = 2.0f)
    fun dailyLimits360LargeFont() {
        app().scrollTo("Daily limits").tap("Daily limits")
        shoot("daily_limits_360_font2")
    }

    // --- Dashboard ---

    @Test
    fun dashboardGettingStarted() {
        val robot = app(populate = { graph.start().join() })
        shoot("dashboard_1_getting_started")
        robot.scrollTo("Done, hide this")
        shoot("dashboard_2_getting_started_end")
    }

    @Test
    fun dashboardGettingStartedLight() {
        app(dark = false, populate = { graph.start().join() })
        shoot("dashboard_getting_started_light")
    }

    @Test
    @Config(qualifiers = NARROW_WP10A, fontScale = 2.0f)
    fun dashboardGettingStarted360LargeFont() {
        app(populate = { graph.start().join() })
        shoot("dashboard_getting_started_360_font2")
    }

    @Test
    fun dashboardTodayAndPhone() {
        val robot = app {
            withUsage()
            settings.update { it.copy(onboardingCompleted = true, maxModelCallsPerDay = 20) }
            backgroundHealth.current.value = BackgroundState(notificationsAllowed = true, batteryOptimised = true, backgroundRestricted = false, standbyBucket = StandbyBucket.RARE)
            tasks.upsert(pausedObjective())
        }
        robot.scrollTo("your provider's bill is what counts")
        shoot("dashboard_today_and_phone")
        robot.scrollTo("Paused by today's limit")
        shoot("dashboard_phone_items")
    }

    @Test
    fun dashboardTodayLight() {
        val robot = app(dark = false) {
            withUsage()
            settings.update { it.copy(onboardingCompleted = true) }
        }
        robot.scrollTo("your provider's bill is what counts")
        shoot("dashboard_today_light")
    }

    @Test
    @Config(qualifiers = WIDE_WP10A)
    fun dashboardWide() {
        app {
            withUsage()
            settings.update { it.copy(onboardingCompleted = true) }
            backgroundHealth.current.value = BackgroundState(notificationsAllowed = false, batteryOptimised = true, backgroundRestricted = false, standbyBucket = null)
        }
        shoot("dashboard_wide")
    }

    @Test
    @Config(qualifiers = NARROW_WP10A)
    fun dashboardToday360() {
        app {
            withUsage()
            settings.update { it.copy(onboardingCompleted = true) }
        }.scrollTo("your provider's bill is what counts")
        shoot("dashboard_today_360")
    }

    @Test
    @Config(qualifiers = NARROW_WP10A, fontScale = 2.0f)
    fun dashboardToday360LargeFont() {
        app {
            withUsage()
            settings.update { it.copy(onboardingCompleted = true) }
        }.scrollTo("your provider's bill is what counts")
        shoot("dashboard_today_360_font2")
    }

    @Test
    fun dashboardSafeStartAndWaitingRoutine() {
        app {
            populateWithModels()
            settings.update { it.copy(routinesEnabled = true) }
            val routine = (graph.ownerRoutines!!.create(
                RoutineDraft(
                    title = "Morning news", objectiveText = "Summarise the news in five lines.",
                    options = ObjectiveOptions(reviewResults = false), schedule = RoutineSchedule.Daily(7 * 60),
                    approvalTimeoutMinutes = null, allowScreenActions = false,
                ),
            ) as OwnerResult.Done).value
            clock.current = routine.nextRunAt!! + 5 * 60_000
            graph.onAppForegrounded()
            safeStart.state.value = SafeStartState(on = true, reason = SafeStartReason.CRASHES)
        }
        compose.waitForIdle()
        shoot("dashboard_safe_start_and_waiting_routine")
    }

    @Test
    fun dashboardSafeStartLight() {
        app(dark = false) {
            populateWithModels()
            settings.update { it.copy(onboardingCompleted = true) }
            safeStart.state.value = SafeStartState(on = true, reason = SafeStartReason.STARTS_WITHOUT_OK)
        }
        shoot("dashboard_safe_start_light")
    }

    private fun pausedObjective() = TaskRecord(
        id = TaskId("obj_paused"), rootId = TaskId("obj_paused"), kind = TaskKind.OBJECTIVE,
        title = "Compare three offline note apps", instructions = "Compare three offline note apps.",
        requestedBy = TaskRequester.Owner, status = TaskStatus.PAUSED, statusDetail = DailyLimits.PAUSED_TEXT,
        createdAt = TestApp.START_TIME - 40 * 60_000L, updatedAt = TestApp.START_TIME - 5 * 60_000L,
    )

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performSemanticsActionClick() {
        performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
    }
}

/** The wide and the narrow window of the wave-3 pictures (WP10a's own names; WP11b's file has its own). */
private const val WIDE_WP10A = "w900dp-h600dp-xhdpi"
private const val NARROW_WP10A = "w360dp-h740dp-xhdpi"
