package com.aicontrolcenter.device.operator.commands

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.WorkspaceFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * G2 and G3 on a device or emulator, with Android's own shell and the app's real runner
 * ([SystemShellRunner.forApp], what the app wires): commands that try to get away from the call
 * (a background job, a new session, job control, nohup, a double fork) leave no process behind
 * when they end, at the time limit, or when the call is cancelled; and a shell the command
 * starts reads no start-up file from the workspace.
 *
 * This runs only as an instrumented test, where the process is an Android app with a user id of
 * its own. It looks at the device's real process table, which on Android shows an app only its
 * own processes. The JVM tests never do that (see LeftoverSweeperTest).
 *
 * Method names are plain so they can be dexed for every supported API level.
 */
@RunWith(AndroidJUnit4::class)
class ShellLeftoversDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root = File(context.filesDir, "shell-test-${UUID.randomUUID()}")
    private val runner = SystemShellRunner.forApp(context)
    private val limits = CommandLimits(timeoutMs = 15_000, maxOutputChars = 4_000)
    private val myPid = android.os.Process.myPid()
    private val myUid = android.os.Process.myUid()

    /** The same runner with a sweeper that finds nothing: what the runner was before the sweep existed (the process group is still killed). */
    private val withoutSweep = SystemShellRunner(
        tempDir = { File(context.cacheDir, "shell") },
        homeDir = { File(context.cacheDir, "shell-home") },
        sweeper = object : LeftoverSweeper {
            override fun sweep(): Sweep = Sweep.Done(stopped = 0, left = 0)
        },
    )

    private fun folder(): WorkspaceFile {
        root.mkdirs()
        return (WorkspaceFile.resolve(root, RelativePath.ROOT) as DocResult.Ok).value
    }

    /** Every running process of this app's user id that is neither this process nor another one the zygote started. */
    private fun leftovers(): List<ProcessEntry> {
        val table = ProcFolderTable(File("/proc")).read() ?: error("the process table cannot be read on this device")
        val own = table.firstOrNull { it.pid == myPid } ?: error("this process is not in the table")
        return table.filter { it.uid == myUid && it.pid != myPid && it.parentPid != own.parentPid }
    }

    /** A killed process takes a moment to leave the table. */
    private fun leftoversAfterSettling(): List<ProcessEntry> {
        var left = leftovers()
        var waited = 0
        while (left.isNotEmpty() && waited < 3_000) {
            Thread.sleep(100)
            waited += 100
            left = leftovers()
        }
        return left
    }

    @Before
    fun nothingRunsBefore() = runBlocking {
        assertIs<ProcessSweeper>(AndroidLeftovers.create(), "in an app process on a device the real sweeper is wired")
        runner.stopLeftovers()
        assertEquals(emptyList(), leftoversAfterSettling(), "a clean start")
    }

    @After
    fun cleanUp() = runBlocking {
        runner.stopLeftovers()
        root.deleteRecursively()
    }

    private val escapes = listOf(
        "background job" to "sleep 300 &",
        "new session" to "setsid sleep 300 &",
        "job control" to "set -m; sleep 300 &",
        "job control by its long name" to "set -o monitor; sleep 300 &",
        "nohup" to "nohup sleep 300 >/dev/null 2>&1 &",
        "double fork" to "(sleep 300 &) &",
        "double fork through two shells" to "sh -c 'sh -c \"sleep 300 &\" &'",
        "setsid inside sh -c" to "sh -c 'setsid sleep 300 >/dev/null 2>&1 &'",
        "a session inside a session" to "setsid sh -c 'setsid sleep 300 >/dev/null 2>&1 &' >/dev/null 2>&1 &",
        "several at once" to "setsid sleep 300 & setsid sleep 301 & (setsid sleep 302 &) & sleep 303 &",
    )

    @Test
    fun nothingACommandStartedIsLeftWhenItEnds() = runBlocking {
        for ((name, command) in escapes) {
            // The escapee gets a moment to start before the command ends.
            val outcome = runner.run("$command\nsleep 0.3", folder(), limits)

            val finished = assertIs<CommandOutcome.Finished>(outcome, "$name: $outcome")
            assertEquals(emptyList(), leftoversAfterSettling(), "$name: [$command] left processes behind")
            assertTrue(finished.stoppedLeftovers, "$name: the outcome says something was stopped")
            assertFalse(finished.leftRunning, name)
        }
    }

    @Test
    fun theSweepIsWhatStopsAProcessThatLeftTheGroup() = runBlocking {
        // Control: without the sweep, a process in a session of its own outlives the call. This is what G2 closes.
        assertIs<CommandOutcome.Finished>(withoutSweep.run("setsid sleep 300 &\nsleep 0.3", folder(), limits))
        Thread.sleep(500)
        assertTrue(leftovers().isNotEmpty(), "without the sweep the new session stays")

        // The sweep at app start-up stops it.
        assertTrue(runner.stopLeftovers() >= 1)
        assertEquals(emptyList(), leftoversAfterSettling())
    }

    @Test
    fun nothingIsLeftAtTheTimeLimit() = runBlocking {
        val outcome = runner.run("setsid sleep 300 & (sleep 300 &) & set -m; sleep 300 & sleep 300", folder(), CommandLimits(timeoutMs = 1_500, maxOutputChars = 4_000))

        assertFalse(assertIs<CommandOutcome.TimedOut>(outcome, "$outcome").leftRunning)
        assertEquals(emptyList(), leftoversAfterSettling())
    }

    @Test
    fun nothingIsLeftWhenTheCallIsCancelled() = runBlocking {
        val call = async(Dispatchers.Default) { runner.run("setsid sleep 300 & (sleep 300 &) & sleep 300", folder(), limits) }
        var waited = 0
        while (leftovers().size < 3 && waited < 5_000) {
            delay(100)
            waited += 100
        }
        assertTrue(leftovers().isNotEmpty(), "the command is running")

        call.cancelAndJoin()

        assertEquals(emptyList(), leftoversAfterSettling(), "by the time the cancelled call has ended, everything it started is gone")
    }

    @Test
    fun aNewCommandStartsCleanAfterOneThatWasLeft() = runBlocking {
        assertIs<CommandOutcome.Finished>(withoutSweep.run("setsid sleep 300 &\nsleep 0.3", folder(), limits))
        Thread.sleep(300)

        val next = assertIs<CommandOutcome.Finished>(runner.run("echo next", folder(), limits))

        assertEquals("next\n", next.stdout)
        assertEquals(emptyList(), leftoversAfterSettling())
    }

    @Test
    fun aLoginShellReadsNoProfileFromTheWorkspace() = runBlocking {
        val dir = folder()
        // What an agent can do without approval: write a file into the workspace.
        File(dir.file, ".profile").writeText("echo PROFILE-RAN\n")
        File(dir.file, ".mkshrc").writeText("echo MKSHRC-RAN\n")

        val out = assertIs<CommandOutcome.Finished>(runner.run("sh -l -c 'echo hi'; sh -i -c 'echo there' 2>/dev/null; echo \"home=\$HOME env=\${ENV:-unset}\"", dir, limits))

        assertFalse("PROFILE-RAN" in out.stdout, out.stdout)
        assertFalse("MKSHRC-RAN" in out.stdout, out.stdout)
        assertTrue("hi\n" in out.stdout, out.stdout)
        val home = out.stdout.substringAfter("home=").substringBefore(" env=")
        assertTrue(home.isNotEmpty() && !home.startsWith(dir.file.path), "HOME ($home) is outside the workspace")
        assertTrue("env=unset" in out.stdout, out.stdout)
    }

    @Test
    fun homeIsEmptyBeforeEveryCommand() = runBlocking {
        val dir = folder()
        assertIs<CommandOutcome.Finished>(runner.run("echo 'echo LEFT-BEHIND' > \"\$HOME/.profile\"; ln -s '${dir.file.path}' \"\$HOME/link\"", dir, limits))
        File(dir.file, "keep.txt").writeText("owner data")

        val out = assertIs<CommandOutcome.Finished>(runner.run("sh -l -c 'echo second'; ls -A \"\$HOME\"", dir, limits))

        assertEquals("second\n", out.stdout)
        assertEquals("owner data", File(dir.file, "keep.txt").readText(), "the link in HOME was removed, not followed")
    }
}
