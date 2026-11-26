package com.aicontrolcenter.app.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.ui.theme.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings › Routines and folder pick-up on screen (WP10c): two switches screen readers know as switches, both off after
 * installing, each turned on only through the second question; the links are 48 dp; a build without them shows nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class RoutinesSectionTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun switch(title: String) =
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasText(title))

    @Test
    fun `both switches are switches, off after installing, and on only after the second question`() {
        val robot = AppRobot(compose).given { graph.start().join() }.launch().also { robot = it }.openSectionViaMore("Settings")
        switch("Routines").performScrollTo().assertIsOff()
        switch("Folder pick-up").performScrollTo().assertIsOff()
        compose.onNode(hasText("Open routines") and hasClickAction()).performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNode(hasText("Open folder pick-up") and hasClickAction()).performScrollTo().assertHeightIsAtLeast(48.dp)

        switch("Folder pick-up").performClick()
        compose.onNode(hasText("Cancel") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        switch("Folder pick-up").assertIsOff()
        assertFalse(runBlocking { robot.app.settings.get().folderPickUpEnabled })

        switch("Routines").performClick()
        compose.onNode(hasText("Switch on") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        switch("Routines").assertIsOn()
        assertTrue(runBlocking { robot.app.settings.get().routinesEnabled })
        switch("Routines").performClick()
        compose.waitForIdle()
        switch("Routines").assertIsOff()
    }

    @Test
    fun `a build without routines or pick-up shows nothing`() {
        compose.setContent {
            AppTheme {
                RoutinesSection(
                    state = RoutinesSettingsUiState(isLoading = false, routinesAvailable = false, pickUpAvailable = false),
                    onRequest = { _, _ -> }, onConfirm = {}, onCancel = {}, onOpenRoutines = {}, onOpenPickUpRules = {},
                )
            }
        }
        assertTrue(compose.onAllNodes(hasText("Routines", substring = true)).fetchSemanticsNodes().isEmpty())
    }
}
