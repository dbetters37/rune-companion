package com.aicontrolcenter.app.share

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.app.ui.theme.Spacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The share screen's semantics (rule 34; WP16): Save is off until a place is picked and is marked sensitive (only the
 * owner's own tap saves; screen control never acts on this app), the places are radio buttons of 48 dp and more in one
 * group, saving shows its progress and Back stops it, the reading state does not move under Calm, touches through
 * another app's window are refused, and at font scale 2.0 everything can still be reached.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class ShareScreenTest {
    @get:Rule val compose = createComposeRule()

    private val space = SpaceId("spc_notes")

    private val calls = ArrayList<String>()
    private val actions = ShareActions(
        onSelect = { calls += "select $it" },
        onSave = { calls += "save" },
        onStop = { calls += "stop" },
        onClose = { calls += "close" },
    )

    private fun form(selected: ShareTarget? = null, saving: ShareProgress? = null, localOnly: Boolean = false) = ShareUiState(
        isLoading = false,
        items = listOf(SharedItemUi("Screen recorder export", "About 120 words · 1.2\u00A0KB", "Tuesday: the boiler engineer comes at nine.", previewIsStart = true, parts = 1)),
        summary = "1 text · about 120 words · 1.2\u00A0KB",
        targets = listOf(
            ShareTargetUi(ShareTarget.Space(space), "Screen notes", "Researcher can read it.", colorIndex = 2),
            ShareTargetUi(ShareTarget.Workspace, ShareTexts.WORKSPACE, ShareTexts.WORKSPACE_DETAIL, colorIndex = 0),
        ),
        selected = selected,
        saveLine = selected?.let { "Saves 1 memory in “Screen notes”." },
        localOnly = localOnly,
        asksTitle = true,
        saving = saving,
    )

    /** Shows [state]; the screen follows changes to what this returns. */
    private fun show(state: ShareUiState, calm: Boolean = false): MutableState<ShareUiState> {
        val current = mutableStateOf(state)
        val calmNow = mutableStateOf(calm)
        calmState = calmNow
        compose.setContent {
            AppTheme(darkTheme = true) {
                CompositionLocalProvider(LocalCalmMotion provides calmNow.value) {
                    ShareContent(current.value, remember { TextFieldState("Screen recorder export") }, actions)
                }
            }
        }
        return current
    }

    private var calmState: MutableState<Boolean>? = null

    private val save get() = compose.onNode(hasText(ShareTexts.SAVE) and hasClickAction()).inView()

    /** Scrolled into view when it is in the list; Save and "Don't save" stay in view at the bottom at usual font sizes. */
    private fun SemanticsNodeInteraction.inView(): SemanticsNodeInteraction = apply {
        try {
            performScrollTo()
        } catch (_: AssertionError) {
            // Not inside the scrolling list.
        }
    }

    @Test
    fun `Save is off until a place is picked, then on, and it is marked sensitive`() {
        val shown = show(form())
        save.assertIsNotEnabled()
        compose.onNodeWithText(ShareTexts.CHOOSE_FIRST).assertExists()
        shown.value = form(selected = ShareTarget.Space(space))
        save.assertIsEnabled().performClick()
        assertEquals(listOf("save"), calls)
        assertEquals(true, save.fetchSemanticsNode().config.getOrNull(SemanticsProperties.IsSensitiveData), "only the owner's own tap saves")
    }

    @Test
    fun `the places are radio buttons in one group, at least 48 dp tall, the chosen one selected`() {
        show(form(selected = ShareTarget.Space(space)))
        val row = compose.onNode(hasText("Memory space “Screen notes”") and hasClickAction())
        row.performScrollTo().assertIsSelected().assertHeightIsAtLeast(Spacing.touchTarget)
        assertEquals(Role.RadioButton, row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        val workspace = compose.onNode(hasText(ShareTexts.WORKSPACE) and hasClickAction())
        workspace.performScrollTo().assertHeightIsAtLeast(Spacing.touchTarget).assertHasClickAction().performClick()
        assertEquals(listOf("select ${ShareTarget.Workspace}"), calls)
        val group = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        group.assertExists()
    }

    @Test
    fun `the cost and privacy line is the plan's, and local-only mode replaces its cloud sentence`() {
        val current = show(form(localOnly = true))
        // One line, not two: with local-only on, the cloud sentence is replaced by what stays on the phone (D3B-3).
        compose.onNodeWithText(ShareTexts.STORING_LOCAL_ONLY).performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText(ShareTexts.STORING).fetchSemanticsNodes().size)
        compose.onNodeWithText(ShareTexts.AS_DATA).performScrollTo().assertIsDisplayed()
        assertEquals(
            "Storing is free. Only what agents recall is sent to a model. Local-only mode is on: agents use only models on " +
                "this phone, so what they recall stays on it.",
            ShareTexts.STORING_LOCAL_ONLY,
        )
        current.value = form(localOnly = false)
        compose.waitForIdle()
        compose.onNodeWithText(ShareTexts.STORING).performScrollTo().assertIsDisplayed()
        assertEquals(
            "Storing is free. Only what agents recall is sent to a model. A cloud model would receive this text; " +
                "local-only mode and space access keep it on the phone.",
            ShareTexts.STORING,
        )
    }

    @Test
    fun `while saving the progress shows, Save is gone, and Back or Stop stop it`() {
        show(form(selected = ShareTarget.Space(space), saving = ShareProgress(3, 12)))
        compose.onNodeWithText("Saving 3 of 12…", substring = true).inView().assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText(ShareTexts.SAVE) and hasClickAction()).fetchSemanticsNodes().size)
        val progress = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNode()
        assertEquals(0.25f, progress.config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        compose.onNode(hasText(ShareTexts.STOP) and hasClickAction()).inView().performClick()
        Espresso.pressBack()
        assertEquals(listOf("stop", "stop"), calls, "Back while saving stops the save; it does not close the screen")
    }

    @Test
    fun `reading what was shared does not move under Calm, and moves otherwise`() {
        show(ShareUiState(isLoading = true), calm = true)
        compose.onNodeWithText(ShareTexts.READING).assertIsDisplayed()
        assertEquals(0, indeterminate())
        calmState!!.value = false
        compose.waitForIdle()
        assertEquals(1, indeterminate())
    }

    private fun indeterminate() = compose.onAllNodes(
        SemanticsMatcher("an indeterminate progress indicator") { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate },
    ).fetchSemanticsNodes().size

    @Test
    fun `when it was saved the screen says where, and Done closes it`() {
        show(form().copy(done = ShareDone(ShareTexts.SAVED, "Saved in “Screen notes” as 1 memory, marked as shared from another app.")))
        compose.onNodeWithText("Saved in “Screen notes” as 1 memory, marked as shared from another app.").assertIsDisplayed()
        compose.onNode(hasText(ShareTexts.DONE) and hasClickAction()).performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `a share with nothing to save says why and offers Close`() {
        show(ShareUiState(isLoading = false, leftOut = listOf("“photo.png” was left out: it is not a text file.")))
        compose.onNodeWithText(ShareTexts.NOTHING_TITLE).assertIsDisplayed()
        compose.onNodeWithText("“photo.png” was left out: it is not a text file.").assertIsDisplayed()
        compose.onNode(hasText(ShareTexts.CLOSE) and hasClickAction()).performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `touches through another app's window are refused while the screen shows`() {
        var view: android.view.View? = null
        val current = mutableStateOf(form())
        compose.setContent {
            view = LocalView.current
            AppTheme(darkTheme = true) { ShareContent(current.value, remember { TextFieldState() }, actions) }
        }
        compose.waitForIdle()
        assertTrue(view!!.filterTouchesWhenObscured)
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi", fontScale = 2.0f)
    fun `at font scale 2,0 every part can still be reached`() {
        show(form(selected = ShareTarget.Space(space)))
        compose.onNodeWithText("Tuesday: the boiler engineer comes at nine.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Memory space “Screen notes”") and hasClickAction()).performScrollTo().assertIsDisplayed()
        save.assertIsDisplayed().assertIsEnabled()
        compose.onNode(hasText(ShareTexts.DONT_SAVE) and hasClickAction()).inView().assertIsDisplayed()
    }
}

