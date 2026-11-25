package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.FolderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SEC-W2B-2-R: a folder agents may change that another app's storage provider gives (a cloud drive, a file manager) may
 * hold the phone's own files, and Android does not say. The Folders screen is told so ([OwnerFolders.fromOtherAppStorage])
 * and shows a fixed sentence ([OwnerFolders.otherAppStorageNote]).
 */
class OwnerFoldersOtherAppTest {
    private val port = object : OwnerFolders {
        override fun observeAll(): Flow<List<SharedFolder>> = emptyFlow()

        override suspend fun add(treeUri: String): OwnerResult<SharedFolder> = error("not used")

        override suspend fun remove(id: FolderId): Boolean = error("not used")

        override suspend fun setReadOnly(id: FolderId, readOnly: Boolean): SharedFolder? = error("not used")
    }

    private fun folder(treeUri: String, readOnly: Boolean = false) =
        SharedFolder(FolderId("fld_1"), treeUri, "Agents", addedAt = 1, readOnly = readOnly, readOnlySince = if (readOnly) 1 else null)

    @Test
    fun `a folder agents may change that another app's storage provider gives is flagged`() {
        val cloud = "content://com.example.cloud.documents/tree/root%3AAgents"
        val files = "content://com.example.files.provider/tree/%2Fstorage%2Femulated%2F0%2FDownload%2FAgents"
        assertTrue(port.fromOtherAppStorage(folder(cloud)))
        assertTrue(port.fromOtherAppStorage(folder(files)))
        // Read-only: agents change nothing there.
        assertFalse(port.fromOtherAppStorage(folder(files, readOnly = true)))
        // The phone's own storage, downloads and media providers, also of another profile, are placed: no flag.
        assertFalse(port.fromOtherAppStorage(folder("content://${FolderTrees.EXTERNAL_STORAGE}/tree/primary%3ADownload%2FAgents")))
        assertFalse(port.fromOtherAppStorage(folder("content://${FolderTrees.DOWNLOADS}/tree/msf%3A31")))
        assertFalse(port.fromOtherAppStorage(folder("content://${FolderTrees.MEDIA}/tree/images_root")))
        assertFalse(port.fromOtherAppStorage(folder("content://10@${FolderTrees.EXTERNAL_STORAGE}/tree/primary%3ADocuments")))
        assertFalse(port.fromOtherAppStorage(folder("not a tree")))
    }

    @Test
    fun `the sentence names the app, or says another app when Android does not tell its name`() {
        assertEquals(
            "This folder comes from Files Pro. If Files Pro shows your phone's own files, agents could change files there even in " +
                "folders you made read-only. Make this folder read-only, or share folders from the phone's storage instead.",
            OwnerFolders.otherAppStorageNote("Files Pro"),
        )
        val unnamed = OwnerFolders.otherAppStorageNote(null)
        assertEquals(
            "This folder comes from another app. If that app shows your phone's own files, agents could change files there even in " +
                "folders you made read-only. Make this folder read-only, or share folders from the phone's storage instead.",
            unnamed,
        )
        assertEquals(unnamed, OwnerFolders.otherAppStorageNote(" \n\t "))
        // Another app's label is its text: one line, cut to a bounded length.
        val long = OwnerFolders.otherAppStorageNote("Files\nPro‮" + "x".repeat(500))
        assertFalse(long.any { it == '\n' || it == '‮' }, long)
        assertTrue(long.startsWith("This folder comes from Files Pro"), long)
        assertTrue(long.length < unnamed.length + 2 * OwnerFolders.MAX_APP_NAME_CHARS, "${long.length}")
    }
}
