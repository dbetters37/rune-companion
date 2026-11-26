package com.aicontrolcenter.app.journeys

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Getting started (Tier B, D47; WP10a): a new owner learns what a model, a provider and an agent are, where to add a key
 * (with the cheaper ways), the daily limits that are on from the start and the simulator, and hides the card for good.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class GettingStartedJourneyTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    @Test
    fun `a new owner follows each step of Getting started and then hides it`() {
        val robot = AppRobot(compose).given { graph.start().join() }.launch().also { this.robot = it }
        assertFalse(runBlocking { robot.app.settings.get().onboardingCompleted })

        robot.assertShown("Getting started")
            .assertShown("A model is the AI that reads and writes text.")
            .assertShown("\"Custom OpenAI-compatible server\" also works with Groq, OpenRouter, Gemini's OpenAI-compatible address")
            .assertShown("On from the start: 200 answers, 500,000 tokens and US$1.00 a day")
            .assertShown("the built-in simulator is not a real model")

        // What the words mean.
        robot.tap("Learn more")
            .assertShown("This app keeps three registries.")
            .back()

        // Add your key: the provider form.
        robot.scrollTo("Add a provider").tap("Add a provider")
            .assertShown("A provider serves models.")
            .back()

        // The daily limits that are already on.
        robot.scrollTo("Set a daily limit")
            .tapStep("Daily limits")
            .assertShown("Model answers a day")
            .assertShown("Save limits")
            .back()

        // The simulator: while the agents have no model, the step asks as the first-run notice does (handoff-WP10a.md
        // item 2.4), and the agents then answer with the simulator.
        robot.scrollTo("Try it now").tap("Try it now")
            .assertShown("Try with the simulator?")
            .tapInDialog("Use the simulator")
        robot.waitUntil { runBlocking { robot.app.agents.getAll() }.all { it.modelId != null } }
        robot.assertNotShown("Try with the simulator?")
        // Once every agent has a model, the step opens the agents, each of which can be given it as its model.
        robot.scrollTo("Open agents").tap("Open agents")
            .assertShown("Researcher")

        robot.openSection("Dashboard")
            .scrollTo("Done, hide this")
        // Through the button's click action, as TalkBack does: the simulator's "now use the simulator" snackbar may still
        // cover the bottom of the screen.
        compose.onAllNodes(hasText("Done, hide this") and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        robot.assertNotShown("Getting started")
        assertTrue(runBlocking { robot.app.settings.get().onboardingCompleted }, "remembered: it does not come back")
    }

    /** Taps the Getting started step's own button (the Today tile has one with the same words further down). */
    private fun AppRobot.tapStep(label: String) = tap(label)
}
