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
