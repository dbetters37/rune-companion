package com.aicontrolcenter.app.ui.workspace

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.operator.CommandTurn
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.framework.operator.LocalWorkspace
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 3, where the shell set's change lock (G1) meets the owner's own file manager and the
 * minor set's trash screen (W1): while a shell command runs, what the owner does in the Workspace
 * screens waits for it. It does not fail, the screens stay readable, and the wait ends when the
 * command has ended or was stopped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceWhileCommandRunsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val clock = FakeClock(current = TestApp.START_TIME, autoAdvanceMs = 1_000)
    private val activity = ActivityRecorder(InMemoryActivityLog(), SequentialIdGenerator(), clock)
    private val root = FakeWorkspaceRoot()
    private val ws = LocalWorkspace(root, clock, io = EmptyCoroutineContext)

    private suspend fun put(path: String, text: String) {
        assertTrue(ws.write(RelativePath.of(path), text.toByteArray(), overwrite = false) is DocResult.Ok)
    }

    @Test
    fun `what the owner does in the file manager waits for a running shell command, and goes on when it has ended`() = runTest(mainRule.dispatcher) {
        put("notes.md", "# Notes")
        put("old.txt", "x")
        val folder = WorkspaceViewModel(ws, "", activity, clock, ZoneOffset.UTC).also { it.uiState.activeIn(backgroundScope) }
        val trash = WorkspaceTrashViewModel(ws, activity, clock, ZoneOffset.UTC).also { it.uiState.activeIn(backgroundScope) }
        assertEquals(listOf("notes.md", "old.txt"), folder.uiState.value.entries.map { it.name })

        // A shell command the owner approved is running.
        val release = CompletableDeferred<Unit>()
        val command = backgroundScope.async { ws.runCommand(ws.changeCount()) { release.await() } }
        runCurrent()

        folder.trash(RelativePath.of("old.txt"))
        trash.emptyTrash()
        runCurrent()
        // Both wait: nothing was done yet, nothing failed, the screens still show what is there, and each says why it waits.
        assertTrue(folder.uiState.value.isBusy && trash.uiState.value.isBusy)
        assertEquals("A shell command is running. This is done as soon as it has ended.", folder.uiState.value.message?.text)
        assertEquals(WAITS_FOR_COMMAND, trash.uiState.value.message?.text)
        assertEquals(listOf("notes.md", "old.txt"), folder.uiState.value.entries.map { it.name })
        assertTrue(trash.uiState.value.items.isEmpty())

        release.complete(Unit)
        runCurrent()
        assertTrue(command.await() is CommandTurn.Ran)
        assertFalse(folder.uiState.value.isBusy || trash.uiState.value.isBusy)
        assertEquals(listOf("notes.md"), folder.uiState.value.entries.map { it.name })
        assertTrue("moved to the trash" in folder.uiState.value.message!!.text, folder.uiState.value.message!!.text)
        // The two went through one after the other, in the order the owner asked for them. Emptying deletes what the
        // trash screen showed when it was asked for, which was nothing: the file moved there meanwhile is kept (4b, F5).
        assertEquals("Nothing was deleted. 1 item that arrived meanwhile was kept.", trash.uiState.value.message?.text)
        assertEquals(listOf("old.txt"), trash.uiState.value.items.map { it.name })
        assertEquals(1, (ws.listTrash() as DocResult.Ok).value.size)

        // With no command running, a change is made at once and nothing is said about waiting.
        assertFalse(ws.commandRunning())
        folder.makeFolder("Later")
        runCurrent()
        assertEquals("Folder “Later” created.", folder.uiState.value.message?.text)
    }

    @Test
    fun `the wait of the file manager also ends when the shell command is stopped`() = runTest(mainRule.dispatcher) {
        put("notes.md", "# Notes")
        val folder = WorkspaceViewModel(ws, "", activity, clock, ZoneOffset.UTC).also { it.uiState.activeIn(backgroundScope) }

        val hanging = backgroundScope.launch { ws.runCommand(ws.changeCount()) { awaitCancellation() } }
        runCurrent()
        assertTrue(ws.commandRunning())
        // Sharing changes nothing in the workspace: it does not wait, and does not say that it does.
        folder.share(RelativePath.of("notes.md"))
        runCurrent()
        assertEquals("notes.md", folder.uiState.value.share?.title)
        assertFalse(folder.uiState.value.isBusy)
        assertNull(folder.uiState.value.message)
        folder.shareHandled()

        folder.makeFolder("Later")
        runCurrent()
        assertTrue(folder.uiState.value.isBusy)
        assertEquals(WAITS_FOR_COMMAND, folder.uiState.value.message?.text)
        assertEquals(listOf("notes.md"), folder.uiState.value.entries.map { it.name })

        // Stop: the call that ran the command is cancelled.
        hanging.cancelAndJoin()
        runCurrent()
        assertFalse(ws.commandRunning())
        assertFalse(folder.uiState.value.isBusy)
        assertEquals("Folder “Later” created.", folder.uiState.value.message?.text)
        assertEquals(listOf("Later", "notes.md"), folder.uiState.value.entries.map { it.name })
    }
}
