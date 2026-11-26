package com.aicontrolcenter.app.ui.recovery

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.typeFast
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.RecoveryResult
import com.aicontrolcenter.testkit.FakeOwnerRecovery
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Recovery screen on screen (rules 9, 27, 30): the window is secure, the confirming word takes fast typing exactly
 * in a hidden field, Start over stays off until it is typed and then restarts the app, a refusal is shown as it is, and
 * Try again and Close do what they say.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class RecoveryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var restarts = 0
    private var closes = 0
    private var tries = 0

    private fun show(viewModel: RecoveryViewModel) {
        compose.setContent {
            AppTheme(darkTheme = true) {
                RecoveryScreen(viewModel, onTryAgain = { tries++ }, restart = { restarts++ }, close = { closes++ })
            }
        }
        compose.waitForIdle()
    }

    private val word get() = compose.onNode(hasSetTextAction() and hasText("Type START OVER to confirm", substring = true))

    private fun button(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).onFirst()

    @Test
    fun `the window is secure, the word takes fast typing exactly, and Start over restarts the app`() {
        val recovery = FakeOwnerRecovery(DatabaseState.KEY_DAMAGED)
        val viewModel = RecoveryViewModel(DatabaseState.KEY_DAMAGED, recovery)
        show(viewModel)
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        compose.onAllNodesWithText(DatabaseState.KEY_DAMAGED.ownerText).onFirst().assertExists()
        compose.onAllNodesWithText("Nothing is deleted", substring = true).onFirst().assertExists()
        button("Start over").performScrollTo().assertIsNotEnabled()

        word.typeFast(compose, "start over")
        assertEquals("start over", viewModel.confirmWord.text.toString())
        word.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onAllNodesWithText("10 characters typed", substring = true).onFirst().assertExists()
        button("Start over").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        assertEquals(1, recovery.startOvers.get())
        assertEquals(1, restarts, "rule 30: the app starts again")
    }

    @Test
    fun `a refusal is shown as it is and nothing restarts`() {
        val recovery = FakeOwnerRecovery(DatabaseState.OTHER, result = RecoveryResult.Refused("Your data opens normally, so nothing was changed."))
        val viewModel = RecoveryViewModel(DatabaseState.OTHER, recovery)
        show(viewModel)
        word.typeFast(compose, "START OVER")
        button("Start over").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Your data opens normally, so nothing was changed.").onFirst().assertExists()
        assertEquals(0, restarts)
    }

    @Test
    fun `Try again asks the gate to look again, and Close closes`() {
        val viewModel = RecoveryViewModel(DatabaseState.KEYSTORE_UNAVAILABLE, FakeOwnerRecovery(DatabaseState.KEYSTORE_UNAVAILABLE))
        show(viewModel)
        compose.onAllNodesWithText(RecoveryViewModel.adviceFor(DatabaseState.KEYSTORE_UNAVAILABLE)).onFirst().assertExists()
        button("Try again").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        assertEquals(1, tries)
        compose.onAllNodesWithText(STILL_NOT_OPENING).onFirst().assertExists()
        button("Close and try later").performClick()
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    @Test
    fun `without a way to start over the screen offers only Try again and Close`() {
        show(RecoveryViewModel(DatabaseState.MIGRATION_FAILED, null))
        assertTrue(compose.onAllNodesWithText("Type START OVER to confirm", substring = true).fetchSemanticsNodes().isEmpty())
        button("Try again").assertExists()
        button("Close and try later").assertExists()
    }
}
