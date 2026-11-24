package com.aicontrolcenter.device.operator.commands

import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.CommandTurn
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.core.operator.WorkspaceRoot
import com.aicontrolcenter.framework.operator.LocalWorkspace
import com.aicontrolcenter.testkit.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Corrective pass 4b, where the sets meet: the shell's deleting of home folders it put aside
 * (DEVICE, E1) next to the workspace's change lock, its look at the trash (WORKSPACE, F1) and its
 * tidy at the app's start (WORKSPACE, F3), with the real runner and the real workspace.
 *
 * Both are background work at the start, and neither holds the other up. The deleting runs in
 * the runner's own scope and never takes the workspace's change lock, so a look at the trash is
 * turned away only while a shell command runs, never because folders are being deleted. The
 * tidy takes the change lock: it waits for a change, and does nothing while a command holds it.
 */
class Round4bMeetingPointsTest {
    private val cache: File = Files.createTempDirectory("aicc-4b-cache").toFile()
    private val home = File(cache, "shell-home")
    private val temp = File(cache, "shell")
    private val root: File = Files.createTempDirectory("aicc-4b-ws").toFile()
    private val workspace = LocalWorkspace(WorkspaceRoot { root }, FakeClock())
    private val limits = CommandLimits(timeoutMs = 10_000, maxOutputChars = 1_000)

    private fun top(): WorkspaceFile = (WorkspaceFile.resolve(root, RelativePath.ROOT) as DocResult.Ok).value

    private fun putAside(): List<String> = cache.list().orEmpty().filter { ".old-" in it }.sorted()

    /** The real operations, but nothing in a folder put aside is deleted before [release]; [entered] once the first try came. */
    private class Held : FolderOps() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun delete(path: Path) {
            if (".old-" in path.toString()) {
                entered.countDown()
                // Bounded: a test that failed before letting it go does not hold the pass for long.
                release.await(30, TimeUnit.SECONDS)
            }
            super.delete(path)
        }
    }

    private fun runner(ops: FolderOps) = SystemShellRunner(
        tempDir = { temp }, homeDir = { home }, sweeper = FakeProc().sweeper(),
        shell = "/bin/sh", setsid = HOST_SETSID, folderOps = ops, tidyBudgetMs = 60_000,
    )

    /** What an earlier start put aside and had not finished deleting when the app stopped. */
    private fun leftByAnEarlierStart() {
        val aside = File(cache, "shell-home.old-earlier").apply { mkdirs() }
        repeat(20) { File(aside, "f$it").writeText("x") }
    }

    /** What a kill in the middle of a change to the trash leaves: a holder without its record, and a half-staged file. */
    private fun interruptedTrashChange() {
        File(root, ".trash/$LOST_ID").mkdirs()
        File(root, ".trash/$LOST_ID/contract.txt").writeText("the owner's signed contract")
        File(root, ".trash/.staging").mkdirs()
        File(root, ".trash/.staging/w-half").writeText("half")
        // And what no tidy can list (5 bytes): a look that really looked names it.
        File(root, ".trash/left-by-a-command").mkdirs()
        File(root, ".trash/left-by-a-command/f").writeText("12345")
    }

    private fun tidied(): Boolean = File(root, ".trash/$LOST_ID.json").exists() && !File(root, ".trash/.staging/w-half").exists()

    @Test
    fun `at the start the shell's deleting does not hold up the workspace's tidy, a look or a change`() = runBlocking<Unit> {
        leftByAnEarlierStart()
        interruptedTrashChange()
        val ops = Held()
        val runner = runner(ops)
        try {
            // AppStartup: the shell's sweep first. It does not wait for the deleting it starts.
            withTimeout(10_000) { runner.stopLeftovers() }
            assertTrue(ops.entered.await(10, TimeUnit.SECONDS), "the deleting of what was put aside began")

            // The deleting is held in the middle of its work; the workspace's tidy, a look and a change go on all the same.
            withTimeout(10_000) { workspace.tidyTrash() }
            assertTrue(tidied(), "the trash was put right while the shell's deleting was under way")
            assertFalse(workspace.commandRunning())
            assertEquals(5L, withTimeout(10_000) { workspace.trashLeftovers() }, "a look is answered, not turned away")
            assertIs<DocResult.Ok<*>>(withTimeout(10_000) { workspace.write(RelativePath.of("notes.txt"), "x".toByteArray(), overwrite = false) })
            assertEquals(listOf("shell-home.old-earlier"), putAside(), "and the deleting is still held")
        } finally {
            ops.release.countDown()
        }
        runner.awaitTidying()
        assertEquals(emptyList(), putAside(), "once it may go on, what was put aside is deleted")
    }

    @Test
    fun `a change that holds the workspace does not hold up the shell's deleting, and the tidy waits for that change`() = runBlocking<Unit> {
        leftByAnEarlierStart()
        interruptedTrashChange()
        val runner = runner(FolderOps())
        val inside = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        // A long change: a copy from outside whose gateway takes its time. It holds the change lock throughout.
        val copy = async(Dispatchers.Default) {
            workspace.receive(RelativePath.of("photo.jpg"), 1_000) { target, _ ->
                inside.complete(Unit)
                goOn.await()
                target.file.writeText("jpeg")
                DocResult.Ok(Unit)
            }
        }
        try {
            withTimeout(10_000) { inside.await() }
            val tidy = async(Dispatchers.Default) { workspace.tidyTrash() }

            // The shell's sweep and the deleting after it need nothing of the workspace.
            withTimeout(10_000) {
                runner.stopLeftovers()
                runner.awaitTidying()
            }
            assertEquals(emptyList(), putAside(), "what was put aside was deleted while a change held the workspace")

            // The tidy does not run while a change is in flight: it waits for its end.
            assertNull(withTimeoutOrNull(300) { tidy.await() }, "the tidy waited for the change")
            assertFalse(tidied())
            goOn.complete(Unit)
            withTimeout(10_000) { tidy.await() }
            assertTrue(tidied(), "the tidy ran once the change had ended")
        } finally {
            goOn.complete(Unit)
        }
        assertIs<DocResult.Ok<*>>(withTimeout(10_000) { copy.await() })
    }

    @Test
    fun `while a command runs a look is turned away and the tidy does nothing, and the deleting after it holds nothing of the workspace`() = runBlocking<Unit> {
        interruptedTrashChange()
        val ops = Held()
        val runner = runner(ops)
        // A first command leaves files in the shell's home folder.
        val first = workspace.runCommand(workspace.changeCount()) { runner.run("echo a > \"\$HOME/a\"; echo b > \"\$HOME/b\"", top(), limits) }
        assertIs<CommandOutcome.Finished>(assertIs<CommandTurn.Ran<CommandOutcome>>(first).result)
        runner.awaitTidying()
        assertEquals(emptyList(), putAside(), "nothing is put aside until the next command")

        val inside = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        val second = async(Dispatchers.Default) {
            workspace.runCommand(workspace.changeCount()) {
                runner.run("true", top(), limits).also {
                    inside.complete(Unit)
                    goOn.await()
                }
            }
        }
        try {
            withTimeout(10_000) { inside.await() }
            // The command holds the workspace: a look is turned away, and the tidy does nothing.
            assertTrue(workspace.commandRunning())
            assertNull(withTimeout(10_000) { workspace.trashLeftovers() }, "nothing is claimed while a command runs")
            withTimeout(10_000) { workspace.tidyTrash() }
            assertFalse(tidied(), "the tidy did not run while the command held the workspace")
            // The runner has answered, and has begun deleting the home folder it put aside; that is held here.
            assertTrue(ops.entered.await(10, TimeUnit.SECONDS), "the deleting began after the command")
            goOn.complete(Unit)
            val ran = assertIs<CommandTurn.Ran<CommandOutcome>>(withTimeout(10_000) { second.await() })
            assertIs<CommandOutcome.Finished>(ran.result)

            // The command has ended. The deleting is still under way, and holds nothing of the workspace.
            assertEquals(1, putAside().size, "the deleting is still held: ${putAside()}")
            assertFalse(workspace.commandRunning())
            withTimeout(10_000) { workspace.tidyTrash() }
            assertTrue(tidied(), "the tidy ran once the command had ended")
            assertEquals(5L, withTimeout(10_000) { workspace.trashLeftovers() }, "a look is answered, not turned away")
            assertIs<DocResult.Ok<*>>(withTimeout(10_000) { workspace.write(RelativePath.of("notes.txt"), "x".toByteArray(), overwrite = false) })
        } finally {
            goOn.complete(Unit)
            ops.release.countDown()
        }
        runner.awaitTidying()
        assertEquals(emptyList(), putAside(), "once it may go on, what was put aside is deleted")
    }

    private companion object {
        /** util-linux setsid on the build machine; Android's toybox one is at /system/bin/setsid. */
        const val HOST_SETSID = "/usr/bin/setsid"

        /** A trash id as the workspace makes them. */
        const val LOST_ID = "1700000000000-abc12345"
    }
}
