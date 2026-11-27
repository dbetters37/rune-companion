package com.aicontrolcenter.app.ui.backup

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.MemoryChosenFile
import com.aicontrolcenter.app.testing.ScriptedBackup
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.stagingFile
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupProgress
import com.aicontrolcenter.core.backup.BackupResult
import com.aicontrolcenter.core.backup.Passphrases
import com.aicontrolcenter.core.backup.RestoreReport
import com.aicontrolcenter.core.backup.RestoreSettings
import com.aicontrolcenter.data.backup.BackupTexts
import com.aicontrolcenter.data.backup.RestoreOutcome
import com.aicontrolcenter.data.backup.SavePassphrase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.ZoneOffset
import java.util.Random
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backup screens' view models (Tier B; rules 8, 27, 30): the generated passphrase is the default and goes to the
 * save exactly as generated; an own one only through `Passphrases.problemForSave` and typed twice; a save that does not
 * finish removes its file; opening a file says what a restore would do, the Director by name; a wrong passphrase and a
 * damaged file say different things; a restore waits while work runs; the passphrases go when the screen closes.
 */
class BackupViewModelsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val strong = "copper lantern meadow violin harbour"

    private fun saver(backup: ScriptedBackup = ScriptedBackup()) =
        BackupSaveViewModel(backup, work = EmptyCoroutineContext, passphrase = SavePassphrase(Random(7)))

    // --- Save ---

    @Test
    fun `the generated passphrase is shown in groups and Save waits until the owner says it is written down`() = runTest(mainRule.dispatcher) {
        val backup = ScriptedBackup()
        val viewModel = saver(backup)
        val generated = assertNotNull(viewModel.uiState.value.generated)
        assertTrue(Passphrases.isGeneratedForm(generated))
        assertEquals(5, generated.split('-').size)
        assertFalse(viewModel.canSave(), "not before it is written down")

        viewModel.setWroteItDown(true)
        assertTrue(viewModel.canSave())
        val file = MemoryChosenFile()
        viewModel.save(file, stagingFile())
        val call = backup.saves.single()
        assertEquals(generated, call.passphrase)
        assertEquals(generated, call.generatedShown, "the save is told exactly what was generated and shown (NEW-1)")
        val saved = assertNotNull(viewModel.uiState.value.saved)
        assertEquals("6 agents, 2 memories.", saved.what)
        assertContentEquals(ScriptedBackup.SAVED_BYTES, file.bytes)
        assertNull(viewModel.uiState.value.generated, "shown once: gone after the save")
    }

    @Test
    fun `a new passphrase must be written down again`() = runTest(mainRule.dispatcher) {
        val viewModel = saver()
        val first = viewModel.uiState.value.generated
        viewModel.setWroteItDown(true)
        viewModel.newPassphrase()
        assertTrue(viewModel.uiState.value.generated != first)
        assertFalse(viewModel.uiState.value.wroteItDown)
        assertFalse(viewModel.canSave())
    }

    @Test
    fun `an own passphrase goes only through the rules, with their plain reasons, and must be typed twice alike`() = runTest(mainRule.dispatcher) {
        val backup = ScriptedBackup()
        val viewModel = saver(backup)
        viewModel.useOwn(true)
        assertNull(viewModel.ownProblem(), "an empty field is not scolded")
        assertFalse(viewModel.canSave())

        viewModel.ownPassphrase.setTextAndPlaceCursorAtEnd("password123")
        assertEquals(Passphrases.problemForSave("password123", null), viewModel.ownProblem())
        assertNotNull(viewModel.ownProblem())
        assertFalse(viewModel.canSave())

        viewModel.ownPassphrase.setTextAndPlaceCursorAtEnd(strong)
        assertNull(viewModel.ownProblem())
        assertFalse(viewModel.canSave(), "not before it is typed again")
        viewModel.ownPassphraseAgain.setTextAndPlaceCursorAtEnd("copper lantern meadow violin harbor")
        assertEquals(BackupSaveViewModel.NOT_THE_SAME, viewModel.againProblem())
        assertFalse(viewModel.canSave())
        viewModel.ownPassphraseAgain.setTextAndPlaceCursorAtEnd("Copper-Lantern-Meadow-Violin-Harbour")
        assertNull(viewModel.againProblem(), "case, spaces and dashes do not matter, as when it is typed in to restore")
        assertTrue(viewModel.canSave())

        viewModel.save(MemoryChosenFile(), stagingFile())
        val call = backup.saves.single()
        assertEquals(strong, call.passphrase)
        assertNull(call.generatedShown, "an own passphrase is never handed over as a generated one")
        assertEquals("", viewModel.ownPassphrase.text.toString(), "gone after the save")
        assertEquals("", viewModel.ownPassphraseAgain.text.toString())
    }

    @Test
    fun `a save that fails says why and removes the file the picker made`() = runTest(mainRule.dispatcher) {
        val backup = ScriptedBackup().apply { onSave = { BackupResult.Failed(BackupProblem.CannotOpenFile) } }
        val viewModel = saver(backup)
        viewModel.setWroteItDown(true)
        val file = MemoryChosenFile()
        viewModel.save(file, stagingFile())
        assertEquals(BackupProblem.CannotOpenFile.text, viewModel.uiState.value.failure)
        assertEquals(1, file.removed)
        assertFalse(viewModel.uiState.value.working)
        assertNotNull(viewModel.uiState.value.generated, "the same passphrase can be used again")
    }

    @Test
    fun `while the key is made the step has no measure, Cancel stops it, and the file is removed`() = runTest(mainRule.dispatcher) {
        val backup = ScriptedBackup().apply {
            hold = CompletableDeferred()
            heldProgress = BackupProgress(BackupTexts.STEP_LOCKING, 0, null)
        }
        val viewModel = saver(backup)
        viewModel.setWroteItDown(true)
        val file = MemoryChosenFile()
        viewModel.save(file, stagingFile())
        val working = viewModel.uiState.value
        assertTrue(working.working)
        assertEquals(BackupStepUi(BackupTexts.STEP_LOCKING, null), working.step)
        assertFalse(viewModel.canSave(), "one save at a time")

        viewModel.cancel()
        assertEquals(BackupSaveViewModel.CANCELLED, viewModel.uiState.value.failure)
        assertFalse(viewModel.uiState.value.working)
        assertEquals(1, file.removed)
    }

    @Test
    fun `a file that held something is never removed, and the owner is told it is as it was`() = runTest(mainRule.dispatcher) {
        val earlier = "EARLIER BACKUP".encodeToByteArray()
        val refused = ScriptedBackup().apply { onSave = { out -> out.write(ByteArray(64)); BackupResult.Failed(BackupProblem.CannotOpenFile) } }
        val viewModel = saver(refused)
        viewModel.setWroteItDown(true)
        val file = MemoryChosenFile(earlier.copyOf())
        val staging = stagingFile()
        viewModel.save(file, staging)
        assertContentEquals(earlier, file.bytes, "the partial backup went only into the private file")
        assertEquals(0, file.removed)
        assertEquals("${BackupProblem.CannotOpenFile.text} ${BackupSaveViewModel.UNTOUCHED}", viewModel.uiState.value.failure)
        assertFalse(staging.exists(), "the private file is removed after the save")
    }

    @Test
    fun `a backup that reports more than it wrote is not put into the chosen file`() = runTest(mainRule.dispatcher) {
        val short = ScriptedBackup().apply {
            onSave = { out ->
                out.write(ScriptedBackup.SAVED_BYTES, 0, 100)
                BackupResult.Done(com.aicontrolcenter.data.backup.SaveOutcome(com.aicontrolcenter.core.backup.SaveReport(com.aicontrolcenter.core.backup.BackupCounts(agents = 6), 2_048), emptyList()))
            }
        }
        val viewModel = saver(short)
        viewModel.setWroteItDown(true)
        val earlier = "EARLIER".encodeToByteArray()
        val file = MemoryChosenFile(earlier.copyOf())
        viewModel.save(file, stagingFile())
        assertContentEquals(earlier, file.bytes)
        assertNull(viewModel.uiState.value.saved)
        assertNotNull(viewModel.uiState.value.failure)
    }

    @Test
    fun `when the ready backup cannot be put into a file that held something, the owner is told plainly`() = runTest(mainRule.dispatcher) {
        val viewModel = saver()
        viewModel.setWroteItDown(true)
        val file = MemoryChosenFile("EARLIER".encodeToByteArray()).apply { failReplace = ReplaceFailed(chosenUntouched = false) }
        viewModel.save(file, stagingFile())
        assertEquals("${BackupSaveViewModel.NOT_WRITTEN} ${BackupSaveViewModel.MAY_BE_DAMAGED}", viewModel.uiState.value.failure)
        assertEquals(0, file.removed)
        assertNull(viewModel.uiState.value.saved)
    }

    @Test
    fun `steps with a measure become a fraction`() {
        assertEquals(0.25f, BackupStepUi.of(BackupProgress(BackupTexts.STEP_WRITING, 1, 4)).fraction)
        assertNull(BackupStepUi.of(BackupProgress(BackupTexts.STEP_LOCKING, 0, null)).fraction)
        assertEquals(1f, BackupStepUi.of(BackupProgress(BackupTexts.STEP_WRITING, 9, 4)).fraction)
        assertEquals("Writing the file: 25 percent", BackupStepUi.of(BackupProgress(BackupTexts.STEP_WRITING, 1, 4)).spoken)
        assertEquals(
            "AI Control Center backup 2026-11-14.aiccbackup",
            BackupSaveViewModel.suggestedName(TestApp.START_TIME, ZoneOffset.UTC),
        )
    }

    @Test
    fun `closing the screen forgets both passphrases (rule 27)`() = runTest(mainRule.dispatcher) {
        val viewModel = saver()
        viewModel.useOwn(true)
        viewModel.ownPassphrase.setTextAndPlaceCursorAtEnd(strong)
        viewModel.ownPassphraseAgain.setTextAndPlaceCursorAtEnd(strong)
        viewModel.forget()
        assertEquals("", viewModel.ownPassphrase.text.toString())
        assertEquals("", viewModel.ownPassphraseAgain.text.toString())
        assertFalse(viewModel.ownPassphrase.undoState.canUndo, "nothing to undo back into")
    }

    // --- Restore ---

    private fun kotlinx.coroutines.test.TestScope.restorer(
        backup: ScriptedBackup = ScriptedBackup(),
        busy: MutableStateFlow<String?> = MutableStateFlow(null),
        app: TestApp = TestApp(backgroundScope),
    ) = BackupRestoreViewModel(backup, app.graph.registryDirectory, work = EmptyCoroutineContext, busy = busy, zone = { ZoneOffset.UTC })

    private fun BackupRestoreViewModel.chooseAndType(passphrase: String) {
        fileChosen(open = { ByteArrayInputStream(ByteArray(8)) }, name = { "phone.aiccbackup" })
        this.passphrase.setTextAndPlaceCursorAtEnd(passphrase)
    }

    @Test
    fun `opening needs a file and a passphrase, and says what the backup holds and every setting that changes`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        val director = app.director()
        val backup = ScriptedBackup().apply { onPreview = { BackupResult.Done(ScriptedBackup.samplePreview(directorFrom = director.profile.id)) } }
        val viewModel = restorer(backup, app = app)
        assertFalse(viewModel.canOpen())
        viewModel.fileChosen(open = { ByteArrayInputStream(ByteArray(8)) }, name = { "phone.aiccbackup" })
        assertEquals("phone.aiccbackup", viewModel.uiState.value.fileName)
        assertFalse(viewModel.canOpen(), "not without the passphrase")
        viewModel.passphrase.setTextAndPlaceCursorAtEnd("7KQ2-M9XD-AAAA-BBBB-CCCC")
        assertTrue(viewModel.canOpen())

        viewModel.openBackup()
        assertEquals(listOf("7KQ2-M9XD-AAAA-BBBB-CCCC"), backup.previews)
        val summary = assertNotNull(viewModel.uiState.value.summary)
        assertEquals("6 agents, 1 provider, 2 models, 1 memory space, 2 memories, 1 chat, 4 chat messages.", summary.what)
        assertEquals("Made on 14 November 2026 at 12:00 by version 0.6.0 of the app.", summary.madeOn)
        assertEquals(SettingChangeUi("Local-only mode", "Off", "On"), summary.changes[0])
        assertEquals(SettingChangeUi("Director", "${director.profile.name} (on this phone now)", "Planner"), summary.changes[1])
        assertTrue(summary.changes.none { "agt_" in it.from || "agt_" in it.to }, "never an id")
        assertEquals(listOf(KeptBackUi("Daily limit of model answers", "1,000 model answers", "200 model answers")), summary.keptBack)
        assertEquals(BackupTexts.ALWAYS_OFF, summary.alwaysOff)
        assertEquals(listOf(BackupTexts.KEYS_AGAIN), summary.notes)
        assertNull(summary.refusal)
        assertTrue(viewModel.uiState.value.canRestore)
        assertTrue(backup.restores.isEmpty(), "opening restores nothing")
    }

    @Test
    fun `a Director the backup names is never shown by id, even when nothing on this phone has that name`() = runTest(mainRule.dispatcher) {
        val backup = ScriptedBackup().apply { onPreview = { BackupResult.Done(ScriptedBackup.samplePreview(directorFrom = AgentId("agt_gone"))) } }
        val viewModel = restorer(backup)
        viewModel.chooseAndType("abc")
        viewModel.openBackup()
        val director = viewModel.uiState.value.summary!!.changes[1]
        assertEquals(RestoreSettings.UNKNOWN_AGENT, director.from)
    }

    @Test
    fun `a wrong passphrase and a damaged file say different things, and a wrong one is typed again from the start`() = runTest(mainRule.dispatcher) {
        val backup = ScriptedBackup().apply {
            onPreview = { typed -> if (typed == "right") BackupResult.Done(ScriptedBackup.samplePreview()) else if (typed == "damaged") BackupResult.Failed(BackupProblem.Damaged) else BackupResult.Failed(BackupProblem.WrongPassphrase) }
        }
        val viewModel = restorer(backup)
        viewModel.chooseAndType("wrong")
        viewModel.openBackup()
        assertEquals(BackupProblem.WrongPassphrase.text, viewModel.uiState.value.failure)
        assertEquals("", viewModel.passphrase.text.toString(), "emptied: the hidden field is typed again")
        assertNull(viewModel.uiState.value.summary)

        viewModel.passphrase.setTextAndPlaceCursorAtEnd("damaged")
        viewModel.openBackup()
        assertEquals(BackupProblem.Damaged.text, viewModel.uiState.value.failure)
        assertTrue(BackupProblem.Damaged.text != BackupProblem.WrongPassphrase.text)
        assertEquals("damaged", viewModel.passphrase.text.toString(), "a damaged file is not the passphrase's fault")
    }

    @Test
    fun `a refusal known in advance and work that runs both keep Restore off`() = runTest(mainRule.dispatcher) {
        val busy = MutableStateFlow<String?>(null)
        val backup = ScriptedBackup().apply { onPreview = { BackupResult.Done(ScriptedBackup.samplePreview(refusal = BackupProblem.NotEmpty)) } }
        val viewModel = restorer(backup, busy)
        viewModel.chooseAndType("right")
        viewModel.openBackup()
        assertEquals(BackupProblem.NotEmpty.text, viewModel.uiState.value.summary!!.refusal)
        assertFalse(viewModel.uiState.value.canRestore)
        viewModel.restore()
        assertTrue(backup.restores.isEmpty())

        backup.onPreview = { BackupResult.Done(ScriptedBackup.samplePreview()) }
        viewModel.openBackup()
        assertTrue(viewModel.uiState.value.canRestore)
        busy.value = BackupRestoreViewModel.busyText(objectiveRunning = true, chatAnswering = false)
        assertEquals("An objective is running. Try again when it has finished.", viewModel.uiState.value.busy)
        assertFalse(viewModel.uiState.value.canRestore)
        viewModel.restore()
        assertTrue(backup.restores.isEmpty(), "refused while an objective runs")
        assertEquals("An agent is answering in a chat. Try again when it has finished.", BackupRestoreViewModel.busyText(false, true))
        busy.value = null
        assertTrue(viewModel.uiState.value.canRestore)
    }

    @Test
    fun `a restore says what it restored and its notes, the settings note included, and the passphrase goes`() = runTest(mainRule.dispatcher) {
        val backup = ScriptedBackup().apply {
            onRestore = {
                BackupResult.Done(
                    RestoreOutcome(
                        RestoreReport(com.aicontrolcenter.core.backup.BackupCounts(agents = 6, memories = 2), 0, emptyList()),
                        listOf(BackupTexts.SETTINGS_NOT_RESTORED),
                    ),
                )
            }
        }
        val viewModel = restorer(backup)
        viewModel.chooseAndType("right")
        viewModel.openBackup()
        viewModel.restore()
        assertEquals(listOf("right"), backup.restores)
        val restored = assertNotNull(viewModel.uiState.value.restored)
        assertEquals("6 agents, 2 memories.", restored.what)
        assertEquals(listOf(BackupTexts.SETTINGS_NOT_RESTORED), restored.notes)
        assertEquals("", viewModel.passphrase.text.toString())
        assertFalse(viewModel.uiState.value.canRestore, "once")
        assertFalse(viewModel.canOpen())
    }

    @Test
    fun `while the passphrase is checked Cancel stops, and once the records are written Cancel goes away`() = runTest(mainRule.dispatcher) {
        val backup = ScriptedBackup().apply {
            hold = CompletableDeferred()
            heldProgress = BackupProgress(BackupTexts.STEP_UNLOCKING, 0, null)
        }
        val viewModel = restorer(backup)
        viewModel.chooseAndType("right")
        viewModel.openBackup()
        assertTrue(viewModel.uiState.value.working)
        assertTrue(viewModel.uiState.value.cancellable)
        viewModel.cancel()
        assertEquals(BackupRestoreViewModel.CANCELLED_OPENING, viewModel.uiState.value.failure)
        assertFalse(viewModel.uiState.value.working)

        backup.hold = null
        viewModel.openBackup()
        backup.hold = CompletableDeferred()
        backup.heldProgress = BackupProgress(BackupTexts.STEP_RESTORING, 3, 10)
        viewModel.restore()
        assertFalse(viewModel.uiState.value.cancellable, "records being written are not stopped half way")
        viewModel.cancel()
        assertTrue(viewModel.uiState.value.working, "Cancel does nothing now")
        backup.hold?.complete(Unit)
        assertNotNull(viewModel.uiState.value.restored)
    }
}
