package com.aicontrolcenter.app.ui.models

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.populateWithProviders
import com.aicontrolcenter.core.usage.ModelPrice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Rule 27 for the model editor's price fields (Phase 6): what is typed is exactly what the field holds and what is
 * stored, however fast. Keys arrive with the frame clock paused and a frame only every few keys, as `FastTypingTest`
 * types them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class PriceTypingTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    @Test
    fun `the price fields hold and store exactly what was typed`() {
        val robot = AppRobot(compose).given { populateWithProviders() }.launch().also { robot = it }
            .openSectionViaMore("Models").tap("Models · 5").scrollListTo("Claude Sonnet 4.5").tapItemContaining("Claude Sonnet 4.5")
        val app = robot.app

        labelled("Input, per million tokens").typeFast("3.25")
        labelled("Output, per million tokens").typeFast("1x5,5")
        labelled("Cached input, per million tokens").typeFast("0.3.0")
        assertEquals("3.25", labelled("Input, per million tokens").typed())
        assertEquals("15,5", labelled("Output, per million tokens").typed(), "only an amount is taken")
        assertEquals("0.30", labelled("Cached input, per million tokens").typed(), "one decimal point")
        // One currency (D3A-8): no currency field; the price is in US$.
        assertTrue(compose.onAllNodes(hasSetTextAction() and hasText("Currency")).fetchSemanticsNodes().isEmpty())
        robot.assertShown(PRICES_IN_US_DOLLARS)

        robot.scrollTo("Save price").tap("Save price").waitUntilShown("Price saved.")
        val model = runBlocking { app.models.getAll() }.single { it.modelName == "claude-sonnet-4-5" }
        val stored = assertNotNull(runBlocking { app.prices.get(model.id) })
        assertEquals(ModelPrice(model.id, 3_250_000, 15_500_000, 300_000, "US$", stored.updatedAt), stored)
    }

    private fun labelled(label: String): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasText(label))

    /** Types [text] one key at a time with the frame clock paused, letting a frame through every [keysPerFrame] keys. */
    private fun SemanticsNodeInteraction.typeFast(text: String, keysPerFrame: Int = 3): SemanticsNodeInteraction {
        try {
            performScrollTo()
        } catch (_: AssertionError) {
            // Not inside a scrolling column.
        }
        performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            text.forEachIndexed { index, key ->
                performTextInput(key.toString())
                if (index % keysPerFrame == keysPerFrame - 1) compose.mainClock.advanceTimeByFrame()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        return this
    }

    private fun SemanticsNodeInteraction.typed(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
}
