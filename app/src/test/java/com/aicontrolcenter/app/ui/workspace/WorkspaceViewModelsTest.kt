package com.aicontrolcenter.app.ui.workspace

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.framework.operator.LocalWorkspace
import com.aicontrolcenter.framework.operator.WorkspaceLimits
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workspace file manager's view models over the real workspace service (the one the tools
 * use), on a temporary folder, with file work on the test's own thread.
 */
class WorkspaceViewModelsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val clock = FakeClock(current = TestApp.START_TIME, autoAdvanceMs = 1_000)
    private val log = InMemoryActivityLog()
    private val activity = ActivityRecorder(log, SequentialIdGenerator(), clock)

    private fun workspace(limits: WorkspaceLimits = WorkspaceLimits()) = LocalWorkspace(FakeWorkspaceRoot(), clock, limits, io = EmptyCoroutineContext)

    private suspend fun LocalWorkspace.put(path: String, text: String) {
        assertTrue(write(RelativePath.of(path), text.toByteArray(), overwrite = false) is DocResult.Ok)
    }

    private fun TestScope.folder(workspace: LocalWorkspace, path: String = "") =
        WorkspaceViewModel(workspace, path, activity, clock, ZoneOffset.UTC).also { it.uiState.activeIn(backgroundScope) }

    private fun TestScope.file(workspace: LocalWorkspace, path: String) =
        WorkspaceFileViewModel(workspace, path, activity, clock, ZoneOffset.UTC).also { it.uiState.activeIn(backgroundScope) }

    private fun TestScope.trash(workspace: LocalWorkspace) =
        WorkspaceTrashViewModel(workspace, activity, clock, ZoneOffset.UTC).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `a folder lists folders first, then files, with sizes, and follows changes made elsewhere`() = runTest(mainRule.dispatcher) {
        val ws = workspace()
        ws.put("notes.md", "# Notes")
        ws.put("reports/2025/q1.csv", "a,b")
        val vm = folder(ws)

        val state = vm.uiState.value
        assertEquals("Workspace", state.title)
        assertEquals(listOf("reports", "notes.md"), state.entries.map { it.name })
        assertTrue(state.entries.first().isFolder)
        assertTrue(state.entries.last().detail.startsWith("7\u00A0bytes"), state.entries.last().detail)

        // An agent's tool writes through the same service: the listing follows.
        ws.put("from-agent.txt", "hello")
        assertEquals(listOf("reports", "from-agent.txt", "notes.md"), vm.uiState.value.entries.map { it.name })

        val inner = folder(ws, "reports/2025")
        assertEquals(listOf("Workspace", "reports", "2025"), inner.uiState.value.crumbs.map { it.title })
        assertEquals(RelativePath.of("reports"), inner.uiState.value.crumbs[1].path)
        assertEquals(listOf("q1.csv"), inner.uiState.value.entries.map { it.name })
    }

    @Test
    fun `a folder that is gone or a path that escapes says so`() = runTest(mainRule.dispatcher) {
        val ws = workspace()
        assertEquals("This folder is no longer there. It may have been moved or put in the trash.", folder(ws, "missing").uiState.value.problem)
        assertEquals("\"..\" is not allowed in a path.", folder(ws, "../outside").uiState.value.problem)
        assertEquals("The trash is reached only through the trash and restore operations.", folder(ws, ".trash").uiState.value.problem)
    }

    @Test
    fun `new folders and renames take a single free name, never replacing anything`() = runTest(mainRule.dispatcher) {
        val ws = workspace()
        ws.put("notes.md", "x")
        ws.put("todo.md", "y")
        val vm = folder(ws)

        vm.makeFolder("  Trips ")
        assertEquals("Folder “Trips” created.", vm.uiState.value.message?.text)
        vm.makeFolder("Trips")
        assertEquals("Something named “Trips” is already here.", vm.uiState.value.message?.text)
        vm.makeFolder("a/b")
        assertEquals("A name cannot contain “/”.", vm.uiState.value.message?.text)
        vm.makeFolder(".trash")
        assertEquals("“.trash” is reserved for the trash.", vm.uiState.value.message?.text)
        vm.makeFolder("..")
        assertEquals("\"..\" is not allowed in a path.", vm.uiState.value.message?.text)

        vm.rename(RelativePath.of("notes.md"), "todo.md")
        assertEquals("Something named “todo.md” is already here. Nothing was renamed.", vm.uiState.value.message?.text)
        vm.rename(RelativePath.of("notes.md"), "ideas.md")
        assertEquals("Renamed to “ideas.md”.", vm.uiState.value.message?.text)
        assertEquals(listOf("Trips", "ideas.md", "todo.md"), vm.uiState.value.entries.map { it.name })
    }

    @Test
    fun `importing copies the document under a free name, logs it, and refuses one that is too large`() = runTest(mainRule.dispatcher) {
        val ws = workspace(WorkspaceLimits(maxFileBytes = 16))
        ws.put("Report.txt", "old")
        val vm = folder(ws)

        vm.import("Report.txt") { ByteArrayInputStream("new".toByteArray()) }
        assertEquals("Imported as “Report (2).txt”.", vm.uiState.value.message?.text)
        assertEquals("old", (ws.read(RelativePath.of("Report.txt"), 0, 100) as DocResult.Ok).value.bytes.decodeToString(), "nothing is replaced")
        assertEquals("new", (ws.read(RelativePath.of("Report (2).txt"), 0, 100) as DocResult.Ok).value.bytes.decodeToString())
        assertEquals("File imported into the workspace: Report (2).txt", log.all.last().title)

        vm.import("big.bin") { ByteArrayInputStream(ByteArray(64)) }
        assertEquals("The file is larger than 16\u00A0bytes, the most the workspace takes in one file. Nothing was imported.", vm.uiState.value.message?.text)
        assertFalse(vm.uiState.value.entries.any { it.name == "big.bin" })

        vm.import("x.txt") { null }
        assertEquals("The file could not be opened. Nothing was imported.", vm.uiState.value.message?.text)
        assertEquals("Imported file", WorkspaceViewModel.safeName("../‮"))
        assertEquals("notes.md", WorkspaceViewModel.safeName("/storage/emulated/0/notes.md"))
    }

    @Test
    fun `sharing hands the one file to the share sheet once, and trashing moves it to the trash`() = runTest(mainRule.dispatcher) {
        val ws = workspace()
        ws.put("notes.md", "x")
        ws.put("drafts/a.txt", "y")
        val vm = folder(ws)

        vm.share(RelativePath.of("notes.md"))
        val share = assertNotNull(vm.uiState.value.share)
        assertEquals("text/markdown", share.mimeType)
        assertEquals("notes.md", share.title)
        assertTrue(share.file.isFile && share.file.name == "notes.md")
        assertEquals("Workspace file shared: notes.md", log.all.last().title)
        vm.shareHandled()
        assertNull(vm.uiState.value.share)

        vm.share(RelativePath.of("drafts"))
        assertEquals("“drafts” is not a file that can be shared.", vm.uiState.value.message?.text)

        vm.trash(RelativePath.of("drafts"))
        assertEquals("“drafts” moved to the trash. You can restore it from there.", vm.uiState.value.message?.text)
        assertEquals(listOf("notes.md"), vm.uiState.value.entries.map { it.name })
        assertEquals(1, vm.uiState.value.trashCount)
    }

    @Test
    fun `a text file is previewed as stored, a long one in part, a binary one not at all`() = runTest(mainRule.dispatcher) {
        val ws = workspace()
        ws.put("notes.md", "# Plan\nBuy milk‮")
        val vm = file(ws, "notes.md")
        val state = vm.uiState.value
        assertEquals("notes.md", state.name)
        assertEquals("# Plan\nBuy milk‮", state.preview?.text, "shown exactly as stored, read-only")
        assertNull(state.preview?.partial)
        assertEquals(listOf("Size", "Changed", "Type", "In"), state.facts.map { it.first })
        assertEquals("Text (Markdown)", state.facts.first { it.first == "Type" }.second)

        val long = "é".repeat(40_000)
        ws.put("long.txt", long)
        val preview = assertNotNull(file(ws, "long.txt").uiState.value.preview)
        assertEquals("Showing the first 64.0\u00A0KB of 78.1\u00A0KB", preview.partial)
        assertTrue(preview.text!!.all { it == 'é' }, "a character cut at the end does not make it binary")

        assertTrue(ws.write(RelativePath.of("photo.png"), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 13), overwrite = false) is DocResult.Ok)
        assertNull(file(ws, "photo.png").uiState.value.preview?.text)

        assertEquals("This file is no longer there. It may have been moved or put in the trash.", file(ws, "gone.txt").uiState.value.problem)
        ws.put("dir/x.txt", "x")
        assertEquals("This is a folder.", file(ws, "dir").uiState.value.problem)
    }

    @Test
    fun `trashing from the preview goes back, and the trash restores and empties with the owner's confirmation`() = runTest(mainRule.dispatcher) {
        val ws = workspace()
        ws.put("notes.md", "x")
        ws.put("old/a.txt", "y")
        val preview = file(ws, "notes.md")
        preview.trash()
        assertTrue(preview.uiState.value.trashed)
        folder(ws).trash(RelativePath.of("old"))

        val vm = trash(ws)
        val items = vm.uiState.value.items
        assertEquals(setOf("notes.md", "old"), items.map { it.name }.toSet())
        assertTrue(items.first { it.name == "notes.md" }.detail.startsWith("From the workspace · trashed"))

        vm.restore(items.first { it.name == "notes.md" }.id)
        assertEquals("Restored to notes.md.", vm.uiState.value.message?.text)
        assertEquals(listOf("old"), vm.uiState.value.items.map { it.name })

        // Something now sits where the item was: it is not restored over it.
        ws.put("old/a.txt", "new")
        vm.restore(vm.uiState.value.items.single().id)
        assertEquals("Something is at old now, so it was not restored. Rename or trash that first.", vm.uiState.value.message?.text)

        vm.emptyTrash()
        assertEquals("1 item deleted for good.", vm.uiState.value.message?.text)
        assertEquals(emptyList(), vm.uiState.value.items)
        assertEquals("Workspace trash emptied", log.all.last().title)
        vm.emptyTrash()
        assertEquals("The trash was already empty.", vm.uiState.value.message?.text)
    }

    @Test
    fun `W1 - the trash can be emptied whenever anything is under its folder, and lists again what a kill left without its record`() = runTest(mainRule.dispatcher) {
        // The verifier's state: a holder a kill left without its record (as an earlier version of the app could leave one)...
        val root = Files.createTempDirectory("aicc-trash-ui").toFile()
        File(root, ".trash/1700000000000-abc12345").mkdirs()
        File(root, ".trash/1700000000000-abc12345/report.txt").writeBytes(ByteArray(40_000))
        // ...and something no record can be written for: two things in one holder.
        File(root, ".trash/1700000000000-two").mkdirs()
        File(root, ".trash/1700000000000-two/a.txt").writeText("aaaa")
        File(root, ".trash/1700000000000-two/b.txt").writeText("bb")
        val ws = LocalWorkspace(FakeWorkspaceRoot(root), clock, io = EmptyCoroutineContext)
        val vm = trash(ws)
        val browser = folder(ws)

        // Right after the start nothing is listed yet, but the screen knows that the trash is not empty.
        assertTrue(vm.uiState.value.items.isEmpty())
        assertEquals(40_006L, vm.uiState.value.leftoverBytes)
        assertTrue(vm.uiState.value.canEmpty, "\"Empty the trash\" is offered")
        assertTrue(browser.uiState.value.trashHasLeftovers && browser.uiState.value.trashCount == 0, "the trash button does not call it empty")

        // The first change after the start, by a tool or the owner: what can be restored is listed again.
        ws.put("after-restart.txt", "x")
        val item = vm.uiState.value.items.single()
        assertEquals("report.txt", item.name)
        assertTrue(item.detail.startsWith("Old place not known, so it is restored to the top of the workspace · trashed"), item.detail)
        assertEquals(6L, vm.uiState.value.leftoverBytes)
        assertEquals(1, browser.uiState.value.trashCount)

        vm.restore(item.id)
        assertEquals("Restored to report.txt.", vm.uiState.value.message?.text)
        assertEquals(40_000L, File(root, "report.txt").length())
        assertTrue(vm.uiState.value.items.isEmpty())
        assertTrue(vm.uiState.value.canEmpty, "nothing is listed, yet the trash is not empty: it can still be emptied")

        vm.emptyTrash()
        assertEquals("What was left in the trash was deleted for good.", vm.uiState.value.message?.text)
        assertFalse(File(root, ".trash/1700000000000-two").exists())
        assertNull(vm.uiState.value.leftoverBytes)
        assertFalse(vm.uiState.value.canEmpty)
        assertFalse(browser.uiState.value.trashHasLeftovers)
        assertEquals("Workspace trash emptied", log.all.last().title)
        assertEquals(40_001L, ws.usedBytes())
        vm.emptyTrash()
        assertEquals("The trash was already empty.", vm.uiState.value.message?.text)
    }
}
