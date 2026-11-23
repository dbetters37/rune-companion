package com.aicontrolcenter.device.operator.commands

import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.WorkspaceFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs real processes. The host's `/bin/sh` stands in for Android's `/system/bin/sh`; with the
 * stripped PATH (/system/bin) only shell builtins work here, which is all these tests use.
 */
class SystemShellRunnerTest {
    private val root: File = Files.createTempDirectory("aicc-shell-ws").toFile()
    private val tmp: File = Files.createTempDirectory("aicc-shell-tmp").toFile()
    private val runner = SystemShellRunner(tempDir = { tmp }, shell = "/bin/sh")
    private val limits = CommandLimits(timeoutMs = 10_000, maxOutputChars = 1_000)

    private fun dir(path: String = ""): WorkspaceFile {
        val file = (WorkspaceFile.resolve(root, RelativePath.of(path)) as DocResult.Ok).value
        file.file.mkdirs()
        return file
    }

    private fun finished(outcome: CommandOutcome): CommandOutcome.Finished = assertIs<CommandOutcome.Finished>(outcome, "got $outcome")

    @Test
    fun `runs the command as one shell argument in the workspace folder`() = runBlocking {
        val project = dir("project")

        val out = finished(runner.run("""pwd; printf '%s|' "a b" 'c' '$'HOME""", project, limits))

        assertEquals("${project.file.canonicalPath}\na b|c|\$HOME|", out.stdout)
        assertEquals(0, out.exitCode)
        assertFalse(out.truncated)
    }

    @Test
    fun `the environment is replaced, not inherited`() = runBlocking {
        val project = dir("p")

        val out = finished(runner.run("echo \"\$HOME|\$PATH|\$TMPDIR|\${USER:-unset}|\${JAVA_HOME:-unset}|\${JAVA_TOOL_OPTIONS:-unset}\"", project, limits))

        assertEquals("${project.file.path}|/system/bin|${tmp.path}|unset|unset|unset\n", out.stdout)
        assertEquals(mapOf("PATH" to "/system/bin", "HOME" to project.file.path, "TMPDIR" to tmp.path), SystemShellRunner.environmentFor(project.file, tmp))
    }

    @Test
    fun `exit code, stdout and stderr are reported separately`() = runBlocking {
        val out = finished(runner.run("echo out; echo err 1>&2; exit 3", dir(), limits))

        assertEquals(CommandOutcome.Finished(3, "out\n", "err\n", truncated = false, durationMs = out.durationMs), out)
    }

    @Test
    fun `standard input is closed`() = runBlocking {
        assertEquals("got::1\n", finished(runner.run("read line; echo \"got:\$line:\$?\"", dir(), limits)).stdout)
    }

    @Test
    fun `output is decoded as UTF-8`() = runBlocking {
        assertEquals("héllo ✓", finished(runner.run("printf 'h\\303\\251llo \\342\\234\\223'", dir(), limits)).stdout)
    }

    @Test
    fun `each stream is cut at the limit while the command runs to the end`() = runBlocking {
        val loud = "i=0; while [ \$i -lt 2000 ]; do echo 0123456789; echo e 1>&2; i=\$((i+1)); done; echo done"

        val out = finished(runner.run(loud, dir(), CommandLimits(timeoutMs = 10_000, maxOutputChars = 100)))

        assertEquals(0, out.exitCode)
        assertEquals(100, out.stdout.length)
        assertEquals(100, out.stderr.length)
        assertTrue(out.stdout.startsWith("0123456789\n"))
        assertTrue(out.truncated)
    }

    @Test
    fun `a command that runs past the limit is killed and its output so far returned`() = runBlocking {
        val started = System.nanoTime()

        val out = runner.run("echo started; while :; do :; done", dir(), CommandLimits(timeoutMs = 300, maxOutputChars = 1_000))

        val timedOut = assertIs<CommandOutcome.TimedOut>(out)
        assertEquals("started\n", timedOut.stdout)
        assertTrue(timedOut.durationMs >= 300)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "the call returns soon after the limit")
    }

    @Test
    fun `cancelling the caller kills the shell`() = runBlocking {
        val project = dir("cancel")
        val pidFile = File(project.file, "pid")
        val call = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            runner.run("echo \$\$ > pid; while :; do :; done", project, limits)
        }
        while (!pidFile.isFile || pidFile.readText().isBlank()) delay(20)
        val pid = pidFile.readText().trim().toLong()
        assertTrue(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))

        call.cancel()

        var alive = true
        repeat(100) {
            alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            if (!alive) return@repeat
            delay(20)
        }
        assertFalse(alive, "the shell process must be gone after cancellation")
    }

    @Test
    fun `bad input is refused without starting anything`() = runBlocking {
        assertIs<CommandOutcome.Failed>(runner.run("   ", dir(), limits))
        assertIs<CommandOutcome.Failed>(runner.run("echo a\u0000b", dir(), limits))
        assertIs<CommandOutcome.Failed>(runner.run("x".repeat(SystemShellRunner.MAX_COMMAND_CHARS + 1), dir(), limits))
        val missing = (WorkspaceFile.resolve(root, RelativePath.of("not/there")) as DocResult.Ok).value
        assertTrue("not/there" in assertIs<CommandOutcome.Failed>(runner.run("echo hi", missing, limits)).reason)
        val noShell = SystemShellRunner(tempDir = { tmp }, shell = "/nonexistent/sh")
        assertTrue("could not be started" in assertIs<CommandOutcome.Failed>(noShell.run("echo hi", dir(), limits)).reason)
    }
}
