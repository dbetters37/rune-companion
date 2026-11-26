package com.aicontrolcenter.app.ui.team

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.TeamSamples
import com.aicontrolcenter.app.testing.TeamSamples.agent
import com.aicontrolcenter.app.testing.TeamSamples.director
import com.aicontrolcenter.app.testing.TeamSamples.header
import com.aicontrolcenter.app.testing.TeamSamples.objective
import com.aicontrolcenter.app.testing.TeamSamples.researcher
import com.aicontrolcenter.app.testing.TeamSamples.root
import com.aicontrolcenter.app.testing.TeamSamples.summary
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.TaskStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * With Android's animations off (a motion duration scale of 0, what "Remove animations" and battery saver give
 * Compose), nothing travels in the Team view even when Calm is not set in the app (rule 17).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class TeamMotionOffTest {
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor: Float = 0f
    })

    private val now = TestApp.START_TIME + 600_000

    private fun state(extra: Int) = TeamUiState(
        isLoading = false,
        filter = TeamFilter.One(root),
        graph = TeamGraphBuilder.build(
            TeamSamples.snapshot(),
            listOf(objective(), summary("t1", "Find note apps", TaskStatus.RUNNING, researcher)),
            (0..extra).map { header(agent(director), agent(researcher), MessageType.ASSIGNMENT, now - 10_000 + it) },
            emptyList(), emptyMap(), now, TeamSamples.spaces, root,
        ),
    )

    @Test
    fun `with a motion duration scale of 0 no dot travels`() {
        var current by mutableStateOf(state(0))
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme(darkTheme = false) { TeamScreen(current, "", TeamActions(TeamNavigation(onBack = {}))) } }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { Snapshot.withMutableSnapshot { current = state(3) } }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0, compose.onNodeWithTag(TEAM_PICTURE_TAG).fetchSemanticsNode().config[DotsInFlight])
    }
}
