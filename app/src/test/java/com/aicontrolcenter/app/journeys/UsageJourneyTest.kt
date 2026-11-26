package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TeamModel
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.teamOnCloud
import com.aicontrolcenter.app.testing.teamOnSimulator
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.framework.llm.DailyUse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Goal 1 ("see what you spend"), WP10a: an objective's model calls show up in Activity › Usage and cost with non-zero
 * numbers, and a price the owner enters turns its tokens into an estimated cost, on the Usage tab and the Dashboard.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class UsageJourneyTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun runObjective(robot: AppRobot) = robot
        .openSection("Tasks")
        .tap("New objective")
        .typeIn("Objective", OBJECTIVE)
        .scrollTo("Start objective")
        .tap("Start objective")
        .waitUntilShown("Took ")
        .back()

    @Test
    fun `a simulator objective shows non-zero numbers in Usage and cost, and a row opens the log on its calls`() {
        val robot = AppRobot(compose).given { teamOnSimulator() }.launch().also { this.robot = it }
        runObjective(robot)
        val totals = runBlocking { robot.app.usage.totals(UsageRange.ALL) }
        val answers = DailyUse.counted(totals)
        assertTrue(answers > 0, "the objective's calls were recorded")

        robot.openSection("Activity")
            .tap("Usage and cost")
            .assertShown("Simulator (not a real model)")
            .assertShown("Free: on this phone")
            .assertShown("came from models on this phone: they cost nothing")
            .assertNotShown("Nothing used today")
        // The answers tile says the same number the store holds, counted as the limits count.
        assertDescribed("$answers Answers")
        robot.scrollListTo("Estimated from the prices you entered")
            .assertShown("Estimated from the prices you entered; your provider's bill is what counts.")
            .scrollListTo("Simulator (not a real model)")

        robot.tapItemContaining("Simulator (not a real model)")
            .assertShown("Model call")
        val search = compose.onNode(hasSetTextAction() and hasText("Search the log", substring = true))
        val searched = search.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        assertEquals("Simulator (not a real model) via Simulator", searched, "the log is searched for that model's calls")
    }

    @Test
    fun `a price the owner enters turns the tokens into an estimated cost`() {
        val model = TeamModel()
        val robot = AppRobot(compose).given { teamOnCloud(model) }.launch().also { this.robot = it }
        runObjective(robot)
        val served = runBlocking { robot.app.snapshot().models.single { it.displayName == "Scripted model" } }
        val objective = runBlocking { robot.app.tasks.observeObjectives().first().single() }
        assertEquals(TaskStatus.COMPLETED, objective.status)

        robot.openSection("Activity").tap("Usage and cost")
            .assertShown("Scripted model")
            .assertShown("No price entered")
            .assertShown("has no price, so its tokens are not in the cost")
            .assertShown("Add a price for Scripted model")

        // The owner types the price from the provider's page (the model editor's fields; WP11b).
        runBlocking {
            robot.app.graph.ownerModelPrices.set(ModelPrice(served.id, inputPerMTokMicros = 1_000_000_000, outputPerMTokMicros = 2_000_000_000))
        }
        robot.waitUntilShown("about US$")
            .assertNotShown("No price entered")
            .assertShown("Today's limits count")
            .assertShown(" of US$1.00.")

        // The Dashboard's Today tile shows the same day, now with its cost.
        robot.openSection("Dashboard")
        assertDescribed("Estimated cost")
        assertTrue(
            compose.onAllNodes(hasContentDescription("US$", substring = true) and hasContentDescription("Estimated cost", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            "the tile shows a cost in money",
        )
        assertTrue(compose.onAllNodes(hasContentDescription("a price is missing", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    /** A tile reads as one phrase ("37 Answers, of 200"): it is found by what screen readers say. */
    private fun assertDescribed(text: String) {
        compose.onAllNodes(hasContentDescription(text, substring = true)).onFirst().assertExists()
    }

    private companion object {
        const val OBJECTIVE = "Research offline note apps, compare the best three, and write a recommendation"
    }
}
