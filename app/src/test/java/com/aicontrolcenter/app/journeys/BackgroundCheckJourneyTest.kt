package com.aicontrolcenter.app.journeys

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestBackgroundScreens
import com.aicontrolcenter.app.testing.showApp
import com.aicontrolcenter.app.testing.wp10bGraph
import com.aicontrolcenter.app.ui.settings.BackgroundSectionViewModel
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.StandbyBucket
import com.aicontrolcenter.device.health.BackgroundSettingsIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Goal 5 (A3): the Dashboard says Android may stop agents in the background; one tap leads to Settings › Working in the
 * background, whose button opens Android's own screen; coming back, the app asks Android again and shows what changed,
 * and the Dashboard's item is gone. A phone without that screen gets the plain sentence and the app's page instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class BackgroundCheckJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var robot: AppRobot? = null
    private val application get() = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun stop() {
        robot?.close()
        scope.cancel()
    }

    private val held = BackgroundState(notificationsAllowed = true, batteryOptimised = true, backgroundRestricted = false, standbyBucket = StandbyBucket.ACTIVE)

    private fun launch(screens: TestBackgroundScreens): AppRobot {
        val robot = AppRobot(compose).also { robot = it }
        runBlocking { robot.app.graph.start().join() }
        robot.app.backgroundHealth.current.value = held
        compose.showApp(robot.app.wp10bGraph(scope, screens = screens))
        return robot
    }

    private fun backgroundSection() {
        compose.onNode(hasContentDescription("Working in the background") and isHeading()).performScrollTo()
        compose.waitForIdle()
    }

    /** An item of the check, read as one phrase. */
    private fun item(spoken: String) {
        compose.onNode(hasContentDescription(spoken, substring = true)).assertExists()
    }

    /** As if the owner left for Android's screen and came back: the activity pauses and resumes. */
    private fun comeBack() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test
    fun `from the Dashboard to Android's battery screen and back, the check sees the change`() {
        val robot = launch(TestBackgroundScreens())
        robot.waitUntilShown("Android may stop agents working in the background")
            .tapItemContaining("Android may stop agents working in the background")
        backgroundSection()
        robot.assertShown(BackgroundSectionViewModel.NOT_CLEAR)
        item("Battery saving: Battery saving applies to the app.")
        val asked = robot.app.backgroundHealth.refreshes
        assertTrue(asked >= 1, "Android is asked when the section shows")

        robot.tap("Let the app run in the background")
        val started = shadowOf(application).nextStartedActivity ?: error("Android's screen was not opened")
        assertEquals(TestBackgroundScreens.BATTERY_ACTION, started.action)

        // The owner allowed it on Android's screen and came back.
        robot.app.backgroundHealth.current.value = held.copy(batteryOptimised = false)
        comeBack()
        assertTrue(robot.app.backgroundHealth.refreshes > asked, "asked again on return")
        robot.assertShown(BackgroundSectionViewModel.ALL_CLEAR)
        item("Battery saving: Does not hold the app back.")

        robot.openSection("Dashboard").assertNotShown("Android may stop agents working in the background")
    }

    @Test
    fun `a phone without the battery screen says so and offers the app's page, which opens`() {
        val robot = launch(TestBackgroundScreens(battery = null))
        robot.openSectionViaMore("Settings")
        backgroundSection()
        robot.tap("Let the app run in the background").assertShown(BackgroundSettingsIntents.NO_SUCH_SCREEN)
        assertNull(shadowOf(application).nextStartedActivity)
        robot.tap("Open the app's page in Android")
        assertEquals(TestBackgroundScreens.APP_PAGE_ACTION, shadowOf(application).nextStartedActivity?.action)
        robot.assertNotShown(BackgroundSettingsIntents.NO_SUCH_SCREEN)
    }

    @Test
    fun `when Android refuses to open a screen, the owner reads why instead of a crash`() {
        val robot = launch(TestBackgroundScreens())
        shadowOf(application).checkActivities(true)
        robot.openSectionViaMore("Settings")
        backgroundSection()
        robot.tap("Let the app run in the background").assertShown(BackgroundSettingsIntents.NO_SUCH_SCREEN)
        robot.tap("Open the app's page in Android").assertShown(BackgroundSectionViewModel.NO_APP_PAGE)
    }
}
