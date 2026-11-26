package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.populateWithMemories
import com.aicontrolcenter.app.testing.recordSampleUsage
import com.aicontrolcenter.app.ui.shell.AiControlCenterRoot
import com.aicontrolcenter.core.memory.MemoryFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Wave 3 hand-off (WP8; handoff-WP10a.md item 2.1): the Dashboard route hands the Today tile the usage reports, the
 * owner's prices and the owner's zone, so the tile names a model used today without a price instead of showing a
 * partial cost, and adds this month. (Item 2.5, the attention card's empty line, is pinned by `KeyJourneysTest`.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DashboardWiringJourneyTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    @Test
    fun `the Today tile shows what the money limit counts, names the model used today without a price, and shows this month`() {
        val robot = AppRobot(compose).given {
            graph.start().join()
            recordSampleUsage()
        }.launch().also { robot = it }

        // D3A-1: the same US$0.38 as Usage and cost and Daily limits, never "No cost".
        robot.waitUntilShown("Llama 3.3 70B has no price yet, so its tokens are not in the cost.")
            .assertShown("This month:")
            .assertNotShown("No cost")
            .assertShown("Add its price")
            .assertNotShown("The cost counts only the models you entered a price for.")
        // The tile reads as one phrase for screen readers.
        compose.onAllNodes(hasContentDescription("US$0.38 Estimated cost, of US$1.00", substring = true)).onFirst().assertExists()
    }

    /**
     * Wave 3 hand-off (WP8; handoff-WP9.md item 1): the Dashboard route hands its view model the app's background pool
     * (P2: nothing of the Dashboard's state is built on the main thread) and the store's count of memories (P4, D35).
     * The pool here is one that runs only when the test says so: until it runs, the Dashboard has built nothing.
     */
    @Test
    fun `the Dashboard is built on the pool it is handed, and its memory tile is the store's count`() {
        val pool = StandardTestDispatcher()
        val robot = AppRobot(compose).given {
            graph.start().join()
            populateWithMemories()
        }.also { robot = it }
        val stored = runBlocking { robot.app.memories.observeCount(MemoryFilter()).first() }
        assertTrue(stored > 0, "the sample world has memories")
        compose.setContent { AiControlCenterRoot(robot.app.graph, darkTheme = true, screenWork = pool) }
        compose.waitForIdle()
        val tile = hasContentDescription("$stored Memories", substring = true)
        assertTrue(compose.onAllNodes(tile).fetchSemanticsNodes().isEmpty(), "nothing is built before the pool runs")

        // Only what is due now runs: no virtual time passes, so no ticker can loop.
        repeat(5) {
            pool.scheduler.runCurrent()
            compose.waitForIdle()
        }
        compose.onAllNodes(tile).onFirst().assertExists()
    }
}
