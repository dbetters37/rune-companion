package com.aicontrolcenter.app.journeys

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestSystemScreens
import com.aicontrolcenter.app.ui.tools.SETUP_ALL_SET
import com.aicontrolcenter.core.operator.ScreenServiceState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tools › Screen control's "Set up phone control" (the owner's own words: "Each step you should be able to click on and it
 * takes you where you need. Then mark if it's done or not."): the owner leaves for App info and Android's accessibility
 * settings and comes back, and the steps are read again and marked; step 2 opens Settings at the Screen control switch,
 * also at 360 dp and font scale 2.0, where it is far below the Phone operator switch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenSetupJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var robot: AppRobot? = null
    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @After
    fun stopApp() {
        robot?.close()
    }

    /** The next activity the app asked Android to start, once it has. */
    private fun AppRobot.startedActivity(): Intent {
        var started: Intent? = null
        waitUntil { shadowOf(application).nextStartedActivity?.also { started = it } != null }
        return assertNotNull(started)
    }

    /** As if the owner left for Android's screen and came back: the activity pauses and resumes. */
    private fun comeBack() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    private fun described(description: String) = compose.onAllNodes(hasContentDescription(description, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `coming back from App info and Android's accessibility settings, the steps are read again and marked done`() {
        val robot = AppRobot(compose).given {
            graph.start().join()
            settings.update { it.copy(operatorEnabled = true, accessibilityOperatorEnabled = true) }
            installSampleApps()
            graph.ownerOperatorApps.authorise("org.example.notes", "Notes", "12".repeat(32))
            screen.setState(ScreenServiceState.RESTRICTED)
        }.launch().also { robot = it }
            .openSectionViaMore("Tools").tap("Screen")
            .waitUntilDescribed("Step 3, Allow restricted settings (Android blocks this for apps installed from a file), Not done")
            .assertShown("3 of 5 done")

        robot.pressDescribed("Step 3, Allow restricted settings")
        assertEquals(TestSystemScreens.APP_INFO, robot.startedActivity().action)
        // The owner allowed restricted settings; Android still cannot say so, so steps 3 and 4 stay not done.
        robot.app.screenStateOnRefresh = ScreenServiceState.NOT_ENABLED
        val asked = robot.app.screenRefreshes.get()
        comeBack()
        robot.waitUntil { robot.app.screenRefreshes.get() > asked }
            .waitUntilDescribed("Step 4, Turn on AI Control Center in Accessibility, Not done. Find “AI Control Center operator”")
        assertTrue(described("Step 3, Allow restricted settings (Android blocks this for apps installed from a file), Not done"))

        robot.pressDescribed("Step 4, Turn on AI Control Center in Accessibility")
        assertEquals(TestSystemScreens.ACCESSIBILITY_SETTINGS, robot.startedActivity().action)
        // The owner switched the service on. As on Android 9 to 12, the app learns it only when it asks again.
        robot.app.screenStateOnRefresh = ScreenServiceState.READY
        assertTrue(described("Step 4, Turn on AI Control Center in Accessibility, Not done"), "nothing changes before the owner is back")
        comeBack()
        robot.waitUntilDescribed("Step 4, Turn on AI Control Center in Accessibility, Done")
            .waitUntilShown(SETUP_ALL_SET)
        assertTrue(described("Step 3, Allow restricted settings (Android blocks this for apps installed from a file), Done"), "switched on proves it was allowed")
        robot.assertNotShown("Check again")
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 2.0f)
    fun `steps 1 and 2 open Settings at their own switch, at 360 dp and font scale 2_0`() {
        val robot = AppRobot(compose).given { graph.start().join() }.launch().also { robot = it }
            .openSectionViaMore("Tools").tap("Screen")
            .waitUntilDescribed("Step 1, Turn on Phone operator, Not done")
            .pressDescribed("Step 1, Turn on Phone operator, Not done")
            .waitUntil { compose.onAllNodes(hasText("Phone operator") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        switch("Phone operator").assertIsDisplayed()
        robot.tap("Phone operator").waitUntil { runBlocking { robot.app.settings.get() }.operatorEnabled }

        robot.openSectionViaMore("Tools").tap("Screen")
            .waitUntilDescribed("Step 1, Turn on Phone operator, Done")
            .pressDescribed("Step 2, Turn on Screen control, Not done")
            .waitUntil { compose.onAllNodes(hasText("Screen control") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        switch("Screen control").assertIsDisplayed()
        robot.tap("Screen control").waitUntil { runBlocking { robot.app.settings.get() }.accessibilityOperatorEnabled }
    }

    /** A Settings switch, as screen readers know it. */
    private fun switch(title: String) = compose.onNode(hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
}
