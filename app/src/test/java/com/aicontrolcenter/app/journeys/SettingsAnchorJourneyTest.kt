package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.ui.settings.ROUTINES_SECTION_TITLE
import com.aicontrolcenter.core.health.BackgroundState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Wave 3 hand-off (WP8; handoff-WP10b.md 3.1, handoff-wave3a-merge.md §3 row 1, goal 5): one tap from what needs fixing to
 * the fix. A phone item on the Dashboard opens Settings scrolled to "Working in the background", where each item has its
 * button to Android's own screen; a Routines screen whose master switch is off opens Settings at that switch. Settings
 * opened from the navigation still starts at the top.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsAnchorJourneyTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    /** A section's header (shown in capitals; its description is the title as written). */
    private fun header(title: String) = compose.onAllNodes(hasContentDescription(title) and isHeading()).onFirst()

    private fun AppRobot.waitForHeader(title: String) =
        waitUntil { compose.onAllNodes(hasContentDescription(title) and isHeading()).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `a phone item on the Dashboard opens Settings at Working in the background`() {
        val robot = AppRobot(compose).given {
            backgroundHealth.current.value = BackgroundState(notificationsAllowed = false, batteryOptimised = true, backgroundRestricted = false, standbyBucket = null)
        }.launch().also { robot = it }

        // From the navigation, Settings starts at the top: the background part is further down.
        robot.openSectionViaMore("Settings").waitForHeader("Working in the background")
        header("Working in the background").assertExists().assertIsNotDisplayed()

        robot.openSection("Dashboard").waitUntilShown("Notifications are off").tap("Notifications are off")
        robot.waitForHeader("Working in the background")
        header("Working in the background").assertIsDisplayed()
        robot.assertShown("Turn on notifications")
    }

    @Test
    fun `a Routines screen with routines off opens Settings at the routines' switches`() {
        val robot = AppRobot(compose).launch().also { robot = it }
        robot.openSectionViaMore("Tools").tap("Routines: objectives that start on a schedule")
            .tap("Open Settings")
        robot.waitForHeader(ROUTINES_SECTION_TITLE)
        header(ROUTINES_SECTION_TITLE).assertIsDisplayed()
    }
}
