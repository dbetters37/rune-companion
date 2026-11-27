package com.aicontrolcenter.app.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TeamSamples
import com.aicontrolcenter.app.testing.TeamSamples.agent
import com.aicontrolcenter.app.testing.TeamSamples.analyst
import com.aicontrolcenter.app.testing.TeamSamples.director
import com.aicontrolcenter.app.testing.TeamSamples.header
import com.aicontrolcenter.app.testing.TeamSamples.objective
import com.aicontrolcenter.app.testing.TeamSamples.researcher
import com.aicontrolcenter.app.testing.TeamSamples.reviewer
import com.aicontrolcenter.app.testing.TeamSamples.root
import com.aicontrolcenter.app.testing.TeamSamples.summary
import com.aicontrolcenter.app.testing.TeamSamples.writer
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleQuestion
import com.aicontrolcenter.app.testing.teamAtWork
import com.aicontrolcenter.app.ui.team.TeamActions
import com.aicontrolcenter.app.ui.team.TeamFilter
import com.aicontrolcenter.app.ui.team.TeamGraphBuilder
import com.aicontrolcenter.app.ui.team.TeamNavigation
import com.aicontrolcenter.app.ui.team.TeamScreen
import com.aicontrolcenter.app.ui.team.TeamUiState
import com.aicontrolcenter.app.ui.team.TeamWords
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** WP9's own sizes: wide landscape and the 360 dp phone (scratchpad/phase6/wave3-ownership.md, rule 7). */
private const val WIDE_LANDSCAPE = "w900dp-h600dp-xhdpi"
private const val SMALL_PHONE = "w360dp-h740dp-xhdpi"

/**
 * Pictures of the Team view (goal 9) and the Dashboard's network card: the picture while the team works, dark and light,
 * wide and 360 dp, at font scale 1.5 and 2.0 (the list comes first), the list, a member's sheet, a link's sheet with its
 * messages, "You" with a question, a paused objective, 30 agents, a dot in flight, and Calm. Review material, not golden
 * files: recorded into `build/outputs/roborazzi` as `p6_wp9_*.png`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TeamScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/p6_wp9_$name.png")

    /** Everything on the display, for a sheet (a second window) over the screen. */
    private fun shootScreen(name: String) = captureScreenRoboImage("build/outputs/roborazzi/p6_wp9_$name.png")

    private fun app(dark: Boolean = true, populate: suspend TestApp.() -> Unit = { working() }): AppRobot =
        AppRobot(compose).given(populate).launch(dark).also { robot = it }

    /** The team at work, with a question for you from the Analyst. */
    private suspend fun TestApp.working() {
        val objective = teamAtWork()
        raise(
            sampleQuestion(
                agentNamed("Analyst").profile.id, "Analyst", createdAt = clock.current, taskId = null, rootId = objective.id,
                question = "Should the comparison include apps that cost money, or only free ones?",
            ),
        )
    }

    /** Opens the Team view on the working objective from its header. */
    private fun AppRobot.teamOfObjective() = openSection("Tasks")
        .tapItemContaining("Research offline-first note apps")
        .tap("Team view")
        .waitUntilShown("Open objective")

    private fun tapMember(name: String) {
        compose.onAllNodes(
            SemanticsMatcher("member $name") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.let { it.startsWith("$name,") || it.startsWith("$name.") } == true
            } and hasClickAction(),
        ).onFirst().performClick()
        compose.waitForIdle()
    }

    // --- Through the app ---

    @Test
    fun objectiveDark() {
        app().teamOfObjective()
        shoot("team_objective_dark")
    }

    @Test
    fun objectiveLight() {
        app(dark = false).teamOfObjective()
        shoot("team_objective_light")
    }

    @Test
    @Config(qualifiers = WIDE_LANDSCAPE)
    fun objectiveWide() {
        app().teamOfObjective()
        shoot("team_objective_wide")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun objectiveSmallPhone() {
        app().teamOfObjective()
        shoot("team_objective_360")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = 1.5f)
    fun objectiveFont15ListFirst() {
        app().teamOfObjective()
        shoot("team_objective_360_font15_list")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = 2.0f)
    fun objectiveFont20ListFirst() {
        val robot = app().teamOfObjective()
        shoot("team_objective_360_font20_list_1")
        robot.scrollListTo("Writer")
        shoot("team_objective_360_font20_list_2")
    }

    @Test
    fun allCurrentFromTheDashboard() {
        val robot = app()
        robot.waitUntilShown("Compare the three best apps")
        robot.scrollTo("Pinch to zoom")
        shoot("dashboard_network_card")
        compose.onNode(hasText("Open Team view") and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        robot.waitUntilShown(TeamWords.ALL_CURRENT)
        shoot("team_all_current")
    }

    @Test
    fun listMode() {
        app().teamOfObjective().tap(TeamWords.LIST)
        shoot("team_list_1")
        robot!!.scrollListTo("Writer")
        shoot("team_list_2")
    }

    @Test
    fun memberSheet() {
        app().teamOfObjective()
        tapMember("Researcher")
        shootScreen("team_sheet_member")
    }

    @Test
    fun linkSheet() {
        app().teamOfObjective()
        tapMember("Researcher")
        robot!!.tapItemContaining("Was asked by Analyst").waitUntilShown("Which of the apps you found sync without an account?")
        shootScreen("team_sheet_link")
    }

    @Test
    fun youSheet() {
        app().teamOfObjective()
        tapMember("You")
        shootScreen("team_sheet_you")
    }

    @Test
    fun legend() {
        app().teamOfObjective().tapIcon("What the picture shows")
        shootScreen("team_legend")
    }

    // --- From a state of its own: what the app cannot show at a chosen moment ---

    private val now = TestApp.START_TIME + 600_000

    private fun sampleState(extra: List<com.aicontrolcenter.core.tasks.MessageHeader> = emptyList(), crowd: Int = 0, paused: Boolean = false): TeamUiState {
        val helpers = (1..crowd).map { TeamSamples.entry(AgentId("agt_helper_$it"), "Helper $it", colorIndex = it) }
        val snapshot = TeamSamples.snapshot(*helpers.toTypedArray())
        val detail = if (paused) DailyLimits.PAUSED_TEXT else null
        val status = if (paused) TaskStatus.PAUSED else TaskStatus.RUNNING
        val summaries = listOf(
            objective(status, detail),
            summary("t1", "Find offline note apps", if (paused) TaskStatus.PAUSED else TaskStatus.RUNNING, researcher, detail = detail),
            summary("t2", "Compare the best three", if (paused) TaskStatus.PAUSED else TaskStatus.WAITING_FOR_AGENT, analyst, detail = detail ?: "Waiting for Researcher's answer"),
            summary("t3", "Check the comparison", TaskStatus.QUEUED, reviewer, kind = TaskKind.REVIEW, parentId = TaskId("t2")),
            summary("t4", "Write the recommendation", TaskStatus.QUEUED, writer, dependsOn = listOf("t1", "t2")),
        ) + helpers.mapIndexed { index, helper -> summary("h$index", "Help with part ${index + 1}", TaskStatus.RUNNING, helper.profile.id) }
        val headers = baseHeaders(helpers.map { it.profile.id }) + extra
        val graph = TeamGraphBuilder.build(snapshot, summaries, headers, emptyList(), emptyMap(), now, TeamSamples.spaces, root)
        return stateOf(graph, status, detail, paused)
    }

    private val cachedHeaders = HashMap<List<AgentId>, List<com.aicontrolcenter.core.tasks.MessageHeader>>()

    /** The same messages (the same ids) every time, so only the extra ones are new to the picture. */
    private fun baseHeaders(helpers: List<AgentId>) = cachedHeaders.getOrPut(helpers) {
        listOf(
            header(Participant.Owner, agent(director), MessageType.ASSIGNMENT, now - 500_000),
            header(agent(director), agent(researcher), MessageType.ASSIGNMENT, now - 400_000),
            header(agent(director), agent(analyst), MessageType.ASSIGNMENT, now - 390_000),
            header(agent(analyst), agent(researcher), MessageType.ASSISTANCE_REQUEST, now - 30_000),
            header(agent(researcher), Participant.Space(TeamSamples.commons), MessageType.MEMORY_SHARE, now - 20_000),
            header(Participant.Owner, agent(analyst), MessageType.GUIDANCE, now - 200_000),
        ) + helpers.map { header(agent(director), agent(it), MessageType.ASSIGNMENT, now - 300_000) }
    }

    private fun stateOf(graph: com.aicontrolcenter.app.ui.team.TeamGraph, status: TaskStatus, detail: String?, paused: Boolean): TeamUiState {
        return TeamUiState(
            isLoading = false,
            filter = TeamFilter.One(root),
            objectives = listOf(
                com.aicontrolcenter.app.ui.team.ObjectiveChipUi(
                    root, "Find the best offline note app",
                    com.aicontrolcenter.app.ui.tasks.taskStatusLabel(status, TaskKind.OBJECTIVE, null, detail), finished = false,
                ),
            ),
            currentCount = 1,
            graph = graph,
            summary = if (paused) "0 working, 0 waiting for you, 0 waiting for a teammate, 2 paused" else "2 working, 0 waiting for you, 1 waiting for a teammate",
        )
    }

    private var shown by mutableStateOf(TeamUiState())

    private fun render(state: TeamUiState, calm: Boolean = false, dark: Boolean = true) {
        shown = state
        compose.setContent {
            AppTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalCalmMotion provides calm) { TeamScreen(shown, shown.summary, TeamActions(TeamNavigation(onBack = {}))) }
            }
        }
    }

    @Test
    fun dotInFlight() {
        compose.mainClock.autoAdvance = false
        render(sampleState())
        compose.mainClock.advanceTimeBy(100)
        val sent = header(agent(researcher), agent(director), MessageType.FINDINGS, now - 500)
        val asked = header(agent(analyst), agent(researcher), MessageType.ASSISTANCE_RESPONSE, now - 400)
        compose.runOnUiThread { Snapshot.withMutableSnapshot { shown = sampleState(listOf(sent, asked)) } }
        compose.mainClock.advanceTimeBy(600)
        shoot("team_dots_in_flight")
    }

    @Test
    fun calmNoDots() {
        compose.mainClock.autoAdvance = false
        render(sampleState(), calm = true)
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread {
            Snapshot.withMutableSnapshot { shown = sampleState(listOf(header(agent(researcher), agent(director), MessageType.FINDINGS, now - 500))) }
        }
        compose.mainClock.advanceTimeBy(600)
        shoot("team_calm_no_dots")
    }

    @Test
    fun thirtyAgents() {
        render(sampleState(crowd = 25))
        compose.waitForIdle()
        // Their names cannot fit: the view opens on the list (D3B-2); the picture is one tap away.
        shoot("team_30_agents")
        compose.onAllNodes(hasText(TeamWords.PICTURE) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        shoot("team_30_agents_picture")
    }

    @Test
    fun twelveAgents() {
        render(sampleState(crowd = 7))
        compose.waitForIdle()
        shoot("team_12_agents")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun twelveAgentsSmallPhone() {
        render(sampleState(crowd = 7), dark = false)
        compose.waitForIdle()
        shoot("team_12_agents_360")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun pausedByTheLimit() {
        val state = sampleState(paused = true)
        val card = com.aicontrolcenter.app.ui.team.ObjectiveCardUi(
            id = root,
            title = "Find the best offline note app",
            status = com.aicontrolcenter.app.ui.components.StatusLabel("Paused: today's limit", com.aicontrolcenter.app.ui.theme.StatusTone.DISABLED),
            routine = TeamWords.routine("Morning research"),
            notice = com.aicontrolcenter.app.ui.team.ObjectiveNoticeUi(
                root, "Find the best offline note app", TeamWords.pauseReason(DailyLimits.PAUSED_TEXT), TeamWords.pauseAdvice(DailyLimits.PAUSED_TEXT),
                com.aicontrolcenter.app.ui.theme.StatusTone.DISABLED,
            ),
        )
        render(state.copy(card = card), dark = false)
        compose.waitForIdle()
        shoot("team_paused_light_360")
    }
}
