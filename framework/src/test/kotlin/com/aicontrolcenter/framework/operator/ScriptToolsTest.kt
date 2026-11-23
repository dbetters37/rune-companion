package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.TermuxAvailability
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.testkit.contract.PreparedActionChecks
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real threads: the JavaScript sandbox measures wall-clock time. */
class ScriptToolsTest {
    private val f = OperatorFixture()

    @Test
    fun `run js returns the result and console output, and previews the exact code`() = runBlocking {
        val (assessment, outcome) = f.run("scripts.run_js", "code" to "console.log('n', input.n)\ninput.n * 2", "input" to mapOf("n" to 21))
        assertRisk(RiskLevel.MODIFYING, assessment)
        assertPrepared(assessment)
        assertTrue("Code: \"console.log('n', input.n)⏎\ninput.n * 2\"" in assessment.preview, assessment.preview)
        assertTrue("Input (JSON): {\"n\":21}" in assessment.preview, assessment.preview)
        assertTrue("no access to files" in assessment.summary)
        assertTrue(outcome.ok)
        assertEquals("Result: 42\n--- console ---\nn 21", outcome.content)
    }

    @Test
    fun `run js reaches the workspace only as asked, and never replaces a file`() = runBlocking {
        f.put("data/in.json", """{"items":[3,4]}""")
        val none = f.fails("scripts.run_js", "code" to "workspace.read('data/in.json')")
        assertTrue("workspace" in none.content && "not defined" in none.content, none.content)
        val read = f.ok("scripts.run_js", "code" to "JSON.parse(workspace.read('data/in.json')).items.length", "workspace" to "read")
        assertEquals("Result: 2", read.content)
        val readOnly = f.fails("scripts.run_js", "code" to "workspace.write('out.txt', 'x')", "workspace" to "read")
        assertTrue("only read" in readOnly.content, readOnly.content)
        val write = f.ok("scripts.run_js", "code" to "workspace.write('out/sum.txt', String(7))", "workspace" to "write")
        assertEquals("Result: \"out/sum.txt\"", write.content)
        assertEquals("7", f.file("out/sum.txt").readText())
        val again = f.fails("scripts.run_js", "code" to "workspace.write('out/sum.txt', 'replaced')", "workspace" to "write")
        assertTrue("never replace" in again.content, again.content)
        assertEquals("7", f.file("out/sum.txt").readText())
        val escape = f.fails("scripts.run_js", "code" to "workspace.read('../secret')", "workspace" to "read")
        assertTrue("\"..\"" in escape.content, escape.content)
        val trash = f.fails("scripts.run_js", "code" to "workspace.list('.trash')", "workspace" to "read")
        assertTrue("trash" in trash.content, trash.content)
    }

    @Test
    fun `run js reports stopped and failed scripts as failures`() = runBlocking {
        val loop = f.fails("scripts.run_js", "code" to "while (true) {}", "timeout_ms" to 300)
        assertTrue("stopped" in loop.content, loop.content)
        val error = f.fails("scripts.run_js", "code" to "\nnull.x")
        assertTrue("at line 2" in error.content, error.content)
        assertTrue("\"workspace\"" in f.rejected("scripts.run_js", "code" to "1", "workspace" to "everything"))
    }

    @Test
    fun `a shell command is consequential, shown exactly, and runs in the approved folder`() = runBlocking {
        f.put("proj/readme.md", "x")
        f.shell.respond = { cmd, dir -> CommandOutcome.Finished(0, "ran $cmd in ${dir.path}", "", false, 5) }
        val (assessment, outcome) = f.run("scripts.run_shell", "command" to "ls -la\nrm -rf  ~", "directory" to "proj")
        assertRisk(RiskLevel.CONSEQUENTIAL, assessment)
        assertTrue("\"ls -la⏎\nrm -rf  ~\"" in assessment.summary, assessment.summary)
        assertTrue(assessment.details.any { "this app's own data" in it })
        assertTrue(outcome.ok, outcome.content)
        assertEquals("ls -la\nrm -rf  ~", f.shell.runs.single().command)
        assertEquals("proj", f.shell.runs.single().workingDirectory!!.path.value)

        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("scripts.run_shell"), json("command" to "pwd", "directory" to "proj"), f.context,
            change = { f.file("proj").deleteRecursively() }, nothingDone = { f.shell.runs.size == 1 },
        )
        assertTrue("no workspace folder" in f.fails("scripts.run_shell", "command" to "pwd", "directory" to "proj").content)
        assertTrue("55000" in f.rejected("scripts.run_shell", "command" to "sleep 100", "timeout_ms" to 120_000))
    }

    @Test
    fun `command results carry the exit code, output and errors`() = runBlocking {
        f.shell.respond = { _, _ -> CommandOutcome.Finished(2, "partial", "boom", true, 9) }
        val failed = f.fails("scripts.run_shell", "command" to "make")
        assertEquals("Exit code 2 after 9 ms. Output was cut at the limit.\n--- output ---\npartial\n--- errors ---\nboom", failed.content)
        f.shell.respond = { _, _ -> CommandOutcome.TimedOut("so far", "", 30_000) }
        assertTrue("time limit" in f.fails("scripts.run_shell", "command" to "sleep 99").content)
    }

    @Test
    fun `termux runs only when available, and is consequential`() = runBlocking {
        f.termux.availability = TermuxAvailability.NOT_INSTALLED
        assertTrue("not installed" in f.fails("termux.run", "command" to "ls").content)
        assertTrue(f.termux.runs.isEmpty())
        f.termux.availability = TermuxAvailability.READY
        val (assessment, outcome) = f.run("termux.run", "command" to "python3 --version")
        assertRisk(RiskLevel.CONSEQUENTIAL, assessment)
        assertEquals("the Termux app, and any address its command connects to (it can use the internet)", assessment.dataGoesTo)
        assertTrue(assessment.details.any { "keeps running the command" in it }, assessment.preview)
        assertTrue(outcome.ok)
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("termux.run"), json("command" to "ls"), f.context,
            change = { f.termux.availability = TermuxAvailability.PERMISSION_MISSING }, nothingDone = { f.termux.runs.size == 1 },
        )
        assertFalse(f.termux.runs.any { it.command == "ls" })
    }
}
