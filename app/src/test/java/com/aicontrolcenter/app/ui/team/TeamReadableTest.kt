package com.aicontrolcenter.app.ui.team

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D3B-1: on a 360 dp phone the Picture/List switch and the hint under it show in full, also with a paused objective's
 * card.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class TeamReadableTest {
    @get:Rule val compose = createComposeRule()

    private val now = TestApp.START_TIME + 600_000

    private fun state(crowd: Int = 0, paused: Boolean = false): TeamUiState {
        val helpers = (1..crowd).map { TeamSamples.entry(AgentId("agt_helper_$it"), "Helper $it", colorIndex = it) }
        val status = if (paused) TaskStatus.PAUSED else TaskStatus.RUNNING
        val detail = if (paused) DailyLimits.PAUSED_TEXT else null
        val summaries = listOf(
            objective(status, detail),
            summary("t1", "Find offline note apps", status, researcher, detail = detail),
            summary("t2", "Compare the best three", status, analyst, detail = detail),
            summary("t3", "Check the comparison", TaskStatus.QUEUED, reviewer, kind = TaskKind.REVIEW, parentId = TaskId("t2")),
            summary("t4", "Write the recommendation", TaskStatus.QUEUED, writer, dependsOn = listOf("t1", "t2")),
        ) + helpers.mapIndexed { index, helper -> summary("h$index", "Help with part ${index + 1}", TaskStatus.RUNNING, helper.profile.id) }
        val headers = listOf(
            header(Participant.Owner, agent(director), MessageType.ASSIGNMENT, now - 500_000),
            header(agent(director), agent(researcher), MessageType.ASSIGNMENT, now - 400_000),
        )
        val graph = TeamGraphBuilder.build(TeamSamples.snapshot(*helpers.toTypedArray()), summaries, headers, emptyList(), emptyMap(), now, TeamSamples.spaces, root)
        val card = if (!paused) null else ObjectiveCardUi(
            id = root,
            title = "Find the best offline note app",
            status = StatusLabel("Paused: today's limit", StatusTone.DISABLED),
            routine = TeamWords.routine("Morning research"),
            notice = ObjectiveNoticeUi(root, "Find the best offline note app", TeamWords.pauseReason(DailyLimits.PAUSED_TEXT), TeamWords.pauseAdvice(DailyLimits.PAUSED_TEXT), StatusTone.DISABLED),
        )
        return TeamUiState(isLoading = false, filter = TeamFilter.One(root), graph = graph, summary = "0 working, 2 paused", card = card)
    }

    private fun show(state: TeamUiState) {
        compose.setContent { AppTheme(darkTheme = false) { TeamScreen(state, state.summary, TeamActions(TeamNavigation(onBack = {}))) } }
        compose.waitForIdle()
    }

    /** True when nothing of the node is cut off by a parent: its clipped bounds are its whole size. */
    private fun fullyShown(matcher: SemanticsMatcher): Boolean {
        val node = compose.onAllNodes(matcher).onFirst().fetchSemanticsNode()
        val clipped = node.boundsInRoot
        return node.size.height > 0 && clipped.height >= node.size.height - 1f && clipped.width >= node.size.width - 1f
    }

    @Test
    fun `at 360 dp with a paused objective the Picture and List switch and the hint show in full`() {
        show(state(paused = true))
        assertTrue(fullyShown(hasText(TeamWords.LIST) and hasClickAction()), "the List button is whole")
        assertTrue(fullyShown(hasText(TeamWords.PICTURE) and hasClickAction()), "the Picture button is whole")
        assertTrue(fullyShown(hasText(TeamWords.TAP_NOTE)), "the hint is whole")
    }
}
