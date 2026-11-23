package com.aicontrolcenter.app.testing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.aicontrolcenter.app.ui.shell.AiControlCenterRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.aicontrolcenter.app.ui.memory.graph.GRAPH_TEST_TAG
import com.aicontrolcenter.app.ui.memory.graph.GraphCanvasPadding
import com.aicontrolcenter.app.ui.memory.graph.GraphFilters
import com.aicontrolcenter.app.ui.memory.graph.GraphViewport
import com.aicontrolcenter.app.ui.memory.graph.MemoryGraphViewModel
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.MemoryActor
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Drives the whole app in a Compose test the way the owner would: by what is written on the
 * screen. It hides the mechanics (finding the right node among several with the same text,
 * scrolling a lazy list before tapping) so journeys read as a sequence of taps.
 */
@OptIn(ExperimentalTestApi::class)
class AppRobot(private val compose: ComposeContentTestRule) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** The app's composition root over in-memory ports. */
    val app = TestApp(scope)

    /** Runs set-up against the registry before (or while) the UI is shown. */
    fun given(block: suspend TestApp.() -> Unit) = apply { runBlocking { app.block() } }

    fun launch(darkTheme: Boolean = true) = apply {
        compose.setContent { AiControlCenterRoot(app.graph, darkTheme) }
        compose.waitForIdle()
    }

    fun close() = scope.cancel()

    /** Taps the bottom-bar or rail item of a section. */
    fun openSection(title: String) = apply {
        compose.onNode(hasContentDescription(title, substring = true) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    /** Opens the phone's "More" sheet. */
    fun openMore() = apply {
        compose.onNode(hasContentDescription("More sections", substring = true)).performClick()
        compose.waitForIdle()
    }

    /** Opens a section that lives behind "More" on a phone. */
    fun openSectionViaMore(title: String) = apply {
        openMore()
        compose.onNode(hasText(title) and hasAnyAncestor(hasClickLabel("Open $title")), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    /** Taps the first thing that shows exactly [text], scrolling it into view when it is inside a scrolling column. */
    fun tap(text: String) = apply {
        compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performScrollToIfPossible().performClick()
        compose.waitForIdle()
    }

    /** Taps a card or row that contains [text] somewhere inside it. */
    fun tapItemContaining(text: String) = apply {
        compose.onAllNodes(hasText(text, substring = true) and hasClickAction()).onFirst().performScrollToIfPossible().performClick()
        compose.waitForIdle()
    }

    fun tapIcon(description: String) = apply {
        compose.onAllNodes(hasContentDescription(description) and hasClickAction()).onFirst().performScrollToIfPossible().performClick()
        compose.waitForIdle()
    }

    /** Taps a button inside the open dialog. */
    fun tapInDialog(text: String) = apply {
        compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
    }

    fun type(fieldLabel: String, value: String) = apply {
        compose.onNodeWithText(fieldLabel).performScrollToIfPossible().performTextReplacement(value)
        compose.waitForIdle()
    }

    /** Brings an item of a lazy list into view. */
    fun scrollListTo(text: String) = apply {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = true))
        compose.waitForIdle()
    }

    /** Scrolls the screen's main column all the way down. */
    fun scrollToEnd() = apply {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).onFirst()
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
    }

    fun scrollTo(text: String) = apply {
        compose.onAllNodes(hasText(text, substring = true)).onFirst().performScrollTo()
        compose.waitForIdle()
    }

    /**
     * Waits until [text] is on screen, for work that finishes off the main thread or after a
     * delay (a debounced search, a graph layout). The main looper's clock is moved on while
     * waiting, so delayed posts run without real waiting.
     */
    fun waitUntilShown(text: String, timeoutMs: Long = 10_000) = apply {
        waitUntil(timeoutMs) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Waits for [condition], letting delayed main-thread work run in between checks. */
    fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) = apply {
        compose.waitUntil(timeoutMs) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            condition()
        }
        compose.waitForIdle()
    }

    /** Waits until the memory graph has been laid out (that happens off the main thread) and drawn. */
    fun waitForGraph() = waitUntil { compose.onAllNodes(hasTestTag(GRAPH_TEST_TAG)).fetchSemanticsNodes().isNotEmpty() }

    /**
     * Taps where memory [id] is drawn in the graph, unzoomed. The position comes from the same
     * deterministic layout of the same snapshot the screen drew, so this is a real touch, not a shortcut.
     */
    fun tapGraphMemory(id: MemoryId, filters: GraphFilters = GraphFilters()) = apply {
        waitForGraph()
        val snapshot = runBlocking { app.graph.memoryService.graph(MemoryActor.Owner, filters.request(MemoryGraphViewModel.MAX_VIEW_NODES)) }
        val point = MemoryGraphViewModel.layoutOf(snapshot).getValue("mem:${id.value}")
        val canvas = compose.onNodeWithTag(GRAPH_TEST_TAG)
        val size = canvas.fetchSemanticsNode().size
        val padding = with(compose.density) { GraphCanvasPadding.toPx() }
        val target = GraphViewport().toScreen(point.x.toFloat(), point.y.toFloat(), size.width.toFloat(), size.height.toFloat(), padding)
        canvas.performTouchInput { click(target) }
        compose.waitForIdle()
    }

    fun assertShown(text: String) = apply {
        compose.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()
    }

    fun assertNotShown(text: String) = apply {
        check(compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty()) { "\"$text\" is shown" }
    }

    fun back() = apply {
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun hasClickLabel(label: String) = SemanticsMatcher("has click label \"$label\"") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }
}

/** `performScrollTo` fails for nodes outside a scrollable container; those are on screen already. */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.performScrollToIfPossible() = apply {
    try {
        performScrollTo()
    } catch (_: AssertionError) {
        // Not inside a scrolling column (a top bar action, a lazy list item, a dialog button).
    }
}
