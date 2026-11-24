package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.CommandTurn
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.MediaId
import com.aicontrolcenter.core.operator.MediaItem
import com.aicontrolcenter.core.operator.MediaKind
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.testkit.FakeScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G1: nothing else changes the workspace between the question to the owner and the end of the
 * command. This holds however the command names what it runs: it does not depend on the reader
 * finding the script.
 */
class ShellWorkspaceLockTest {
    private val f = OperatorFixture()
    private val tool = f.tool("scripts.run_shell")

    private fun p(s: String) = RelativePath.of(s)

    /** A real /bin/sh behind the port, so a script's effect is visible. */
    private fun realShell() {
        f.shell.respond = { command, dir ->
            val builder = ProcessBuilder("/bin/sh", "-c", command).directory(dir.file).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment()["PATH"] = "/usr/bin:/bin"
            val p = builder.start()
            p.outputStream.close()
            p.waitFor(10, TimeUnit.SECONDS)
            CommandOutcome.Finished(p.exitValue(), p.inputStream.bufferedReader().readText(), "", false, 1)
        }
    }

    /** A spelling no reader of shell text finds: awk puts the script's name together and starts the shell itself. */
    private val hidden = json("command" to "awk 'BEGIN { system(\"sh ti\" \"dy\") }'")

    @Test
    fun `a script swapped after the owner was asked does not run, although the command does not name it`() = runBlocking<Unit> {
        realShell()
        f.put("tidy", "echo RAN-TIDY\n")
        f.put("keep/important.txt", "owner data")

        // Control: nothing changes, and the script runs.
        val control = tool.assess(hidden, f.context)
        assertTrue(control.details.none { "tidy" in it }, "the reader does not find it: ${control.preview}")
        assertNull(control.prepared!!.changedSinceOwnerAnswered())
        assertTrue("RAN-TIDY" in tool.execute(hidden, f.context, control).content)

        val assessment = tool.assess(hidden, f.context)
        // After the owner was asked, another agent rewrites the script with a file tool. That needs no approval of its own.
        val other = f.ok("workspace.write", "path" to "tidy", "content" to "rm -rf keep; echo EVIL\n", "overwrite" to true)
        assertTrue("Replaced" in other.content, other.content)
        val runsBefore = f.shell.runs.size

        // Right after the owner answered:
        assertNotNull(assessment.prepared!!.changedSinceOwnerAnswered())
        // and again at the moment of running:
        val outcome = tool.execute(hidden, f.context, assessment)

        assertFalse(outcome.ok)
        assertTrue("something in the workspace changed" in outcome.content && "ask anew" in outcome.content, outcome.content)
        assertEquals(runsBefore, f.shell.runs.size, "the command never reached the shell")
        assertTrue(f.file("keep/important.txt").exists())
    }

    @Test
    fun `every way of changing the workspace through the app stops a command that was asked for before it`() = runBlocking<Unit> {
        f.put("notes.md", "one two")
        f.put("old.txt", "x")
        f.put("bin/gone.txt", "x")
        f.media.items += MediaItem(MediaId("img:1"), MediaKind.IMAGE, "photo.jpg", "image/jpeg", 3)
        f.media.contents[MediaId("img:1")] = byteArrayOf(1, 2, 3)
        val changes: List<Pair<String, suspend () -> Unit>> = listOf(
            "a new file" to { f.ok("workspace.write", "path" to "new.txt", "content" to "x") },
            "an appended line" to { f.ok("workspace.append", "path" to "notes.md", "content" to "\nmore") },
            "an edit" to { f.ok("workspace.replace_text", "path" to "notes.md", "find" to "one", "replace" to "1") },
            "a new folder" to { f.ok("workspace.make_folder", "path" to "fresh") },
            "a move" to { f.ok("workspace.move", "from" to "old.txt", "to" to "moved.txt") },
            "a trashed file" to { f.ok("workspace.trash", "paths" to listOf("bin/gone.txt")) },
            "a script's own write" to { f.ok("scripts.run_js", "code" to "workspace.write('from-js.txt', 'x')", "workspace" to "write") },
            "a copy from the photo library" to { f.ok("media.copy_to_workspace", "id" to "img:1", "to" to "in/photo.jpg") },
            "the owner's import in the file manager" to { assertIs<DocResult.Ok<*>>(f.workspace.import(p("imported.txt"), ByteArrayInputStream("x".toByteArray()), 100, false)) },
            "the owner's own trash and restore" to {
                val item = (f.workspace.trash(p("moved.txt")) as DocResult.Ok).value
                assertIs<DocResult.Ok<*>>(f.workspace.restore(item.id))
            },
            "emptying the trash" to { assertIs<DocResult.Ok<*>>(f.workspace.emptyTrash(null)) },
        )
        for ((name, change) in changes) {
            val assessment = tool.assess(json("command" to "ls"), f.context)
            assertNull(assessment.prepared!!.changedSince(), name)
            change()
            assertNotNull(assessment.prepared!!.changedSince(), "$name was not noticed")
            assertFalse(tool.execute(json("command" to "ls"), f.context, assessment).ok, name)
        }
        assertTrue(f.shell.runs.isEmpty(), "no command ran")

        // Looking does not count: reads, listings and searches leave the counter alone.
        val assessment = tool.assess(json("command" to "ls"), f.context)
        f.ok("workspace.read", "path" to "notes.md")
        f.ok("workspace.list")
        f.ok("workspace.search", "query" to "two")
        f.workspace.listTrash()
        assertTrue(tool.execute(json("command" to "ls"), f.context, assessment).ok)
    }

    @Test
    fun `a screenshot saved into the workspace counts as a change too`() = runBlocking<Unit> {
        val shots = OperatorFixture(initialScreen = FakeScreen("com.example.notes", "Notes", emptyList()))
        shots.authorise("com.example.notes")
        val shell = shots.tool("scripts.run_shell")
        val assessment = shell.assess(json("command" to "ls"), shots.context)

        shots.ok("screen.screenshot", "to" to "shots/now.png")

        assertNotNull(assessment.prepared!!.changedSince())
        assertFalse(shell.execute(json("command" to "ls"), shots.context, assessment).ok)
    }

    @Test
    fun `while a command runs, every other change waits for it to end`() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.shell.respond = { _, _ ->
            started.countDown()
            release.await(20, TimeUnit.SECONDS)
            CommandOutcome.Finished(0, "done", "", false, 1)
        }
        f.put("tidy.sh", "echo tidy\n")
        val args = json("command" to "sh tidy.sh")
        val assessment = tool.assess(args, f.context)
        val command = async(Dispatchers.Default) { tool.execute(args, f.context, assessment) }
        assertTrue(started.await(10, TimeUnit.SECONDS))

        // Another agent's file tool, a script's write, and the owner's file manager, all while the command runs.
        val byTool = async(Dispatchers.Default) { f.run("workspace.write", "path" to "tidy.sh", "content" to "rm -rf keep\n", "overwrite" to true).second }
        val byScript = async(Dispatchers.Default) { f.run("scripts.run_js", "code" to "workspace.write('from-js.txt', 'x')", "workspace" to "write").second }
        val byOwner = async(Dispatchers.Default) { f.workspace.trash(p("tidy.sh")) }
        delay(500)

        assertFalse(byTool.isCompleted || byScript.isCompleted || byOwner.isCompleted, "a change went through while the command ran")
        assertEquals("echo tidy\n", f.file("tidy.sh").readText())
        assertFalse(f.file("from-js.txt").exists())
        // Looking is not held back.
        assertTrue(withTimeout(5_000) { f.run("workspace.read", "path" to "tidy.sh").second }.ok)

        release.countDown()
        assertTrue(command.await().ok)
        // They went through once it had ended; nothing deadlocked.
        withTimeout(10_000) {
            byTool.await()
            byScript.await()
            byOwner.await()
        }
        assertTrue(f.file("from-js.txt").exists())
    }

    @Test
    fun `only one shell command runs at a time, for all agents`() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.shell.respond = { command, _ ->
            if (command == "echo first") {
                started.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
            CommandOutcome.Finished(0, command, "", false, 1)
        }
        val first = json("command" to "echo first")
        val second = json("command" to "echo second")
        // Two agents were both asked before either command ran.
        val a1 = tool.assess(first, f.context)
        val a2 = tool.assess(second, f.context)
        val running = async(Dispatchers.Default) { tool.execute(first, f.context, a1) }
        assertTrue(started.await(10, TimeUnit.SECONDS))

        // The second is refused at once: the first one's start counts as a change.
        val refused = tool.execute(second, f.context, a2)
        assertFalse(refused.ok)
        assertTrue("another shell command ran" in refused.content, refused.content)

        // A third is asked for while the first runs: it could never run, so it is refused in the assessment and nobody is asked (R5 of round 4).
        assertTrue("Another shell command is running." in f.rejected("scripts.run_shell", "command" to "echo third"))
        // At the workspace itself, a command that comes while another runs waits for it, and then does not run:
        // the workspace it was counted on is the one the first command was changing.
        val counted = f.workspace.changeCount()
        val waiting = async(Dispatchers.Default) { f.workspace.runCommand(counted) { "ran" } }
        delay(400)
        assertFalse(waiting.isCompleted, "it waits while the first command runs")
        release.countDown()

        assertTrue(running.await().ok)
        assertEquals(CommandTurn.Changed, withTimeout(10_000) { waiting.await() })
        assertEquals(listOf("echo first"), f.shell.runs.map { it.command })

        // Asked again afterwards, it runs.
        assertTrue(f.ok("scripts.run_shell", "command" to "echo third").ok)
    }

    @Test
    fun `the lock is the workspace's - the counter is checked under it, raised at both ends, and the tool's own bookkeeping does not wait for it`() = runBlocking<Unit> {
        val ws = f.workspace
        val before = ws.changeCount()

        // Stale count: nothing runs.
        ws.write(p("a.txt"), "a".toByteArray(), false)
        var ran = false
        assertEquals(CommandTurn.Changed, ws.runCommand(before) { ran = true })
        assertFalse(ran)

        // Current count: it runs, and may itself call the workspace (what the tool does after a command) without waiting for the lock it holds.
        val now = ws.changeCount()
        val turn = withTimeout(10_000) {
            ws.runCommand(now) {
                assertEquals(now + 1, ws.changeCount(), "raised when the command starts")
                assertNull(ws.changedOutside())
                assertIs<DocResult.Ok<*>>(ws.write(p("from-the-command.txt"), "x".toByteArray(), false))
                assertIs<DocResult.Ok<*>>(ws.receive(p("received.bin"), 10) { target, _ -> target.file.writeBytes(byteArrayOf(1)); DocResult.Ok(1L) })
                "result"
            }
        }
        assertEquals(CommandTurn.Ran("result", overLimit = null), turn)
        assertTrue(ws.changeCount() > now + 1, "raised again when it has ended")

        // A cancelled command gives the lock back and still counts as a change.
        val count = ws.changeCount()
        val entered = CompletableDeferred<Unit>()
        val hanging = launch(Dispatchers.Default) {
            ws.runCommand(count) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        val blocked = async(Dispatchers.Default) { ws.write(p("later.txt"), "x".toByteArray(), false) }
        delay(300)
        assertFalse(blocked.isCompleted)
        hanging.cancelAndJoin()
        assertIs<DocResult.Ok<*>>(withTimeout(10_000) { blocked.await() })
        assertTrue(ws.changeCount() >= count + 2)

        // A command that fails gives the lock back as well.
        val failing = runCatching { ws.runCommand(ws.changeCount()) { error("the runner broke") } }
        assertTrue(failing.isFailure)
        assertIs<DocResult.Ok<*>>(withTimeout(10_000) { ws.write(p("after-failure.txt"), "x".toByteArray(), false) })
    }

    @Test
    fun `a workspace over its limit after a command is said in the answer`() = runBlocking<Unit> {
        val small = OperatorFixture(workspaceLimits = WorkspaceLimits(maxFileBytes = 1_000, maxTotalBytes = 2_000))
        small.shell.respond = { _, dir ->
            java.io.File(dir.file, "made-by-the-command.bin").writeBytes(ByteArray(3_000))
            CommandOutcome.Finished(0, "", "", false, 1)
        }
        val before = small.workspace.changeCount()

        val outcome = small.ok("scripts.run_shell", "command" to "dd if=/dev/zero of=made-by-the-command.bin bs=1000 count=3")

        assertTrue("The workspace is full" in outcome.content, outcome.content)
        assertEquals(before + 2, small.workspace.changeCount(), "one for the start, one for the end")
    }

    @Test
    fun `a change that fails half way still counts when it left something behind, and a refused one does not`() = runBlocking<Unit> {
        val small = OperatorFixture(workspaceLimits = WorkspaceLimits(maxFileBytes = 1_000, maxTotalBytes = 5_000)).workspace
        val before = small.changeCount()

        // Refused before anything was touched.
        assertIs<DocResult.Ok<*>>(small.write(p("a.txt"), "a".toByteArray(), false))
        assertEquals(before + 1, small.changeCount())
        assertIs<DocResult.AlreadyExists>(small.write(p("a.txt"), "b".toByteArray(), false))
        assertIs<DocResult.NotFound>(small.trash(p("nothing")))
        assertEquals(before + 1, small.changeCount(), "a refused change announces nothing")

        // Too large, found out only after the folders on the way were made: those stay, so it counts.
        assertIs<DocResult.TooLarge>(small.import(p("deep/down/big.bin"), ByteArrayInputStream(ByteArray(1_500)), 10_000, false))
        assertTrue(java.io.File(small.resolveForTest("deep/down"), "").isDirectory)
        assertTrue(small.changeCount() > before + 1)

        // A gateway that fails after it began to write.
        val count = small.changeCount()
        val failed = runCatching {
            small.receive<Long>(p("partial.bin"), 100) { target, _ ->
                target.file.writeBytes(byteArrayOf(1, 2))
                throw IllegalStateException("the copy broke")
            }
        }
        assertTrue(failed.isFailure)
        assertTrue(small.changeCount() > count)
    }

    private suspend fun LocalWorkspace.resolveForTest(path: String): java.io.File = (resolve(p(path)) as DocResult.Ok).value.file
}
