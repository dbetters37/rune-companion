package com.aicontrolcenter.app.ui.settings

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.StandbyBucket
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.device.health.BackgroundSettingsIntents
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP10b's Settings sections on screen: what each says in words, what each tap stores, the background items read as one
 * phrase each with their buttons, a phone without Android's screen, 48 dp targets, and the Backup section only where the
 * build has a backup.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class WP10bSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    /** Section headers show their title in capitals and are read by their title. */
    private fun heading(title: String) {
        compose.onNode(hasContentDescription(title) and androidx.compose.ui.test.isHeading()).performScrollTo()
        compose.waitForIdle()
    }

    private fun settings(given: suspend com.aicontrolcenter.app.testing.TestApp.() -> Unit = {}): AppRobot =
        AppRobot(compose).given { graph.start().join(); given() }.launch().also { robot = it }.openSectionViaMore("Settings")

    @Test
    fun `Questions from agents says what a question is, and the switch and the times store what is tapped`() {
        val robot = settings()
        heading("Questions from agents")
        robot.assertShown("A question is never a permission: your answer is only text for the agent.")
        robot.scrollTo("Time to answer").assertShown("20 minutes (usual)")
        compose.onAllNodes(hasText("1 hour") and hasClickAction()).onFirst().performScrollTo().assertHeightIsAtLeast(48.dp)
        robot.tap("1 hour")
        assertEquals(60, runBlocking { robot.app.settings.get().ownerQuestionTimeoutMinutes })
        robot.tap("Agents may ask me questions")
        assertFalse(runBlocking { robot.app.settings.get().agentQuestionsEnabled })
    }

    @Test
    fun `each background item is read as one phrase, and a phone without Android's screen says so and offers the app's page`() {
        val robot = settings {
            backgroundHealth.current.value = BackgroundState(notificationsAllowed = true, batteryOptimised = true, backgroundRestricted = false, standbyBucket = StandbyBucket.ACTIVE)
        }
        heading("Working in the background")
        robot.assertShown(BackgroundSectionViewModel.NOT_CLEAR)
        assertTrue(robot.app.backgroundHealth.refreshes >= 1, "Android is asked again when the section shows")
        val battery = compose.onNode(hasContentDescription("Battery saving: Battery saving applies to the app.", substring = true))
        battery.performScrollTo().assertExists()
        compose.onNode(hasContentDescription("Notifications: Allowed.", substring = true)).assertExists()

        // The test app has no Android screens (BackgroundScreens.None): the tap says so instead of failing.
        robot.tap("Let the app run in the background").assertShown(BackgroundSettingsIntents.NO_SUCH_SCREEN)
        compose.onAllNodes(hasText("Open the app's page in Android") and hasClickAction()).onFirst().performScrollTo().assertHeightIsAtLeast(48.dp)
        robot.tap("Open the app's page in Android").assertShown(BackgroundSectionViewModel.NO_APP_PAGE)

        robot.tap("Keep the phone awake while agents work")
        assertFalse(runBlocking { robot.app.settings.get().keepAwakeWhileAgentsWork })
        robot.tap("Tell me when work ends")
        assertFalse(runBlocking { robot.app.settings.get().notifyWhenWorkEnds })
    }

    @Test
    fun `Calm is under Appearance and stores the owner's choice`() {
        val robot = settings()
        heading("Appearance")
        robot.assertShown(CALM_SWITCH)
        robot.tap("Calm")
        assertTrue(runBlocking { robot.app.settings.get().calmMotion })
    }

    @Test
    fun `with Android's animations off the section says the app is calm already`() {
        val settings: SettingsRepository = InMemorySettingsRepository()
        val viewModel = MotionSettingsViewModel(settings)
        compose.setContent { AppTheme(darkTheme = true) { MotionSection(viewModel, animatorsEnabled = { false }) } }
        compose.waitForIdle()
        compose.onNodeWithText(ANDROID_ANIMATIONS_OFF).assertExists()
    }

    @Test
    fun `there is no Backup section where the build has no backup`() {
        val robot = settings()
        robot.scrollToEnd()
        assertTrue(compose.onAllNodesWithText("Save a backup").fetchSemanticsNodes().isEmpty())
    }
}
