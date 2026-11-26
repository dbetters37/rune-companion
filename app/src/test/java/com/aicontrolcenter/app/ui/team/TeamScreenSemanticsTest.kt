package com.aicontrolcenter.app.ui.team

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.TeamSamples
import com.aicontrolcenter.app.testing.TeamSamples.agent
import com.aicontrolcenter.app.testing.TeamSamples.analyst
import com.aicontrolcenter.app.testing.TeamSamples.director
import com.aicontrolcenter.app.testing.TeamSamples.header
import com.aicontrolcenter.app.testing.TeamSamples.objective
import com.aicontrolcenter.app.testing.TeamSamples.researcher
import com.aicontrolcenter.app.testing.TeamSamples.root
import com.aicontrolcenter.app.testing.TeamSamples.summary
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.sampleQuestion
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.MessageHeader
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.TaskStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Team view as TalkBack and a finger meet it: every member says its state in words, the reading order is You, the
 * Director, the ring, the spaces; custom actions open what waits for you and the messages with each teammate; 48 dp
 * targets; the list has the same members as the picture; and dots travel once per new message, never under Calm
 * (rule 17). Counts and properties, never times (rule 16's F19).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class TeamScreenSemanticsTest {
    @get:Rule val compose = createComposeRule()

    private val now = TestApp.START_TIME + 600_000
    private val opened = mutableListOf<String>()
    private val links = mutableListOf<String>()
    private val questions = mutableListOf<RequestId>()

    private val question = sampleQuestion(researcher, createdAt = now - 60_000, taskId = TaskId("tsk_find"), rootId = root)

    private fun graph(headers: List<MessageHeader> = baseHeaders) = TeamGraphBuilder.build(
        TeamSamples.snapshot(),
        listOf(
            objective(),
            summary("tsk_find", "Find note apps", TaskStatus.WAITING_FOR_OWNER, researcher),
            summary("tsk_compare", "Compare them", TaskStatus.RUNNING, analyst),
        ),
        headers,
        listOf(question),
        emptyMap(),
        now,
        TeamSamples.spaces,
        root,
    )

    private val baseHeaders = listOf(
        header(agent(director), agent(researcher), MessageType.ASSIGNMENT, now - 300_000),
        header(agent(analyst), agent(researcher), MessageType.ASSISTANCE_REQUEST, now - 200_000),
        header(agent(researcher), agent(analyst), MessageType.ASSISTANCE_RESPONSE, now - 100_000),
    )

    private fun state(graph: TeamGraph) = TeamUiState(isLoading = false, filter = TeamFilter.One(root), graph = graph, summary = "1 working, 1 waiting for you, 0 waiting for a teammate")

    private var current by mutableStateOf(TeamUiState())

    private fun show(state: TeamUiState, calm: Boolean = false, screenReader: Boolean = false) {
        current = state
        val actions = TeamActions(
            navigation = TeamNavigation(onBack = {}, onOpenQuestion = { questions += it }),
            onOpenNode = { opened += it },
            onOpenLink = { links += it },
        )
        compose.setContent {
            AppTheme(darkTheme = true) {
                CompositionLocalProvider(LocalCalmMotion provides calm) { TeamScreen(current, current.summary, actions, screenReader) }
            }
        }
        compose.waitForIdle()
    }

    /** The member whose spoken description starts with its name ("Researcher, Specialist. …", "You. …"). */
    /** A new state, as the view model would send it: a snapshot write the composition is told about. */
    private fun update(state: TeamUiState) {
        compose.runOnUiThread {
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { current = state }
        }
    }

    private fun member(name: String): SemanticsNodeInteraction = compose.onAllNodes(
        SemanticsMatcher("member $name") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.let { it.startsWith("$name,") || it.startsWith("$name.") } == true
        } and hasClickAction(),
    ).onFirst()

    private fun dotsInFlight(): Int = compose.onNodeWithTag(TEAM_PICTURE_TAG).fetchSemanticsNode().config[DotsInFlight]

    @Test
    fun `every member says its state, task and connections in words, and the reading order starts with you`() {
        show(state(graph()))
        val researcherText = member("Researcher").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(researcherText.startsWith("Researcher, Specialist. Waiting for you. Asks you: “"), researcherText)
        assertTrue("Task: Find note apps" in researcherText, researcherText)
        assertTrue("Connections:" in researcherText && "asked by Analyst" in researcherText, researcherText)

        val order = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TraversalIndex) and hasClickAction())
            .fetchSemanticsNodes()
            .sortedBy { it.config[SemanticsProperties.TraversalIndex] }
            .map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull().orEmpty().substringBefore(",").substringBefore(".") }
        assertEquals(listOf("You", "Director", "Researcher", "Analyst", "Team notes"), order.take(5), order.toString())
        assertTrue(order.drop(5).all { it.startsWith("Work handed out") || it.startsWith("Questions") }, "then the lines' signs: $order")
    }

    @Test
    fun `custom actions answer the question and open the messages with a teammate`() {
        show(state(graph()))
        val node = member("Researcher").fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        assertEquals("Answer the question", actions.first().label)
        actions.first().action()
        assertEquals(listOf(question.id), questions, "a question opens the question screen, never an approval")
        val withAnalyst = actions.first { it.label == "Show messages with Analyst" }
        withAnalyst.action()
        assertEquals(listOf(linkKeyOf(analyst.value, researcher.value)), links)
        member("Researcher").performClick()
        assertEquals(listOf(researcher.value), opened)
    }

    @Test
    fun `members and the signs of lines are 48 dp targets, and the list has everyone the picture has`() {
        val graph = graph()
        show(state(graph))
        listOf("You", "Director", "Researcher", "Analyst", "Team notes").forEach { member(it).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
        compose.onAllNodes(hasContentDescription("Questions between", substring = true) and hasClickAction()).onFirst()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(listOf(linkKeyOf(analyst.value, researcher.value)), links)

        compose.onAllNodes(hasText(TeamWords.LIST) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(TEAM_PICTURE_TAG).fetchSemanticsNodes().size)
        val list = compose.onNodeWithTag(TEAM_LIST_TAG)
        graph.nodes.forEach { node ->
            list.performScrollToKey("node-${node.id}")
            assertTrue(
                compose.onAllNodes(hasText(node.name, substring = true) and hasClickAction()).fetchSemanticsNodes().isNotEmpty(),
                "${node.name} is in the list",
            )
        }
    }

    @Test
    fun `with TalkBack the list comes first, with an offer of the picture`() {
        show(state(graph()), screenReader = true)
        assertEquals(1, compose.onAllNodesWithTag(TEAM_LIST_TAG).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag(TEAM_PICTURE_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun `a new message sends one dot that arrives, and what was there when the screen opened never travels`() {
        compose.mainClock.autoAdvance = false
        show(state(graph()))
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0, dotsInFlight(), "history does not travel")

        val fresh = header(agent(researcher), agent(director), MessageType.COMPLETION, now - 1_000)
        update(state(graph(baseHeaders + fresh)))
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, dotsInFlight())
        compose.mainClock.advanceTimeBy(PulseScheduler.TRAVEL_MS + 200)
        assertEquals(0, dotsInFlight(), "arrived: the frame loop has stopped")
    }

    @Test
    fun `under Calm no dot travels`() {
        compose.mainClock.autoAdvance = false
        show(state(graph()), calm = true)
        compose.mainClock.advanceTimeBy(100)
        update(state(graph(baseHeaders + header(agent(researcher), agent(director), MessageType.COMPLETION, now - 1_000))))
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0, dotsInFlight())
    }

    @Test
    fun `the summary is one polite live region`() {
        show(state(graph()))
        val live = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()
        assertEquals(1, live.size)
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, live.single().config[SemanticsProperties.LiveRegion])
    }

    @Test
    fun `a member's sheet opens the question on its own screen and lists its links`() {
        val graph = graph()
        val sheet = TeamSheetUi.Member(
            node = graph.node(researcher.value)!!,
            links = TeamDescriptions.linkRows(researcher.value, graph),
            messages = emptyList(),
            requests = emptyList(),
        )
        show(state(graph).copy(sheet = sheet))
        compose.onAllNodes(hasText("Who it dealt with")).onFirst().assertExists()
        compose.onAllNodes(hasText("Was asked by Analyst", substring = true) and hasClickAction()).onFirst().performClick()
        assertEquals(listOf(linkKeyOf(analyst.value, researcher.value)), links)
    }

}
