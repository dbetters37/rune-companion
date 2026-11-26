package com.aicontrolcenter.app.ui.shell

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Wave 3 hand-off (WP8; handoff-WP10c.md §2 row 3): on the razr's 360 dp outer screen at font size 2.0, the "All sections"
 * sheet holds Settings below its fold; the test robot scrolls to a section before tapping it, so journeys at large
 * fonts reach every section (the sheet itself scrolls, as the owner does).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi", fontScale = 2.0f)
class MoreSheetLargeFontTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    @Test
    fun `at 360 dp and font size 2 the More sheet still opens the last sections`() {
        val robot = AppRobot(compose).launch().also { robot = it }
        robot.openSectionViaMore("Settings")
        robot.waitUntil { compose.onAllNodes(hasContentDescription("Privacy") and isHeading()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasContentDescription("Privacy") and isHeading()).fetchSemanticsNodes().isNotEmpty(), "Settings opened")
        robot.openSectionViaMore("Activity")
    }
}
