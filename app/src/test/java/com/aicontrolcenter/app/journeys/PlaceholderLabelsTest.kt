package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.populateWithProviders
import com.aicontrolcenter.app.ui.settings.OrchestrationLimit
import com.aicontrolcenter.app.ui.shell.Section
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Every control that does not fully work yet shows "(test)" (see `testLabel`). This opens each
 * known placeholder in the real app and fails if one has lost its label.
 *
 * When a feature becomes real, remove its `testLabel` call and its entry here in the same change.
 * Removing only the label makes this test fail, which is the point: a placeholder must not
 * start to look finished by accident.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class PlaceholderLabelsTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    private fun app(): AppRobot = AppRobot(compose).given { populateWithProviders() }.launch().also { robot = it }

    @After
    fun stopApp() {
        robot?.close()
    }

    /** Fails unless something on screen reads exactly [label]. */
    private fun AppRobot.assertLabel(label: String) = apply {
        this@PlaceholderLabelsTest.compose.onAllNodes(hasText(label)).onFirst().assertExists("\"$label\" is not shown")
    }

    /** Fails if anything on screen reads exactly [label]. */
    private fun AppRobot.assertNoLabel(label: String) = apply {
        assertEquals(0, this@PlaceholderLabelsTest.compose.onAllNodes(hasText(label)).fetchSemanticsNodes().size, "\"$label\" is still shown")
    }

    @Test
    fun `the model capabilities nothing reads are marked`() {
        app().openSectionViaMore("Models")
            .tap("Models · 5")
            .tapItemContaining("Llama 3.2 3B")
            // No image is ever sent to a model, and nothing picks models by tier.
            .assertLabel("Vision (test)")
            .assertLabel("Reasoning (test)")
            .assertLabel("Coding (test)")
    }

    /** Phase 5 made these real: the broker asks the owner, and the Operator's phone tools exist. */
    @Test
    fun `the approval time-out, the approval filter and the Phone Operator template are no longer marked`() {
        assertEquals("Approval time-out", OrchestrationLimit.APPROVAL_TIMEOUT_MINUTES.title)
        app().openSectionViaMore("Settings")
            .scrollTo("Approval time-out")
            .assertLabel("Approval time-out")
            .assertNoLabel("Approval time-out (test)")
            .openSection("Activity")
            .assertLabel("Approval")
            .assertNoLabel("Approval (test)")
            .openSection("Agents")
            .tapIcon("New agent")
            .scrollListTo("Phone Operator")
            .assertLabel("Phone Operator")
            .assertNoLabel("Phone Operator (test)")
    }

    /**
     * Phase 5 made these real too: the Tools section has its five tabs, and authorising an AI app
     * for accessibility operation goes through the list the screen operator reads (operator_apps).
     */
    @Test
    fun `the Tools section and authorising accessibility operation are no longer marked`() {
        assertEquals("Tools", Section.TOOLS.title)
        app().openSectionViaMore("Tools")
            .assertNoLabel("Tools (test)")
            .assertLabel("All tools")
            .assertLabel("Allowed")
            .assertLabel("Screen")
            .openSectionViaMore("Models")
            .tap("AI apps · 4")
            .scrollListTo("com.example.chatbot")
            .tapItemContaining("com.example.chatbot")
            .scrollTo("Allow accessibility operation")
            .assertLabel("Allow accessibility operation")
            .assertNoLabel("Allow accessibility operation (test)")
            .tapItemContaining("Allow accessibility operation")
            .assertLabel("Authorise")
            .assertNoLabel("Authorise (test)")
            .tapInDialog("Cancel")
    }
}
