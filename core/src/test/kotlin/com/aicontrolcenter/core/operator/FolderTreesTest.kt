package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.pickup.PickUpSafety
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Security review of wave 1 (F1, second review) and of wave 2b (SEC-W2B-2): whether two shared folders may be the same
 * place on the phone, however the folder picker named them. The picker's "Downloads" entry gives trees of the downloads
 * provider ("downloads", "msd:<n>" as the device gate of wave 2b saw on Android 14, "msf:<n>" for files), which are
 * folders inside the phone's Download folder, a place the phone's storage entry names as "primary:Download/...". Unknown
 * (null) counts as overlapping wherever it protects something.
 */
class FolderTreesTest {
    private fun storage(id: String) = "content://${FolderTrees.EXTERNAL_STORAGE}/tree/" + id.replace(":", "%3A").replace("/", "%2F")

    private fun downloads(id: String) = "content://${FolderTrees.DOWNLOADS}/tree/" + id.replace(":", "%3A").replace("/", "%2F")

    private fun media(id: String) = "content://${FolderTrees.MEDIA}/tree/" + id.replace(":", "%3A").replace("/", "%2F")

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
    fun `raw paths name the phone's storage however they are written, and another profile's storage is unknown (SEC-W2B-2-R)`() {
        val inbox = storage("primary:Download/Agents/inbox")
        // /storage/self/primary is the phone's own storage, as /storage/emulated/0 and /sdcard are.
        assertEquals(true, FolderTrees.overlap(downloads("raw:/storage/self/primary/Download/Agents"), inbox))
        assertEquals(true, FolderTrees.overlap(inbox, downloads("raw:/storage/self/primary/Download")))
        assertEquals(true, FolderTrees.overlap(downloads("raw:/storage/self/primary"), storage("primary:Documents")))
        assertEquals(false, FolderTrees.overlap(downloads("raw:/storage/self/primary/Download/Other"), inbox))
        // Another profile (a work profile, a second user, the private space): /storage/emulated/10 is this app's own
        // storage when the app runs in that profile, another's when it does not. Unknown wherever the same place in one
        // profile would overlap; that counts as overlapping.
        assertNull(FolderTrees.overlap(downloads("raw:/storage/emulated/10/Download/Agents"), inbox))
        assertNull(FolderTrees.overlap(inbox, downloads("raw:/storage/emulated/10/Download/Agents")))
        assertNull(FolderTrees.overlap(downloads("raw:/storage/emulated/10/Download"), storage("primary:")))
        assertNull(FolderTrees.overlap(downloads("raw:/storage/emulated/10/Download/Agents"), downloads("raw:/storage/emulated/0/Download")))
        assertNull(FolderTrees.overlap(downloads("raw:/storage/emulated/10/Download/Agents"), downloads("raw:/storage/emulated/11/Download/Agents")))
        assertNull(FolderTrees.overlap(downloads("raw:/storage/emulated/10"), downloads("msf:31")), "a Download folder is in it")
        // Apart in every profile: apart. Within one profile: exact.
        assertEquals(false, FolderTrees.overlap(downloads("raw:/storage/emulated/10/Download/Other"), inbox))
        assertEquals(false, FolderTrees.overlap(downloads("raw:/storage/emulated/10/Download/Agents"), storage("primary:Documents")))
        assertEquals(false, FolderTrees.overlap(downloads("raw:/storage/emulated/10/Documents/Notes"), downloads("msf:31")))
        assertEquals(true, FolderTrees.overlap(downloads("raw:/storage/emulated/10/Download/Agents/inbox"), downloads("raw:/storage/emulated/10/Download/Agents")))
        // A raw path that names no place on the phone's storage the app knows, and a tree of the phone's storage or
        // downloads provider of another profile (its URI names the profile): unknown.
        assertNull(FolderTrees.overlap(downloads("raw:/data/media/0/Download/Agents"), inbox))
        val otherProfile = "content://10@${FolderTrees.EXTERNAL_STORAGE}/tree/primary%3ADocuments"
        assertNull(FolderTrees.overlap(otherProfile, storage("primary:Documents/Notes")))
        assertNull(FolderTrees.overlap(storage("primary:Music"), otherProfile))
        assertNull(FolderTrees.overlap("content://10@${FolderTrees.DOWNLOADS}/tree/msf%3A31", storage("primary:Documents")))
        assertEquals(true, FolderTrees.overlap(otherProfile, otherProfile))
    }

    @Test
    fun `a folder the Downloads entry gives on Android 11 and later (msd) lies in a Download folder (device gate, wave 2b)`() {
        // On Android 14 the picker's Downloads entry names a folder MediaStore has indexed "msd:<n>" (a MediaStore folder;
        // "msf:<n>" are files and never come back as a folder). These are the exact tree URIs the device gate measured:
        // GateDL in Download through the Downloads entry, its inner folder, and Documents/Notes through the storage entry.
        val gateDl = "content://com.android.providers.downloads.documents/tree/msd%3A1000000019"
        val notes = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FNotes"
        val inner = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FGateDL%2FInner"
        assertEquals(false, FolderTrees.overlap(gateDl, notes), "Documents/Notes is outside every Download folder")
        assertEquals(false, FolderTrees.overlap(notes, gateDl))
        assertNull(FolderTrees.overlap(gateDl, inner), "GateDL may hold Download/GateDL/Inner")
        assertNull(FolderTrees.overlap(inner, gateDl))
        // In or around a Download folder: unknown, as for msf. Elsewhere, and other providers: apart.
        assertNull(FolderTrees.overlap(downloads("msd:1000000019"), storage("primary:Download")))
        assertNull(FolderTrees.overlap(downloads("msd:1000000019"), storage("primary:")))
        assertNull(FolderTrees.overlap(downloads("msd:1000000019"), storage("1234-ABCD:Download/x")), "a memory card has a Download folder too")
        assertEquals(false, FolderTrees.overlap(downloads("msd:1000000019"), storage("1234-ABCD:Music")))
        assertEquals(false, FolderTrees.overlap(downloads("msd:1000000019"), storage("primary:Pictures")))
        assertEquals(false, FolderTrees.overlap(downloads("msd:1000000019"), storage("primary:Downloads2")))
        assertEquals(false, FolderTrees.overlap(downloads("msd:1000000019"), downloads("raw:/storage/emulated/0/Documents/Notes")))
        assertEquals(false, FolderTrees.overlap(downloads("msd:1000000019"), cloud))
        assertNull(FolderTrees.overlap(downloads("msd:1000000019"), downloads("raw:/storage/emulated/0/Download/GateDL")))
        // Within the downloads provider: two MediaStore folders may be one inside the other (GateDL and its Inner).
        assertNull(FolderTrees.overlap(downloads("msd:1000000019"), downloads("msd:1000000020")))
        assertNull(FolderTrees.overlap(downloads("msd:1000000019"), downloads("msf:31")))
        assertNull(FolderTrees.overlap(downloads("downloads"), downloads("msd:1000000019")))
        assertEquals(true, FolderTrees.overlap(gateDl, gateDl))
        // For folder pick-up the same, and the Folders screen does not count it as another app's folder.
        assertEquals(false, FolderTrees.mayShareFiles(gateDl, notes))
        assertNull(FolderTrees.mayShareFiles(gateDl, inner))
        assertNull(FolderTrees.otherAppProvider(gateDl))
        assertEquals(true, FolderTrees.fromDownloadsEntryUnplaced(gateDl))
        assertEquals(false, FolderTrees.fromDownloadsEntryUnplaced(downloads("raw:/storage/emulated/0/Download/GateDL")))
    }

    @Test
    fun `pick-up beside a writable Downloads-entry folder stops only in or around Download, and says why truthfully (device gate, wave 2b)`() {
        // The device gate's scenarios A and D with the ids Android 14 gives. A: GateDL through the Downloads entry, writable;
        // Inner (in GateDL) and the unrelated Documents/Notes read-only, each with a pick-up rule.
        val gateDl = SharedFolder(FolderId("fld_gatedl"), downloads("msd:1000000019"), "GateDL", addedAt = 1, readOnly = false)
        val inner = SharedFolder(FolderId("fld_inner"), storage("primary:Download/GateDL/Inner"), "Inner", addedAt = 2, readOnly = true, readOnlySince = 5)
        val notes = SharedFolder(FolderId("fld_notes"), storage("primary:Documents/Notes"), "Notes", addedAt = 3, readOnly = true, readOnlySince = 5)
        fun rule(folder: SharedFolder) =
            PickUpRule(PickUpRuleId("pku_" + folder.id.value), folder.id, SpaceId("spc_1"), enabled = true, createdAt = 1, updatedAt = 1, folderReadOnlySince = 5)
        val all = listOf(gateDl, inner, notes)
        assertNull(PickUpSafety.folderProblem(notes, all), "a rule on Documents/Notes stays possible")
        assertNull(PickUpSafety.runProblem(rule(notes), notes, all, masterSwitchOn = true), "a rule on Documents/Notes stays on")
        assertNull(PickUpSafety.runProblemBeside(notes, gateDl))
        assertEquals(false, PickUpSafety.overlapsWritable(notes, all))
        // Inner may be inside GateDL: refused and switched off, with a sentence that says Android does not tell where the
        // Downloads-entry folder is, not that Inner is inside it.
        val refusal = PickUpSafety.folderProblem(inner, all)!!
        val run = PickUpSafety.runProblem(rule(inner), inner, all, masterSwitchOn = true)!!
        val beside = PickUpSafety.runProblemBeside(inner, gateDl)!!
        for (sentence in listOf(refusal, run, beside)) {
            assertTrue("Downloads entry" in sentence, sentence)
            assertTrue("may be inside" in sentence, sentence)
            assertFalse("This folder is inside" in sentence || "this folder is inside" in sentence, sentence)
        }
        assertEquals(run, beside)
        assertEquals(PickUpSafety.FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE, refusal)
        assertEquals(PickUpSafety.RUN_FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE, run)
        assertTrue(PickUpSafety.switchesRuleOff(run))
        // D: the Downloads-entry folder is the read-only one with the rule, Inner writable: the same sentence.
        val readOnlyDl = gateDl.copy(readOnly = true, readOnlySince = 5)
        val writableInner = inner.copy(readOnly = false, readOnlySince = null)
        assertEquals(refusal, PickUpSafety.folderProblem(readOnlyDl, listOf(readOnlyDl, writableInner, notes)))
        assertEquals(run, PickUpSafety.runProblem(rule(readOnlyDl), readOnlyDl, listOf(readOnlyDl, writableInner, notes), masterSwitchOn = true))
        assertNull(PickUpSafety.folderProblem(readOnlyDl, listOf(readOnlyDl, notes.copy(readOnly = false, readOnlySince = null))))
    }

    @Test
    fun `pick-up beside a folder whose place cannot be told for another reason says so, not that one holds the other`() {
        // Another profile's folder and the media list: unknown, counted as overlapping, with its own sentence.
        val inbox = SharedFolder(FolderId("fld_inbox"), storage("primary:Documents/Inbox"), "Inbox", addedAt = 2, readOnly = true, readOnlySince = 5)
        val rule = PickUpRule(PickUpRuleId("pku_1"), inbox.id, SpaceId("spc_1"), enabled = true, createdAt = 1, updatedAt = 1, folderReadOnlySince = 5)
        for (uri in listOf("content://10@${FolderTrees.EXTERNAL_STORAGE}/tree/primary%3ADocuments", media("documents_root"))) {
            val other = SharedFolder(FolderId("fld_other"), uri, "Other", addedAt = 1, readOnly = false)
            val refusal = PickUpSafety.folderProblem(inbox, listOf(other, inbox))!!
            val run = PickUpSafety.runProblem(rule, inbox, listOf(other, inbox), masterSwitchOn = true)!!
            for (sentence in listOf(refusal, run)) {
                assertTrue("cannot tell" in sentence, sentence)
                assertTrue("another profile on this phone" in sentence, sentence)
                assertFalse("This folder is inside" in sentence || "this folder is inside" in sentence, sentence)
                assertFalse("Downloads" in sentence, sentence)
            }
            assertEquals(PickUpSafety.FOLDER_PLACE_UNKNOWN_NEAR_WRITABLE, refusal, uri)
            assertEquals(PickUpSafety.RUN_FOLDER_PLACE_UNKNOWN_NEAR_WRITABLE, run, uri)
            assertTrue(PickUpSafety.switchesRuleOff(run), uri)
            assertEquals(run, PickUpSafety.runProblemBeside(inbox, other))
        }
        // A folder that is certainly around it keeps the certain sentence.
        val docs = SharedFolder(FolderId("fld_docs"), storage("primary:Documents"), "Documents", addedAt = 1, readOnly = false)
        assertEquals(PickUpSafety.FOLDER_OVERLAPS_WRITABLE, PickUpSafety.folderProblem(inbox, listOf(docs, inbox)))
    }

    @Test
    fun `a folder of the downloads provider is apart from folders outside every Download folder`() {
        assertEquals(false, FolderTrees.overlap(downloads("msf:31"), storage("primary:Documents/Notes")))
        assertEquals(false, FolderTrees.overlap(downloads("msf:31"), storage("primary:Downloads2")))
        assertEquals(false, FolderTrees.overlap(downloads("msf:31"), cloud), "a cloud drive never holds the phone's files")
        assertEquals(false, FolderTrees.overlap(storage("primary:Download"), cloud))
    }

    @Test
    fun `the Downloads root picked as a folder may hold downloads saved anywhere on the phone (B1-DL)`() {
        // On Android 9 and 10 the picker lets the Downloads root itself be picked. It lists every file the download
        // manager fetched, wherever the downloading app saved it (Pictures, Documents, any path before Android 10), and
        // those files can be changed through it. So it may hold files of any folder on the phone's storage: unknown.
        assertNull(FolderTrees.overlap(downloads("downloads"), storage("primary:Pictures")))
        assertNull(FolderTrees.overlap(storage("primary:Pictures"), downloads("downloads")))
        assertNull(FolderTrees.overlap(downloads("downloads"), storage("primary:Documents")))
        assertNull(FolderTrees.overlap(downloads("downloads"), storage("1234-ABCD:Pictures")))
        assertNull(FolderTrees.overlap(downloads("downloads"), downloads("raw:/storage/emulated/0/Documents/Notes")))
        // A MediaStore folder of the Downloads entry is a folder in a Download folder (Android keeps them there): apart.
        assertEquals(false, FolderTrees.overlap(downloads("msf:31"), storage("primary:Pictures")))
        // A cloud drive never holds the phone's files the download manager fetched.
        assertEquals(false, FolderTrees.overlap(downloads("downloads"), cloud))
    }

    @Test
    fun `another app's storage provider stays apart from the phone's storage and is named for the Folders screen (SEC-W2B-2-R)`() {
        // A file manager's provider may show the phone's own files under ids of its own; Android does not tell. Counting it
        // as overlapping would stop changes in every such folder while any folder is read-only: apart, and the Folders
        // screen warns instead (OwnerFolders.fromOtherAppStorage). One that names folders by their path is compared by it
        // (the next test).
        val files = "content://com.example.files.provider/tree/d%3A1234"
        assertEquals(false, FolderTrees.overlap(files, storage("primary:Download/Agents/inbox")))
        assertEquals(false, FolderTrees.overlap(downloads("msf:31"), files))
        assertEquals("com.example.files.provider", FolderTrees.otherAppProvider(files))
        assertEquals("com.example.cloud.documents", FolderTrees.otherAppProvider(cloud))
        assertEquals("com.example.cloud.documents", FolderTrees.otherAppProvider("content://10@com.example.cloud.documents/tree/root%3AAgents"))
        // The system's own providers are placed instead, also of another profile; a URI that is no tree names nothing.
        assertNull(FolderTrees.otherAppProvider(storage("primary:Download")))
        assertNull(FolderTrees.otherAppProvider(downloads("msf:31")))
        assertNull(FolderTrees.otherAppProvider(media("images_bucket:123")))
        assertNull(FolderTrees.otherAppProvider("content://10@${FolderTrees.EXTERNAL_STORAGE}/tree/primary%3ADocuments"))
        assertNull(FolderTrees.otherAppProvider("not a tree"))
        // The media provider shows the phone's own files only: a folder of it is somewhere on the phone's storage.
        assertNull(FolderTrees.overlap(media("images_bucket:123"), storage("primary:DCIM")))
        assertNull(FolderTrees.overlap(storage("primary:Documents"), media("documents_root")))
        assertEquals(false, FolderTrees.overlap(media("images_bucket:123"), cloud))
    }

    @Test
    fun `another app's folder named by its path on the phone's storage is compared by that path (B1)`() {
        fun files(path: String, user: String = "") = "content://${user}com.example.files.provider/tree/" + path.replace("/", "%2F")
        val inbox = storage("primary:Download/Agents/inbox")
        // The verifier's mirror case: a file manager gives Download/Agents/inbox read-only, the phone's storage entry gave
        // Download/Agents for agents to change. And the other way round.
        assertEquals(true, FolderTrees.overlap(storage("primary:Download/Agents"), files("/storage/emulated/0/Download/Agents/inbox")))
        assertEquals(true, FolderTrees.overlap(files("/storage/emulated/0/Download/Agents"), inbox))
        assertEquals(true, FolderTrees.overlap(files("/sdcard/Download/Agents/"), inbox))
        assertEquals(true, FolderTrees.overlap(files("/storage/self/primary/Download"), inbox))
        assertEquals(true, FolderTrees.overlap(files("/storage/1234-ABCD/Music"), storage("1234-ABCD:Music/Old")))
        assertEquals(true, FolderTrees.overlap(files("/storage/emulated/0/Download"), "content://com.other.manager/tree/%2Fstorage%2Femulated%2F0%2FDownload%2FAgents"))
        assertEquals(false, FolderTrees.overlap(files("/storage/emulated/0/Download/Other"), inbox))
        assertEquals(false, FolderTrees.overlap(files("/storage/emulated/0/Pictures"), storage("primary:Documents")))
        // Where the path does not tell exactly: unknown, as for the phone's own providers.
        assertNull(FolderTrees.overlap(files("/storage/emulated/0/Download/Agents"), downloads("msf:31")))
        assertNull(FolderTrees.overlap(files("/storage/emulated/10/Download/Agents"), inbox))
        assertNull(FolderTrees.overlap(files("/storage/emulated/0/Download/Agents", user = "10@"), inbox), "another profile's file manager")
        // An id that is no such path says nothing about the phone's storage: apart for changes, as before.
        assertEquals(false, FolderTrees.overlap(files("/data/user/0/com.example.files/notes"), storage("primary:Documents")))
        assertEquals(false, FolderTrees.overlap(files("/storage-old/x"), storage("primary:")))
        // It is still another app's folder for the Folders screen.
        assertEquals("com.example.files.provider", FolderTrees.otherAppProvider(files("/storage/emulated/0/Download/Agents")))
    }

    @Test
    fun `for folder pick-up another app's folder may hold the same files as a folder of any other provider (B1)`() {
        // A file manager's provider that names folders by ids of its own may show the phone's storage. Changes stay apart
        // (the Folders screen warns), but pick-up reads only a folder no change can reach: unknown there.
        val files = "content://com.example.files.provider/tree/d%3A1234"
        val inbox = storage("primary:Download/Agents/inbox")
        assertEquals(false, FolderTrees.overlap(files, inbox))
        assertNull(FolderTrees.mayShareFiles(files, inbox))
        assertNull(FolderTrees.mayShareFiles(inbox, files))
        assertNull(FolderTrees.mayShareFiles(files, downloads("msf:31")))
        assertNull(FolderTrees.mayShareFiles(files, downloads("raw:/storage/emulated/0/Documents")))
        assertNull(FolderTrees.mayShareFiles(media("documents_root"), files))
        assertNull(FolderTrees.mayShareFiles(files, cloud), "a file manager may show a cloud drive too")
        assertNull(FolderTrees.mayShareFiles(files, "content://10@com.example.files.provider/tree/d%3A99"), "another profile's provider")
        // One provider tells by its ids; the phone's storage is placed exactly.
        assertEquals(false, FolderTrees.mayShareFiles(files, "content://com.example.files.provider/tree/d%3A5678"))
        assertEquals(true, FolderTrees.mayShareFiles(files, "content://com.example.files.provider/tree/d%3A1234%2Finbox"))
        assertEquals(false, FolderTrees.mayShareFiles(storage("primary:Documents"), inbox))
        assertEquals(true, FolderTrees.mayShareFiles(storage("primary:Download"), inbox))
        assertNull(FolderTrees.mayShareFiles(downloads("msf:31"), inbox))
        assertNull(FolderTrees.mayShareFiles("not a tree", inbox))
    }

    @Test
    fun `a pick-up rule beside another app's folder does not run while either may be changed by agents (B1)`() {
        val files = "content://com.example.files.provider/tree/d%3A1234"
        // The mirror case: the pick-up folder comes from a file manager, the folder agents may change from the phone's storage.
        val agents = SharedFolder(FolderId("fld_agents"), storage("primary:Download/Agents"), "Agents", addedAt = 1, readOnly = false)
        val inbox = SharedFolder(FolderId("fld_inbox"), files, "inbox", addedAt = 2, readOnly = true, readOnlySince = 5)
        val rule = PickUpRule(PickUpRuleId("pku_1"), inbox.id, SpaceId("spc_1"), enabled = true, createdAt = 1, updatedAt = 1, folderReadOnlySince = 5)
        assertEquals(PickUpSafety.FOLDER_OTHER_APP_NEAR_WRITABLE, PickUpSafety.folderProblem(inbox, listOf(agents, inbox)))
        assertEquals(PickUpSafety.RUN_FOLDER_OTHER_APP_NEAR_WRITABLE, PickUpSafety.runProblem(rule, inbox, listOf(agents, inbox), masterSwitchOn = true))
        assertEquals(true, PickUpSafety.overlapsWritable(inbox, listOf(agents, inbox)))
        // The other way round: pick-up from the phone's storage, a file manager's folder agents may change.
        val phoneInbox = inbox.copy(treeUri = storage("primary:Download/Agents/inbox"))
        val fileManager = agents.copy(treeUri = files)
        assertEquals(PickUpSafety.FOLDER_OTHER_APP_NEAR_WRITABLE, PickUpSafety.folderProblem(phoneInbox, listOf(fileManager, phoneInbox)))
        assertEquals(PickUpSafety.RUN_FOLDER_OTHER_APP_NEAR_WRITABLE, PickUpSafety.runProblem(rule, phoneInbox, listOf(fileManager, phoneInbox), masterSwitchOn = true))
        // A folder that may be the same place keeps its own sentence; read-only folders and the same provider's other
        // folders do not stop it.
        val around = agents.copy(id = FolderId("fld_download"), treeUri = storage("primary:Download"))
        assertEquals(PickUpSafety.FOLDER_OVERLAPS_WRITABLE, PickUpSafety.folderProblem(phoneInbox, listOf(fileManager, around, phoneInbox)))
        assertNull(PickUpSafety.folderProblem(phoneInbox, listOf(fileManager.copy(readOnly = true, readOnlySince = 1), phoneInbox)))
        val sameApp = agents.copy(treeUri = "content://com.example.files.provider/tree/d%3A5678")
        assertNull(PickUpSafety.folderProblem(inbox, listOf(sameApp, inbox)))
        assertNull(PickUpSafety.runProblem(rule, inbox, listOf(sameApp, inbox), masterSwitchOn = true))
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
        // Unknown counts as overlapping; since the device gate of wave 2b the sentence says that Android does not tell where
        // the Downloads-entry folder is, not that one holds the other.
        assertEquals(PickUpSafety.RUN_FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE, PickUpSafety.runProblem(rule, inbox, listOf(agents, inbox), masterSwitchOn = true))
        assertEquals(PickUpSafety.RUN_FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE, PickUpSafety.runProblem(rule, inbox, listOf(root, inbox), masterSwitchOn = true))
        assertEquals(PickUpSafety.FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE, PickUpSafety.folderProblem(inbox, listOf(agents, inbox)))
        assertTrue(PickUpSafety.switchesRuleOff(PickUpSafety.RUN_FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE))
        // A writable folder elsewhere does not stop it.
        val elsewhere = SharedFolder(FolderId("fld_docs"), storage("primary:Documents"), "Documents", addedAt = 1, readOnly = false)
        assertNull(PickUpSafety.runProblem(rule, inbox, listOf(elsewhere, inbox), masterSwitchOn = true))
    }
}
