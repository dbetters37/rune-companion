package com.aicontrolcenter.app.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.typeFast
import com.aicontrolcenter.app.testing.typed
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.UsageSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Settings › Daily limits on screen (WP10a): rule 27's paused-clock typing into its three number fields, what Save
 * stores, and what screen readers hear (one phrase per limit, "Reached." in words, 48 dp buttons).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class DailyLimitsScreenTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun openDailyLimits(): AppRobot =
        AppRobot(compose).given { graph.start().join() }.launch().also { robot = it }
            .openSectionViaMore("Settings")
            .scrollTo("Change daily limits")
            .tap("Change daily limits")

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))

    @Test
    fun `the three limits are exactly what was typed, however fast, and Save stores them`() {
        val robot = openDailyLimits()
        for (label in listOf("Model answers a day", "Tokens a day", "Money a day (estimated)")) field(label).performScrollTo().performTextClearance()
        field("Model answers a day").typeFast(compose, "1234")
        field("Tokens a day").typeFast(compose, "2500000")
        field("Money a day (estimated)").typeFast(compose, "12,75")
        compose.waitForIdle()
        assertEquals("1234", field("Model answers a day").typed())
        assertEquals("2500000", field("Tokens a day").typed())
        assertEquals("12.75", field("Money a day (estimated)").typed(), "a comma is read as the decimal point")

        robot.tap("Save limits").waitUntilShown("Saved.")
        assertEquals(DailyLimits(1_234, 2_500_000, 12_750_000), runBlocking { robot.app.settings.get().dailyLimits })
    }

    @Test
    fun `letters cannot get into the number fields, and a wrong amount says why next to the field`() {
        val robot = openDailyLimits()
        field("Model answers a day").performScrollTo().performTextClearance()
        field("Model answers a day").typeFast(compose, "3a0b0")
        assertEquals("300", field("Model answers a day").typed())
        field("Money a day (estimated)").performScrollTo().performTextClearance()
        field("Money a day (estimated)").typeFast(compose, "0.001")
        robot.tap("Save limits")
        robot.assertShown("Write an amount such as 1.00 or 0.50.")
        assertEquals(DailyLimits.DEFAULT, runBlocking { robot.app.settings.get().dailyLimits }, "nothing saved")
    }

    @Test
    fun `each limit is read as one phrase that says when it is reached`() {
        val robot = AppRobot(compose).given {
            graph.start().join()
            settings.update { it.copy(maxModelCallsPerDay = 1) }
            usage.record(
                ModelCallSample(
                    at = com.aicontrolcenter.app.testing.TestApp.START_TIME - 60_000, modelId = ModelId("mdl_grok"), modelLabel = "Grok 4",
                    providerId = ProviderId("prv_xai"), providerLabel = "xAI", locality = Locality.CLOUD, agentId = AgentId("agt_x"),
                    source = UsageSource.CHAT, outcome = CallOutcome.OK, inputTokens = 10, outputTokens = 5, answerMs = 100,
                ),
            )
        }.launch().also { robot = it }
        robot.openSectionViaMore("Settings").scrollTo("Change daily limits")
        compose.onAllNodes(hasContentDescription("Answers: 1 of 1 today. Reached.")).onFirst().assertExists()
        compose.onAllNodes(hasContentDescription("Tokens: 15 of 500,000 today")).onFirst().assertExists()
        compose.onAllNodes(hasText("Change daily limits") and hasClickAction()).onFirst().assertHeightIsAtLeast(48.dp)
        robot.assertShown("Today's limit of 1 model answer is reached.")

        robot.tap("Change daily limits")
        compose.onAllNodes(hasText("Save limits") and hasClickAction()).onFirst().performScrollTo().assertHeightIsAtLeast(48.dp)
        // The money field is a field with a label, and the switch says what it does.
        compose.onAllNodes(hasText("Limit money too")).onFirst().assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState) and hasText("Limit money too", substring = true))
            .onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        robot.assertShown("Off: no money limit.")
    }
}
