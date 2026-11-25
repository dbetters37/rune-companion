package com.aicontrolcenter.app.scripts

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.framework.operator.JsHost
import com.aicontrolcenter.framework.operator.JsLimits
import com.aicontrolcenter.framework.operator.JsOutcome
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 6 (WP6; spike F-B, F-G, Q8): the script process's half, driven as the app drives it. Its watchdog is per run
 * (F-G: a shared one ended a later, legitimate run), it ends its process only for a run that did not end cleanly, it
 * answers a request it cannot read without running anything, and its quiet end tells every run before the process goes.
 *
 * TEST SAFETY: scripts end by themselves or at their budget; the one run held past its time is held by a host function
 * of this test with a bound of its own, let go at the end of the test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScriptRunnerTest {
    private val pipes = TestPipes()
    private val ends = AtomicInteger()
    private val runner = ScriptRunner(pipes, endProcess = { ends.incrementAndGet() }, pid = { 99 })
    private val release = CountDownLatch(1)
    private val io = Executors.newCachedThreadPool()

    @After
    fun stop() {
        release.countDown()
        io.shutdownNow()
    }

    /** What a run tells the app, in order. */
    private class Heard : IScriptResult.Stub() {
        val events = CopyOnWriteArrayList<String>()
        val outcomes = CopyOnWriteArrayList<ParcelFileDescriptor>()

        override fun started() {
            events += "started"
        }

        override fun done(status: Int, outcome: ParcelFileDescriptor?) {
            events += "done $status"
            outcome?.let { outcomes += it }
        }
    }

    private fun send(code: String, limits: JsLimits, host: JsHost? = null, heard: Heard = Heard()): Heard {
        val pipe = pipes.open()
        val runHost = host?.let { RunHost(it, limits, pipes, io, deadlineMs = { Long.MAX_VALUE }) }
        runner.run(pipe.read, runHost, heard)
        pipes.writer(pipe.write).use { ScriptFrames.writeRequest(it, ScriptFrames.Request(limits, code, null, host != null, false)) }
        return heard
    }

    private fun outcome(heard: Heard, limits: JsLimits): ScriptFrames.Outcome {
        val end = System.nanoTime() + 30_000_000_000L
        while (heard.outcomes.isEmpty() && System.nanoTime() < end) Thread.sleep(20)
        return pipes.reader(heard.outcomes.first()).use { ScriptFrames.readOutcome(it, limits) }
    }

    @Test(timeout = 60_000)
    fun `a run says when its code begins, then answers through a pipe, and its process stays`() {
        val limits = JsLimits()
        val heard = send("[/a+/.test('caa'), 1 + 1]", limits)
        val answer = outcome(heard, limits)
        assertEquals(JsOutcome.Finished("[true,2]", false, emptyList(), false), answer.outcome)
        assertTrue(answer.threadEnded)
        assertEquals(listOf("started", "done ${ScriptStatus.OUTCOME}"), heard.events.toList())
        Thread.sleep(200)
        assertEquals(0, ends.get())
        assertEquals(99, runner.pid())
    }

    @Test(timeout = 60_000)
    fun `each run has its own watchdog, and one that ended cleanly never ends a run that is still going`() {
        val noBudget = JsLimits(maxInstructions = Long.MAX_VALUE)
        // Ends at once, cleanly: its watchdog's deadline (100 ms + grace + 3 s) passes while the next run still works.
        val first = send("1", noBudget.copy(timeoutMs = 100))
        assertIs<JsOutcome.Finished>(outcome(first, noBudget).outcome)
        val second = send("var t = Date.now(); while (Date.now() - t < 6500) {} 'still here'", noBudget.copy(timeoutMs = 10_000))
        val answer = outcome(second, noBudget)
        assertEquals("\"still here\"", assertIs<JsOutcome.Finished>(answer.outcome).resultJson)
        assertEquals(0, ends.get(), "no watchdog ended the process")
    }

    @Test(timeout = 60_000)
    fun `a run given up while its thread still runs tells the app, and its watchdog ends the process at its bound`() {
        val deaf = object : JsHost {
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
        val limits = JsLimits(timeoutMs = 200)
        val start = System.nanoTime()
        val heard = send("workspace.read('a')", limits, deaf)
        val answer = outcome(heard, limits)
        assertIs<JsOutcome.Stopped>(answer.outcome)
        assertEquals(false, answer.threadEnded, "the app is told the thread still runs, and lets the process go")
        val end = System.nanoTime() + 15_000_000_000L
        while (ends.get() == 0 && System.nanoTime() < end) Thread.sleep(20)
        val tookMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(1, ends.get())
        assertTrue(tookMs >= 200 + ScriptWatchdog.PAST_BOUND_MS - 500, "ended after $tookMs ms")
        assertEquals(listOf("started", "done ${ScriptStatus.OUTCOME}", "done ${ScriptStatus.TIMED_OUT}"), heard.events.toList())
    }

    @Test(timeout = 60_000)
    fun `a request it cannot read runs nothing and keeps the process`() {
        val pipe = pipes.open()
        val heard = Heard()
        runner.run(pipe.read, null, heard)
        pipes.writer(pipe.write).use { it.write(ByteArray(64) { 7 }) }
        val end = System.nanoTime() + 10_000_000_000L
        while (heard.events.isEmpty() && System.nanoTime() < end) Thread.sleep(20)
        assertEquals(listOf("done ${ScriptStatus.BAD_REQUEST}"), heard.events.toList())
        Thread.sleep(200)
        assertEquals(0, ends.get())
    }

    @Test(timeout = 60_000)
    fun `the quiet end tells every run that is still going, by status alone`() {
        val noBudget = JsLimits(maxInstructions = Long.MAX_VALUE)
        val heard = send("var t = Date.now(); while (Date.now() - t < 1500) {} 1", noBudget)
        val end = System.nanoTime() + 10_000_000_000L
        while (heard.events.isEmpty() && System.nanoTime() < end) Thread.sleep(20)
        runner.failAll(OutOfMemoryError())
        assertEquals(listOf("started", "done ${ScriptStatus.OUT_OF_MEMORY}"), heard.events.toList())
        val other = send("var t = Date.now(); while (Date.now() - t < 1500) {} 1", noBudget)
        while (other.events.isEmpty() && System.nanoTime() < end) Thread.sleep(20)
        runner.failAll(IllegalStateException("x"))
        assertTrue("done ${ScriptStatus.FAILED}" in other.events, "${other.events}")
    }
}
