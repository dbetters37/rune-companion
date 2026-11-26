package com.aicontrolcenter.app.ui.pickup

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.downloadsEntryFolder
import com.aicontrolcenter.app.testing.makePickUpRule
import com.aicontrolcenter.app.testing.makeSpace
import com.aicontrolcenter.app.testing.otherAppFolder
import com.aicontrolcenter.app.testing.phoneFolder
import com.aicontrolcenter.app.testing.runPickUp
import com.aicontrolcenter.app.testing.shareFolder
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.pickup.PickUpSafety
import com.aicontrolcenter.framework.pickup.DefaultOwnerPickUpRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Folder pick-up list and its editor (WP10c; Appendix A, F1, SEC-W2B-2, B1): what each rule picked up and left out, a
 * folder agents may change stopping its rule, every refusal of the owner's port shown as it is, and "Make it read-only".
 */
class PickUpViewModelsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.list(app: TestApp) = PickUpRulesViewModel(
        app.graph.ownerPickUpRules!!, app.graph.pickUpImports!!, app.graph.ownerFolders, app.graph.memoryService, app.graph.settingsView, app.clock, app.graph.zone,
    ).also { it.uiState.activeIn(backgroundScope) }

    private fun TestScope.editor(app: TestApp, ruleId: PickUpRuleId? = null, folderId: FolderId? = null) = PickUpRuleEditorViewModel(
        ruleId, folderId, app.graph.ownerPickUpRules!!, app.graph.ownerFolders, app.graph.memoryService,
    ).also { it.uiState.activeIn(backgroundScope) }

    // --- the list ---

    @Test
    fun `a rule shows its folder and space, what the last pick-up did, and its files, those left out saying why`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val inbox = app.shareFolder("Inbox", readOnly = true)
        app.folderAccess.putFile(inbox.treeUri, "old.txt", "Already in the folder.")
        val rule = app.makePickUpRule(inbox, app.makeSpace("Inbox notes"))
        val viewModel = list(app)
        var state = viewModel.uiState.value
        assertFalse(state.masterOn, "folder pick-up is off after installing")
        var row = state.rules.single()
        assertEquals("Inbox", row.folder)
        assertEquals("Inbox notes", row.space)
        assertEquals("On", row.status)
        assertEquals("Not looked at yet", row.lastRun)
        assertEquals(listOf("old.txt"), row.files.map { it.name })
        assertEquals(PickUpRulesViewModel.LEFT_OUT, row.files.single().line, "a file there before the rule is never picked up (F1)")
        assertEquals("1 of 10 rules", state.count)

        app.settings.update { it.copy(folderPickUpEnabled = true) }
        app.folderAccess.putFile(inbox.treeUri, "new.txt", "A note from the other app.")
        app.runPickUp()
        state = viewModel.uiState.value
        assertTrue(state.masterOn)
        row = state.rules.single()
        assertTrue(row.lastRun.startsWith("Last looked today at "), row.lastRun)
        assertEquals("Picked up 1 file.", row.lastResult)
        assertEquals(listOf("new.txt", "old.txt"), row.files.map { it.name })
        assertTrue(row.files[0].line.startsWith("Picked up today at ") && row.files[0].line.endsWith("· 26 bytes"), row.files[0].line)
        assertFalse(row.files[0].leftOut)
        assertTrue(row.files[1].leftOut)
        // What was picked up is an import in the rule's space, never the owner's own words.
        val memory = app.graph.memoryService.observe(MemoryActor.Owner, MemoryFilter(scopes = setOf(MemoryScope.Shared(rule.spaceId)))).first().single()
        assertEquals(ProvenanceOrigin.IMPORT, memory.provenance.origin)
    }

    @Test
    fun `a folder made writable stops its rule, which says why, and it cannot be switched on until the folder is read-only again`() =
        runTest(mainRule.dispatcher) {
            val app = TestApp(backgroundScope)
            val inbox = app.shareFolder("Inbox", readOnly = true)
            val rule = app.makePickUpRule(inbox, app.makeSpace())
            val viewModel = list(app)
            app.graph.ownerFolders.setReadOnly(inbox.id, false)
            var row = viewModel.uiState.value.rules.single()
            assertFalse(row.enabled)
            assertTrue(row.stoppedForSafety)
            assertEquals("Stopped", row.status)
            assertEquals(StatusTone.ATTENTION, row.tone)
            assertEquals(PickUpSafety.RUN_FOLDER_NOT_READ_ONLY, row.lastResult)

            viewModel.setEnabled(rule.id, true)
            assertEquals(PickUpSafety.FOLDER_NOT_READ_ONLY, viewModel.uiState.value.message?.text)
            assertFalse(app.pickUpRuleStore.get(rule.id)!!.enabled)

            app.graph.ownerFolders.setReadOnly(inbox.id, true)
            viewModel.setEnabled(rule.id, true)
            assertEquals(PickUpRulesViewModel.SWITCHED_ON, viewModel.uiState.value.message?.text)
            row = viewModel.uiState.value.rules.single()
            assertTrue(row.enabled)
            assertEquals("On", row.status)
        }

    @Test
    fun `a rule whose folder or space went away says so in words`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val inbox = app.shareFolder("Inbox", readOnly = true)
        val space = app.makeSpace()
        app.makePickUpRule(inbox, space)
        val viewModel = list(app)
        app.graph.memoryService.deleteSpace(MemoryActor.Owner, space.id)
        app.graph.ownerFolders.remove(inbox.id)
        val row = viewModel.uiState.value.rules.single()
        assertEquals(PickUpRulesViewModel.FOLDER_REMOVED, row.folder)
        assertEquals(PickUpRulesViewModel.SPACE_REMOVED, row.space)
    }

    // --- the editor ---

    @Test
    fun `a writable folder is refused, Make it read-only makes it usable, and the rule is made`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val inbox = app.shareFolder("Inbox", readOnly = false)
        val space = app.makeSpace()
        val viewModel = editor(app, folderId = inbox.id)
        var state = viewModel.uiState.value
        assertTrue(state.isNew)
        assertEquals(inbox.id, state.selectedFolder, "the folder the owner came from is chosen")
        assertEquals(space.id, state.selectedSpace, "the only space is chosen")
        assertEquals(PickUpSafety.FOLDER_NOT_READ_ONLY, state.folder?.problem)
        assertTrue(state.canMakeReadOnly)
        assertFalse(state.canSave)

        viewModel.makeReadOnly()
        state = viewModel.uiState.value
        assertTrue(app.sharedFolders.get(inbox.id)!!.readOnly)
        assertEquals("“Inbox” is read-only now: no agent can change anything in it.", state.message?.text)
        assertNull(state.folder?.problem)
        assertFalse(state.canMakeReadOnly)
        assertTrue(state.canSave)

        viewModel.save()
        val made = app.pickUpRuleStore.getAll().single()
        assertEquals(inbox.id, made.folderId)
        assertEquals(space.id, made.spaceId)
        assertTrue(made.enabled)
        assertEquals(made.id, viewModel.uiState.value.saved)
    }

    @Test
    fun `every refusal of the owner's port is shown as it is`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.makeSpace()
        // Inside a shared folder agents may change: the same place.
        app.shareFolder("Documents", readOnly = false)
        val inner = app.shareFolder("Inner", readOnly = true, treeUri = phoneFolder("Documents/Inner"))
        // Beside a folder of the Downloads entry agents may change: Android does not tell where it is.
        app.shareFolder("Shared downloads", readOnly = false, treeUri = downloadsEntryFolder("msd:12"))
        val notes = app.shareFolder("Notes", readOnly = true, treeUri = phoneFolder("Download/Notes"))
        // Beside another app's folder agents may change: that app may show the same files.
        app.shareFolder("Work", readOnly = false, treeUri = otherAppFolder("Work"))
        val viewModel = editor(app)
        val problems = viewModel.uiState.value.folders.associate { it.name to it.problem }
        assertEquals(PickUpSafety.FOLDER_NOT_READ_ONLY, problems["Documents"])
        assertEquals(PickUpSafety.FOLDER_OVERLAPS_WRITABLE, problems["Inner"])
        assertEquals(PickUpSafety.FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE, problems["Notes"])

        viewModel.selectFolder(inner.id)
        assertFalse(viewModel.uiState.value.canMakeReadOnly, "it is read-only already: the other folder is the problem")
        viewModel.save()
        assertEquals(PickUpSafety.FOLDER_OVERLAPS_WRITABLE, viewModel.uiState.value.problem, "the port refuses it too")
        viewModel.selectFolder(notes.id)
        viewModel.save()
        assertEquals(PickUpSafety.FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE, viewModel.uiState.value.problem)
        assertTrue(app.pickUpRuleStore.getAll().isEmpty(), "nothing was made")
    }

    @Test
    fun `another app's folder beside a phone folder is refused with its own sentence`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.makeSpace()
        app.shareFolder("Agents", readOnly = false, treeUri = phoneFolder("Agents"))
        val cloud = app.shareFolder("Work", readOnly = true, treeUri = otherAppFolder("Work"))
        val viewModel = editor(app, folderId = cloud.id)
        assertEquals(PickUpSafety.FOLDER_OTHER_APP_NEAR_WRITABLE, viewModel.uiState.value.folder?.problem)
        viewModel.save()
        assertEquals(PickUpSafety.FOLDER_OTHER_APP_NEAR_WRITABLE, viewModel.uiState.value.problem)
    }

    @Test
    fun `a folder that cannot be read just now is refused, and a folder with a rule is not offered twice`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val space = app.makeSpace()
        val inbox = app.shareFolder("Inbox", readOnly = true)
        val other = app.shareFolder("Other", readOnly = true)
        app.makePickUpRule(other, space)
        val viewModel = editor(app, folderId = inbox.id)
        assertEquals(PickUpRuleEditorViewModel.HAS_RULE, viewModel.uiState.value.folders.single { it.id == other.id }.problem)
        assertTrue(viewModel.uiState.value.folders.single { it.id == other.id }.hasRule)
        app.folderAccess.failNext = com.aicontrolcenter.core.operator.DocResult.Failed("unavailable")
        viewModel.save()
        assertEquals(PickUpSafety.FOLDER_UNREADABLE, viewModel.uiState.value.problem)
        assertEquals(1, app.pickUpRuleStore.getAll().size)
    }

    @Test
    fun `without a space nothing can be saved, and the eleventh rule is refused`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val inbox = app.shareFolder("Inbox", readOnly = true)
        val viewModel = editor(app, folderId = inbox.id)
        assertTrue(viewModel.uiState.value.spaces.isEmpty())
        assertFalse(viewModel.uiState.value.canSave)
        viewModel.save()
        assertEquals(PickUpRuleEditorViewModel.CHOOSE_SPACE, viewModel.uiState.value.problem)

        val space = app.makeSpace()
        repeat(10) { app.makePickUpRule(app.shareFolder("Folder $it", readOnly = true), space) }
        viewModel.selectSpace(space.id)
        viewModel.save()
        assertEquals(DefaultOwnerPickUpRules.TOO_MANY, viewModel.uiState.value.problem)
    }

    @Test
    fun `an existing rule changes its space, switches off and on, and is deleted after asking`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val inbox = app.shareFolder("Inbox", readOnly = true)
        val first = app.makeSpace("Inbox notes")
        val second = app.makeSpace("Reading list")
        val rule = app.makePickUpRule(inbox, first)
        val viewModel = editor(app, ruleId = rule.id)
        var state = viewModel.uiState.value
        assertFalse(state.isNew)
        assertEquals("Inbox", state.folder?.name)
        assertEquals(first.id, state.selectedSpace)
        assertTrue(state.enabled)

        val logged = app.activityLog.all.size
        viewModel.save()
        assertEquals(rule.id, viewModel.uiState.value.saved, "the same space: the page just goes back")
        assertEquals(logged, app.activityLog.all.size, "nothing changed, nothing logged")
        viewModel.savedHandled()
        viewModel.selectSpace(second.id)
        viewModel.save()
        assertEquals(second.id, app.pickUpRuleStore.get(rule.id)!!.spaceId)
        assertEquals(rule.id, viewModel.uiState.value.saved)
        viewModel.savedHandled()

        viewModel.setEnabled(false)
        assertFalse(app.pickUpRuleStore.get(rule.id)!!.enabled)
        viewModel.setEnabled(true)
        assertTrue(app.pickUpRuleStore.get(rule.id)!!.enabled)
        assertEquals(PickUpRulesViewModel.SWITCHED_ON, viewModel.uiState.value.message?.text)

        viewModel.askDelete()
        assertTrue(viewModel.uiState.value.confirmDelete)
        viewModel.confirmDelete()
        assertNull(app.pickUpRuleStore.get(rule.id))
        state = viewModel.uiState.value
        assertTrue(state.deleted)
        assertFalse(state.missing)
    }
}
