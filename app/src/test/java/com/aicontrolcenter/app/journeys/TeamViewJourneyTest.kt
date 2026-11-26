package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleQuestion
import com.aicontrolcenter.app.testing.teamAtWork
import com.aicontrolcenter.app.ui.team.TeamWords
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Team view through the whole app (goal 9; WP9): from the Dashboard's network card while the team works, a member's
 * sheet, the messages between two agents (read only once that sheet opens), and from an objective's header to the
 * question that waits for the owner, which opens on the question screen (rule 13), never as an approval.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class TeamViewJourneyTest {
    @get:Rule val compose = createComposeRule()

    private val robot = AppRobot(compose)

    @After
    fun close() = robot.close()

    /** Taps the member whose spoken description starts with its name ("Researcher, Specialist. …", "You. …"). */
    private fun tapMember(name: String) {
        compose.onAllNodes(
            SemanticsMatcher("member $name") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.let { it.startsWith("$name,") || it.startsWith("$name.") } == true
            } and hasClickAction(),
        ).onFirst().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `from the Dashboard, who works, a member's sheet, and the messages between two agents`() {
        robot.given { teamAtWork() }.launch()
            .waitUntilShown("Compare the three best apps")
        compose.onNode(hasText("Open Team view") and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        robot.waitUntilShown(TeamWords.ALL_CURRENT)
            .assertShown("2 working")
        tapMember("Researcher")
        robot.assertShown("Who it dealt with")
            .tapItemContaining("Was asked by Analyst")
            .waitUntilShown("Which of the apps you found sync without an account?")
            .assertShown("Questions between")
    }

    @Test
    fun `from an objective's header, the question waits for you and opens on its own screen`() {
        robot.given {
            val root = teamAtWork().id
            raise(sampleQuestion(agentNamed("Analyst").profile.id, "Analyst", createdAt = clock.current, taskId = null, rootId = root))
        }.launch()
            .openSection("Tasks")
            .tapItemContaining("Research offline-first note apps")
            .tap("Team view")
            .waitUntilShown("Open objective")
        tapMember("You")
        robot.assertShown("Oldest first. Each opens on its own screen, where you answer it.")
            .assertShown("Analyst asks you")
            .tap("Answer")
            .waitUntilShown("A question from Analyst")
            .assertShown("This is not a permission.")
    }

    @Test
    fun `the list shows the same team, and its rows open the same sheets`() {
        robot.given { teamAtWork() }.launch()
            .openSection("Tasks")
            .tapItemContaining("Research offline-first note apps")
            .tap("Team view")
            .waitUntilShown("Open objective")
            .tap(TeamWords.LIST)
            .scrollListTo("Writer")
            .assertShown("Waiting for earlier steps")
            .tapItemContaining("Writer")
            .assertShown("Open agent")
    }
}
