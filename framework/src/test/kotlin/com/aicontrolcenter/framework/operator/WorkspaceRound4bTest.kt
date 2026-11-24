package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.TrashItem
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Corrective pass 4b, set WORKSPACE: what round 4 itself broke around the trash.
 *
 * F1: a look at what is under the trash folder is answered "nothing was looked at" only while a
 * shell command holds the workspace, whose end is always announced. Another look, or a change,
 * is waited for. Before, either of them turned a look away with "nothing is left over", and
 * nothing told a screen to look again.
 *
 * F2: one rule for "the trash is empty". Whatever lies under the trash folder is shown, can be
 * emptied and is never called an empty trash, also when it has no size; that is the test by
 * which a move to the trash is refused in a workspace over its limit.
 *
 * F3: assessing a call has no side effects. The tidy after a start is not run by the assessment
 * of workspace.restore or workspace.empty_trash.
 *
 * F4: a copy from outside that the gateway began counts as a change when it has ended, whatever
 * it left behind.
 *
 * F5: emptying the trash deletes what was listed when it was confirmed. An item that arrived
 * meanwhile stays, and the result says so.
 */
class WorkspaceRound4bTest {
    private fun p(s: String) = RelativePath.of(s)

    private suspend fun OperatorFixture.trash(): List<TrashItem> = (workspace.listTrash() as DocResult.Ok).value

    /** True leftovers that no tidy can list: two things in one holder (6 bytes), and [extraFiles] more of one byte each. */
    private fun OperatorFixture.trueLeftovers(extraFiles: Int = 0): Long {
        put(".trash/1700000000000-two/a.txt", "aaaa")
        put(".trash/1700000000000-two/b.txt", "bb")
        repeat(extraFiles) { put(".trash/left-by-a-command/d${it / 100}/f$it", "x") }
        return 6L + extraFiles
    }

    /** What workspace.restore without an id answers: the trash in words. */
    private suspend fun OperatorFixture.listing(): String = ok("workspace.restore").content

    /** What workspace.empty_trash answers while nothing is listed. (With items listed it would ask the owner; that is not run here.) */
    private suspend fun OperatorFixture.emptying(): String {
        val tool = tool("workspace.empty_trash")
        val assessment = tool.assess(json(), context)
        check(assessment.details.isEmpty()) { "items are listed: ${assessment.details}" }
        return tool.execute(json(), context, assessment).content
    }

    /** [body] on a thread of its own; [Looker.join] waits for its end and hands on what it threw. */
    private class Looker(body: suspend () -> Unit) {
        @Volatile private var failure: Throwable? = null
        private val thread = thread {
            try {
                runBlocking { body() }
            } catch (e: Throwable) {
                failure = e
            }
        }

        fun join() {
            thread.join(120_000)
            assertFalse(thread.isAlive, "the looker ended")
            failure?.let { throw it }
        }
    }

    // ---- F1 ----

    @Test
    fun `F1 - two looks at once are both told what is left over`() {
        val f = OperatorFixture()
        val bytes = f.trueLeftovers(extraFiles = 400)
        runBlocking { assertEquals(bytes, f.workspace.trashLeftovers()) }
        val rounds = 150
        val wrongFigures = AtomicInteger()
        val wrongWords = CopyOnWriteArrayList<String>()

        // No change and no command is under way: only the two lookers meet each other.
        val screen = Looker { repeat(rounds) { if (f.workspace.trashLeftovers() != bytes) wrongFigures.incrementAndGet() } }
        val agent = Looker {
            repeat(rounds) {
                f.listing().let { if ("leftovers of a change that was interrupted" !in it) wrongWords += it }
                f.emptying().let { if ("leftovers of a change that was interrupted" !in it) wrongWords += it }
            }
        }
        screen.join()
        agent.join()

        assertEquals(0, wrongFigures.get(), "of $rounds looks, so many were told that nothing is left over because another look was under way")
        assertTrue(wrongWords.isEmpty(), "the tools called the trash empty ${wrongWords.size} times, e.g. ${wrongWords.firstOrNull()}")
    }

    @Test
    fun `F1 - a look that meets a change waits for it and then says what is left over, also when the change ends refused`() = runBlocking<Unit> {
        val f = OperatorFixture()
        val bytes = f.trueLeftovers()
        assertEquals(bytes, f.workspace.trashLeftovers())
        // The owner imports a document that turns out too large; its source is slow. Such a change raises no counter when it ends.
        val reading = CountDownLatch(1)
        val go = CountDownLatch(1)
        val source = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reading.countDown()
                go.await(20, TimeUnit.SECONDS)
                return minOf(len, 60_000)
            }
        }
        val importing = async(Dispatchers.IO) { f.workspace.import(p("big.bin"), source, 100_000, overwrite = false) }
        try {
            assertTrue(reading.await(10, TimeUnit.SECONDS), "the import is under way")
            val count = f.workspace.changeCount()
            // A screen and two tools look now.
            val look = async(Dispatchers.IO) { f.workspace.trashLeftovers() }
            val listing = async(Dispatchers.IO) { f.listing() }
            val emptying = async(Dispatchers.IO) { f.emptying() }
            delay(300)
            assertFalse(look.isCompleted && look.await() == null, "a look during a change is not told that nothing is left over")

            go.countDown()
            assertIs<DocResult.TooLarge>(withTimeout(20_000) { importing.await() })
            assertEquals(count, f.workspace.changeCount(), "the refused import raised nothing, so nothing tells a listener to look again")

            assertEquals(bytes, withTimeout(20_000) { look.await() })
            withTimeout(20_000) { listing.await() }.let { assertTrue("leftovers of a change that was interrupted" in it, it) }
            withTimeout(20_000) { emptying.await() }.let { assertTrue("leftovers of a change that was interrupted" in it, it) }
            assertFalse(f.file(".trash/.staging").list().orEmpty().isNotEmpty(), "what the import had staged was no leftover and is gone")
        } finally {
            go.countDown()
        }
    }

    @Test
    fun `F1 - two screens that look again after every change both end with the leftovers`() = runBlocking<Unit> {
        val f = OperatorFixture()
        val bytes = f.trueLeftovers(extraFiles = 300)

        // As the owner's folder screen and trash screen do: each looks again whenever the workspace announces a change.
        class Screen {
            @Volatile var last: Pair<Long, Long?>? = null
        }
        val screens = List(2) { Screen() }
        val following = screens.map { screen ->
            launch(Dispatchers.Default) { f.workspace.changes.collect { count -> screen.last = count to f.workspace.trashLeftovers() } }
        }
        try {
            repeat(40) { round ->
                assertIs<DocResult.Ok<*>>(f.workspace.write(p("r$round.txt"), "x".toByteArray(), overwrite = false))
                val count = f.workspace.changeCount()
                // Once it is quiet: each screen has finished the look it began on this announcement.
                withTimeout(30_000) { while (screens.any { it.last?.first != count }) delay(2) }
                screens.forEachIndexed { index, screen ->
                    assertEquals(bytes, screen.last!!.second, "after write $round, screen $index shows no leftovers although $bytes bytes are on disk")
                }
            }
        } finally {
            following.forEach { it.cancel() }
        }
    }

    // ---- F2 ----

    @Test
    fun `F2 - whatever lies under the trash folder is shown, can be emptied and is never called an empty trash`() = runBlocking<Unit> {
        for (what in listOf("an empty file", "an empty folder", "a holder with a link in it", "a holder with an empty file in it")) {
            val limits = WorkspaceLimits(maxFileBytes = 50_000, maxTotalBytes = 100_000)
            val f = OperatorFixture(workspaceLimits = limits)
            f.put("report.txt", "r".repeat(20_000))
            f.put("notes.txt", "n".repeat(20_000))
            // An approved shell command did both: it left something without a size under the trash folder and filled the workspace past its limit.
            when (what) {
                "an empty file" -> f.put(".trash/x", "")
                "an empty folder" -> f.file(".trash/cache/tmp").mkdirs()
                "a holder with a link in it" -> {
                    f.file(".trash/$HOLDER").mkdirs()
                    Files.createSymbolicLink(f.file(".trash/$HOLDER/l").toPath(), f.file("report.txt").toPath())
                }
                else -> f.put(".trash/$HOLDER/empty.txt", "")
            }
            f.put("filled-by-the-command.bin", "z".repeat(70_000))
            val w = f.workspace

            // The first look, by a tool: nothing is listed, and what is there has no size.
            val listing = f.listing()
            val emptying = f.emptying()
            assertEquals(0L, w.trashLeftovers(), "$what: it is there, without a size")
            for (words in listOf(listing, emptying)) {
                assertFalse("is empty" in words, "$what: $words")
                assertTrue("leftovers of a change that was interrupted." in words, "$what: $words")
                assertFalse(ZERO_BYTES.containsMatchIn(words), "$what: no size is named where there is none: $words")
            }

            // The size rule and the screens use one test: told to empty the trash first, the owner finds it offered.
            val refused = assertIs<DocResult.Failed>(w.trash(p("notes.txt")), what)
            assertTrue("Empty the trash first" in refused.reason, refused.reason)
            assertTrue(f.trash().isNotEmpty() || w.trashLeftovers() != null, "$what: \"Empty the trash first\", and emptying is offered")

            // And emptying is the way out.
            assertIs<DocResult.Ok<*>>(w.emptyTrash(null), what)
            assertTrue(f.trash().isEmpty() && w.trashLeftovers() == null, "$what: after emptying, the trash is empty")
            assertEquals("The workspace trash is empty.", f.listing(), what)
            assertIs<DocResult.Ok<*>>(w.trash(p("notes.txt")), "$what: while the trash is empty one item can always be moved there")
            f.root.deleteRecursively()
        }
    }

    // ---- F3 ----

    @Test
    fun `F3 - assessing workspace_restore or workspace_empty_trash changes nothing in the workspace`() = runBlocking<Unit> {
        val f = OperatorFixture()
        // After a start: a holder that lost its record and a staged file wait to be tidied.
        f.put(".trash/$HOLDER/contract.txt", "the owner's signed contract")
        f.put(".trash/.staging/w-left", "half a file")
        f.put("notes.md", "x")
        // A shell command waits for the owner's answer.
        val shell = f.tool("scripts.run_shell")
        val ls = json("command" to "ls")
        val asked = shell.assess(ls, f.context)
        val count = f.workspace.changeCount()

        // Another agent's calls are assessed, which happens before anyone decides whether it may make them.
        f.assess("workspace.restore")
        f.assess("workspace.restore", "id" to HOLDER)
        f.assess("workspace.empty_trash")
        f.assess("workspace.empty_trash", "ids" to listOf(HOLDER))

        assertEquals(count, f.workspace.changeCount(), "an assessment has no side effects")
        assertTrue(f.file(".trash/.staging/w-left").exists(), "nothing was tidied")
        assertFalse(f.file(".trash/$HOLDER.json").exists(), "no record was written")
        // So the command the owner then approves still runs.
        val outcome = shell.execute(ls, f.context, asked)
        assertTrue(outcome.ok, outcome.content)
        assertEquals(1, f.shell.runs.size)
    }

    // ---- F4 ----

    @Test
    fun `F4 - a copy from outside that fails and removes what it wrote is counted when it has ended`() {
        for (ending in listOf("fails and removes what it wrote", "ends well")) {
            val f = OperatorFixture()
            f.put("inbox/readme.txt", "hello")
            val shell = f.tool("scripts.run_shell")
            val ls = json("command" to "ls -R")
            val half = CountDownLatch(1)
            val go = CountDownLatch(1)
            var copied: DocResult<Long>? = null
            // The gateway as WorkspaceCopy.copyNew behaves: it creates the target and writes; when the source turns out too
            // large it deletes what it wrote and answers with a failure.
            val copier = Looker {
                copied = f.workspace.receive(p("inbox/statement.pdf"), 1_000_000) { target, _ ->
                    val path = target.file.toPath()
                    Files.write(path, ByteArray(40_000))
                    half.countDown()
                    go.await(20, TimeUnit.SECONDS)
                    if (ending == "ends well") {
                        Files.write(path, ByteArray(80_000))
                        DocResult.Ok(80_000L)
                    } else {
                        Files.deleteIfExists(path)
                        DocResult.TooLarge(2_000_000, 1_000_000)
                    }
                }
            }
            try {
                assertTrue(half.await(10, TimeUnit.SECONDS), "half the copy is written")
                // The owner is asked for a shell command now: the half-written file is there.
                val whenAsked = f.workspace.changeCount()
                assertTrue(f.file("inbox/statement.pdf").exists())
                val asked = runBlocking { shell.assess(ls, f.context) }
                go.countDown()
                copier.join()

                assertEquals(ending == "ends well", copied is DocResult.Ok, ending)
                assertEquals(ending == "ends well", f.file("inbox/statement.pdf").exists(), ending)
                assertNotEquals(whenAsked, f.workspace.changeCount(), "$ending: the copy ended after the owner was asked, and that is counted")
                runBlocking {
                    assertNotNull(asked.prepared!!.changedSince(), ending)
                    val outcome = shell.execute(ls, f.context, asked)
                    assertFalse(outcome.ok, "$ending: ${outcome.content}")
                    assertTrue("something in the workspace changed" in outcome.content, outcome.content)
                }
                assertTrue(f.shell.runs.isEmpty(), "$ending: the command never reached the shell")
            } finally {
                go.countDown()
            }
        }
    }

    @Test
    fun `F4 - a copy from outside that is refused before the gateway is called counts nothing`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.put("inbox/statement.pdf", "already here")
        val count = f.workspace.changeCount()
        var called = false

        val result = f.workspace.receive(p("inbox/statement.pdf"), 1_000_000) { _, _ ->
            called = true
            DocResult.Ok(0L)
        }

        assertIs<DocResult.AlreadyExists>(result)
        assertFalse(called)
        assertEquals(count, f.workspace.changeCount())
    }

    // ---- F5 ----

    @Test
    fun `F5 - workspace_empty_trash deletes the items its approval listed, keeps one that arrived meanwhile and says so`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.put("old.txt", "old")
        f.put("contract.txt", "the owner's signed contract")
        f.ok("workspace.trash", "paths" to listOf("old.txt"))
        val tool = f.tool("workspace.empty_trash")

        // The agent asks for the whole trash; the owner's approval lists one item.
        val asked = tool.assess(json(), f.context)
        assertEquals("Delete permanently 1 item (3 bytes) from the workspace trash", asked.summary)
        assertEquals(1, asked.details.size)
        // While the request waits, another agent moves a file to the trash, which needs no approval because it can be undone.
        f.ok("workspace.trash", "paths" to listOf("contract.txt"))

        val outcome = tool.execute(json(), f.context, asked)

        assertTrue(outcome.ok, outcome.content)
        assertEquals(listOf("contract.txt"), f.trash().map { it.originalPath }, "what the owner never saw in the trash is still there")
        assertEquals("Deleted 1 item from the trash for good. 1 item that arrived meanwhile was kept.", outcome.content)
    }

    private companion object {
        const val HOLDER = "1700000000000-abc12345"

        /** "0 bytes" as a size of its own (not the end of "500 bytes"), with either kind of space. */
        val ZERO_BYTES = Regex("(?<![0-9.,])0[ \\u00A0]bytes")
    }
}
