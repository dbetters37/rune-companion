package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.TrashItem
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 4, set STATE, in the workspace.
 *
 * W1: the change counter is raised when a change has ENDED, also when the tidy after a start or
 * a folder made on the way raised it earlier in the same change. Otherwise a shell command
 * assessed between that early raise and the moment the file lands reads a counter that does not
 * move again, and runs although a file changed after the owner was asked.
 *
 * W2: while a change or a shell command is under way, nothing under the trash folder is called a
 * leftover of an interrupted change; what is there is the change's own work in progress.
 *
 * W3: the tidy after a start runs before the trash is first looked at, so a holder that gets its
 * record back is listed as a recovered item from the first look on, and is never offered as
 * leftovers that "Empty the trash" deletes for good. Nothing of 0 bytes is reported.
 */
class WorkspaceRound4Test {
    private fun p(s: String) = RelativePath.of(s)

    private suspend fun OperatorFixture.trash(): List<TrashItem> = (workspace.listTrash() as DocResult.Ok).value

    private class Held<T>(val before: Long, val whileHeld: Long, val after: Long, val seen: T)

    /**
     * Runs [change] on another thread and holds it at the file-system step [holdAt] (the
     * workspace's own test hook, the first time the step comes); runs [meanwhile] while it is
     * held; then lets the change go on to its end. Every wait has a limit.
     */
    private fun <T> heldAt(f: OperatorFixture, holdAt: String, change: suspend () -> Unit, meanwhile: suspend () -> T): Held<T> {
        val reached = CountDownLatch(1)
        val go = CountDownLatch(1)
        f.workspace.beforeStep = { step ->
            if (step == holdAt && reached.count > 0) {
                reached.countDown()
                go.await(20, TimeUnit.SECONDS)
            }
        }
        val before = f.workspace.changeCount()
        var failure: Throwable? = null
        val changer = thread {
            try {
                runBlocking { change() }
            } catch (e: Throwable) {
                failure = e
            }
        }
        try {
            assertTrue(reached.await(10, TimeUnit.SECONDS), "the step \"$holdAt\" was never reached (${failure ?: "the change is still running"})")
            val whileHeld = f.workspace.changeCount()
            val seen = runBlocking { withTimeout(10_000) { meanwhile() } }
            go.countDown()
            changer.join(20_000)
            assertFalse(changer.isAlive, "the change ended")
            failure?.let { throw it }
            return Held(before, whileHeld, f.workspace.changeCount(), seen)
        } finally {
            go.countDown()
            f.workspace.beforeStep = null
        }
    }

    private val ls = json("command" to "ls")

    /** The shell command is assessed (the owner is asked) while the change is held; afterwards it must not run. */
    private fun assertTheCommandAskedForMeanwhileDoesNotRun(f: OperatorFixture, holdAt: String, change: suspend () -> Unit): Held<*> {
        val tool = f.tool("scripts.run_shell")
        val held = heldAt(f, holdAt, change) { tool.assess(ls, f.context) }
        val assessment = held.seen

        assertNotEquals(held.whileHeld, held.after, "the counter read when the owner was asked must not be the counter after the file landed")
        runBlocking {
            assertNotNull(assessment.prepared!!.changedSince(), "the change that ended after the owner was asked is noticed")
            val outcome = tool.execute(ls, f.context, assessment)
            assertFalse(outcome.ok, outcome.content)
            assertTrue("something in the workspace changed" in outcome.content, outcome.content)
        }
        assertTrue(f.shell.runs.isEmpty(), "the command never reached the shell")
        return held
    }

    // ---- W1 ----

    @Test
    fun `W1 - control - an ordinary replace under way when the owner is asked is counted when it ends`() {
        val f = OperatorFixture()
        f.put("tidy", "echo BENIGN\n")

        val held = assertTheCommandAskedForMeanwhileDoesNotRun(f, "write the new file to a staging file") {
            f.ok("workspace.write", "path" to "tidy", "content" to "rm -rf keep\n", "overwrite" to true)
        }

        assertEquals(listOf(0L, 0L, 1L), listOf(held.before, held.whileHeld, held.after))
    }

    @Test
    fun `W1 - the first change after a start that had something to tidy is counted again when it ends`() {
        val f = OperatorFixture()
        f.put("tidy", "echo BENIGN\n")
        // What a killed change left: the tidy before this first change clears it, and counts that at once.
        f.put(".trash/.staging/w-left", "half a file")

        val held = assertTheCommandAskedForMeanwhileDoesNotRun(f, "write the new file to a staging file") {
            f.ok("workspace.write", "path" to "tidy", "content" to "rm -rf keep\n", "overwrite" to true)
        }

        assertEquals(1L, held.whileHeld - held.before, "the tidy was counted before the file was written")
        assertEquals("rm -rf keep\n", f.file("tidy").readText())
    }

    @Test
    fun `W1 - a new file in a new folder is counted again when it has landed`() {
        val f = OperatorFixture()

        val held = assertTheCommandAskedForMeanwhileDoesNotRun(f, "write the new file to a staging file") {
            f.ok("workspace.write", "path" to "hooks/run", "content" to "rm -rf keep\n")
        }

        assertEquals(1L, held.whileHeld - held.before, "the new folder was counted before the file was written")
        assertTrue(f.file("hooks/run").isFile)
    }

    @Test
    fun `W1 - every way of changing counts at its end although a folder was made first`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.put("a.txt", "a")
        f.put("b.txt", "b")
        val ways: List<Pair<String, suspend () -> DocResult<*>>> = listOf(
            "write" to { f.workspace.write(p("n1/x.txt"), "x".toByteArray(), overwrite = false) },
            "append" to { f.workspace.append(p("n2/x.txt"), "x".toByteArray()) },
            "import" to { f.workspace.import(p("n3/x.txt"), ByteArrayInputStream("x".toByteArray()), 100, overwrite = false) },
            "move" to { f.workspace.move(p("a.txt"), p("n4/a.txt"), overwrite = false) },
            "make folder" to { f.workspace.makeFolder(p("n5/inner")) },
        )
        for ((name, change) in ways) {
            val counts = ArrayList<Long>()
            f.workspace.beforeStep = { counts += f.workspace.changeCount() }
            val before = f.workspace.changeCount()
            assertIs<DocResult.Ok<*>>(change(), name)
            f.workspace.beforeStep = null
            val after = f.workspace.changeCount()

            assertTrue(after > before, name)
            // Whatever the counter was at any step on the way, it is not that at the end.
            assertTrue(counts.none { it == after }, "$name: the counter at a step on the way ($counts) is still the counter at the end ($after)")
        }
        // A restore into a folder that is gone makes the folder again first: counted then, and again when the file is back.
        val file = (f.workspace.trash(p("n4/a.txt")) as DocResult.Ok).value
        assertIs<DocResult.Ok<*>>(f.workspace.trash(p("n4")))
        val before = f.workspace.changeCount()
        assertIs<DocResult.Ok<*>>(f.workspace.restore(file.id))
        assertEquals(before + 2, f.workspace.changeCount())
    }

    @Test
    fun `W1 - an import into a new folder from a slow source is counted when it has ended`() = runBlocking<Unit> {
        // No test hook here: the owner's Workspace screen imports a file while an agent asks for a command.
        val f = OperatorFixture()
        val tool = f.tool("scripts.run_shell")
        val firstRead = CountDownLatch(1)
        val go = CountDownLatch(1)
        val bytes = "rm -rf keep\n".toByteArray()
        val slow = object : InputStream() {
            var at = 0

            override fun read(): Int {
                firstRead.countDown()
                go.await(20, TimeUnit.SECONDS)
                return if (at < bytes.size) bytes[at++].toInt() else -1
            }
        }
        val importer = async(Dispatchers.IO) { f.workspace.import(p("inbox/run"), slow, 1_000_000, overwrite = false) }
        try {
            assertTrue(firstRead.await(10, TimeUnit.SECONDS), "the import is under way")
            val whileImporting = f.workspace.changeCount()
            val assessment = tool.assess(ls, f.context)

            go.countDown()
            assertIs<DocResult.Ok<*>>(withTimeout(20_000) { importer.await() })

            assertNotEquals(whileImporting, f.workspace.changeCount(), "the import ended after the owner was asked")
            assertFalse(tool.execute(ls, f.context, assessment).ok)
            assertTrue(f.shell.runs.isEmpty())
        } finally {
            go.countDown()
        }
    }

    @Test
    fun `W1 - a change the file system stopped half way counts, one that was refused before it began does not`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.put("notes.md", "one")
        f.ok("workspace.write", "path" to "other.txt", "content" to "x")
        val tool = f.tool("scripts.run_shell")

        // Refused before anything was touched: nothing counted, and a command asked for before it runs.
        val first = tool.assess(ls, f.context)
        val count = f.workspace.changeCount()
        assertIs<DocResult.AlreadyExists>(f.workspace.write(p("notes.md"), "two".toByteArray(), overwrite = false))
        assertIs<DocResult.NotFound>(f.workspace.trash(p("no-such-file")))
        assertEquals(count, f.workspace.changeCount())
        assertTrue(tool.execute(ls, f.context, first).ok)

        // Stopped by the file system at its last step: by then a version was kept and a file staged. It counts.
        val second = tool.assess(ls, f.context)
        val before = f.workspace.changeCount()
        f.workspace.beforeStep = { step -> if (step == "rename the new file into place") throw IOException("the disk is gone") }
        val stopped = f.workspace.write(p("notes.md"), "two".toByteArray(), overwrite = true)
        f.workspace.beforeStep = null
        assertIs<DocResult.Failed>(stopped)
        assertEquals("one", f.file("notes.md").readText())
        assertTrue(f.workspace.changeCount() > before, "a change that ended with an error may have changed something first")
        assertFalse(tool.execute(ls, f.context, second).ok)
        assertEquals(1, f.shell.runs.size)
    }

    // ---- W2 ----

    @Test
    fun `W2 - nothing is called a leftover while an ordinary change is under way`() = runBlocking<Unit> {
        for (links in listOf(true, false)) {
            for (how in listOf("a write over a file", "an import over it", "an edit", "a move over it", "its trash")) {
                val f = OperatorFixture()
                f.workspace.linksWork = links
                val where = "links=$links, $how"
                f.ok("workspace.write", "path" to "report.txt", "content" to "old ".repeat(10_000))
                f.ok("workspace.write", "path" to "incoming.txt", "content" to "new ".repeat(7_000))
                val seen = ArrayList<String>()
                // As a reader sees it before each file-system step of the change: another agent's tool, or the owner's screen.
                f.workspace.beforeStep = { step ->
                    runBlocking {
                        val listed = withTimeout(5_000) { f.trash() }
                        val leftovers = withTimeout(5_000) { f.workspace.trashLeftovers() }
                        val words = TrashText.list(listed, leftovers)
                        seen += step
                        assertNull(leftovers, "$where, before \"$step\": ${listed.size} listed")
                        assertFalse("leftovers" in words || "interrupted" in words, "$where, before \"$step\": $words")
                    }
                }
                val result: DocResult<*> = when (how) {
                    "a write over a file" -> f.workspace.write(p("report.txt"), "new ".repeat(7_000).toByteArray(), overwrite = true)
                    "an import over it" -> f.workspace.import(p("report.txt"), ByteArrayInputStream(ByteArray(30_000)), 1_000_000, overwrite = true)
                    "an edit" -> f.workspace.replaceText(p("report.txt"), "old", "new", expectedCount = null)
                    "a move over it" -> f.workspace.move(p("incoming.txt"), p("report.txt"), overwrite = true)
                    else -> f.workspace.trash(p("report.txt"))
                }
                f.workspace.beforeStep = null

                assertIs<DocResult.Ok<*>>(result, where)
                assertTrue(seen.size >= 3, "$where: the change was looked at on its way: $seen")
                assertEquals(1, f.trash().size, where)
                assertNull(f.workspace.trashLeftovers(), where)
            }
        }
    }

    @Test
    fun `W2 - a look from another thread during a change does not wait and claims nothing, the tools included`() {
        val f = OperatorFixture()
        f.put("report.txt", "the report")
        var restoreListing: ToolOutcome? = null
        var emptying: ToolOutcome? = null

        val held = heldAt(
            f, "link or copy the current version into the holder",
            change = { f.ok("workspace.write", "path" to "report.txt", "content" to "a new report", "overwrite" to true) },
        ) {
            // The record of the version being kept is written and its holder is empty; the new file is staged.
            assertTrue(f.file(".trash/.staging").list().orEmpty().isNotEmpty(), "a file is staged right now")
            restoreListing = f.ok("workspace.restore")
            emptying = f.fails("workspace.empty_trash")
            f.trash() to f.workspace.trashLeftovers()
        }

        assertEquals(emptyList<TrashItem>() to null, held.seen)
        assertEquals("The workspace trash is empty.", restoreListing!!.content)
        assertTrue("The trash is empty; nothing to delete." in emptying!!.content, emptying!!.content)
        runBlocking {
            assertEquals(1, f.trash().size, "once the change has ended, the version it kept is listed")
            assertNull(f.workspace.trashLeftovers())
        }
    }

    @Test
    fun `W2 - what really is left over is still reported once nothing is under way`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.put(".trash/stray.bin", "x".repeat(500))
        f.put(".trash/1700000000000-two/a.txt", "aaaa")
        f.put(".trash/1700000000000-two/b.txt", "bb")

        assertEquals(506L, f.workspace.trashLeftovers())
        f.ok("workspace.write", "path" to "notes.md", "content" to "x")
        assertEquals(506L, f.workspace.trashLeftovers())
        assertTrue("leftovers of a change that was interrupted" in f.ok("workspace.restore").content)
    }

    @Test
    fun `W2 - a listener that looks again when a change is announced finds the change ended`() = runBlocking<Unit> {
        // A screen: it looks at the trash whenever the workspace says it changed. Everything on one thread here, so the
        // listener runs at the very moment of the announcement. Were that made while the change still holds the change
        // lock, the listener would be told "a change is under way, nothing is claimed", and nothing would make it look again.
        val root = Files.createTempDirectory("aicc-announce").toFile()
        File(root, ".trash").mkdirs()
        File(root, ".trash/stray.bin").writeText("x".repeat(500))
        val ws = LocalWorkspace(FakeWorkspaceRoot(root), FakeClock(autoAdvanceMs = 1), io = EmptyCoroutineContext)
        val seen = ArrayList<Pair<Long, Long?>>()
        val screen = launch(Dispatchers.Unconfined) { ws.changes.collect { seen += it to ws.trashLeftovers() } }
        try {
            assertEquals(listOf(0L to 500L), seen.toList())

            assertIs<DocResult.Ok<*>>(ws.write(p("docs/a.txt"), "x".toByteArray(), overwrite = false))
            assertEquals(ws.changeCount() to 500L, seen.last(), "after a change that made a folder first: $seen")

            assertIs<DocResult.Ok<*>>(ws.trash(p("docs/a.txt")))
            assertEquals(ws.changeCount() to 500L, seen.last(), "after a trash: $seen")

            var duringTheCommand: Pair<Long, Long?>? = null
            ws.runCommand(ws.changeCount()) { duringTheCommand = seen.last() }
            assertNull(duringTheCommand!!.second, "the start of a command is announced, and while it runs nothing is claimed")
            assertEquals(ws.changeCount() to 500L, seen.last(), "after a shell command: $seen")

            ws.changedOutside()
            assertEquals(ws.changeCount() to 500L, seen.last())
        } finally {
            screen.cancel()
        }
    }

    // ---- W3 ----

    @Test
    fun `W3 - the first look at the trash after a start lists a holder that lost its record and never calls it a leftover`() = runBlocking<Unit> {
        for (first in listOf("the trash is listed first", "the leftovers are asked for first", "an agent lists the trash first")) {
            val f = OperatorFixture()
            // What a build before round 3 could leave when it was killed, or a damaged record: content in a holder, no record.
            f.put(".trash/$LOST_ID/contract.txt", "the owner's signed contract")
            val count = f.workspace.changeCount()

            val leftoversFirst = if (first == "the leftovers are asked for first") f.workspace.trashLeftovers() else null
            val words = if (first == "an agent lists the trash first") f.ok("workspace.restore").content else null
            val listed = f.trash()

            assertNull(leftoversFirst, first)
            val item = listed.singleOrNull()
            assertEquals(LOST_ID to true, item?.id to item?.recovered, "$first: it is listed, as an item whose old place is not known")
            assertEquals("contract.txt", item!!.originalPath)
            assertNull(f.workspace.trashLeftovers(), "$first: nothing is offered as leftovers that would be deleted for good")
            words?.let { assertTrue("contract.txt" in it && "leftovers" !in it, it) }
            assertTrue(f.workspace.changeCount() > count, "$first: the tidy gave it a record, which is a change in the workspace")
            assertEquals("the owner's signed contract", f.file(".trash/$LOST_ID/contract.txt").readText())

            // So it can be restored, which the state before never offered.
            assertIs<DocResult.Ok<*>>(f.workspace.restore(LOST_ID))
            assertEquals("the owner's signed contract", f.file("contract.txt").readText())
            // And looking again changes nothing more.
            val after = f.workspace.changeCount()
            f.trash()
            f.workspace.trashLeftovers()
            assertEquals(after, f.workspace.changeCount(), first)
        }
    }

    @Test
    fun `W3 - an empty holder without a record is removed at the first look, and nothing of 0 bytes is reported`() = runBlocking<Unit> {
        val f = OperatorFixture()
        // A restore that was killed after it deleted its record leaves the empty holder; a killed trash leaves a record alone.
        f.file(".trash/$EMPTY_ID").mkdirs()
        f.put(".trash/$LOST_ID.json", """{"id":"$LOST_ID","originalPath":"gone.txt","isFolder":false,"sizeBytes":1,"trashedAt":1700000000001}""")

        assertNull(f.workspace.trashLeftovers(), "never \"leftovers (0 bytes)\"")
        assertTrue(f.trash().isEmpty())
        assertFalse(f.file(".trash/$EMPTY_ID").exists(), "the empty holder is simply removed")
        assertFalse(f.file(".trash/$LOST_ID.json").exists(), "and so is a record whose content never arrived")
        assertEquals("The workspace trash is empty.", f.ok("workspace.restore").content)

        // Also what the tidy leaves alone is not reported while it has no size: an empty file or folder a shell command made.
        f.put(".trash/empty-file", "")
        f.file(".trash/empty-folder/inner").mkdirs()
        assertNull(f.workspace.trashLeftovers())
        f.put(".trash/one-byte", "x")
        assertEquals(1L, f.workspace.trashLeftovers())
    }

    @Test
    fun `W3 - something without a size under the staging folder's name blocks no write, since it would not be shown`() = runBlocking<Unit> {
        // Only a shell command can do this. Reported "leftovers (0 bytes)" were the way out before; now there is none to need.
        val f = OperatorFixture()
        f.put("keep/important.txt", "owner data")
        f.put(".trash/.staging", "")
        assertNull(f.workspace.trashLeftovers())
        f.ok("workspace.write", "path" to "a.txt", "content" to "x")
        assertTrue(f.file(".trash/.staging").isDirectory)

        // A link in its place is removed as a link; nothing is staged through it.
        f.file(".trash/.staging").deleteRecursively()
        Files.createSymbolicLink(f.file(".trash/.staging").toPath(), f.file("keep").toPath())
        assertNull(f.workspace.trashLeftovers())
        f.ok("workspace.write", "path" to "a.txt", "content" to "y", "overwrite" to true)
        assertTrue(f.file(".trash/.staging").isDirectory && !Files.isSymbolicLink(f.file(".trash/.staging").toPath()))
        assertEquals(listOf("important.txt"), f.file("keep").list()!!.toList(), "nothing was written into what the link pointed to")
        assertEquals("y", f.file("a.txt").readText())

        // A file with content there is somebody's: it stays, writing fails, and it is shown, so the owner can clear it.
        f.file(".trash/.staging").deleteRecursively()
        f.put(".trash/.staging", "not a folder")
        f.fails("workspace.write", "path" to "b.txt", "content" to "x")
        assertEquals(12L, f.workspace.trashLeftovers())
        assertEquals("not a folder", f.file(".trash/.staging").readText())
    }

    @Test
    fun `W3 - a first look while a shell command runs waits for nothing, tidies nothing and claims nothing - the look after it tidies`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.put(".trash/$LOST_ID/contract.txt", "the owner's signed contract")
        f.put(".trash/.staging/half-written", "x")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.shell.respond = { _, _ ->
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
            CommandOutcome.Finished(0, "done", "", false, 1)
        }
        val tool = f.tool("scripts.run_shell")
        val assessment = tool.assess(ls, f.context)
        val command = async(Dispatchers.Default) { tool.execute(ls, f.context, assessment) }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS), "the command started")

            assertTrue(withTimeout(5_000) { f.trash() }.isEmpty())
            assertNull(withTimeout(5_000) { f.workspace.trashLeftovers() })
            assertTrue(f.file(".trash/.staging/half-written").exists(), "nothing was tidied under the command's feet")
            assertFalse(f.file(".trash/$LOST_ID.json").exists())
            // An agent that asks meanwhile is told what is listed, not that the trash is empty: nothing was counted.
            assertEquals("The workspace trash lists no items.", withTimeout(5_000) { f.ok("workspace.restore") }.content)
            assertTrue("The trash lists no items to delete." in withTimeout(5_000) { f.fails("workspace.empty_trash") }.content)
        } finally {
            release.countDown()
        }
        assertTrue(withTimeout(20_000) { command.await() }.ok)

        // No change is made; the next look alone puts the trash right.
        assertEquals(listOf(LOST_ID), f.trash().map { it.id })
        assertFalse(f.file(".trash/.staging/half-written").exists())
        assertNull(f.workspace.trashLeftovers())
    }

    private companion object {
        const val LOST_ID = "1700000000000-abc12345"
        const val EMPTY_ID = "1700000000001-def67890"
    }
}
