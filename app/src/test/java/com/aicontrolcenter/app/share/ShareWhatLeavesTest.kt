package com.aicontrolcenter.app.share

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TeamModel
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.teamOnCloud
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.agents.toDraft
import com.aicontrolcenter.core.memory.MemoryActor
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals

/**
 * D3B-3: the line right above Save says what leaves the phone, for the place chosen: who can recall it, and whether
 * their model is off this phone (and where), or that it stays on the phone (no reader yet, local-only mode on).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class ShareWhatLeavesTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @get:Rule val compose = createComposeRule()

    private suspend fun TestApp.space(name: String): SpaceId = graph.memoryService.createSpace(MemoryActor.Owner, name).id

    private suspend fun TestApp.letRead(agent: String, space: SpaceId) {
        val profile = agentNamed(agent).profile
        graph.agentManager.update(profile.id, profile.toDraft().copy(memory = profile.memory.copy(readableSpaces = setOf(space))))
    }

    private fun TestScope.share(app: TestApp) = ShareViewModel(
        listOf(SharedItem("Train times", "The 8:14 to Leeds leaves from platform 3.")),
        app.graph.memoryService, app.graph.workspace, app.graph.settingsView, app.graph.registryDirectory, app.graph.activityRecorder,
        computation = EmptyCoroutineContext,
    ).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `a space whose readers use a model off the phone says so, and where to`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnCloud(TeamModel(), name = "Example Cloud")
        val notes = app.space("Screen notes")
        app.letRead("Researcher", notes)
        app.letRead("Writer", notes)
        val viewModel = share(app)
        viewModel.select(ShareTarget.Space(notes))
        assertEquals(
            "Saves 1 memory in “Screen notes”. Researcher and Writer can recall it. They use a model off this phone " +
                "(Example Cloud), so what they recall from it is sent there.",
            viewModel.uiState.value.saveLine,
        )
    }

    @Test
    fun `a space nobody can read yet stays on the phone`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnCloud(TeamModel())
        val notes = app.space("Screen notes")
        val viewModel = share(app)
        viewModel.select(ShareTarget.Space(notes))
        assertEquals(
            "Saves 1 memory in “Screen notes”. No agent can read it yet, so it stays on this phone until you let one.",
            viewModel.uiState.value.saveLine,
        )
    }

    @Test
    fun `with local-only mode on, what agents recall stays on the phone`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnCloud(TeamModel())
        app.settings.update { it.copy(localOnlyMode = true) }
        val notes = app.space("Screen notes")
        app.letRead("Researcher", notes)
        val viewModel = share(app)
        viewModel.select(ShareTarget.Space(notes))
        assertEquals(
            "Saves 1 memory in “Screen notes”. Researcher can recall it. Local-only mode is on, so what Researcher recalls stays on this phone.",
            viewModel.uiState.value.saveLine,
        )
    }

    @Test
    fun `the words for readers on this phone, without a model, and in the workspace`() {
        assertEquals(
            "Researcher can recall it with a model on this phone, so it stays here.",
            ShareTexts.whatLeaves(listOf("Researcher"), emptyList(), emptyList(), localOnly = false, workspace = false),
        )
        assertEquals(
            "Researcher can recall it. It has no model yet: what Researcher recalls goes to the model you give it.",
            ShareTexts.whatLeaves(listOf("Researcher"), emptyList(), emptyList(), localOnly = false, workspace = false, noModel = listOf("Researcher")),
        )
        assertEquals(
            "Researcher and Writer can read it. Writer uses a model off this phone (xAI), so what Writer reads from it is sent there.",
            ShareTexts.whatLeaves(listOf("Researcher", "Writer"), listOf("Writer"), listOf("xAI"), localOnly = false, workspace = true),
        )
    }

    @Test
    fun `the line is right above Save, on screen at 360 dp`() {
        val line = "Saves 1 memory in “Screen notes”. Researcher can recall it. Researcher uses a model off this phone (xAI), so what Researcher recalls from it is sent there."
        val state = ShareUiState(
            isLoading = false,
            items = listOf(SharedItemUi("Train times", "9 words · 41 B", "The 8:14 to Leeds leaves from platform 3.", previewIsStart = false, parts = 1)),
            targets = listOf(ShareTargetUi(ShareTarget.Space(SpaceId("spc_notes")), "Screen notes", "Researcher can read it.", 0)),
            selected = ShareTarget.Space(SpaceId("spc_notes")),
            saveLine = line,
        )
        compose.setContent {
            AppTheme(darkTheme = true) {
                ShareContent(state, androidx.compose.foundation.text.input.TextFieldState(""), ShareActions({}, {}, {}, {}))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(line).assertIsDisplayed()
    }
}
