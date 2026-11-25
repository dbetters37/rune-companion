package com.aicontrolcenter.app.scripts

import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.framework.operator.JsHost
import com.aicontrolcenter.framework.operator.JsLimits
import com.aicontrolcenter.framework.operator.JsOutcome
import com.aicontrolcenter.framework.operator.JsSandbox
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 6 (WP6; rules 11, 31; spike Q2, Q3, Q6, F5, F-G): how the isolated engine starts, follows and lets go of its
 * script processes. The script process here is the real runner and sandbox in this test's process, behind
 * [DirectLauncher], which stands in for Android's binding and reports a process's death as Android would.
 *
 * TEST SAFETY: every script ends by itself or at its budget; a run is kept going past its time only by a host function
 * that waits for this test with a bound of its own, and every such wait is let go at the end of its test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class IsolatedJsEngineTest {
    private val pipes = TestPipes()
    private val ends = AtomicInteger()
    private val release = CountDownLatch(1)

    @After
    fun letGo() = release.countDown()

    private fun <T> blocking(block: suspend () -> T): T = runBlocking(Dispatchers.Default) { block() }

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000

    private fun engine(launcher: ScriptProcessLauncher, limits: JsLimits = jsLimits(), shared: Boolean = false) =
        IsolatedJsEngine(limits, launcher, pipes, JsSandbox(limits), sharedProcess = shared)

    /** A workspace whose read waits for the test's release (at most 20 s), deaf to every interrupt: it holds its run. */
    private inner class DeafHost : JsHost {
        override val canWrite = false

        override fun read(path: String): String {
            val until = System.nanoTime() + 20_000_000_000L
            while (release.count > 0 && System.nanoTime() < until) {
                try {
                    release.await(50, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    // deaf
                }
            }
            return "x"
        }

        override fun list(path: String): List<Triple<String, Boolean, Long?>> = emptyList()

        override fun write(path: String, text: String): String = path
    }

    private fun result(outcome: JsOutcome): String? = assertIs<JsOutcome.Finished>(outcome, "$outcome").resultJson

    @Test(timeout = 60_000)
    fun `a script runs in its process, and the next one reuses it`() = blocking {
        val launcher = DirectLauncher(makeProcess = honestProcess(pipes, ends))
        val engine = engine(launcher)
        assertEquals("2", result(engine.run("1 + 1", null, null, 5_000)))
        assertEquals("3", result(engine.run("1 + 2", null, null, 5_000)))
        assertEquals(listOf("js0"), launcher.binds.toList())
        assertEquals(emptyList(), launcher.unbinds.toList())
        assertNull(assertIs<JsOutcome.Finished>(engine.run("1", null, null, 5_000)).note, "no note when the script ran in its own process")
    }

    @Test(timeout = 60_000)
    fun `a script that does not stop is let go of with its process, and the next run gets a fresh one`() = blocking {
        val launcher = DirectLauncher(makeProcess = honestProcess(pipes, ends))
        val engine = engine(launcher)
        val start = System.nanoTime()
        val stopped = engine.run("workspace.read('a')", null, DeafHost(), 300)
        val took = elapsedMs(start)
        assertIs<JsOutcome.Stopped>(stopped, "$stopped")
        assertTrue(took < 300 + JsSandbox.GRACE_MS + 2_000, "answered after $took ms")
        // Its thread still runs there: the app let go of that process at once.
        assertEquals(listOf("js0"), launcher.unbinds.toList())
        assertEquals("2", result(engine.run("1 + 1", null, null, 5_000)))
        assertEquals(listOf("js0", "js0"), launcher.binds.toList(), "a fresh process for the next run")
        // The process's own watchdog ends it too, at its bound (limit + grace + 3 s), had the app not.
        withTimeout(10_000) { while (ends.get() == 0) delay(50) }
    }

    @Test(timeout = 60_000)
    fun `a process that dies during a run is answered in plain words, and the next run binds afresh`() = blocking {
        val launcher = DirectLauncher(makeProcess = honestProcess(pipes, ends))
        val engine = engine(launcher)
        assertEquals("2", result(engine.run("1 + 1", null, null, 5_000)))
        val outcome = coroutineScope {
            val running = async { engine.run("var t = Date.now(); while (Date.now() - t < 3000) {} 1", null, null, 5_000) }
            delay(500)
            launcher.handles.forEach { launcher.kill(it.binder) }
            running.await()
        }
        assertEquals(IsolatedJsEngine.PROCESS_ENDED, assertIs<JsOutcome.Stopped>(outcome).reason)
        assertEquals(listOf("js0"), launcher.unbinds.toList(), "unbound at once after the death (F5)")
        assertEquals("2", result(engine.run("1 + 1", null, null, 5_000)))
        assertEquals(listOf("js0", "js0"), launcher.binds.toList())
    }

    @Test(timeout = 60_000)
    fun `two scripts run at once, each in its own process, and a third waits for one of them`() = blocking {
        val launcher = DirectLauncher(makeProcess = honestProcess(pipes, ends))
        val engine = engine(launcher)
        engine.run("1", null, null, 5_000)
        val start = System.nanoTime()
        val outcomes = coroutineScope {
            List(3) { async { engine.run("var t = Date.now(); while (Date.now() - t < 1000) {} 7", null, null, 5_000) } }.awaitAll()
        }
        val took = elapsedMs(start)
        outcomes.forEach { assertEquals("7", result(it)) }
        assertTrue(took >= 1_900, "the third ran alongside the others: all done after $took ms")
        assertEquals(setOf("js0", "js1"), launcher.binds.toSet())
        assertEquals(2, launcher.binds.size)
    }

    @Test(timeout = 60_000)
    fun `no workspace call is served once the run is over`() = blocking {
        val hosts = CopyOnWriteArrayList<IScriptHost>()
        val honest = honestProcess(pipes, ends)
        val launcher = DirectLauncher { instance, l ->
            val inner = honest(instance, l) as IScriptRunner
            object : IScriptRunner.Stub() {
                override fun run(request: ParcelFileDescriptor?, host: IScriptHost?, result: IScriptResult?) {
                    host?.let { hosts += it }
                    inner.run(request, host, result)
                }

                override fun pid(): Int = inner.pid()
            }
        }
        val workspace = object : JsHost {
            val calls = AtomicInteger()
            override val canWrite = true

            override fun read(path: String): String = "r".also { calls.incrementAndGet() }

            override fun list(path: String): List<Triple<String, Boolean, Long?>> = emptyList<Triple<String, Boolean, Long?>>().also { calls.incrementAndGet() }

            override fun write(path: String, text: String): String = path.also { calls.incrementAndGet() }
        }
        assertEquals("\"r\"", result(engine(launcher).run("workspace.read('a')", null, workspace, 5_000)))
        assertEquals(1, workspace.calls.get())
        val host = hosts.single()
        assertEquals("!${RunHost.OVER}", host.list(""))
        val content = pipes.open()
        assertEquals("!${RunHost.OVER}", host.write("late.txt", content.read))
        val reply = pipes.reader(host.read("a")).use { ScriptFrames.readReply(it, 100) }
        assertEquals(false to RunHost.OVER, reply)
        assertEquals(1, workspace.calls.get(), "no call after the run reached the workspace")
    }

    @Test(timeout = 60_000)
    fun `when Android refuses the process, the script runs in the app and the answer says so`() = blocking {
        val launcher = DirectLauncher { _, _ -> null }
        val outcome = engine(launcher).run("1 + 1", null, null, 5_000)
        assertEquals("2", result(outcome))
        assertEquals(IsolatedJsEngine.FALLBACK_NOTE, outcome.note)
        assertEquals(emptyList(), launcher.binds.toList())
    }

    @Test(timeout = 60_000)
    fun `a process that does not start in time is answered in plain words and let go of`() = blocking {
        val launcher = DirectLauncher(connectDelayMs = 3_000, makeProcess = honestProcess(pipes, ends))
        val start = System.nanoTime()
        val outcome = engine(launcher, jsLimits(bindTimeoutMs = 500)).run("1 + 1", null, null, 5_000)
        assertEquals(IsolatedJsEngine.BIND_SLOW, assertIs<JsOutcome.Stopped>(outcome).reason)
        assertTrue(elapsedMs(start) < 2_500)
        assertEquals(listOf("js0"), launcher.unbinds.toList())
    }

    /** A script process that takes the run and then says nothing more ([started]: only that its code began). */
    private class Silent(private val started: Boolean) : IScriptRunner.Stub() {
        override fun run(request: ParcelFileDescriptor?, host: IScriptHost?, result: IScriptResult?) {
            if (started) result?.started()
        }

        override fun pid(): Int = 1
    }

    @Test(timeout = 60_000)
    fun `a process that never gets ready is let go of at its bound`() = blocking {
        val launcher = DirectLauncher { _, _ -> Silent(started = false) }
        val start = System.nanoTime()
        val outcome = engine(launcher, jsLimits(engineStartMs = 300)).run("1", null, null, 5_000)
        val took = elapsedMs(start)
        assertEquals(JsSandbox.engineSlow(300), assertIs<JsOutcome.Stopped>(outcome).reason)
        assertTrue(took in 300 + IsolatedJsEngine.PAST_BOUND_MS - 200..300 + IsolatedJsEngine.PAST_BOUND_MS + 3_000, "answered after $took ms")
        assertEquals(listOf("js0"), launcher.unbinds.toList())
    }

    @Test(timeout = 60_000)
    fun `a process that never answers after the script began is let go of at the time limit and its bound`() = blocking {
        val launcher = DirectLauncher { _, _ -> Silent(started = true) }
        val start = System.nanoTime()
        val outcome = engine(launcher).run("1", null, null, 300)
        val took = elapsedMs(start)
        assertEquals(JsSandbox.ABANDONED_TEXT, assertIs<JsOutcome.Stopped>(outcome).reason)
        assertTrue(took in 300 + IsolatedJsEngine.PAST_BOUND_MS - 200..300 + IsolatedJsEngine.PAST_BOUND_MS + 3_000, "answered after $took ms")
        assertEquals(listOf("js0"), launcher.unbinds.toList())
    }

    @Test(timeout = 60_000)
    fun `cancelling the caller lets the script's process go`() = blocking {
        val launcher = DirectLauncher(makeProcess = honestProcess(pipes, ends))
        val engine = engine(launcher)
        engine.run("1", null, null, 5_000)
        coroutineScope {
            val call = async(start = CoroutineStart.UNDISPATCHED) { engine.run("var t = Date.now(); while (Date.now() - t < 4000) {} 1", null, null, 10_000) }
            delay(500)
            call.cancel()
            withTimeout(3_000) { call.join() }
        }
        assertEquals(listOf("js0"), launcher.unbinds.toList())
        assertEquals("2", result(engine.run("1 + 1", null, null, 5_000)))
    }

    @Test(timeout = 60_000)
    fun `on Android 9 both runs share one process, and a run ended for the other one is told so`() = blocking {
        val launcher = DirectLauncher(makeProcess = honestProcess(pipes, ends))
        val engine = engine(launcher, shared = true)
        engine.run("1", null, null, 5_000)
        val (stuck, other) = coroutineScope {
            val other = async { engine.run("var t = Date.now(); while (Date.now() - t < 4000) {} 1", null, null, 6_000) }
            delay(200)
            val stuck = async { engine.run("workspace.read('a')", null, DeafHost(), 300) }
            stuck.await() to other.await()
        }
        assertIs<JsOutcome.Stopped>(stuck)
        assertEquals(IsolatedJsEngine.OTHER_RUN_ENDED, assertIs<JsOutcome.Stopped>(other).reason)
        assertEquals(listOf("js0"), launcher.binds.toList(), "one process for both")
    }

    @Test(timeout = 60_000)
    fun `a process that died while idle is replaced, and the run goes ahead`() = blocking {
        val honest = honestProcess(pipes, ends)
        val first = AtomicBoolean(true)
        val launcher = DirectLauncher { instance, l ->
            if (first.getAndSet(false)) {
                object : IScriptRunner.Stub() {
                    override fun run(request: ParcelFileDescriptor?, host: IScriptHost?, result: IScriptResult?) = throw DeadObjectException()

                    override fun pid(): Int = throw DeadObjectException()
                }
            } else {
                honest(instance, l)
            }
        }
        assertEquals("2", result(engine(launcher).run("1 + 1", null, null, 5_000)))
        assertEquals(listOf("js0", "js0"), launcher.binds.toList())
        assertEquals(listOf("js0"), launcher.unbinds.toList())
    }

    @Test(timeout = 60_000)
    fun `a process that runs out of memory tells the run before it ends`() = blocking {
        val runner = AtomicReference<ScriptRunner>()
        val launcher = DirectLauncher { _, l ->
            lateinit var made: ScriptRunner
            made = ScriptRunner(pipes, endProcess = { l.kill(made) }, pid = { 7 })
            runner.set(made)
            made
        }
        val outcome = coroutineScope {
            val running = async { engine(launcher).run("var t = Date.now(); while (Date.now() - t < 3000) {} 1", null, null, 5_000) }
            delay(500)
            // The quiet end of the process: an error nothing caught, every run told, then the process ends.
            runner.get().failAll(OutOfMemoryError())
            launcher.handles.forEach { launcher.kill(it.binder) }
            running.await()
        }
        assertEquals(IsolatedJsEngine.OUT_OF_MEMORY, assertIs<JsOutcome.Stopped>(outcome).reason)
    }

    @Test(timeout = 60_000)
    fun `an answer over the caps is not used, and the process is let go of`() = blocking {
        val launcher = DirectLauncher { _, _ -> HostileRunner(pipes) as IBinder }
        val outcome = engine(launcher).run("1", null, null, 5_000)
        assertEquals(IsolatedJsEngine.UNREADABLE, assertIs<JsOutcome.Stopped>(outcome).reason)
        assertEquals(listOf("js0"), launcher.unbinds.toList())
    }

    @Test(timeout = 60_000)
    fun `a script process that ignores every limit gets twenty files, no big read, and nothing after its answer`() = blocking {
        val hostile = HostileRunner(pipes)
        val launcher = DirectLauncher { _, _ -> hostile }
        val workspace = object : JsHost {
            val written = CopyOnWriteArrayList<String>()
            val reads = AtomicInteger()
            override val canWrite = true

            override fun read(path: String): String = "b".repeat(1_001).also { reads.incrementAndGet() }

            override fun list(path: String): List<Triple<String, Boolean, Long?>> = emptyList()

            override fun write(path: String, text: String): String = path.also { written += it }
        }
        val outcome = engine(launcher, jsLimits(maxStringChars = 1_000)).run("1", null, workspace, 5_000)
        assertEquals(IsolatedJsEngine.UNREADABLE, assertIs<JsOutcome.Stopped>(outcome).reason)
        withTimeout(10_000) { while (!hostile.finished) delay(20) }
        val replies = hostile.replies.toList()
        assertEquals(List(20) { "+h$it.txt" }, replies.take(20))
        assertEquals(List(5) { "!${RunHost.TOO_MANY_FILES}" }, replies.subList(20, 25))
        assertEquals("!The file is longer than 1000 characters.", replies[25])
        assertEquals(List(5) { "!${RunHost.OVER}" } + "!${RunHost.OVER}", replies.drop(26))
        assertEquals(List(20) { "h$it.txt" }, workspace.written.toList())
        assertEquals(1, workspace.reads.get(), "the read after the answer never reached the workspace")
    }

    @Test(timeout = 60_000)
    fun `the script process's ids are never the app's own`() = blocking {
        val launcher = DirectLauncher(makeProcess = honestProcess(pipes, ends))
        val engine = engine(launcher)
        engine.run("1", null, null, 5_000)
        val ids = engine.processIds()
        assertEquals(1, ids.size)
        assertTrue(android.os.Process.myPid() !in ids)
    }
}
