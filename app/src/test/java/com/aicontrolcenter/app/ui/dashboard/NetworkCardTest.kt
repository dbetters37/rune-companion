package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.TeamSamples
import com.aicontrolcenter.app.ui.dashboard.network.AgentTraffic
import com.aicontrolcenter.app.ui.dashboard.network.toAgentNetwork
import com.aicontrolcenter.app.ui.team.DotsInFlight
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The Dashboard's network card (N1, N2, N9): a message new to the card sends one dot, which arrives and stops the frame
 * loop; nothing loops; nothing travels under Calm; and the card opens the Team view.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class NetworkCardTest {
    @get:Rule val compose = createComposeRule()

    private val snapshot = TeamSamples.snapshot()
    private var network by mutableStateOf(snapshot.toAgentNetwork())
    private var openedTeam = 0

    private fun show(calm: Boolean) {
        compose.setContent {
            AppTheme(darkTheme = true) {
                CompositionLocalProvider(LocalCalmMotion provides calm) {
                    NetworkCard(network, null, {}, {}, {}, onOpenTeam = { openedTeam++ })
                }
            }
        }
    }

    private fun dots() = compose.onNodeWithTag("agent-network").fetchSemanticsNode().config[DotsInFlight]

    private fun message(id: String) = compose.runOnUiThread {
        Snapshot.withMutableSnapshot {
            network = snapshot.toAgentNetwork(traffic = listOf(AgentTraffic(TeamSamples.analyst, TeamSamples.researcher, id)))
        }
    }

    @Test
    fun `a new message travels once and the loop stops when it arrives`() {
        compose.mainClock.autoAdvance = false
        network = snapshot.toAgentNetwork(traffic = listOf(AgentTraffic(TeamSamples.director, TeamSamples.researcher, "old")))
        show(calm = false)
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0, dots(), "what was there when the card appeared does not travel")
        message("new")
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1, dots())
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(0, dots(), "arrived; nothing loops")
    }

    @Test
    fun `under Calm nothing travels`() {
        compose.mainClock.autoAdvance = false
        show(calm = true)
        compose.mainClock.advanceTimeBy(100)
        message("new")
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0, dots())
    }

    @Test
    fun `the card opens the Team view`() {
        show(calm = false)
        compose.waitForIdle()
        compose.onNode(hasText("Open Team view") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, openedTeam)
    }
}
