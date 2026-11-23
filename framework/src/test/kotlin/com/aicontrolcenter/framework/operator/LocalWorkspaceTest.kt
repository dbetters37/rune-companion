package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.TrashItem
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalWorkspaceTest {
    private val parent: File = Files.createTempDirectory("aicc-parent").toFile()
    private val rootDir = File(parent, "workspace").apply { mkdirs() }
    private val outside = File(parent, "private").apply { mkdirs(); File(this, "secret.txt").writeText("app secret") }
    private val clock = FakeClock(autoAdvanceMs = 1)
    private val workspace = LocalWorkspace(
        FakeWorkspaceRoot(rootDir), clock,
        WorkspaceLimits(maxFileBytes = 1_000, maxTotalBytes = 5_000, maxTextEditBytes = 500, maxSearchFileBytes = 400, maxSearchFiles = 50),
    )

    private fun p(s: String) = RelativePath.of(s)

    private fun link(at: String, target: File) = Files.createSymbolicLink(File(rootDir, at).toPath(), target.toPath())

    private fun <T> ok(r: DocResult<T>): T = assertIs<DocResult.Ok<T>>(r, r.toString()).value

    @Test
    fun `paths that climb, are absolute or hide characters are refused before any file is touched`() {
        val refused = listOf(
            "../private/secret.txt", "a/../../private", "/etc/passwd", "..", "a\\..\\b", "notes\u0000.txt", "a\nb", "‮txt.exe",
            "zero​width", "nbsp name", "tag󠁁", " leading", "trailing /x", "a".repeat(300),
        )
        for (raw in refused) assertIs<RelativePath.Check.Invalid>(RelativePath.parse(raw), "should refuse ${raw.take(20)}")
        // Look-alike separators are ordinary characters: the name stays one name inside the workspace.
        val lookalike = RelativePath.of("..∕private∕secret.txt")
        assertEquals(1, lookalike.segments.size)
    }

    @Test
    fun `a look-alike path is written inside the workspace, never next to it`() = runTest {
        val path = p("..∕private∕secret.txt")
        ok(workspace.write(path, "x".toByteArray(), overwrite = true))
        assertEquals("app secret", File(outside, "secret.txt").readText())
        assertTrue(File(rootDir, path.value).isFile)
    }

    @Test
    fun `links inside the workspace are never followed`() = runTest {
        link("escape", outside)
        link("secret-link.txt", File(outside, "secret.txt"))

        assertIs<DocResult.NotAllowed>(workspace.read(p("escape/secret.txt"), 0, 100))
        assertIs<DocResult.NotAllowed>(workspace.read(p("secret-link.txt"), 0, 100))
        assertIs<DocResult.NotAllowed>(workspace.write(p("escape/new.txt"), "x".toByteArray(), false))
        assertIs<DocResult.NotAllowed>(workspace.write(p("secret-link.txt"), "x".toByteArray(), true))
        assertIs<DocResult.NotAllowed>(workspace.append(p("secret-link.txt"), "x".toByteArray()))
        assertIs<DocResult.NotAllowed>(workspace.list(p("escape")))
        assertIs<DocResult.NotAllowed>(workspace.move(p("secret-link.txt"), p("moved.txt"), false))
        assertIs<DocResult.NotAllowed>(workspace.trash(p("escape")))
        assertIs<DocResult.NotAllowed>(workspace.replaceText(p("secret-link.txt"), "app", "x", null))
        assertIs<DocResult.NotAllowed>(workspace.resolve(p("escape/secret.txt")))
        assertIs<DocResult.NotAllowed>(workspace.import(p("escape/in.txt"), ByteArrayInputStream("x".toByteArray()), 100, false))
        assertEquals("app secret", File(outside, "secret.txt").readText())
        assertFalse(File(outside, "new.txt").exists())

        // Listed as unusable links; searches do not look through them.
        val entries = ok(workspace.list(RelativePath.ROOT))
        assertEquals(setOf("escape", "secret-link.txt"), entries.map { it.name }.toSet())
        assertTrue(entries.all { it.mimeType == LocalWorkspace.LINK_MIME && !it.isFolder })
        val hits = ok(workspace.search("secret", RelativePath.ROOT, 50))
        assertTrue(hits.all { it.line == null }, hits.toString())
    }

    @Test
    fun `a link inside a trashed folder is deleted itself, never what it points to`() = runTest {
        ok(workspace.makeFolder(p("box")))
        link("box/out", outside)
        ok(workspace.write(p("box/a.txt"), "a".toByteArray(), false))
        val item = ok(workspace.trash(p("box")))
        assertEquals(1L, item.sizeBytes, "the size counts the file, not what the link points to")
        assertEquals(1, ok(workspace.emptyTrash(null)))
        assertEquals("app secret", File(outside, "secret.txt").readText())
        assertTrue(File(outside, "secret.txt").exists())
    }

    @Test
    fun `a workspace whose own folder is a link is not used`() = runTest {
        val linked = File(parent, "linked-ws").toPath()
        Files.createSymbolicLink(linked, outside.toPath())
        val bad = LocalWorkspace(FakeWorkspaceRoot(linked.toFile()), clock)
        assertIs<DocResult.NotAllowed>(bad.read(p("secret.txt"), 0, 100))
        assertIs<DocResult.NotAllowed>(bad.list(RelativePath.ROOT))
    }

    @Test
    fun `the trash is reached only through the trash operations`() = runTest {
        ok(workspace.write(p("a.txt"), "a".toByteArray(), false))
        ok(workspace.trash(p("a.txt")))
        assertIs<DocResult.NotAllowed>(workspace.list(p(Workspace.TRASH_FOLDER)))
        assertIs<DocResult.NotAllowed>(workspace.read(p(".trash/x.json"), 0, 10))
        assertIs<DocResult.NotAllowed>(workspace.write(p(".trash/evil.json"), "{}".toByteArray(), true))
        assertIs<DocResult.NotAllowed>(workspace.move(p("b"), p(".trash/b"), false))
        assertFalse(ok(workspace.list(RelativePath.ROOT)).any { it.name == Workspace.TRASH_FOLDER })
        assertTrue(ok(workspace.search("a", RelativePath.ROOT, 10)).none { it.path.value.startsWith(".trash") })
    }

    @Test
    fun `trash, list, restore and empty keep the documented format`() = runTest {
        ok(workspace.write(p("docs/plan.md"), "plan".toByteArray(), false))
        ok(workspace.write(p("docs/old/notes.txt"), "notes".toByteArray(), false))
        val file = ok(workspace.trash(p("docs/plan.md")))
        val folder = ok(workspace.trash(p("docs/old")))
        assertFalse(File(rootDir, "docs/plan.md").exists())

        // The format: .trash/<id>/<name> and .trash/<id>.json holding the TrashItem.
        val record = File(rootDir, ".trash/${file.id}.json")
        assertEquals(file, Json.decodeFromString(TrashItem.serializer(), record.readText()))
        assertEquals("plan", File(rootDir, ".trash/${file.id}/plan.md").readText())
        assertEquals(TrashItem(folder.id, "docs/old", isFolder = true, sizeBytes = 5, trashedAt = folder.trashedAt), folder)

        assertEquals(listOf(folder.id, file.id), ok(workspace.listTrash()).map { it.id }, "newest first")

        // Restoring recreates missing parents and refuses to replace what is there now.
        File(rootDir, "docs").deleteRecursively()
        ok(workspace.restore(file.id))
        assertEquals("plan", File(rootDir, "docs/plan.md").readText())
        assertFalse(record.exists() || File(rootDir, ".trash/${file.id}").exists())
        ok(workspace.write(p("docs/old"), "in the way".toByteArray(), false))
        assertIs<DocResult.AlreadyExists>(workspace.restore(folder.id))

        assertEquals(1, ok(workspace.emptyTrash(setOf(folder.id, "1-unknown"))))
        assertEquals(emptyList(), ok(workspace.listTrash()))
        assertIs<DocResult.NotFound>(workspace.restore(folder.id))
    }

    @Test
    fun `trash ids that are not ids reach nothing`() = runTest {
        ok(workspace.write(p("keep.txt"), "keep".toByteArray(), false))
        for (id in listOf("../keep.txt", "..", ".staging", "", "1-ok/../../keep.txt", "UPPER-1")) {
            assertIs<DocResult.NotFound>(workspace.restore(id), id)
        }
        assertEquals(0, ok(workspace.emptyTrash(setOf("../keep.txt", "../../private"))))
        assertTrue(File(rootDir, "keep.txt").exists())
        assertTrue(outside.exists())
    }

    @Test
    fun `a tampered trash record still restores only inside the workspace`() = runTest {
        ok(workspace.write(p("a.txt"), "a".toByteArray(), false))
        val item = ok(workspace.trash(p("a.txt")))
        File(rootDir, ".trash/${item.id}.json").writeText(Json.encodeToString(TrashItem.serializer(), item.copy(originalPath = "../private/a.txt")))
        assertIs<DocResult.Failed>(workspace.restore(item.id))
        File(rootDir, ".trash/${item.id}.json").writeText(Json.encodeToString(TrashItem.serializer(), item.copy(originalPath = ".trash/x/a.txt")))
        assertIs<DocResult.NotAllowed>(workspace.restore(item.id))
        assertFalse(File(outside, "a.txt").exists())
    }

    @Test
    fun `size limits apply to writes, appends, imports and edits`() = runTest {
        assertEquals(DocResult.TooLarge(1_001, 1_000), workspace.write(p("big.txt"), ByteArray(1_001) { 'a'.code.toByte() }, false))
        ok(workspace.write(p("a.txt"), ByteArray(900) { 'a'.code.toByte() }, false))
        assertIs<DocResult.TooLarge>(workspace.append(p("a.txt"), ByteArray(200)))
        val imported = workspace.import(p("in.bin"), ByteArrayInputStream(ByteArray(5_000)), maxBytes = 100, overwrite = false)
        assertIs<DocResult.TooLarge>(imported)
        assertFalse(File(rootDir, "in.bin").exists(), "a refused import leaves no partial file")
        ok(workspace.write(p("edit.txt"), ByteArray(600) { 'x'.code.toByte() }, false))
        assertIs<DocResult.TooLarge>(workspace.replaceText(p("edit.txt"), "x", "y", null))

        // The total: 5000 bytes across the workspace, the trash included (1500 used so far).
        repeat(3) { ok(workspace.write(p("fill$it.txt"), ByteArray(900), false)) }
        ok(workspace.trash(p("fill2.txt")))
        val full = workspace.write(p("more.txt"), ByteArray(900), false)
        assertIs<DocResult.Failed>(full)
        assertTrue("full" in full.reason, full.reason)
    }

    @Test
    fun `an import counts once against the total, and a file it replaces is kept in the trash and still counts`() = runTest {
        repeat(5) { ok(workspace.write(p("f$it.bin"), ByteArray(900), false)) }
        ok(workspace.import(p("in.bin"), ByteArrayInputStream(ByteArray(400)), 1_000, false))
        val full = workspace.import(p("in2.bin"), ByteArrayInputStream(ByteArray(200)), 1_000, false)
        assertIs<DocResult.Failed>(full)
        assertFalse(File(rootDir, "in2.bin").exists())
        // The replaced file goes to the trash, so even a smaller replacement needs room for both.
        assertIs<DocResult.Failed>(workspace.import(p("in.bin"), ByteArrayInputStream(ByteArray(300)), 1_000, true))
        assertEquals(400L, File(rootDir, "in.bin").length())
        ok(workspace.trash(p("f0.bin")))
        ok(workspace.emptyTrash(null))
        assertEquals(300L, ok(workspace.import(p("in.bin"), ByteArrayInputStream(ByteArray(300)), 1_000, true)).sizeBytes)
        assertTrue(File(rootDir, ".trash/.staging").list().orEmpty().isEmpty(), "no staged copy is left behind")
        val records = File(rootDir, ".trash").listFiles { it: File -> it.name.endsWith(".json") }.orEmpty().sumOf { it.length() }
        assertEquals(4_300L, workspace.usedBytes() - records, "the replaced 400 bytes are in the trash")
        assertEquals(listOf("in.bin" to 400L), ok(workspace.listTrash()).map { it.originalPath to it.sizeBytes })
    }

    @Test
    fun `a file replaced by a write or a move is kept in the trash and can be restored`() = runTest {
        ok(workspace.write(p("notes/thesis.md"), "four years of work".toByteArray(), false))
        ok(workspace.write(p("notes/thesis.md"), ByteArray(0), overwrite = true))
        assertEquals(0L, File(rootDir, "notes/thesis.md").length())
        val kept = ok(workspace.listTrash()).single()
        assertEquals("notes/thesis.md", kept.originalPath)
        assertEquals("four years of work", File(rootDir, ".trash/${kept.id}/thesis.md").readText())

        ok(workspace.write(p("draft.md"), "draft".toByteArray(), false))
        ok(workspace.write(p("final.md"), "final".toByteArray(), false))
        ok(workspace.move(p("draft.md"), p("final.md"), overwrite = true))
        assertEquals("draft", File(rootDir, "final.md").readText())
        val movedOver = ok(workspace.listTrash()).first { it.originalPath == "final.md" }
        assertEquals("final", File(rootDir, ".trash/${movedOver.id}/final.md").readText())

        // Restoring needs the place free: the replacement is moved away first, then the old file comes back.
        assertIs<DocResult.AlreadyExists>(workspace.restore(kept.id))
        ok(workspace.trash(p("notes/thesis.md")))
        ok(workspace.restore(kept.id))
        assertEquals("four years of work", File(rootDir, "notes/thesis.md").readText())
    }

    @Test
    fun `a gateway write goes through the limits, the lock and the change signal`() = runTest {
        val before = workspace.changes.first()
        val copied = workspace.receive(p("in/photo.jpg"), 50_000) { target, max ->
            assertEquals(1_000L, max, "the per-file limit, not the caller's")
            target.file.writeBytes(ByteArray(600))
            DocResult.Ok(600L)
        }
        assertEquals(600L, ok(copied))
        assertTrue(workspace.changes.first() > before, "screens are told")
        assertIs<DocResult.AlreadyExists>(workspace.receive<Long>(p("in/photo.jpg"), 50_000) { _, _ -> error("not called for a taken name") })

        // A gateway that writes more than it was allowed: the file is removed.
        val tooBig = workspace.receive(p("big.jpg"), 50_000) { target, _ -> target.file.writeBytes(ByteArray(1_500)); DocResult.Ok(1_500L) }
        assertIs<DocResult.TooLarge>(tooBig)
        assertFalse(File(rootDir, "big.jpg").exists())

        // The room left bounds what it may write; past it, the copy is removed and the workspace reported full.
        repeat(4) { ok(workspace.write(p("f$it.bin"), ByteArray(1_000), false)) }
        val room = workspace.receive(p("more.jpg"), 50_000) { target, max ->
            assertEquals(5_000L - 4_600L, max)
            target.file.writeBytes(ByteArray(500))
            DocResult.Ok(500L)
        }
        assertIs<DocResult.Failed>(room)
        assertTrue("full" in (room as DocResult.Failed).reason)
        assertFalse(File(rootDir, "more.jpg").exists())
        assertIs<DocResult.NotAllowed>(workspace.receive<Long>(p(".trash/x"), 10) { _, _ -> error("not called") })
    }

    @Test
    fun `a change from outside is announced and a workspace over its limit is reported`() = runTest {
        val before = workspace.changes.first()
        assertNull(workspace.changedOutside())
        assertTrue(workspace.changes.first() > before)
        File(rootDir, "made-by-a-shell.bin").writeBytes(ByteArray(6_000))
        assertTrue("full" in workspace.changedOutside()!!)
    }

    @Test
    fun `writes never replace without asking, never replace folders and need folders as parents`() = runTest {
        ok(workspace.write(p("a.txt"), "one".toByteArray(), false))
        assertIs<DocResult.AlreadyExists>(workspace.write(p("a.txt"), "two".toByteArray(), false))
        ok(workspace.write(p("a.txt"), "two".toByteArray(), true))
        assertEquals("two", File(rootDir, "a.txt").readText())
        ok(workspace.makeFolder(p("dir")))
        assertIs<DocResult.AlreadyExists>(workspace.write(p("dir"), "x".toByteArray(), true))
        assertIs<DocResult.Failed>(workspace.write(p("a.txt/child.txt"), "x".toByteArray(), false))
        assertIs<DocResult.NotAllowed>(workspace.write(RelativePath.ROOT, "x".toByteArray(), true))
        assertIs<DocResult.AlreadyExists>(workspace.makeFolder(p("a.txt")))
        assertEquals("dir", ok(workspace.makeFolder(p("dir"))).name)
    }

    @Test
    fun `moves rename, refuse a folder into itself and replace only a file with a file`() = runTest {
        ok(workspace.write(p("f/a.txt"), "a".toByteArray(), false))
        ok(workspace.write(p("b.txt"), "b".toByteArray(), false))
        assertIs<DocResult.Failed>(workspace.move(p("f"), p("f/inner/f"), false))
        assertIs<DocResult.AlreadyExists>(workspace.move(p("f/a.txt"), p("b.txt"), false))
        ok(workspace.move(p("f/a.txt"), p("b.txt"), true))
        assertEquals("a", File(rootDir, "b.txt").readText())
        ok(workspace.makeFolder(p("g")))
        assertIs<DocResult.AlreadyExists>(workspace.move(p("f"), p("g"), true))
        assertIs<DocResult.AlreadyExists>(workspace.move(p("b.txt"), p("g"), true))
        ok(workspace.move(p("f"), p("deep/er/f2"), false))
        assertTrue(File(rootDir, "deep/er/f2").isDirectory)
        assertIs<DocResult.NotFound>(workspace.move(p("nothing"), p("x"), false))
    }

    @Test
    fun `reads return a part with an explicit truncation flag`() = runTest {
        ok(workspace.write(p("t.txt"), "0123456789".toByteArray(), false))
        val first = ok(workspace.read(p("t.txt"), 0, 4))
        assertContentEquals("0123".toByteArray(), first.bytes)
        assertTrue(first.truncated)
        assertEquals(10L, first.totalBytes)
        val last = ok(workspace.read(p("t.txt"), 8, 4))
        assertContentEquals("89".toByteArray(), last.bytes)
        assertFalse(last.truncated)
        assertIs<DocResult.Failed>(workspace.read(RelativePath.ROOT, 0, 4))
        assertIs<DocResult.NotFound>(workspace.read(p("none.txt"), 0, 4))
    }

    @Test
    fun `replace text counts exactly and changes nothing when the count differs`() = runTest {
        ok(workspace.write(p("t.md"), "cat cat dog".toByteArray(), false))
        assertIs<DocResult.Failed>(workspace.replaceText(p("t.md"), "cat", "cow", expectedCount = 3))
        assertEquals("cat cat dog", File(rootDir, "t.md").readText())
        assertEquals(2, ok(workspace.replaceText(p("t.md"), "cat", "cow", expectedCount = 2)))
        assertEquals("cow cow dog", File(rootDir, "t.md").readText())
        File(rootDir, "bin.dat").writeBytes(byteArrayOf(0, 1, 2, 0xFF.toByte()))
        assertIs<DocResult.Failed>(workspace.replaceText(p("bin.dat"), "a", "b", null))
    }

    @Test
    fun `search finds names first, then lines of text files, ignoring case and binary files`() = runTest {
        ok(workspace.write(p("Budget/notes.txt"), "line one\nThe BUDGET is set\nend".toByteArray(), false))
        ok(workspace.write(p("other.md"), "nothing here\nbudget again".toByteArray(), false))
        File(rootDir, "budget.bin").writeBytes(byteArrayOf(0, 'b'.code.toByte(), 0))
        ok(workspace.write(p("large.txt"), ("budget " + "x".repeat(450)).toByteArray(), false))
        val hits = ok(workspace.search("budget", RelativePath.ROOT, 50))
        assertEquals(listOf("Budget", "budget.bin"), hits.filter { it.line == null }.map { it.path.value })
        assertEquals(listOf("Budget/notes.txt" to 2, "other.md" to 2), hits.filter { it.line != null }.map { it.path.value to it.line })
        assertEquals("The BUDGET is set", hits.first { it.path.value == "Budget/notes.txt" }.excerpt)
        assertEquals(1, ok(workspace.search("budget", p("Budget"), 50)).size)
        assertEquals(2, ok(workspace.search("budget", RelativePath.ROOT, 2)).size)
    }

    @Test
    fun `every change is announced and listings order folders first`() = runTest {
        val before = workspace.changes.first()
        ok(workspace.write(p("b.txt"), "b".toByteArray(), false))
        ok(workspace.makeFolder(p("Z")))
        ok(workspace.write(p("a.txt"), "a".toByteArray(), false))
        assertEquals(before + 3, workspace.changes.first())
        assertIs<DocResult.AlreadyExists>(workspace.write(p("a.txt"), "x".toByteArray(), false))
        assertEquals(before + 3, workspace.changes.first(), "a refused change announces nothing")
        assertEquals(listOf("Z", "a.txt", "b.txt"), ok(workspace.list(RelativePath.ROOT)).map { it.name })
        assertEquals("text/plain", ok(workspace.stat(p("a.txt"))).mimeType)
        assertNull(ok(workspace.stat(p("Z"))).sizeBytes)
    }

    @Test
    fun `names the sandbox would refuse are not listed`() = runTest {
        File(rootDir, "ok.txt").writeText("x")
        File(rootDir, " spaced").writeText("x")
        File(rootDir, "bad‮name").writeText("x")
        assertEquals(listOf("ok.txt"), ok(workspace.list(RelativePath.ROOT)).map { it.name })
    }

    @Test
    fun `import copies a chosen document and resolve gives the checked file`() = runTest {
        val entry = ok(workspace.import(p("in/doc.csv"), ByteArrayInputStream("a,b\n1,2".toByteArray()), 100, false))
        assertEquals("text/csv", entry.mimeType)
        assertIs<DocResult.AlreadyExists>(workspace.import(p("in/doc.csv"), ByteArrayInputStream("x".toByteArray()), 100, false))
        val checked = ok(workspace.resolve(p("in/doc.csv")))
        assertEquals(File(rootDir, "in/doc.csv").canonicalFile, checked.file.canonicalFile)
        assertTrue(workspace.usedBytes() >= 7)
    }
}
