package com.aicontrolcenter.app.ui.activity

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.recordSampleUsage
import com.aicontrolcenter.app.testing.typeFast
import com.aicontrolcenter.app.testing.typed
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.testkit.Samples
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Activity section on screen (WP10a): the log's search field takes fast typing exactly (rule 27), "Show older" pages
 * through the log (D41), and the Usage and cost tab reads well with TalkBack (tiles as one phrase, rows that say where
 * they lead, 48 dp targets).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ActivityScreenTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun search() = compose.onNode(hasSetTextAction() and hasText("Search the log", substring = true))

    @Test
    fun `the search is exactly what was typed, however fast, and narrows the log`() {
        val robot = AppRobot(compose).given {
            graph.start().join()
            graph.activityRecorder.record(ActivityType.MODEL_CALL, "Model call", "Grok 4 via xAI: 3 messages")
            graph.activityRecorder.record(ActivityType.TOOL_CALL, "Web page read", "news.example.org")
        }.launch().also { robot = it }
        robot.openSection("Activity")
        search().typeFast(compose, "Grok 4 via xAI")
        compose.waitForIdle()
        assertEquals("Grok 4 via xAI", search().typed())
        robot.assertShown("Grok 4 via xAI: 3 messages")
            .assertNotShown("news.example.org")
            .assertShown("Clear filters")
            .tap("Clear filters")
        assertEquals("", search().typed())
        robot.assertShown("news.example.org")
    }

    @Test
    fun `Show older adds the next page at the end of the log`() {
        val robot = AppRobot(compose).given {
            for (i in 0 until 130) activityLog.record(Samples.event("old_$i", at = TestApp.START_TIME - (130 - i) * 60_000L, egress = false))
        }.launch().also { robot = it }
        robot.openSection("Activity")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Show older events"))
        compose.onAllNodes(hasText("Show older events") and hasClickAction()).onFirst().assertHeightIsAtLeast(48.dp)
        compose.onAllNodes(hasText("Show older events") and hasClickAction()).onFirst()
            .performSemanticsActionOnClick()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("That is every one of the 130 events."))
        robot.assertShown("That is every one of the 130 events.")
    }

    @Test
    fun `the Usage tab reads as phrases and every row says where it leads`() {
        val robot = AppRobot(compose).given {
            graph.start().join()
            recordSampleUsage()
        }.launch().also { robot = it }
        robot.openSection("Activity").tap("Usage and cost")
        // Tiles read as one phrase.
        compose.onAllNodes(hasContentDescription("Answers", substring = true)).onFirst().assertExists()
        compose.onAllNodes(hasContentDescription("Estimated cost", substring = true)).onFirst().assertExists()
        // The period chips are 48 dp and selectable.
        for (period in UsagePeriod.entries) {
            compose.onAllNodes(hasText(period.title) and hasClickAction()).onFirst().assertHeightIsAtLeast(48.dp)
        }
        // A model row says it opens the log; the slowed-down calls are named as such.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Grok 4"))
        val row = compose.onAllNodes(hasClickLabel("Show its calls in the log")).fetchSemanticsNodes()
        assertTrue(row.isNotEmpty(), "model rows say where they lead")
        robot.assertShown("ended because xAI asked the app to slow down. They are not counted as answers.")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasContentDescription("By agent", substring = true))
        assertTrue(compose.onAllNodes(hasClickLabel("Show what Researcher did in the log")).fetchSemanticsNodes().isNotEmpty())
    }

    private fun hasClickLabel(label: String) = SemanticsMatcher("has click label \"$label\"") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performSemanticsActionOnClick() =
        performSemanticsAction(SemanticsActions.OnClick)
}
