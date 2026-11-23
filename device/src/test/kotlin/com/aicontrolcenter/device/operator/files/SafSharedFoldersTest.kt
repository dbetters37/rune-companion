package com.aicontrolcenter.device.operator.files

import android.app.Application
import android.content.Intent
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.DocumentEntry
import com.aicontrolcenter.core.operator.FileContent
import com.aicontrolcenter.core.operator.FolderAccess
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.testkit.InMemorySharedFolderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SafSharedFoldersTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val provider = FakeDocumentsProvider.install()
    private val repository = InMemorySharedFolderRepository()
    private val folders = SafSharedFolders(app, repository, Dispatchers.Unconfined)
    private val treeUri = provider.tree("Documents")
    private val rootId = provider.rootIdOf(treeUri)
    private val id = FolderId("fld_docs")
    private val workspace = Files.createTempDirectory("aicc-ws").toFile()

    private fun p(path: String) = RelativePath.of(path)

    private fun ws(path: String): WorkspaceFile = (WorkspaceFile.resolve(workspace, p(path)) as DocResult.Ok).value

    /** The owner picks the tree and stores it, as OwnerFolders.add does. */
    private suspend fun picked(readOnly: Boolean = false): SharedFolder {
        assertEquals(FolderAccess.Granted("Documents"), folders.takeAccess(treeUri.toString()))
        return SharedFolder(id, treeUri.toString(), "Documents", addedAt = 1, readOnly = readOnly).also { repository.upsert(it) }
    }

    private fun <T> DocResult<T>.ok(): T = assertIs<DocResult.Ok<T>>(this, "expected Ok, got $this").value

    private fun assertNotAllowed(result: DocResult<*>, mentioning: String) {
        val refusal = assertIs<DocResult.NotAllowed>(result)
        assertTrue(mentioning in refusal.reason, refusal.reason)
    }

    @Test
    fun `taking access persists a read and write grant and names the folder`() = runTest {
        picked()

        val grant = app.contentResolver.persistedUriPermissions.single()
        assertEquals(treeUri, grant.uri)
        assertTrue(grant.isReadPermission && grant.isWritePermission)
        assertTrue(folders.hasAccess(repository.get(id)!!))
    }

    @Test
    fun `the folder's name from the provider is reduced to one plain line`() = runTest {
        val odd = provider.tree("Photos\n\u202Egnp.exe\tbackup")

        assertEquals(FolderAccess.Granted("Photos gnp.exe backup"), folders.takeAccess(odd.toString()))
    }

    @Test
    fun `only folder-picker trees are accepted`() = runTest {
        val document = "content://${FakeDocumentsProvider.AUTHORITY}/document/$rootId"
        assertIs<FolderAccess.Refused>(folders.takeAccess(document))
        assertIs<FolderAccess.Refused>(folders.takeAccess("file:///sdcard/Documents"))
        assertIs<FolderAccess.Refused>(folders.takeAccess("not a uri at all"))
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
    }

    @Test
    fun `lists folders first, then files by name, with sizes`() = runTest {
        picked()
        provider.file(rootId, "b.txt", "bee")
        provider.folder(rootId, "Zeta")
        provider.file(rootId, "a.md", "a")
        provider.folder(rootId, "alpha")

        val entries = folders.list(id, RelativePath.ROOT).ok()

        assertEquals(listOf("alpha", "Zeta", "a.md", "b.txt"), entries.map { it.name })
        assertEquals(listOf(true, true, false, false), entries.map { it.isFolder })
        assertEquals(3L, entries.last().sizeBytes)
        assertEquals(null, entries.first().sizeBytes)
    }

    @Test
    fun `walks nested paths by display name and reports what is missing`() = runTest {
        picked()
        val reports = provider.folder(rootId, "Reports")
        provider.file(provider.folder(reports, "2026"), "q3.txt", "quarter three")

        assertEquals("quarter three", folders.read(id, p("Reports/2026/q3.txt"), 1_000).ok().bytes.decodeToString())
        assertEquals(DocumentEntry(p("Reports/2026"), isFolder = true, modifiedAt = 1_700_000_000_000L), folders.stat(id, p("Reports/2026")).ok())
        assertEquals(DocResult.NotFound("Reports/2025"), folders.read(id, p("Reports/2025/q3.txt"), 1_000))
        assertIs<DocResult.Failed>(folders.list(id, p("Reports/2026/q3.txt")))
    }

    @Test
    fun `reads say explicitly whether they were cut short`() = runTest {
        picked()
        provider.file(rootId, "notes.txt", "0123456789")

        assertEquals(FileContent("0123".toByteArray(), 10, truncated = true), folders.read(id, p("notes.txt"), 4).ok())
        assertEquals(FileContent("0123456789".toByteArray(), 10, truncated = false), folders.read(id, p("notes.txt"), 10).ok())
        assertFalse(folders.read(id, p("notes.txt"), 1_000).ok().truncated)
    }

    @Test
    fun `two children with the same name are ambiguous and nothing is done`() = runTest {
        picked()
        provider.file(rootId, "twin.txt", "one")
        provider.file(rootId, "twin.txt", "two")
        val a = provider.folder(rootId, "dup")
        provider.folder(rootId, "dup")
        provider.file(a, "inside.txt", "x")

        assertEquals(DocResult.Ambiguous("twin.txt", 2), folders.read(id, p("twin.txt"), 100))
        assertEquals(DocResult.Ambiguous("twin.txt", 2), folders.write(id, p("twin.txt"), "new".toByteArray(), null, overwrite = true))
        assertEquals(DocResult.Ambiguous("twin.txt", 2), folders.delete(id, p("twin.txt")))
        assertEquals(DocResult.Ambiguous("twin.txt", 2), folders.rename(id, p("twin.txt"), "single.txt"))
        assertEquals(DocResult.Ambiguous("dup", 2), folders.read(id, p("dup/inside.txt"), 100))
        assertEquals(DocResult.Ambiguous("dup", 2), folders.write(id, p("dup/new.txt"), "x".toByteArray(), null, overwrite = false))
        assertEquals(listOf("dup", "dup", "twin.txt", "twin.txt"), provider.namesIn(rootId))
    }

    @Test
    fun `writes create missing folders and never replace unless asked`() = runTest {
        picked()

        val entry = folders.write(id, p("Drafts/2026/plan.txt"), "first".toByteArray(), "text/plain", overwrite = false).ok()

        assertEquals(p("Drafts/2026/plan.txt"), entry.path)
        assertEquals(5L, entry.sizeBytes)
        val fileId = provider.idAt(rootId, "Drafts/2026/plan.txt")!!
        assertEquals("first", provider.textOf(fileId))
        assertEquals(
            DocResult.AlreadyExists("Drafts/2026/plan.txt"),
            folders.write(id, p("Drafts/2026/plan.txt"), "second".toByteArray(), null, overwrite = false),
        )
        assertEquals("first", provider.textOf(fileId))

        folders.write(id, p("Drafts/2026/plan.txt"), "2nd".toByteArray(), null, overwrite = true).ok()
        assertEquals("2nd", provider.textOf(fileId), "overwrite replaces the whole content")
        assertEquals(DocResult.AlreadyExists("Drafts"), folders.write(id, p("Drafts"), "x".toByteArray(), null, overwrite = true))
    }

    @Test
    fun `new files are created with a type that keeps their name`() = runTest {
        picked()

        folders.write(id, p("data.csv"), "a,b".toByteArray(), "text/plain", overwrite = false).ok()
        folders.write(id, p("README"), "hi".toByteArray(), "text/plain", overwrite = false).ok()
        folders.write(id, p("blob"), "hi".toByteArray(), "not a type", overwrite = false).ok()

        // A type that disagrees with the extension would make some providers append one ("data.csv.txt"),
        // so the extension decides: its own type, or a generic one, never the caller's text/plain.
        val csv = MimeTypeMap.getSingleton().getMimeTypeFromExtension("csv") ?: "application/octet-stream"
        assertEquals(listOf("data.csv ($csv)", "README (text/plain)", "blob (application/octet-stream)"), provider.created)
    }

    @Test
    fun `a provider that adjusts a name is reported truthfully`() = runTest {
        picked()
        provider.renameAppends = " (1)"

        val entry = folders.write(id, p("report.txt"), "x".toByteArray(), null, overwrite = false).ok()

        assertEquals(p("report.txt (1)"), entry.path)
    }

    @Test
    fun `makeFolder creates parents, returns an existing folder, and refuses a file in the way`() = runTest {
        picked()

        assertEquals(p("a/b/c"), folders.makeFolder(id, p("a/b/c")).ok().path)
        assertEquals(p("a/b/c"), folders.makeFolder(id, p("a/b/c")).ok().path)
        assertEquals(listOf("a"), provider.namesIn(rootId))
        folders.write(id, p("a/file"), "x".toByteArray(), null, overwrite = false).ok()
        assertEquals(DocResult.AlreadyExists("a/file"), folders.makeFolder(id, p("a/file/d")))
    }

    @Test
    fun `rename works in place and never on the root, onto an existing name or to an invalid name`() = runTest {
        picked()
        provider.file(rootId, "old.txt", "x")
        provider.file(rootId, "taken.txt", "y")

        assertEquals(p("new.txt"), folders.rename(id, p("old.txt"), "new.txt").ok().path)
        assertEquals(listOf("new.txt", "taken.txt"), provider.namesIn(rootId))
        assertEquals(DocResult.AlreadyExists("taken.txt"), folders.rename(id, p("new.txt"), "taken.txt"))
        assertIs<DocResult.Failed>(folders.rename(id, p("new.txt"), "../escape.txt"))
        assertIs<DocResult.Failed>(folders.rename(id, p("new.txt"), "a/b"))
        assertNotAllowed(folders.rename(id, RelativePath.ROOT, "Renamed"), "cannot be renamed")
        assertEquals(DocResult.NotFound("missing.txt"), folders.rename(id, p("missing.txt"), "x.txt"))
    }

    @Test
    fun `operations the provider does not support are refused before anything is tried`() = runTest {
        picked()
        val fileId = provider.file(rootId, "fixed.txt", "x")
        provider.removeFlag(fileId, Document.FLAG_SUPPORTS_RENAME)
        provider.removeFlag(fileId, Document.FLAG_SUPPORTS_DELETE)
        provider.removeFlag(fileId, Document.FLAG_SUPPORTS_WRITE)
        provider.removeFlag(rootId, Document.FLAG_DIR_SUPPORTS_CREATE)

        assertNotAllowed(folders.rename(id, p("fixed.txt"), "other.txt"), "does not allow renaming")
        assertNotAllowed(folders.delete(id, p("fixed.txt")), "does not allow deleting")
        assertNotAllowed(folders.write(id, p("fixed.txt"), "y".toByteArray(), null, overwrite = true), "does not allow changing")
        assertNotAllowed(folders.write(id, p("new.txt"), "y".toByteArray(), null, overwrite = false), "does not allow creating")
        assertEquals("x", provider.textOf(fileId))
    }

    @Test
    fun `delete removes files and folders with their content, never the root`() = runTest {
        picked()
        val docs = provider.folder(rootId, "old")
        val inner = provider.file(docs, "inner.txt", "x")
        val single = provider.file(rootId, "single.txt", "y")

        assertEquals(DocResult.Ok(Unit), folders.delete(id, p("single.txt")))
        assertEquals(DocResult.Ok(Unit), folders.delete(id, p("old")))
        assertFalse(provider.exists(single) || provider.exists(docs) || provider.exists(inner))
        assertNotAllowed(folders.delete(id, RelativePath.ROOT), "never deleted")
        assertTrue(provider.exists(rootId))
        assertEquals(DocResult.NotFound("gone.txt"), folders.delete(id, p("gone.txt")))
    }

    @Test
    fun `a read-only folder refuses every change but can still be read`() = runTest {
        picked(readOnly = true)
        provider.file(rootId, "keep.txt", "keep")
        val target = ws("in.txt").also { it.file.writeText("from the workspace") }

        assertNotAllowed(folders.write(id, p("new.txt"), "x".toByteArray(), null, overwrite = false), "read-only")
        assertNotAllowed(folders.makeFolder(id, p("dir")), "read-only")
        assertNotAllowed(folders.rename(id, p("keep.txt"), "moved.txt"), "read-only")
        assertNotAllowed(folders.delete(id, p("keep.txt")), "read-only")
        assertNotAllowed(folders.copyFromWorkspace(target, id, p("in.txt"), overwrite = false), "read-only")
        assertEquals(listOf("keep.txt"), provider.namesIn(rootId))
        assertEquals("keep", folders.read(id, p("keep.txt"), 100).ok().bytes.decodeToString())
    }

    @Test
    fun `the folder is re-read on every call, so switching it to read-only applies at once`() = runTest {
        val folder = picked()
        folders.write(id, p("a.txt"), "x".toByteArray(), null, overwrite = false).ok()

        repository.upsert(folder.copy(readOnly = true))

        assertNotAllowed(folders.write(id, p("b.txt"), "x".toByteArray(), null, overwrite = false), "read-only")
    }

    @Test
    fun `a removed folder is refused for every operation`() = runTest {
        picked()
        provider.file(rootId, "a.txt", "x")
        repository.delete(id)

        assertNotAllowed(folders.list(id, RelativePath.ROOT), "removed")
        assertNotAllowed(folders.read(id, p("a.txt"), 10), "removed")
        assertNotAllowed(folders.copyToWorkspace(id, p("a.txt"), ws("a.txt"), 10), "removed")
        assertFalse(ws("a.txt").file.exists())
    }

    @Test
    fun `released or lost access is detected and reported`() = runTest {
        val folder = picked()
        provider.file(rootId, "a.txt", "x")

        folders.releaseAccess(folder)

        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        assertFalse(folders.hasAccess(folder))
        assertNotAllowed(folders.read(id, p("a.txt"), 10), "Access to Documents is gone")
        assertNotAllowed(folders.list(id, RelativePath.ROOT), "add it again")
    }

    @Test
    fun `a folder deleted outside the app is reported as lost access`() = runTest {
        val folder = picked()

        provider.vanish(rootId)

        assertFalse(folders.hasAccess(folder))
        assertNotAllowed(folders.list(id, RelativePath.ROOT), "Access to Documents is gone")
    }

    @Test
    fun `read-only access from Android refuses changes`() = runTest {
        picked()
        app.contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        assertNotAllowed(folders.write(id, p("a.txt"), "x".toByteArray(), null, overwrite = false), "read-only access")
        assertIs<DocResult.Ok<*>>(folders.list(id, RelativePath.ROOT))
    }

    @Test
    fun `copies to the workspace never overwrite and respect the size limit`() = runTest {
        picked()
        provider.file(rootId, "photo.txt", "0123456789")
        provider.folder(rootId, "dir")

        assertEquals(DocResult.Ok(10L), folders.copyToWorkspace(id, p("photo.txt"), ws("in/photo.txt"), 100))
        assertEquals("0123456789", ws("in/photo.txt").file.readText())

        ws("in/photo.txt").file.writeText("mine")
        assertEquals(DocResult.AlreadyExists("in/photo.txt"), folders.copyToWorkspace(id, p("photo.txt"), ws("in/photo.txt"), 100))
        assertEquals("mine", ws("in/photo.txt").file.readText())

        assertEquals(DocResult.TooLarge(10, 4), folders.copyToWorkspace(id, p("photo.txt"), ws("small.txt"), 4))
        assertFalse(ws("small.txt").file.exists(), "nothing is left behind")
        assertIs<DocResult.Failed>(folders.copyToWorkspace(id, p("dir"), ws("dir"), 100))
    }

    @Test
    fun `copies from the workspace write like write does`() = runTest {
        picked()
        val source = ws("out/report.txt").also {
            it.file.parentFile!!.mkdirs()
            it.file.writeText("report")
        }

        assertEquals(p("Exports/report.txt"), folders.copyFromWorkspace(source, id, p("Exports/report.txt"), overwrite = false).ok().path)
        assertEquals("report", provider.textOf(provider.idAt(rootId, "Exports/report.txt")!!))
        assertEquals(DocResult.AlreadyExists("Exports/report.txt"), folders.copyFromWorkspace(source, id, p("Exports/report.txt"), overwrite = false))
        assertEquals(DocResult.NotFound("out/missing.txt"), folders.copyFromWorkspace(ws("out/missing.txt"), id, p("x.txt"), overwrite = false))
    }

    @Test
    fun `names that cannot be a path are left out of listings`() = runTest {
        picked()
        provider.file(rootId, " leading space", "x")
        provider.file(rootId, "fine.txt", "y")

        assertEquals(listOf("fine.txt"), folders.list(id, RelativePath.ROOT).ok().map { it.name })
    }
}
