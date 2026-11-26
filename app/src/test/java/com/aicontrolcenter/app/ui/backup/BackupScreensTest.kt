package com.aicontrolcenter.app.ui.backup

import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.PickerResults
import com.aicontrolcenter.app.testing.ScriptedBackup
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.typeFast
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.core.backup.BackupProgress
import com.aicontrolcenter.data.backup.BackupTexts
import com.aicontrolcenter.data.backup.SavePassphrase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneOffset
import java.util.Random
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backup screens on screen (Tier B; rule 27): the window is secure while they show, every passphrase field takes
 * fast typing exactly (paused frame clock) and is hidden, the generated passphrase is spelled out for TalkBack, the key
 * step shows no measure and moves nothing under Calm, and the restore summary leads to one confirmed restore and a
 * restart.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class BackupScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val pickers = PickerResults()

    @After
    fun stop() = scope.cancel()

    private val secure: Boolean get() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun show(calm: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            val owner = remember { object : ActivityResultRegistryOwner { override val activityResultRegistry: ActivityResultRegistry = pickers } }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalCalmMotion provides calm) {
                AppTheme(darkTheme = true) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))

    private fun button(text: String) = compose.onAllNodes(hasText(text) and hasClickAction()).onFirst()

    private fun saver(backup: ScriptedBackup) = BackupSaveViewModel(backup, work = EmptyCoroutineContext, passphrase = SavePassphrase(Random(3)))

    @Test
    fun `the save screen keeps the window secure while it shows, and the generated passphrase is spelled out for TalkBack`() {
        val viewModel = saver(ScriptedBackup())
        val shown = mutableStateOf(true)
        show { if (shown.value) BackupSaveScreen(viewModel, suggestedName = "backup.aiccbackup", onBack = {}) }
        assertTrue(secure, "FLAG_SECURE while the passphrase can be seen")
        val groups = viewModel.uiState.value.generated!!.split('-')
        compose.onNode(hasContentDescription(spokenPassphrase(groups))).assertExists()
        assertTrue(spokenPassphrase(listOf("7KQ2", "M9XD")).startsWith("Passphrase, 2 groups: 7 K Q 2. M 9 X D"))
        button("Choose where to save it").assertIsNotEnabled()
        compose.runOnUiThread { shown.value = false }
        compose.waitForIdle()
        assertTrue(!secure, "the flag goes with the screen")
    }

    @Test
    fun `both own passphrase fields take fast typing exactly, and say why one cannot be used`() {
        val viewModel = saver(ScriptedBackup())
        show { BackupSaveScreen(viewModel, suggestedName = "backup.aiccbackup", onBack = {}) }
        button("Use my own passphrase").performScrollTo().performClick()
        compose.waitForIdle()
        field("Your passphrase").typeFast(compose, "aaaaaaa")
        assertEquals("aaaaaaa", viewModel.ownPassphrase.text.toString())
        compose.onAllNodesWithText(viewModel.ownProblem()!!, substring = true).onFirst().assertExists()

        viewModel.ownPassphrase.edit { replace(0, length, "") }
        compose.waitForIdle()
        field("Your passphrase").typeFast(compose, "copper lantern meadow violin harbour")
        field("The same passphrase again").typeFast(compose, "copper lantern meadow violin harbour")
        assertEquals("copper lantern meadow violin harbour", viewModel.ownPassphrase.text.toString())
        assertEquals("copper lantern meadow violin harbour", viewModel.ownPassphraseAgain.text.toString())
        compose.onAllNodesWithText("36 characters typed").onFirst().assertExists()
        field("Your passphrase").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        field("The same passphrase again").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        button("Choose where to save it").assertIsEnabled()
    }

    @Test
    fun `Save asks where the file goes, saves exactly the generated passphrase, and says what the file holds`() {
        val backup = ScriptedBackup()
        val viewModel = saver(backup)
        val file = File.createTempFile("backup", ".aiccbackup")
        pickers.next = Uri.fromFile(file)
        show { BackupSaveScreen(viewModel, suggestedName = "AI Control Center backup.aiccbackup", onBack = {}) }
        val generated = viewModel.uiState.value.generated
        button("I have written down this passphrase").performScrollTo().performClick()
        button("Choose where to save it").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Backup saved").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf<Any?>("AI Control Center backup.aiccbackup"), pickers.launched)
        assertEquals(generated, backup.saves.single().passphrase)
        assertEquals(3L, file.length(), "written through the picked document")
        compose.onAllNodesWithText("6 agents, 2 memories", substring = true).onFirst().assertExists()
    }

    @Test
    fun `the key step shows no measure and a Cancel, and under Calm nothing moves, only words`() {
        val backup = ScriptedBackup().apply {
            hold = CompletableDeferred()
            heldProgress = BackupProgress(BackupTexts.STEP_LOCKING, 0, null)
        }
        val viewModel = saver(backup)
        viewModel.setWroteItDown(true)
        show(calm = true) { BackupSaveScreen(viewModel, suggestedName = "b.aiccbackup", onBack = {}) }
        viewModel.save(open = { java.io.ByteArrayOutputStream() })
        compose.waitForIdle()
        compose.onAllNodesWithText(BackupTexts.STEP_LOCKING).onFirst().assertExists()
        compose.onAllNodesWithText(STILL_WORKING).onFirst().assertExists()
        compose.onAllNodesWithText(BackupTexts.KEEP_OPEN).onFirst().assertExists()
        val moving = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
        assertTrue(compose.onAllNodes(moving, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "no moving bar under Calm")

        button("Cancel").performScrollTo().performClick()
        compose.onNode(hasText("Stop") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(BackupSaveViewModel.CANCELLED).onFirst().assertExists()
    }

    @Test
    fun `without Calm the key step has a moving bar`() {
        val backup = ScriptedBackup().apply {
            hold = CompletableDeferred()
            heldProgress = BackupProgress(BackupTexts.STEP_LOCKING, 0, null)
        }
        val viewModel = saver(backup)
        viewModel.setWroteItDown(true)
        show(calm = false) { BackupSaveScreen(viewModel, suggestedName = "b.aiccbackup", onBack = {}) }
        viewModel.save(open = { java.io.ByteArrayOutputStream() })
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        assertTrue(compose.onAllNodesWithText(STILL_WORKING).fetchSemanticsNodes().isEmpty())
        compose.onNode(hasContentDescription("${BackupTexts.STEP_LOCKING}. This can take a while.")).assertExists()
        compose.mainClock.autoAdvance = true
        backup.hold?.complete(Unit)
    }

    // --- Restore ---

    private fun restorer(backup: ScriptedBackup, app: TestApp = TestApp(scope)) =
        BackupRestoreViewModel(backup, app.graph.registryDirectory, work = EmptyCoroutineContext, zone = { ZoneOffset.UTC })

    @Test
    fun `the restore screen is secure, its passphrase takes fast typing exactly, and the summary leads to one confirmed restore and a restart`() {
        val backup = ScriptedBackup()
        val viewModel = restorer(backup)
        val file = File.createTempFile("phone", ".aiccbackup").apply { writeBytes(ByteArray(16)) }
        pickers.next = Uri.fromFile(file)
        var restarts = 0
        show { BackupRestoreScreen(viewModel, onBack = {}, restart = { restarts++ }) }
        assertTrue(secure)
        button("Open the backup").assertIsNotEnabled()
        button("Choose the backup file").performScrollTo().performClick()
        compose.waitUntil(5_000) { viewModel.uiState.value.fileName != null && viewModel.uiState.value.fileName != BackupRestoreViewModel.CHOSEN }
        assertEquals(file.name, viewModel.uiState.value.fileName)

        field("Passphrase of the backup").typeFast(compose, "7KQ2-M9XD-AB12-CD34-EF56")
        assertEquals("7KQ2-M9XD-AB12-CD34-EF56", viewModel.passphrase.text.toString())
        button("Open the backup").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("7KQ2-M9XD-AB12-CD34-EF56"), backup.previews)
        compose.onNode(hasContentDescription("Settings that change on this phone")).performScrollTo().assertExists()
        compose.onNode(hasContentDescription("Local-only mode: now Off, after the restore On")).assertExists()
        compose.onNode(hasContentDescription("Daily limit of model answers: 1,000 model answers in the backup, 200 model answers after the restore")).assertExists()
        compose.onAllNodesWithText(BackupTexts.ALWAYS_OFF).onFirst().assertExists()
        compose.onAllNodesWithText(BackupTexts.WHAT_IS_NOT).onFirst().assertExists()

        button("Restore this backup").performScrollTo().performClick()
        compose.onNode(hasText("Restore") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(1, backup.restores.size)
        compose.onAllNodesWithText("Backup restored").onFirst().assertExists()
        assertEquals(0, restarts, "the owner reads what was restored first")
        button("Start the app again").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, restarts)
        assertNotNull(viewModel.uiState.value.restored)
    }

    @Test
    fun `a summary with a refusal has no Restore button`() {
        val backup = ScriptedBackup().apply {
            onPreview = { com.aicontrolcenter.core.backup.BackupResult.Done(ScriptedBackup.samplePreview(refusal = com.aicontrolcenter.core.backup.BackupProblem.NotEmpty)) }
        }
        val viewModel = restorer(backup)
        viewModel.fileChosen(open = { java.io.ByteArrayInputStream(ByteArray(4)) })
        viewModel.passphrase.edit { replace(0, length, "x") }
        show { BackupRestoreScreen(viewModel, onBack = {}, restart = {}) }
        viewModel.openBackup()
        compose.waitForIdle()
        compose.onAllNodesWithText(com.aicontrolcenter.core.backup.BackupProblem.NotEmpty.text).onFirst().assertExists()
        assertTrue(compose.onAllNodes(hasText("Restore this backup") and hasClickAction()).fetchSemanticsNodes().isEmpty())
        assertNull(viewModel.uiState.value.restored)
    }
}
