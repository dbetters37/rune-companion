package com.aicontrolcenter.app.ui.workspace

import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.framework.operator.LocalWorkspace
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Corrective pass 4b (F1): the owner's Workspace screens over the real workspace on its real
 * dispatcher (Dispatchers.IO, as the app wires it), with one real thread standing in for the main
 * thread, so that two screens really look at the same moment. A screen that looks at the trash
 * while another look or a change is under way must not be left showing "The trash is empty"
 * while leftovers are on disk. Every wait here has a limit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceTrashLooksTest {
    private class World(
        val ws: LocalWorkspace,
        val main: ExecutorCoroutineDispatcher,
        val collectors: CoroutineScope,
        val activity: ActivityRecorder,
        val clock: FakeClock,
        val scope: CoroutineScope,
    ) {
        suspend fun folderScreen(): WorkspaceViewModel = withContext(main) {
            WorkspaceViewModel(ws, "", activity, clock, ZoneOffset.UTC).also { vm -> collectors.launch { vm.uiState.collect { } } }
        }

        suspend fun trashScreen(): WorkspaceTrashViewModel = withContext(main) {
            WorkspaceTrashViewModel(ws, activity, clock, ZoneOffset.UTC).also { vm -> collectors.launch { vm.uiState.collect { } } }
        }

        /** Waits, with a limit, until [condition] holds for what the screen shows; returns whether it came to hold. */
        suspend fun <T> shows(state: () -> T, condition: (T) -> Boolean): Boolean {
            repeat(1_000) {
                if (withContext(main) { condition(state()) }) return true
                delay(10)
            }
            return false
        }
    }

    private fun world(build: (File) -> Unit, body: suspend (World) -> Unit) {
        val main = Executors.newSingleThreadExecutor { Thread(it, "main-stand-in") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val root = Files.createTempDirectory("aicc-looks").toFile()
        try {
            runBlocking {
                val clock = FakeClock(autoAdvanceMs = 1)
                build(root)
                val ws = LocalWorkspace(FakeWorkspaceRoot(root), clock)
                val collectors = CoroutineScope(main)
                try {
                    withTimeout(120_000) {
                        body(World(ws, main, collectors, ActivityRecorder(InMemoryActivityLog(), SequentialIdGenerator(), clock), clock, this))
                    }
                } finally {
                    collectors.coroutineContext[Job]?.cancel()
                }
            }
        } finally {
            Dispatchers.resetMain()
            main.close()
            root.deleteRecursively()
        }
    }

    /** True leftovers that no tidy can list: two things in one holder (6 bytes), and [extra] more files of one byte each. */
    private fun trueLeftovers(root: File, extra: Int = 0): Long {
        File(root, ".trash/1700000000000-two").mkdirs()
        File(root, ".trash/1700000000000-two/a.txt").writeText("aaaa")
        File(root, ".trash/1700000000000-two/b.txt").writeText("bb")
        repeat(extra) { File(root, ".trash/left-by-a-command/d${it / 100}").also { d -> d.mkdirs() }.resolve("f$it").writeText("x") }
        return 6L + extra
    }

    @Test
    fun `F1 - the trash screen opened while an import that ends refused is under way shows the leftovers when it has ended`() {
        world(build = { trueLeftovers(it) }) { w ->
            // The owner imports a document from a slow provider; it turns out larger than the workspace takes.
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
            val importing = w.scope.async(Dispatchers.IO) { w.ws.import(RelativePath.of("big.bin"), source, 100_000, overwrite = false) }
            try {
                assertTrue(reading.await(10, TimeUnit.SECONDS), "the import is under way")
                val trash = w.trashScreen()
                delay(400)
                val during = withContext(w.main) { trash.uiState.value }
                assertFalse(!during.isLoading && during.items.isEmpty() && during.leftoverBytes == null, "while the import runs the screen does not say \"The trash is empty\"")

                go.countDown()
                assertIs<DocResult.TooLarge>(importing.await())

                // The refused import raised no counter, so nothing announces its end; the screen's look waited for it.
                assertTrue(w.shows({ trash.uiState.value }) { it.leftoverBytes == 6L }, "after the import: ${withContext(w.main) { trash.uiState.value }}")
                assertTrue(withContext(w.main) { trash.uiState.value.canEmpty }, "\"Empty the trash\" is offered")
            } finally {
                go.countDown()
            }
        }
    }

    @Test
    fun `F1 - the folder screen and the trash screen look again at the same moment and both go on showing the leftovers`() {
        world(build = { trueLeftovers(it, extra = 300) }) { w ->
            val folder = w.folderScreen()
            val trash = w.trashScreen()
            assertTrue(w.shows({ trash.uiState.value }) { it.leftoverBytes == 306L })
            assertTrue(w.shows({ folder.uiState.value }) { it.trashHasLeftovers })
            var trashWrong = 0
            var folderWrong = 0
            repeat(30) { round ->
                // An agent writes a file; both screens are told when the change has ended, and both look again.
                assertIs<DocResult.Ok<*>>(withContext(Dispatchers.Default) { w.ws.write(RelativePath.of("r$round.txt"), "x".toByteArray(), overwrite = false) })
                assertTrue(w.shows({ folder.uiState.value }) { state -> state.entries.any { it.name == "r$round.txt" } }, "the folder screen follows")
                delay(60)
                val (shown, flag) = withContext(w.main) { trash.uiState.value.leftoverBytes to folder.uiState.value.trashHasLeftovers }
                if (shown == null) trashWrong++
                if (!flag) folderWrong++
            }
            assertEquals(0 to 0, trashWrong to folderWrong, "of 30 times, the trash screen showed no leftovers (and no Empty button) / the folder screen's trash button showed none")
        }
    }
}
