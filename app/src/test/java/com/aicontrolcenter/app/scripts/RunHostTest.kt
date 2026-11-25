package com.aicontrolcenter.app.scripts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.framework.operator.JsHost
import com.aicontrolcenter.framework.operator.JsHostError
import com.aicontrolcenter.framework.operator.JsLimits
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6 (WP6, rule 11; F22): the workspace of one run, as the app serves it to the script process. Every limit is
 * kept here, whatever the script process asks: the calls below are what a script process that ignores its sandbox can
 * send.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RunHostTest {
    private val pipes = TestPipes()
    private val io = Executors.newCachedThreadPool()
    private val deadline = AtomicLong(Long.MAX_VALUE)
    private val release = CountDownLatch(1)

    @After
    fun stop() {
        release.countDown()
        io.shutdownNow()
    }

    private inner class Workspace(override val canWrite: Boolean = true, private val readWaitMs: Long = 0) : JsHost {
        val calls = CopyOnWriteArrayList<String>()
        val files = java.util.concurrent.ConcurrentHashMap<String, String>()

        override fun read(path: String): String {
            calls += "read $path"
            if (readWaitMs > 0) release.await(readWaitMs, TimeUnit.MILLISECONDS)
            return files[path] ?: throw JsHostError("\"$path\" does not exist.")
        }

        override fun list(path: String): List<Triple<String, Boolean, Long?>> {
            calls += "list $path"
            return files.keys.sorted().map { Triple(it, false, null) }
        }

        override fun write(path: String, text: String): String {
            calls += "write $path"
            if (files.putIfAbsent(path, text) != null) throw JsHostError("\"$path\" already exists; scripts never replace files.")
            return path
        }
    }

    private fun host(workspace: JsHost, limits: JsLimits = JsLimits()) = RunHost(workspace, limits, pipes, io, deadlineMs = { deadline.get() })

    private fun write(host: IScriptHost, path: String?, text: String): String {
        val pipe = pipes.open()
        Thread { runCatching { pipes.writer(pipe.write).use { ScriptFrames.writeContent(it, text) } } }.start()
        return host.write(path, pipe.read)
    }

    private fun read(host: IScriptHost, path: String?, maxChars: Int = Int.MAX_VALUE): Pair<Boolean, String> =
        pipes.reader(host.read(path)).use { ScriptFrames.readReply(it, maxChars) }

    @Test(timeout = 30_000)
    fun `at most twenty files, and only with write access`() {
        val workspace = Workspace()
        val host = host(workspace)
        val replies = List(25) { write(host, "f$it.txt", "x") }
        assertEquals(List(20) { "+f$it.txt" } + List(5) { "!${RunHost.TOO_MANY_FILES}" }, replies)
        assertEquals(20, workspace.files.size)
        assertEquals(20, host.filesCreated)

        val reader = Workspace(canWrite = false)
        assertEquals("!${RunHost.READ_ONLY}", write(host(reader), "a.txt", "x"))
        assertEquals(emptyList(), reader.calls.toList())
    }

    @Test(timeout = 30_000)
    fun `a file that exists is not replaced, and failed writes do not count`() {
        val workspace = Workspace().also { it.files["a.txt"] = "old" }
        val host = host(workspace)
        assertEquals("!\"a.txt\" already exists; scripts never replace files.", write(host, "a.txt", "new"))
        assertEquals("old", workspace.files["a.txt"])
        assertEquals(0, host.filesCreated)
    }

    @Test(timeout = 30_000)
    fun `texts over the limit are refused both ways`() {
        val workspace = Workspace().also { it.files["big.txt"] = "b".repeat(101); it.files["fits.txt"] = "f".repeat(100) }
        val host = host(workspace, JsLimits(maxStringChars = 100))
        assertEquals(true to "f".repeat(100), read(host, "fits.txt"))
        assertEquals(false to "The file is longer than 100 characters.", read(host, "big.txt"))
        assertTrue(write(host, "w.txt", "w".repeat(101)).startsWith("!\"text\" is longer than 100 characters"))
        assertEquals(null, workspace.files["w.txt"])
        assertEquals("!The path is longer than ${ScriptFrames.MAX_PATH_CHARS} characters.", host.list("p".repeat(ScriptFrames.MAX_PATH_CHARS + 1)))
        assertEquals("!A path is needed.", host.list(null))
        assertEquals("!${RunHost.NO_TEXT}", host.write("a.txt", null))
    }

    @Test(timeout = 30_000)
    fun `refusals of the workspace reach the script, and other failures by their type only`() {
        val host = host(Workspace())
        assertEquals(false to "\"none.txt\" does not exist.", read(host, "none.txt"))
        val failing = object : JsHost {
            override val canWrite = true

            override fun read(path: String): String = throw IllegalStateException("secret detail")

            override fun list(path: String): List<Triple<String, Boolean, Long?>> = throw IllegalStateException("secret detail")

            override fun write(path: String, text: String): String = throw IllegalStateException("secret detail")
        }
        val other = host(failing)
        assertEquals(false to "The workspace operation failed (IllegalStateException).", read(other, "a"))
        assertEquals("!The workspace operation failed (IllegalStateException).", other.list(""))
        assertEquals("!The workspace operation failed (IllegalStateException).", write(other, "a", "x"))
    }

    @Test(timeout = 30_000)
    fun `one call at a time, the next waiting a moment for the one before and a third refused at once`() {
        val workspace = Workspace(readWaitMs = 600).also { it.files["a.txt"] = "a" }
        val host = host(workspace)
        val first = Thread { read(host, "a.txt") }.apply { start() }
        Thread.sleep(150)
        val second = CopyOnWriteArrayList<String>()
        val waiter = Thread { second += host.list("") }.apply { start() }
        Thread.sleep(150)
        assertEquals("!${RunHost.BUSY}", host.list(""), "a call while another waits")
        first.join(5_000)
        waiter.join(5_000)
        assertEquals(listOf("+[{\"name\":\"a.txt\",\"folder\":false,\"size\":null}]"), second.toList())

        val slow = Workspace(readWaitMs = 5_000).also { it.files["a.txt"] = "a" }
        val busy = host(slow)
        Thread { runCatching { read(busy, "a.txt") } }.start()
        Thread.sleep(150)
        val start = System.nanoTime()
        assertEquals("!${RunHost.BUSY}", busy.list(""))
        val waited = (System.nanoTime() - start) / 1_000_000
        assertTrue(waited in 800..3_000, "waited $waited ms")
    }

    @Test(timeout = 30_000)
    fun `once the run is over nothing is served, and a write still arriving is not made`() {
        val workspace = Workspace().also { it.files["a.txt"] = "a" }
        val host = host(workspace)
        // A write whose text never comes: the call waits for it.
        val stalled = pipes.open()
        val reply = CopyOnWriteArrayList<String>()
        val writer = Thread { reply += host.write("late.txt", stalled.read) }.apply { start() }
        Thread.sleep(300)
        host.end()
        writer.join(5_000)
        assertEquals(listOf("!${RunHost.OVER}"), reply.toList())
        assertEquals(null, workspace.files["late.txt"])
        assertEquals("!${RunHost.OVER}", host.list(""))
        assertEquals(false to RunHost.OVER, read(host, "a.txt"))
        assertEquals("!${RunHost.OVER}", write(host, "b.txt", "b"))
        assertEquals(emptyList(), workspace.calls.toList())
    }

    @Test(timeout = 30_000)
    fun `a call that outlasts the run's time is given up`() {
        val workspace = object : JsHost {
            override val canWrite = true

            override fun read(path: String): String = ""

            override fun list(path: String): List<Triple<String, Boolean, Long?>> {
                release.await(10, TimeUnit.SECONDS)
                return emptyList()
            }

            override fun write(path: String, text: String): String = path
        }
        deadline.set(System.nanoTime() / 1_000_000 + 300)
        val start = System.nanoTime()
        val host = host(workspace)
        assertEquals("!${RunHost.TIME_UP}", host.list(""))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
        assertTrue(host.isOver, "a call given up at the run's time ends the run's workspace")
    }

    /**
     * As [com.aicontrolcenter.framework.operator.LocalWorkspace] writes: on another thread, which an interrupt of the
     * caller does not stop (an interrupted `runBlocking` returns at once while its work goes on).
     */
    private inner class LocalLikeWorkspace(private val writeMs: Long) : JsHost {
        override val canWrite = true
        val calls = CopyOnWriteArrayList<String>()
        val files = java.util.concurrent.ConcurrentHashMap<String, String>()

        override fun read(path: String): String {
            calls += "read $path"
            return files[path] ?: throw JsHostError("\"$path\" does not exist.")
        }

        override fun list(path: String): List<Triple<String, Boolean, Long?>> = emptyList<Triple<String, Boolean, Long?>>().also { calls += "list $path" }

        override fun write(path: String, text: String): String = kotlinx.coroutines.runBlocking {
            calls += "write $path"
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val end = System.nanoTime() + writeMs * 1_000_000
                while (System.nanoTime() < end) Thread.onSpinWait()
                if (files.putIfAbsent(path, text) != null) throw JsHostError("exists")
                path
            }
        }
    }

    private fun nowMs() = System.nanoTime() / 1_000_000

    @Test(timeout = 60_000)
    fun `once the run's workspace time is up nothing more reaches the workspace, and the cap holds (SEC-W2B-1)`() {
        val workspace = LocalLikeWorkspace(writeMs = 1).also { it.files["a.txt"] = "a" }
        val host = host(workspace)
        val honest = List(19) { write(host, "ok$it.txt", "x") }
        assertEquals(List(19) { "+ok$it.txt" }, honest)
        val before = workspace.calls.size
        // The run's workspace time is up; the engine lets the process go only some seconds later. A script process
        // that keeps calling in between gets nothing done.
        deadline.set(nowMs() - 1)
        val late = mutableListOf<String>()
        repeat(100) {
            late += write(host, "late$it.txt", "x")
            late += read(host, "a.txt").let { (ok, text) -> if (ok) "+$text" else "!$text" }
            late += host.list("")
        }
        Thread.sleep(300)
        assertEquals(emptyList(), workspace.calls.drop(before), "no call after the time reached the workspace")
        assertEquals(setOf("!${RunHost.OVER}"), late.toSet())
        assertEquals(19, workspace.files.keys.count { it.startsWith("ok") })
        assertEquals(20, workspace.files.size)
        assertEquals(19, host.filesCreated)
        assertTrue(host.isOver)
    }

    @Test(timeout = 60_000)
    fun `a write already in the workspace when the time is up is counted, and the script is told it may have been made (SEC-W2B-1)`() {
        val workspace = LocalLikeWorkspace(writeMs = 400)
        val host = host(workspace)
        deadline.set(nowMs() + 150)
        assertEquals("!${RunHost.IN_FLIGHT}", write(host, "straddle.txt", "x"))
        // The write goes on to its end, and is counted when it is made.
        val until = System.nanoTime() + 5_000_000_000L
        while (host.filesCreated == 0 && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(setOf("straddle.txt"), workspace.files.keys)
        assertEquals(1, host.filesCreated)
        assertEquals("!${RunHost.OVER}", write(host, "next.txt", "x"))
        assertEquals(listOf("write straddle.txt"), workspace.calls.toList())
    }

    @Test(timeout = 60_000)
    fun `a call given up before it reached the workspace leaves nothing done there (SEC-W2B-1)`() {
        val workspace = LocalLikeWorkspace(writeMs = 1)
        val host = host(workspace)
        deadline.set(nowMs() + 200)
        // The text never comes: the call waits for it until the time is up.
        val stalled = pipes.open()
        assertEquals("!${RunHost.TIME_UP}", host.write("stalled.txt", stalled.read))
        Thread.sleep(200)
        assertEquals(emptyList(), workspace.calls.toList())
        assertEquals(0, host.filesCreated)
        assertTrue(host.isOver)
    }
}
