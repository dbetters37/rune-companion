package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.populateWithProviders
import com.aicontrolcenter.app.ui.components.TEST_LABEL_SUFFIX
import com.aicontrolcenter.app.ui.planned.PlannedSection
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

    @Test
    fun `labels declared as data keep their mark`() {
        // Tools: nothing to view or change yet (Phase 5: permission broker UI).
        assertEquals("Tools$TEST_LABEL_SUFFIX", Section.TOOLS.title)
        assertEquals("Tools$TEST_LABEL_SUFFIX", PlannedSection.TOOLS.title)
        // Approval time-out: nothing asks the owner for approval yet, so nothing reads it.
        assertEquals("Approval time-out$TEST_LABEL_SUFFIX", OrchestrationLimit.APPROVAL_TIMEOUT_MINUTES.title)
    }

    @Test
    fun `the Tools section, the approval time-out and the approval filter are marked`() {
        app().openSectionViaMore("Tools (test)")
            .assertLabel("Tools (test)")
            .openSectionViaMore("Settings")
            .scrollTo("Approval time-out")
            .assertLabel("Approval time-out (test)")
            .openSection("Activity")
            // Nothing records approval events until approval requests exist.
            .assertLabel("Approval (test)")
    }

    @Test
    fun `the Phone Operator template and the model capabilities nothing reads are marked`() {
        app().openSection("Agents")
            .tapIcon("New agent")
            .scrollListTo("Phone Operator")
            // Its phone tools (files, documents, apps, the workspace) do not exist yet.
            .assertLabel("Phone Operator (test)")
            .back()
            .openSectionViaMore("Models")
            .tap("Models · 5")
            .tapItemContaining("Llama 3.2 3B")
            // No image is ever sent to a model, and nothing picks models by tier.
            .assertLabel("Vision (test)")
            .assertLabel("Reasoning (test)")
            .assertLabel("Coding (test)")
    }

    @Test
    fun `authorising accessibility operation is marked on the switch and in its dialog`() {
        app().openSectionViaMore("Models")
            .tap("AI apps · 4")
            .scrollListTo("com.example.chatbot")
            .tapItemContaining("com.example.chatbot")
            .scrollTo("Allow accessibility operation")
            // There is no operator service yet, so authorising only records the decision.
            .assertLabel("Allow accessibility operation (test)")
            .tapItemContaining("Allow accessibility operation")
            .assertLabel("Authorise (test)")
            .tapInDialog("Cancel")
    }
}
