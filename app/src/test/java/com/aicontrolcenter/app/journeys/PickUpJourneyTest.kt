package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.makeSpace
import com.aicontrolcenter.app.testing.runPickUp
import com.aicontrolcenter.app.testing.shareFolder
import com.aicontrolcenter.app.ui.pickup.PickUpRulesViewModel
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.pickup.PickUpSafety
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Folder pick-up as the owner uses it (Q1, Appendix A, F1; WP10c): a rule made from Tools › Folders for a read-only folder,
 * folder pick-up switched on in Settings after a second question, files picked up (and those already there left out), a
 * writable folder refused with "Make it read-only", and a folder made writable again stopping its rule.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class PickUpJourneyTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    private fun app(setUp: suspend TestApp.() -> Unit = {}): AppRobot =
        AppRobot(compose).given { graph.start().join(); setUp() }.launch().also { robot = it }

    @After
    fun stopApp() {
        robot?.close()
    }

    @Test
    fun `a rule made from a read-only folder picks up new text files only, once pick-up is switched on`() {
        val robot = app {
            val inbox = shareFolder("Inbox", readOnly = true)
            folderAccess.putFile(inbox.treeUri, "old.txt", "Already here before the rule.")
            makeSpace("Inbox notes")
        }
        // Tools › Folders: a read-only folder offers "Pick up new files".
        robot.openSectionViaMore("Tools").tap("Folders").press("Pick up new files from Inbox")
            .assertShown("New pick-up rule")
            .assertShown("Read-only for agents")
            .assertShown("Nothing is sent to a model by picking up")
            .tap("Inbox notes")
            .tap("Make the rule")
        val rule = runBlocking { robot.app.pickUpRuleStore.getAll().single() }
        assertTrue(rule.enabled)

        // Off after installing: switched on in Settings after a second question.
        robot.openSectionViaMore("Settings").tap("Folder pick-up").assertShown("Switch folder pick-up on?")
        assertFalse(runBlocking { robot.app.settings.get().folderPickUpEnabled })
        robot.tapInDialog("Switch on")
        assertTrue(runBlocking { robot.app.settings.get().folderPickUpEnabled })

        // Another app saves a note into the folder; the daily upkeep picks it up.
        robot.app.folderAccess.putFile(robot.app.sharedFolders.let { runBlocking { it.get(rule.folderId)!!.treeUri } }, "new.txt", "A note from the other app.")
        runBlocking { robot.app.runPickUp() }
        robot.tap("Open folder pick-up")
            .waitUntilShown("From “Inbox” into “Inbox notes”")
            .assertShown("Picked up 1 file.")
            .assertShown("new.txt")
            .assertShown("old.txt")
            .assertShown(PickUpRulesViewModel.LEFT_OUT)
        val memories = runBlocking {
            robot.app.graph.memoryService.observe(MemoryActor.Owner, MemoryFilter(scopes = setOf(MemoryScope.Shared(rule.spaceId)))).first()
        }
        assertEquals(listOf("new.txt"), memories.map { it.title }, "only the new file, never the one that was there before (F1)")
        assertEquals(ProvenanceOrigin.IMPORT, memories.single().provenance.origin)
    }

    @Test
    fun `a writable folder is refused until the owner makes it read-only, and making it writable again stops the rule`() {
        val robot = app {
            settings.update { it.copy(folderPickUpEnabled = true) }
            shareFolder("Exports", readOnly = false)
            makeSpace("Reading list")
        }
        robot.openSectionViaMore("Settings").tap("Open folder pick-up")
            .assertShown("No rules yet")
            .tap("New rule")
            .tap("Exports")
            .assertShown("This folder cannot be used yet")
            .assertShown(PickUpSafety.FOLDER_NOT_READ_ONLY)
            .tap("Make it read-only")
            .waitUntilShown("is read-only now: no agent can change anything in it.")
        val folder = runBlocking { robot.app.sharedFolders.getAll().single() }
        assertTrue(folder.readOnly)
        robot.tap("Reading list").tap("Make the rule").assertShown("From “Exports” into “Reading list”")
        val rule = runBlocking { robot.app.pickUpRuleStore.getAll().single() }

        // The owner lets agents change the folder again: the rule stops at once and says why.
        runBlocking { robot.app.graph.ownerFolders.setReadOnly(folder.id, false) }
        robot.waitUntilShown("Stopped").assertShown(PickUpSafety.RUN_FOLDER_NOT_READ_ONLY)
        robot.tap("Switch on again").waitUntilShown(PickUpSafety.FOLDER_NOT_READ_ONLY)
        assertFalse(runBlocking { robot.app.pickUpRuleStore.get(rule.id)!!.enabled }, "not while agents may change the folder")
    }
}
