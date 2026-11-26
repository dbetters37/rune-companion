package com.aicontrolcenter.app.share

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.screenshots.NARROW_WAVE3
import com.aicontrolcenter.app.screenshots.PHONE
import com.aicontrolcenter.app.screenshots.WIDE_WAVE3
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.testkit.Samples
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Pictures of WP16's share screen ("Save to memory", rule 34): one shared text with a space picked, several files with
 * parts left out, no space yet, local-only mode, reading, saving, saved and nothing to save. Dark, light, wide (900 dp),
 * 360 dp and font scale 2.0. Review material, recorded into `build/outputs/roborazzi/` (`ScreenshotTest`'s KDoc), not
 * golden files.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ShareScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/p6_wp16_$name.png")

    /** Another app's files behind content addresses. */
    private class Files : ShareSource {
        val files = LinkedHashMap<Uri, Triple<String, String, ByteArray>>()

        fun put(name: String, type: String, text: String): Uri =
            Uri.parse("content://com.example.recorder.files/export/${files.size + 1}").also { files[it] = Triple(name, type, text.toByteArray()) }

        override fun isOwnProvider(uri: Uri) = false

        override fun displayName(uri: Uri) = files[uri]?.first

        override fun type(uri: Uri) = files[uri]?.second

        override fun open(uri: Uri): InputStream? = files[uri]?.third?.let(::ByteArrayInputStream)
    }

    private fun world(settings: FrameworkSettings = FrameworkSettings(), spaces: Boolean = true): TestApp {
        val app = TestApp(scope, settings)
        if (spaces) runBlocking {
            val notes = app.graph.memoryService.createSpace(MemoryActor.Owner, "Screen notes", "What my screen recorder exports.").id
            app.graph.memoryService.createSpace(MemoryActor.Owner, "Recipes")
            app.graph.memoryService.createSpace(MemoryActor.Owner, "Team knowledge")
            app.agents.upsert(Samples.agent("agt_researcher", name = "Researcher").let { it.copy(memory = it.memory.copy(readableSpaces = setOf(notes))) })
            app.agents.upsert(Samples.agent("agt_writer", name = "Writer").let { it.copy(memory = it.memory.copy(readableSpaces = setOf(notes))) })
        }
        return app
    }

    private fun host(app: TestApp, request: ShareRequest, source: ShareSource = Files(), dark: Boolean = true) {
        val intake = ShareIntakeViewModel(request, source, io = EmptyCoroutineContext)
        compose.setContent { AppTheme(darkTheme = dark) { ShareHost(app.graph, intake, screenWork = null, onDone = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(ShareTexts.WHERE, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun oneText(app: TestApp, dark: Boolean = true) = host(
        app,
        ShareRequest(listOf(EXPORT), subject = "Tuesday's screens", streams = emptyList(), streamsLeftOut = 0, type = "text/plain"),
        dark = dark,
    )

    private fun pick(row: String) = compose.onNode(hasText(row) and hasClickAction()).performScrollTo().performClick()

    @Test
    fun oneTextDark() {
        oneText(world())
        shoot("share_text_dark_1_top")
        pick("Memory space “Screen notes”")
        shoot("share_text_dark_2_picked")
        compose.onNodeWithText(ShareTexts.STORING).performScrollTo()
        shoot("share_text_dark_3_bottom")
    }

    @Test
    fun oneTextLight() {
        oneText(world(), dark = false)
        pick("Memory space “Screen notes”")
        compose.onNodeWithText(ShareTexts.STORING).performScrollTo()
        shoot("share_text_light")
    }

    @Test
    @Config(qualifiers = WIDE_WAVE3)
    fun oneTextWide() {
        oneText(world())
        pick("Memory space “Screen notes”")
        shoot("share_text_wide")
        compose.onNodeWithText(ShareTexts.STORING).performScrollTo()
        shoot("share_text_wide_bottom")
    }

    @Test
    @Config(qualifiers = NARROW_WAVE3)
    fun oneTextNarrow() {
        oneText(world())
        shoot("share_text_360")
    }

    @Test
    @Config(qualifiers = NARROW_WAVE3, fontScale = 2.0f)
    fun oneTextLargeFont() {
        oneText(world())
        shoot("share_text_360_font2_1_top")
        pick("Memory space “Screen notes”")
        shoot("share_text_360_font2_2_picked")
        compose.onNode(hasText(ShareTexts.DONT_SAVE) and hasClickAction()).performScrollTo()
        shoot("share_text_360_font2_3_bottom")
    }

    @Test
    fun filesWithPartsLeftOut() {
        val files = Files()
        val uris = listOf(
            files.put("monday-screens.txt", "text/plain", "Monday 08:14 · Train app: the 08:32 to Leeds is 6 minutes late.\n" + LONG),
            files.put("plan.md", "text/markdown", "# Weekend\n- Pick up the parcel\n- Book the dentist"),
            files.put("photo.png", "image/png", "not text"),
        )
        host(world(), ShareRequest(emptyList(), null, uris, streamsLeftOut = 2, type = "text/*"), files)
        shoot("share_files_1_top")
        pick("Memory space “Screen notes”")
        shoot("share_files_2_picked")
    }

    @Test
    fun noSpaceYet() {
        oneText(world(spaces = false))
        pick(ShareTexts.WORKSPACE)
        shoot("share_no_space")
    }

    @Test
    fun localOnly() {
        oneText(world(FrameworkSettings(localOnlyMode = true)))
        pick("Memory space “Screen notes”")
        shoot("share_local_only")
    }

    // --- States shown directly: reading, saving, saved, nothing to save ---

    private val actions = ShareActions(onSelect = {}, onSave = {}, onStop = {}, onClose = {})

    private fun state(dark: Boolean = true, calm: Boolean = false, state: ShareUiState) {
        compose.setContent {
            AppTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalCalmMotion provides calm) { ShareContent(state, remember { TextFieldState("Tuesday's screens") }, actions) }
            }
        }
    }

    private val formState = ShareUiState(
        isLoading = false,
        items = listOf(SharedItemUi("Tuesday's screens", "About 2,400 words · 14\u00A0KB", EXPORT.take(400), previewIsStart = true, parts = 1)),
        targets = listOf(
            ShareTargetUi(ShareTarget.Space(SpaceId("spc_notes")), "Screen notes", "Researcher and Writer can read it.", 2),
            ShareTargetUi(ShareTarget.Workspace, ShareTexts.WORKSPACE, ShareTexts.WORKSPACE_DETAIL, 0),
        ),
        selected = ShareTarget.Space(SpaceId("spc_notes")),
        saveLine = "Saves 12 memories in “Screen notes”. " + ShareTexts.SPLIT,
        asksTitle = true,
    )

    @Test
    fun saving() {
        state(state = formState.copy(saving = ShareProgress(5, 12)))
        shoot("share_saving")
    }

    @Test
    fun saved() {
        state(state = formState.copy(done = ShareDone(ShareTexts.SAVED, "Saved in “Screen notes” as 12 memories, marked as shared from another app. Researcher and Writer can read it.")))
        shoot("share_saved")
    }

    @Test
    fun savedLight() {
        state(dark = false, state = formState.copy(done = ShareDone(ShareTexts.SAVED, "Saved in the workspace as “From other apps/Tuesday's screens.txt”.")))
        shoot("share_saved_light")
    }

    @Test
    fun nothingToSave() {
        state(state = ShareUiState(isLoading = false, leftOut = listOf("“photo.png” was left out: it is not a text file.", "“voice.m4a” was left out: it is not a text file.")))
        shoot("share_nothing")
    }

    @Test
    fun readingCalm() {
        state(calm = true, state = ShareUiState(isLoading = true))
        shoot("share_reading_calm")
    }

    private companion object {
        const val EXPORT = "Tuesday 07:52 · Bank app: balance shown, no payment made.\n" +
            "Tuesday 08:14 · Train app: the 08:32 to Leeds is 6 minutes late; platform 4.\n" +
            "Tuesday 12:30 · Café menu photographed: soup of the day is leek and potato.\n" +
            "Tuesday 18:05 · Messages: Sam asked whether Saturday still works.\n" +
            "Summary: a quiet day. Remember to answer Sam and to book the dentist."

        val LONG = "Screen text, line by line. ".repeat(1_200)
    }
}
