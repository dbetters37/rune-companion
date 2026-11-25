package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.pickup.PickUpSafety
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Security review of wave 1 (F1, second review) and of wave 2b (SEC-W2B-2): whether two shared folders may be the same
 * place on the phone, however the folder picker named them. The picker's "Downloads" entry gives trees of the downloads
 * provider ("downloads", "msf:<n>"), which are folders inside the phone's Download folder, a place the phone's storage
 * entry names as "primary:Download/...". Unknown (null) counts as overlapping wherever it protects something.
 */
class FolderTreesTest {
    private fun storage(id: String) = "content://${FolderTrees.EXTERNAL_STORAGE}/tree/" + id.replace(":", "%3A").replace("/", "%2F")

    private fun downloads(id: String) = "content://${FolderTrees.DOWNLOADS}/tree/" + id.replace(":", "%3A").replace("/", "%2F")

    private val cloud = "content://com.example.cloud.documents/tree/root%3AAgents"

    @Test
    fun `trees of the phone's storage overlap when one holds the other`() {
        assertEquals(true, FolderTrees.overlap(storage("primary:Documents"), storage("primary:Documents/Inbox")))
        assertEquals(true, FolderTrees.overlap(storage("primary:"), storage("primary:Documents/Inbox")))
        assertEquals(false, FolderTrees.overlap(storage("primary:Documents"), storage("primary:DocumentsOld")))
        assertEquals(false, FolderTrees.overlap(storage("primary:Documents"), storage("1234-ABCD:Documents")))
        assertEquals(true, FolderTrees.overlap(storage("1234-ABCD:"), storage("1234-ABCD:Documents")))
    }

    @Test
    fun `the downloads provider's own folders may be any folder inside a Download folder (SEC-W2B-2)`() {
        val inbox = storage("primary:Download/Agents/inbox")
        assertNull(FolderTrees.overlap(downloads("msf:31"), inbox), "a MediaStore folder may be Download/Agents")
        assertNull(FolderTrees.overlap(inbox, downloads("msf:31")))
        assertNull(FolderTrees.overlap(downloads("downloads"), inbox), "the Downloads root holds Download/Agents/inbox")
        assertNull(FolderTrees.overlap(downloads("msf:31"), storage("primary:Download")))
        assertNull(FolderTrees.overlap(downloads("msf:31"), storage("primary:")), "the phone's storage holds every Download folder")
        assertNull(FolderTrees.overlap(downloads("msf:31"), storage("1234-ABCD:Download/x")), "a memory card has a Download folder too")
        // Within the downloads provider: different folders may be one inside the other.
        assertNull(FolderTrees.overlap(downloads("msf:31"), downloads("msf:40")))
        assertNull(FolderTrees.overlap(downloads("downloads"), downloads("msf:40")))
        assertNull(FolderTrees.overlap(downloads("msf:31"), downloads("raw:/storage/emulated/0/Download/Agents")))
        assertEquals(true, FolderTrees.overlap(downloads("msf:31"), downloads("msf:31")))
        // Its "raw:" folders are paths.
        assertEquals(true, FolderTrees.overlap(downloads("raw:/storage/emulated/0/Download/Agents"), inbox))
        assertEquals(true, FolderTrees.overlap(downloads("raw:/sdcard/Download/Agents"), inbox))
        assertEquals(false, FolderTrees.overlap(downloads("raw:/storage/emulated/0/Download/Other"), inbox))
    }

    @Test
    fun `a folder of the downloads provider is apart from folders outside every Download folder`() {
        assertEquals(false, FolderTrees.overlap(downloads("msf:31"), storage("primary:Documents/Notes")))
        assertEquals(false, FolderTrees.overlap(downloads("downloads"), storage("primary:Documents")))
        assertEquals(false, FolderTrees.overlap(downloads("msf:31"), storage("primary:Downloads2")))
        assertEquals(false, FolderTrees.overlap(downloads("msf:31"), cloud), "a cloud drive never holds the phone's files")
        assertEquals(false, FolderTrees.overlap(storage("primary:Download"), cloud))
    }

    @Test
    fun `places the picker names in other ways count as the phone's storage`() {
        // The storage provider's "home" root is the Documents folder; folder names on the phone's storage ignore case.
        assertEquals(true, FolderTrees.overlap(storage("home:Notes"), storage("primary:Documents/Notes/inbox")))
        assertEquals(true, FolderTrees.overlap(storage("primary:download/agents"), storage("primary:Download/Agents/inbox")))
        // An id the phone's storage provider gives without a volume: anywhere on the phone.
        assertNull(FolderTrees.overlap(storage("odd"), storage("primary:Documents")))
        assertNull(FolderTrees.overlap(downloads("12"), storage("primary:Documents")))
        assertNull(FolderTrees.overlap("not a tree", storage("primary:Documents")))
    }

    @Test
    fun `a pick-up rule on a folder inside one shared through the Downloads entry for agents to change does not run (SEC-W2B-2)`() {
        val agents = SharedFolder(FolderId("fld_agents"), downloads("msf:31"), "Agents", addedAt = 1, readOnly = false)
        val root = SharedFolder(FolderId("fld_root"), downloads("downloads"), "Downloads", addedAt = 1, readOnly = false)
        val inbox = SharedFolder(FolderId("fld_inbox"), storage("primary:Download/Agents/inbox"), "inbox", addedAt = 2, readOnly = true, readOnlySince = 5)
        val rule = PickUpRule(PickUpRuleId("pku_1"), inbox.id, SpaceId("spc_1"), enabled = true, createdAt = 1, updatedAt = 1, folderReadOnlySince = 5)
        assertEquals(PickUpSafety.RUN_FOLDER_OVERLAPS_WRITABLE, PickUpSafety.runProblem(rule, inbox, listOf(agents, inbox), masterSwitchOn = true))
        assertEquals(PickUpSafety.RUN_FOLDER_OVERLAPS_WRITABLE, PickUpSafety.runProblem(rule, inbox, listOf(root, inbox), masterSwitchOn = true))
        assertEquals(PickUpSafety.FOLDER_OVERLAPS_WRITABLE, PickUpSafety.folderProblem(inbox, listOf(agents, inbox)))
        // A writable folder elsewhere does not stop it.
        val elsewhere = SharedFolder(FolderId("fld_docs"), storage("primary:Documents"), "Documents", addedAt = 1, readOnly = false)
        assertNull(PickUpSafety.runProblem(rule, inbox, listOf(elsewhere, inbox), masterSwitchOn = true))
    }
}
