package com.aicontrolcenter.device.operator.commands

import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.WorkspaceFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
    private val home: File = File(Files.createTempDirectory("aicc-shell-home").toFile(), "home")

    /** The process table of these tests: a temporary folder, never the machine's own (see [FakeProc]). */
    private val proc = FakeProc()
    private val runner = runner()

    private fun runner(sweeper: LeftoverSweeper = proc.sweeper(), shell: String = "/bin/sh", setsid: String = HOST_SETSID) =
        SystemShellRunner(tempDir = { tmp }, homeDir = { home }, sweeper = sweeper, shell = shell, setsid = setsid)

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

        assertEquals("${home.path}|/system/bin|${tmp.path}|unset|unset|unset\n", out.stdout)
        assertEquals(mapOf("PATH" to "/system/bin", "HOME" to home.path, "TMPDIR" to tmp.path), SystemShellRunner.environmentFor(home, tmp))
        assertEquals("unset\n", finished(runner.run("echo \"\${ENV:-unset}\"", project, limits)).stdout, "no start-up file is named")
    }

    // --- G3: no start-up files from the workspace ---

    @Test
    fun `HOME is not the working folder, so a profile written into the workspace is not run`() = runBlocking {
        val project = dir("profile")
        // What an agent can do without approval: write a file into the workspace.
        File(project.file, ".profile").writeText("echo PROFILE-RAN; echo ran > '${project.file.path}/profile-ran.txt'\n")
        File(root, ".profile").writeText("echo ROOT-PROFILE-RAN\n")

        val out = finished(runner.run("/bin/sh -l -c 'echo hi'; echo \"home=\$HOME\"", project, limits))

        assertFalse("PROFILE-RAN" in out.stdout, out.stdout)
        assertTrue("hi\n" in out.stdout, out.stdout)
        assertTrue("home=${home.path}\n" in out.stdout, out.stdout)
        assertFalse(File(project.file, "profile-ran.txt").exists())
        assertFalse(home.path.startsWith(root.path), "HOME is outside the workspace")
    }

    @Test
    fun `HOME is made empty before every command`() = runBlocking {
        val project = dir("home")
        // A first command (the owner approved it) leaves a profile behind; the next command's login shell must not run it.
        finished(runner.run("echo 'echo LEFT-BEHIND' > \"\$HOME/.profile\"; echo x > \"\$HOME/note\"", project, limits))
        assertTrue(File(home, ".profile").exists())

        val out = finished(runner.run("/bin/sh -l -c 'echo second'; for f in \"\$HOME\"/.* \"\$HOME\"/*; do echo \"in-home:\$f\"; done", project, limits))

        assertFalse("LEFT-BEHIND" in out.stdout, out.stdout)
        assertTrue("second\n" in out.stdout, out.stdout)
        assertFalse("note" in out.stdout, out.stdout)
        assertFalse(File(home, ".profile").exists())
    }

    @Test
    fun `emptying HOME removes links without following them, also when HOME itself became a link`() = runBlocking {
        val project = dir("links")
        val precious = File(root, "keep").apply { mkdirs() }
        File(precious, "important.txt").writeText("owner data")
        // An earlier command left links in HOME that point into the workspace.
        home.mkdirs()
        Files.createSymbolicLink(File(home, "to-folder").toPath(), precious.toPath())
        Files.createSymbolicLink(File(home, "to-file").toPath(), File(precious, "important.txt").toPath())
        File(home, "sub/deeper").mkdirs()
        Files.createSymbolicLink(File(home, "sub/deeper/again").toPath(), precious.toPath())

        finished(runner.run("echo one", project, limits))

        assertEquals(emptyList(), home.list()!!.toList())
        assertEquals("owner data", File(precious, "important.txt").readText())

        // HOME itself replaced by a link to the folder: the link goes, the folder stays as it is.
        home.delete()
        Files.createSymbolicLink(home.toPath(), precious.toPath())

        val out = finished(runner.run("echo \"\$HOME\"; echo planted > \"\$HOME/x\"", project, limits))

        assertEquals("${home.path}\n", out.stdout)
        assertFalse(Files.isSymbolicLink(home.toPath()))
        assertEquals(listOf("important.txt"), precious.list()!!.toList(), "nothing was removed from, or written into, what the link pointed to")
        assertEquals("owner data", File(precious, "important.txt").readText())
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
        val noShell = runner(shell = "/nonexistent/sh")
        assertTrue("could not be started" in assertIs<CommandOutcome.Failed>(noShell.run("echo hi", dir(), limits)).reason)
    }

    @Test
    fun `work a command leaves running in the background is stopped when it ends`() = runBlocking {
        val project = dir("bg")
        // The "&" is built at run time, as an obfuscated command would; the tool's text check cannot see it.
        val command = "eval \"(/bin/sleep 2; echo alive > late.txt) \$(printf '\\046')\"; echo started"
        val started = System.nanoTime()

        val out = finished(runner.run(command, project, limits))

        assertEquals("started\n", out.stdout)
        assertTrue(out.stoppedLeftovers, "the call says it stopped what was left running")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_900, "the call does not wait for the background job")
        Thread.sleep(3_000)
        assertFalse(File(project.file, "late.txt").exists(), "the background job did not outlive the call")
    }

    @Test
    fun `background work is stopped at the time limit too, even if it ignores hangups`() = runBlocking {
        val project = dir("bg-limit")
        val command = "(trap '' HUP TERM INT; /bin/sleep 2; echo alive > late.txt) & while :; do :; done"

        assertIs<CommandOutcome.TimedOut>(runner.run(command, project, CommandLimits(timeoutMs = 500, maxOutputChars = 1_000)))

        Thread.sleep(3_000)
        assertFalse(File(project.file, "late.txt").exists())
    }

    @Test
    fun `a finished command that left nothing running says so`() = runBlocking {
        assertFalse(finished(runner.run("echo done", dir(), limits)).stoppedLeftovers)
    }

    @Test
    fun `without setsid nothing runs, since background work could not be stopped`() = runBlocking {
        val project = dir("no-setsid")
        val noSetsid = runner(setsid = "/nonexistent/setsid")

        val out = assertIs<CommandOutcome.Unavailable>(noSetsid.run("echo hi > ran.txt", project, limits))

        assertTrue("setsid" in out.reason)
        assertFalse(File(project.file, "ran.txt").exists())
    }

    // --- G2: what left the process group is stopped too ---
    //
    // A process that really left the group cannot be chased on the build machine, where the real process table must
    // never be swept. So the command of these tests "starts" its runaway by adding an entry to the fake table, as a
    // process that got away would appear in the real one; the instrumented test (ShellLeftoversDeviceTest) does it for real.

    @Test
    fun `what left the process group is found and stopped when the command ends`() = runBlocking {
        val out = finished(runner.run("${proc.commandAdding(4100)}; ${proc.commandAdding(4101, parent = 4100)}; echo started", dir("away"), limits))

        assertEquals("started\n", out.stdout)
        assertEquals(setOf(4100, 4101), proc.killed.toSet())
        assertEquals(setOf(1, FakeProc.ZYGOTE, FakeProc.APP), proc.pids())
        assertTrue(out.stoppedLeftovers, "the call says it stopped what was left running")
        assertFalse(out.leftRunning)
    }

    @Test
    fun `what left the process group is stopped at the time limit too`() = runBlocking {
        val out = runner.run("${proc.commandAdding(4100)}; while :; do :; done", dir("away-limit"), CommandLimits(timeoutMs = 400, maxOutputChars = 1_000))

        assertFalse(assertIs<CommandOutcome.TimedOut>(out).leftRunning)
        assertEquals(listOf(4100), proc.killed.toList())
    }

    @Test
    fun `what left the process group is stopped when the caller is cancelled`() = runBlocking {
        val project = dir("away-cancel")
        val marker = File(project.file, "started")
        val call = async(Dispatchers.Default) { runner.run("${proc.commandAdding(4100)}; echo x > started; while :; do :; done", project, limits) }
        while (!marker.isFile) delay(20)
        assertTrue(proc.killed.isEmpty())

        call.cancelAndJoin()

        assertEquals(listOf(4100), proc.killed.toList(), "by the time the cancelled call has ended, the sweep has run")
        assertEquals(setOf(1, FakeProc.ZYGOTE, FakeProc.APP), proc.pids())
    }

    @Test
    fun `a command that could not be stopped completely says so, and no further command runs`() = runBlocking {
        val project = dir("stubborn")
        proc.onKill = { } // it does not die
        val stubborn = runner(sweeper = proc.sweeper(maxRounds = 3))

        val out = finished(stubborn.run("${proc.commandAdding(4100)}; echo started", project, limits))
        assertTrue(out.leftRunning)

        val next = stubborn.run("echo again > again.txt", project, limits)
        assertTrue("still running" in assertIs<CommandOutcome.Failed>(next).reason)
        assertFalse(File(project.file, "again.txt").exists())
    }

    @Test
    fun `what the app died under is stopped before the next command starts, and at start-up`() = runBlocking {
        val project = dir("earlier")
        proc.add(4100, 1, FakeProc.UID)

        val out = finished(runner.run("echo next", project, limits))

        assertEquals(listOf(4100), proc.killed.toList())
        assertFalse(out.stoppedLeftovers, "it was not this command's")

        proc.add(4200, 1, FakeProc.UID)
        proc.add(4201, 4200, FakeProc.UID)
        assertEquals(2, runner.stopLeftovers())
        assertEquals(0, runner.stopLeftovers())
        assertEquals(setOf(1, FakeProc.ZYGOTE, FakeProc.APP), proc.pids())
    }

    @Test
    fun `where leftovers cannot be swept nothing runs`() = runBlocking {
        val project = dir("no-sweep")
        // What the production wiring gives anywhere but in an Android app process.
        val none = runner(sweeper = LeftoverSweeper.None)

        val out = assertIs<CommandOutcome.Unavailable>(none.run("echo hi > ran.txt", project, limits))

        assertTrue("not run here" in out.reason, out.reason)
        assertFalse(File(project.file, "ran.txt").exists())
        assertEquals(0, none.stopLeftovers())

        // A phone whose process table cannot be read.
        val unreadable = runner(sweeper = ProcessSweeper(ProcFolderTable(File(proc.folder, "nowhere")), proc.kill, FakeProc.APP, FakeProc.UID, pause = {}))
        assertTrue("see its own processes" in assertIs<CommandOutcome.Unavailable>(unreadable.run("echo hi > ran.txt", project, limits)).reason)
        assertFalse(File(project.file, "ran.txt").exists())
    }

    @Test
    fun `one command runs at a time`() = runBlocking {
        val project = dir("serial")
        fun command(n: Int) = "echo start$n >> log; /bin/sleep 0.3; echo end$n >> log"

        val calls = (1..3).map { n -> async(Dispatchers.Default) { runner.run(command(n), project, limits) } }
        calls.forEach { finished(it.await()) }

        val lines = File(project.file, "log").readLines()
        assertEquals(6, lines.size)
        for (i in 0 until 3) assertEquals(lines[2 * i].removePrefix("start"), lines[2 * i + 1].removePrefix("end"), "no command starts while another runs: $lines")
    }

    private companion object {
        /** util-linux's setsid stands in for toybox's on the build machine. */
        const val HOST_SETSID = "/usr/bin/setsid"
    }
}
