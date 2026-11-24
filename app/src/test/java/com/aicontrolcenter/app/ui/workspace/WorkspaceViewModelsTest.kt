package com.aicontrolcenter.app.ui.workspace

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.operator.CommandTurn
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.framework.operator.LocalWorkspace
import com.aicontrolcenter.framework.operator.WorkspaceLimits
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
@OptIn(ExperimentalCoroutinesApi::class)
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

        // Round 4 (W3): the trash is put right before it is first shown. What can be restored is listed from the first
        // look on, with no change needed first, and is never among the leftovers that emptying would delete for good.
        val item = vm.uiState.value.items.single()
        assertEquals("report.txt", item.name)
        assertTrue(item.detail.startsWith("Old place not known, so it is restored to the top of the workspace · trashed"), item.detail)
        assertEquals(6L, vm.uiState.value.leftoverBytes, "only what no record can be written for is left over")
        assertTrue(vm.uiState.value.canEmpty, "\"Empty the trash\" is offered")
        assertTrue(vm.uiState.value.emptyingDeletes.startsWith("1 item and the leftovers of an interrupted change ("), vm.uiState.value.emptyingDeletes)
        assertTrue(browser.uiState.value.trashHasLeftovers && browser.uiState.value.trashCount == 1)

        // A change after that, by a tool or the owner, finds nothing more to put right.
        ws.put("after-restart.txt", "x")
        assertEquals(listOf(item.id), vm.uiState.value.items.map { it.id })
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

    @Test
    fun `W3 - a file found in the trash without its record is shown as an item to restore, never as leftovers to delete`() = runTest(mainRule.dispatcher) {
        // The verifier's state: the owner opens the trash first thing after a start, before any change was made.
        val root = Files.createTempDirectory("aicc-trash-ui").toFile()
        File(root, "report.txt").writeText("live")
        File(root, ".trash/1700000000000-abc12345").mkdirs()
        File(root, ".trash/1700000000000-abc12345/contract.txt").writeText("the owner's signed contract")
        // And what a restore leaves when the app is killed right after it deleted the record: an empty holder.
        File(root, ".trash/1700000000001-def67890").mkdirs()
        val ws = LocalWorkspace(FakeWorkspaceRoot(root), clock, io = EmptyCoroutineContext)
        val browser = folder(ws)
        val vm = trash(ws)

        val state = vm.uiState.value
        assertEquals(listOf("contract.txt"), state.items.map { it.name })
        assertNull(state.leftoverBytes, "nothing is called a leftover, and nothing of 0 bytes is")
        assertEquals("1 item is deleted for good. This cannot be undone.", state.emptyingDeletes)
        assertEquals(1 to false, browser.uiState.value.trashCount to browser.uiState.value.trashHasLeftovers)
        assertFalse(File(root, ".trash/1700000000001-def67890").exists())

        vm.restore(state.items.single().id)
        assertEquals("Restored to contract.txt.", vm.uiState.value.message?.text)
        assertEquals("the owner's signed contract", File(root, "contract.txt").readText())
        assertFalse(vm.uiState.value.canEmpty, "the trash is empty now")
    }

    @Test
    fun `W2 - while a shell command runs the trash screens claim no leftovers, and show them again when it has ended`() = runTest(mainRule.dispatcher) {
        val root = Files.createTempDirectory("aicc-trash-ui").toFile()
        // True leftovers: two things in one holder, which no record can be written for.
        File(root, ".trash/1700000000000-two").mkdirs()
        File(root, ".trash/1700000000000-two/a.txt").writeText("aaaa")
        File(root, ".trash/1700000000000-two/b.txt").writeText("bb")
        val ws = LocalWorkspace(FakeWorkspaceRoot(root), clock, io = EmptyCoroutineContext)
        val vm = trash(ws)
        val browser = folder(ws)
        assertEquals(6L, vm.uiState.value.leftoverBytes)
        assertTrue(browser.uiState.value.trashHasLeftovers)

        ws.runCommand(ws.changeCount()) {
            // The command's start was announced and the screens looked again. What is under the trash folder now may be the
            // command's own work, so nothing is called a leftover of an interrupted change, and nothing is offered for deletion.
            kotlinx.coroutines.yield()
            assertNull(vm.uiState.value.leftoverBytes)
            assertFalse(vm.uiState.value.canEmpty)
            assertFalse(browser.uiState.value.trashHasLeftovers)
        }

        assertEquals(6L, vm.uiState.value.leftoverBytes, "its end was announced too, and the leftovers are shown again")
        assertTrue(vm.uiState.value.canEmpty && browser.uiState.value.trashHasLeftovers)
    }

    @Test
    fun `W2 - emptying the trash while another change is under way still says what was deleted`() = runTest(mainRule.dispatcher) {
        val root = Files.createTempDirectory("aicc-trash-ui").toFile()
        File(root, ".trash/1700000000000-two").mkdirs()
        File(root, ".trash/1700000000000-two/a.txt").writeText("aaaa")
        File(root, ".trash/1700000000000-two/b.txt").writeText("bb")
        val real = LocalWorkspace(FakeWorkspaceRoot(root), clock, io = EmptyCoroutineContext)
        // The workspace as it answers while an agent's change is in flight: no leftovers are claimed.
        var changeUnderWay = false
        val ws = object : com.aicontrolcenter.core.operator.Workspace by real {
            override suspend fun trashLeftovers(): Long? = if (changeUnderWay) null else real.trashLeftovers()
        }
        val vm = WorkspaceTrashViewModel(ws, activity, clock, ZoneOffset.UTC).also { it.uiState.activeIn(backgroundScope) }
        assertEquals(6L, vm.uiState.value.leftoverBytes)
        assertTrue(vm.uiState.value.emptyingDeletes.startsWith("The leftovers of an interrupted change ("))

        // The owner confirmed that; an agent's write begins at that moment.
        changeUnderWay = true
        vm.emptyTrash()
        changeUnderWay = false

        assertEquals("What was left in the trash was deleted for good.", vm.uiState.value.message?.text)
        assertFalse(File(root, ".trash/1700000000000-two").exists())
        assertTrue(log.all.last().detail.startsWith("Leftovers of an interrupted change ("), log.all.last().detail)
    }

    @Test
    fun `F2 - something without a size under the trash folder is shown and can be emptied, and no size is named`() = runTest(mainRule.dispatcher) {
        // The verifier's state: an approved shell command left an empty file under the trash folder and filled the workspace past its limit.
        val root = Files.createTempDirectory("aicc-trash-ui").toFile()
        File(root, "report.txt").writeText("r".repeat(20_000))
        File(root, "notes.txt").writeText("n".repeat(20_000))
        File(root, ".trash").mkdirs()
        File(root, ".trash/x").writeText("")
        File(root, "filled-by-the-command.bin").writeText("z".repeat(70_000))
        val ws = LocalWorkspace(FakeWorkspaceRoot(root), clock, WorkspaceLimits(maxFileBytes = 50_000, maxTotalBytes = 100_000), io = EmptyCoroutineContext)
        val browser = folder(ws)
        val vm = trash(ws)

        browser.trash(RelativePath.of("notes.txt"))
        assertTrue("Empty the trash first." in browser.uiState.value.message!!.text, browser.uiState.value.message!!.text)
        assertTrue(File(root, "notes.txt").exists())

        // Told to empty the trash first, the owner finds that offered: the size rule and the screens use one test.
        assertTrue(browser.uiState.value.trashHasLeftovers, "the folder screen's trash button does not say \"empty\"")
        val state = vm.uiState.value
        assertTrue(state.items.isEmpty())
        assertEquals(0L, state.leftoverBytes, "it is there, without a size")
        assertTrue(state.canEmpty, "\"Empty the trash\" is offered")
        assertEquals("The leftovers of an interrupted change are deleted for good. This cannot be undone.", state.emptyingDeletes)

        vm.emptyTrash()
        assertEquals("What was left in the trash was deleted for good.", vm.uiState.value.message?.text)
        assertEquals("Leftovers of an interrupted change deleted for good, at your request.", log.all.last().detail)
        assertFalse(File(root, ".trash/x").exists())
        assertNull(vm.uiState.value.leftoverBytes)
        assertFalse(vm.uiState.value.canEmpty || browser.uiState.value.trashHasLeftovers)

        // While the trash is empty one item can always be moved there, as the app now shows it.
        browser.trash(RelativePath.of("notes.txt"))
        assertEquals("“notes.txt” moved to the trash. You can restore it from there.", browser.uiState.value.message?.text)
    }

    @Test
    fun `F5 - emptying the trash deletes what the owner was shown - an item that arrived meanwhile is kept, and the message says so`() = runTest(mainRule.dispatcher) {
        val ws = workspace()
        ws.put("old.txt", "old")
        ws.put("contract.txt", "the owner's signed contract")
        assertTrue(ws.trash(RelativePath.of("old.txt")) is DocResult.Ok)
        val vm = trash(ws)
        // A shell command the owner approved is running, and an agent's move to the trash (which needs no approval,
        // because it can be undone) waits for it.
        val release = CompletableDeferred<Unit>()
        val command = backgroundScope.async { ws.runCommand(ws.changeCount()) { release.await() } }
        runCurrent()
        val agent = backgroundScope.async { ws.trash(RelativePath.of("contract.txt")) }
        runCurrent()

        // The owner opens the trash, reads the confirmation and confirms.
        assertEquals(listOf("old.txt"), vm.uiState.value.items.map { it.name })
        assertEquals("1 item is deleted for good. This cannot be undone.", vm.uiState.value.emptyingDeletes)
        vm.emptyTrash()
        runCurrent()
        assertEquals(WAITS_FOR_COMMAND, vm.uiState.value.message?.text)

        release.complete(Unit)
        runCurrent()
        assertTrue(command.await() is CommandTurn.Ran)
        assertTrue(agent.await() is DocResult.Ok)

        // Only what was confirmed is gone. The file the owner never saw in the trash can still be restored.
        assertEquals("1 item deleted for good. 1 item that arrived meanwhile was kept.", vm.uiState.value.message?.text)
        assertEquals(listOf("contract.txt"), vm.uiState.value.items.map { it.name })
        assertEquals("1 item deleted for good, at your request.", log.all.last().detail)
        vm.restore(vm.uiState.value.items.single().id)
        assertEquals("the owner's signed contract", (ws.read(RelativePath.of("contract.txt"), 0, 100) as DocResult.Ok).value.bytes.decodeToString())
    }
}
