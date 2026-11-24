package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.TrashItem
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 3, where the three sets of fixes meet. The shell set's change counter and change lock
 * (G1) against the minor set's tidy of the trash after a start (W1), its counted trash records
 * (W2) and its export (W3); the lock against the JavaScript host functions of the sandbox the
 * mixed set changed; and the mixed set's marks for quote look-alikes (P1) in the lines and
 * refusals the shell set added to a shell approval. Each case goes through the real tools over
 * the real workspace; none was covered by a set's own tests.
 */
class Round3MeetingPointsTest {
    private fun p(s: String) = RelativePath.of(s)

    private suspend fun OperatorFixture.trash(): List<TrashItem> = (workspace.listTrash() as DocResult.Ok).value

    /** A shell command that has started and runs until [release] is counted down. */
    private class Running(val command: Deferred<com.aicontrolcenter.core.tools.ToolOutcome>, val release: CountDownLatch)

    private suspend fun kotlinx.coroutines.CoroutineScope.startCommand(f: OperatorFixture, text: String = "sleep 20"): Running {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.shell.respond = { _, _ ->
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
            CommandOutcome.Finished(0, "done", "", false, 1)
        }
        val tool = f.tool("scripts.run_shell")
        val args = json("command" to text)
        val assessment = tool.assess(args, f.context)
        val command = async(Dispatchers.Default) { tool.execute(args, f.context, assessment) }
        assertTrue(started.await(10, TimeUnit.SECONDS), "the command started")
        return Running(command, release)
    }

    /** What a kill can leave under the trash folder: a staged file, a record without content, content without a record. */
    private fun leaveLeftovers(f: OperatorFixture) {
        f.put(".trash/.staging/half-written", "x")
        f.put(".trash/$EMPTY_ID.json", """{"id":"$EMPTY_ID","originalPath":"gone.txt","isFolder":false,"sizeBytes":1,"trashedAt":1700000000001}""")
        f.put(".trash/$LOST_ID/report.txt", "the owner's report")
    }

    // ---- G1 and W1: the tidy after a start, the change counter and a command that waits for its answer ----

    @Test
    fun `the tidy after a start counts as a change - a command asked for before it does not run, one asked for after it does`() = runBlocking<Unit> {
        val f = OperatorFixture()
        leaveLeftovers(f)
        f.put("notes.md", "notes")
        val tool = f.tool("scripts.run_shell")
        val args = json("command" to "ls")

        // Asked first thing after the start, and nothing changed through the app: it runs. A command does not start the tidy.
        val first = tool.assess(args, f.context)
        assertNull(first.prepared!!.changedSince())
        assertTrue(tool.execute(args, f.context, first).ok)
        assertTrue(f.file(".trash/.staging/half-written").exists(), "a command tidies nothing")
        assertTrue(f.trash().isEmpty())
        assertNotNull(f.workspace.trashLeftovers(), "looking does not tidy either")

        // Asked for, and then the first change after the start is made. The change itself is refused (there is no such
        // file), but the tidy before it put the trash right, and that changed files in the workspace: announced once.
        val second = tool.assess(args, f.context)
        val count = f.workspace.changeCount()
        assertIs<DocResult.NotFound>(f.workspace.trash(p("no-such-file")))
        assertEquals(count + 1, f.workspace.changeCount(), "the tidy is a change, although the change it ran before was refused")
        assertFalse(f.file(".trash/.staging/half-written").exists())
        assertFalse(f.file(".trash/$EMPTY_ID.json").exists(), "a record without content is removed")
        val recovered = f.trash().single()
        assertEquals(LOST_ID to true, recovered.id to recovered.recovered)
        assertEquals("the owner's report", f.file(".trash/$LOST_ID/report.txt").readText(), "content is never deleted by the tidy")
        assertNotNull(second.prepared!!.changedSince(), "the command was asked for on the workspace as it was before the tidy")
        val refused = tool.execute(args, f.context, second)
        assertFalse(refused.ok)
        assertTrue("something in the workspace changed" in refused.content, refused.content)
        assertEquals(1, f.shell.runs.size, "the second command never reached the shell")

        // Asked again after the tidy, it runs; and a refused change counts for nothing now that nothing is left to tidy.
        val third = tool.assess(args, f.context)
        assertIs<DocResult.NotFound>(f.workspace.trash(p("no-such-file")))
        assertNull(third.prepared!!.changedSince())
        assertTrue(tool.execute(args, f.context, third).ok)
        assertEquals(2, f.shell.runs.size)
    }

    @Test
    fun `a first change that succeeds is announced once together with the tidy it began with`() = runBlocking<Unit> {
        val f = OperatorFixture()
        leaveLeftovers(f)
        val count = f.workspace.changeCount()
        f.ok("workspace.write", "path" to "notes.md", "content" to "x")
        assertEquals(count + 1, f.workspace.changeCount())
        assertEquals(listOf(LOST_ID), f.trash().map { it.id })
    }

    @Test
    fun `the tidy does not run while a shell command holds the change lock - the first change after a start waits with it`() = runBlocking<Unit> {
        val f = OperatorFixture()
        leaveLeftovers(f)
        val running = startCommand(f, "ls -la .trash")

        // The first change after the start, made while the command runs.
        val change = async(Dispatchers.Default) { f.workspace.makeFolder(p("after-restart")) }
        delay(400)
        assertFalse(change.isCompleted, "the change waits for the command")
        // So nothing under the trash folder was touched under the command's feet: it sees the workspace it was approved on.
        assertTrue(f.file(".trash/.staging/half-written").exists())
        assertTrue(f.file(".trash/$EMPTY_ID.json").exists())
        assertFalse(f.file(".trash/$LOST_ID.json").exists())
        // Looking is not held back, and says what is true meanwhile: nothing listed, leftovers there.
        assertTrue(withTimeout(5_000) { f.trash() }.isEmpty())
        assertNotNull(withTimeout(5_000) { f.workspace.trashLeftovers() })

        running.release.countDown()
        assertTrue(running.command.await().ok)
        assertIs<DocResult.Ok<*>>(withTimeout(10_000) { change.await() })
        assertFalse(f.file(".trash/.staging/half-written").exists())
        assertEquals(listOf(LOST_ID), f.trash().map { it.id })
        assertNull(f.workspace.trashLeftovers())
    }

    // ---- G1 and W3: an export while a command runs, and the edit that waited ----

    @Test
    fun `an edit that waited behind a shell command keeps the version an export handed over meanwhile`() = runBlocking<Unit> {
        val f = OperatorFixture()
        val task = ToolContext(Samples.agent(id = "agt_operator", name = "Operator"), TaskId("tsk_a"), TaskId("tsk_root"))
        val write = f.tool("workspace.write")
        val replace = f.tool("workspace.replace_text")
        val export = f.tool("workspace.export")
        val draft = json("path" to "letter.md", "content" to "first letter")
        assertTrue(write.execute(draft, task, write.assess(draft, task)).ok)
        val once = json("path" to "letter.md", "find" to "first", "replace" to "second")
        assertTrue(replace.execute(once, task, replace.assess(once, task)).ok)
        assertTrue(f.trash().isEmpty(), "the task's own version of a moment ago is not kept again")

        val running = startCommand(f)

        // The task's next edit: when it is checked, the version is still the task's own, and the approval says so.
        val again = json("path" to "letter.md", "find" to "second", "replace" to "third")
        val assessment = replace.assess(again, task)
        assertFalse(WorkspaceReplaceText.KEPT_IN_TRASH in assessment.details, assessment.preview)
        val editing = async(Dispatchers.Default) { replace.execute(again, task, assessment) }
        delay(400)
        assertFalse(editing.isCompleted, "the edit waits for the command")

        // Meanwhile the file is shared. Sharing changes no workspace file, so it does not wait for the command.
        val share = json("path" to "letter.md")
        val shared = withTimeout(5_000) { export.execute(share, f.context, export.assess(share, f.context)) }
        assertTrue(shared.ok, shared.content)
        assertEquals("second letter", f.file("letter.md").readText())

        running.release.countDown()
        assertTrue(running.command.await().ok)
        val outcome = withTimeout(10_000) { editing.await() }
        assertTrue(outcome.ok, outcome.content)
        assertEquals("third letter", f.file("letter.md").readText())
        // What the other app received can be restored, and the answer says where it is.
        val kept = f.trash().map { f.file(".trash/${it.id}/letter.md").readText() }
        assertEquals(listOf("second letter"), kept)
        assertTrue("is in the workspace trash" in outcome.content, outcome.content)
    }

    // ---- G1 and the JavaScript host functions ----

    @Test
    fun `a script whose write waits behind a shell command past its own time limit is stopped, writes nothing later and leaves no thread`() = runBlocking<Unit> {
        val f = OperatorFixture()
        // The first script of a JVM loads the interpreter, which can take longer than a short time limit; that is not measured here.
        f.ok("scripts.run_js", "code" to "1 + 1")
        val running = startCommand(f)
        val code = "workspace.write('from-js.txt', 'x'); 'written'"
        val writes = arrayOf<Pair<String, Any?>>("code" to code, "workspace" to "write", "timeout_ms" to 1_000)

        // Twice: two scripts left waiting would be as many as the sandbox lets hang before it refuses new ones.
        repeat(2) {
            val (_, outcome) = withTimeout(15_000) { f.run("scripts.run_js", *writes) }
            assertFalse(outcome.ok, outcome.content)
            assertTrue("waited for the workspace longer than its time limit" in outcome.content, outcome.content)
        }
        assertFalse(f.file("from-js.txt").exists())
        // Scripts still run while the command does: none is left hanging. Reading the workspace is not held back.
        assertEquals("Result: 2", withTimeout(10_000) { f.ok("scripts.run_js", "code" to "1 + 1") }.content)
        assertEquals("Result: 0", withTimeout(10_000) { f.ok("scripts.run_js", "code" to "workspace.list('').length", "workspace" to "read") }.content)

        running.release.countDown()
        assertTrue(running.command.await().ok)
        // The scripts that were stopped do not write now that the lock is free: their calls ended with them.
        delay(600)
        assertFalse(f.file("from-js.txt").exists(), "a stopped script wrote after its call had ended")
        // A script asked for now writes as usual.
        assertTrue(f.ok("scripts.run_js", "code" to code, "workspace" to "write", "timeout_ms" to 10_000).content.contains("written"))
        assertEquals("x", f.file("from-js.txt").readText())
    }

    @Test
    fun `a script whose write waits behind a shell command goes on when the command has ended within the script's time`() = runBlocking<Unit> {
        val f = OperatorFixture()
        val running = startCommand(f)
        val script = async(Dispatchers.Default) {
            f.run("scripts.run_js", "code" to "workspace.write('from-js.txt', 'x'); 'written'", "workspace" to "write", "timeout_ms" to 10_000).second
        }
        delay(500)
        assertFalse(script.isCompleted)
        assertFalse(f.file("from-js.txt").exists())
        running.release.countDown()
        assertTrue(running.command.await().ok)
        val outcome = withTimeout(10_000) { script.await() }
        assertTrue(outcome.ok && "written" in outcome.content, outcome.content)
        assertEquals("x", f.file("from-js.txt").readText())
    }

    // ---- G1 and W2: a command that filled the workspace, and the way out through the trash ----

    @Test
    fun `after a shell command filled the workspace the answer says so, one file can still go to the trash, and emptying it makes room`() = runBlocking<Unit> {
        val f = OperatorFixture(workspaceLimits = WorkspaceLimits(maxFileBytes = 50_000, maxTotalBytes = 100_000))
        f.ok("workspace.write", "path" to "report.txt", "content" to "r".repeat(1_000))
        f.shell.respond = { _, dir ->
            File(dir.file, "big.bin").writeBytes(ByteArray(120_000))
            CommandOutcome.Finished(0, "", "", false, 1)
        }
        val filled = f.ok("scripts.run_shell", "command" to "dd if=/dev/zero of=big.bin bs=1000 count=120")
        assertTrue("The workspace is full" in filled.content, filled.content)

        // Nothing more can be written, and the answer says why.
        val write = f.run("workspace.write", "path" to "new.txt", "content" to "x").second
        assertFalse(write.ok)
        assertTrue("The workspace is full" in write.content, write.content)
        // The way out: with the trash empty, one item can always go there, although its record no longer fits either.
        f.ok("workspace.trash", "paths" to listOf("big.bin"))
        assertEquals(listOf("big.bin"), f.trash().map { it.originalPath })
        // A second one is refused, and told what helps.
        val second = f.run("workspace.trash", "paths" to listOf("report.txt")).second
        assertFalse(second.ok)
        assertTrue("Empty the trash first" in second.content, second.content)
        assertTrue(f.file("report.txt").exists())

        // A command asked for now, and then the owner empties the trash: that is a change, so the command does not run.
        val tool = f.tool("scripts.run_shell")
        val args = json("command" to "ls")
        val assessment = tool.assess(args, f.context)
        assertIs<DocResult.Ok<*>>(f.workspace.emptyTrash(null))
        assertFalse(tool.execute(args, f.context, assessment).ok)
        // There is room again, and no leftover under the trash folder.
        assertNull(f.workspace.trashLeftovers())
        f.ok("workspace.write", "path" to "new.txt", "content" to "x")
        assertTrue(f.workspace.usedBytes() <= f.workspace.limits.maxTotalBytes)
    }

    // ---- G4's fixed lines and refusals, and P1's marks ----

    @Test
    fun `quote look-alikes in a shell command are shown by their code in every line of the approval, and the fixed lines stay as they are`() = runBlocking<Unit> {
        val f = OperatorFixture()
        // A script whose text holds look-alikes too: the approval shows its text between quotes.
        f.put("tidy.sh", "echo ʺdoneʺ ’’\n")
        // U+02BA (modifier double prime), two typographic apostrophes, two acute accents, a full-width quote, a diaeresis.
        val fakes = listOf(0x02BA, 0x2019, 0x00B4, 0xFF02, 0x00A8)
        val command = "sh tidy.sh; echo ʺ; rm -rf keep; echo ʺ ’’ ´´ ＂ ¨"
        val assessment = f.assess("scripts.run_shell", "command" to command)

        val shown = listOf(assessment.summary) + assessment.details
        for (line in shown) {
            for (fake in fakes) assertFalse(String(Character.toChars(fake)) in line, "U+%04X is drawn in: %s".format(fake, line))
        }
        val commandLine = assessment.details.single { it.startsWith("Command: ") }
        for (fake in fakes) assertTrue("⟨U+%04X⟩".format(fake) in commandLine, commandLine)
        assertEquals(2, commandLine.count { it == '"' }, "the only plain quotes are the two around the command: $commandLine")
        // Not a plain "sh file" command, so the script is shown as a reading (R1 of round 4).
        val scriptLine = assessment.details.single { it.startsWith("Seems to run ") }
        assertTrue("⟨U+02BA⟩done⟨U+02BA⟩ ⟨U+2019⟩⟨U+2019⟩" in scriptLine, scriptLine)

        // The lines that hold whatever the reader found are the tool's own words, not the command's.
        assertTrue("A shell command can run any file in the workspace. Only the scripts found in the command are shown here." in assessment.details, assessment.preview)
        assertTrue(assessment.details.any { it.startsWith("Time limit: ") && "Everything the command starts is stopped when it ends" in it }, assessment.preview)
        assertTrue("If anything in the workspace changes before it runs, it does not run." in assessment.details, assessment.preview)
        // A command that writes those words itself gets them inside its own quotes, with the fake quote shown by its code.
        val faking = "lsʺ in the top level of the workspace. A shell command can run any file in the workspace. ʺ; rm -rf keep"
        val faked = f.assess("scripts.run_shell", "command" to faking)
        assertEquals(2, faked.details.single { it.startsWith("Command: ") }.count { it == '"' })
        assertEquals(1, faked.details.count { it.startsWith("A shell command can run any file in the workspace.") })
    }

    @Test
    fun `the refusals of the shell reader show look-alikes in the names they quote by their code`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.put("sub/keep.txt", "x")
        val cases = listOf(
            // A script that does not exist.
            "sh goneʺ.sh",
            // A script named by a relative path after cd.
            "cd sub && sh tidyʺ.sh",
            // A pattern that matches nothing.
            "sh tʺ*.sh",
        )
        for (command in cases) {
            val reason = f.rejected("scripts.run_shell", "command" to command)
            assertFalse("ʺ" in reason, "$command: $reason")
            assertTrue("⟨U+02BA⟩" in reason, "$command: $reason")
        }
        assertTrue(f.shell.runs.isEmpty())
    }

    // ---- The length a provider accepts for a tool description (found by the shell set) and the descriptions the other sets made longer ----

    @Test
    fun `every operator tool's description fits what providers accept`() {
        val f = OperatorFixture()
        val long = f.tools.values.map { it.spec.id.value to it.spec.description.length }.filter { it.second > 1024 }
        assertTrue(long.isEmpty(), "some providers refuse a tool description longer than 1024 characters: $long")
        // The ones round 3 made longer, by name, so that a rename does not empty this check.
        for (id in listOf("scripts.run_shell", "scripts.run_js", "workspace.replace_text", "workspace.empty_trash", "workspace.restore")) {
            assertTrue(f.tool(id).spec.description.length in 1..1024, id)
        }
    }

    private companion object {
        const val EMPTY_ID = "1700000000001-bbbbbbbb"
        const val LOST_ID = "1700000000000-abc12345"
    }
}
