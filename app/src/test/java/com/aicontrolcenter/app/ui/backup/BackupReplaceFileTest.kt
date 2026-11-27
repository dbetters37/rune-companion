package com.aicontrolcenter.app.ui.backup

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
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
import com.aicontrolcenter.app.testing.wp10bGraph
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupProgress
import com.aicontrolcenter.core.backup.BackupResult
import com.aicontrolcenter.data.backup.BackupTexts
import com.aicontrolcenter.data.backup.DetailedOwnerBackup
import com.aicontrolcenter.data.backup.SavePassphrase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Random
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SEC-W3-1: Android's file picker can hand back a file that already exists ("Overwrite …?"). A save that is stopped,
 * refused or fails must leave that file exactly as it was; it is written only once the whole backup is ready. A new,
 * empty file the picker made is still removed when nothing is saved into it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class BackupReplaceFileTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val pickers = PickerResults()

    @After
    fun stop() = scope.cancel()

    private val earlier = "THE OWNER'S EARLIER BACKUP, ENCRYPTED".encodeToByteArray()

    private fun existingFile(): File = File.createTempFile("AI Control Center backup 2026-11-20", ".aiccbackup").apply { writeBytes(earlier) }

    private fun newEmptyFile(): File = File.createTempFile("AI Control Center backup 2026-11-27", ".aiccbackup")

    private fun showSave(backup: DetailedOwnerBackup, chosen: File): BackupSaveViewModel {
        val viewModel = BackupSaveViewModel(backup, work = EmptyCoroutineContext, passphrase = SavePassphrase(Random(3)))
        pickers.next = Uri.fromFile(chosen)
        compose.setContent {
            val owner = remember { object : ActivityResultRegistryOwner { override val activityResultRegistry: ActivityResultRegistry = pickers } }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                AppTheme(darkTheme = true) { BackupSaveScreen(viewModel, suggestedName = "AI Control Center backup.aiccbackup", onBack = {}) }
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasText("I have written down this passphrase") and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.onAllNodes(hasText("Choose where to save it") and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        return viewModel
    }

    private fun heldAtKeyStep() = ScriptedBackup().apply {
        hold = CompletableDeferred()
        heldProgress = BackupProgress(BackupTexts.STEP_LOCKING, 0, null)
    }

    private fun stopSaving() {
        compose.onAllNodes(hasText("Cancel") and hasClickAction()).onFirst().performScrollTo().performClick()
        compose.onNode(hasText("Stop") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(BackupSaveViewModel.CANCELLED, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test
    fun `stopping during the key step leaves the file the owner chose to replace exactly as it was`() {
        val existing = existingFile()
        showSave(heldAtKeyStep(), existing)
        stopSaving()
        assertTrue(existing.exists(), "the earlier backup is still there")
        assertContentEquals(earlier, existing.readBytes(), "byte for byte the same")
        compose.onAllNodesWithText("${BackupSaveViewModel.CANCELLED} ${BackupSaveViewModel.UNTOUCHED}").onFirst().assertExists()
    }

    @Test
    fun `a save the real backup refuses before writing (a restore runs) leaves the chosen file exactly as it was`() {
        val app = TestApp(scope)
        val graph = app.wp10bGraph(scope)
        val held = CompletableDeferred<Unit>()
        scope.launch { app.maintenanceGate.runExclusive { held.await() } }
        assertTrue(app.maintenanceGate.busy.value)
        val existing = existingFile()
        showSave(graph.ownerBackup!!, existing)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Not saved").fetchSemanticsNodes().isNotEmpty() }
        held.complete(Unit)
        assertTrue(existing.exists())
        assertContentEquals(earlier, existing.readBytes())
    }

    @Test
    fun `a save that fails half way through writing never touches the chosen file`() {
        val existing = existingFile()
        val backup = ScriptedBackup().apply {
            onSave = { out ->
                out.write(ByteArray(512) { 7 })
                BackupResult.Failed(BackupProblem.CannotOpenFile)
            }
        }
        showSave(backup, existing)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Not saved").fetchSemanticsNodes().isNotEmpty() }
        assertContentEquals(earlier, existing.readBytes(), "no partial backup in the owner's file")
    }

    @Test
    fun `a finished save replaces the chosen file with the whole new backup`() {
        val existing = existingFile()
        val backup = ScriptedBackup()
        showSave(backup, existing)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Backup saved").fetchSemanticsNodes().isNotEmpty() }
        assertContentEquals(ScriptedBackup.SAVED_BYTES, existing.readBytes())
        val staging = existing.parentFile!!.listFiles()!!.filter { it.name.startsWith(".") && it.name.contains(existing.name) }
        assertEquals(emptyList(), staging, "no half-made file is left next to it")
    }

    @Test
    fun `a new empty file the picker made is still removed when the owner stops`() {
        val made = newEmptyFile()
        showSave(heldAtKeyStep(), made)
        stopSaving()
        assertFalse(made.exists(), "the empty file the picker made is removed again")
    }
}
