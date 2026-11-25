package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 6 (WP6, D2): a script's time starts at its own code. Getting the engine ready (a thread, Rhino's context and
 * the sandbox's guards; seconds on a slow phone, up to 29 s on the software emulator) has a bound of its own,
 * [JsLimits.engineStartMs], and is not taken from the script. The slow start is the sandbox's test hook, which runs on
 * the script's thread just before the script's code.
 *
 * TEST SAFETY: no script here loops without a budget; a slow start sleeps for a bounded time, and a host function that
 * holds a run is let go at the end of its test.
 */
class JsSandboxStartTest {
    private fun sandbox(limits: JsLimits, slowStartMs: Long = 0) = JsSandbox(limits) { if (slowStartMs > 0) Thread.sleep(slowStartMs) }

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000

    @Test(timeout = 60_000)
    fun `a slow start is not taken from the script's time`() {
        // Before D2 the whole run had its time limit plus the grace (3 s here) from the thread's start, and this run was given up.
        val outcome = runBlocking {
            sandbox(JsLimits(timeoutMs = 1_000), slowStartMs = 3_500).run("var s = 0; for (var i = 0; i < 100000; i++) s += i; s", null, null)
        }
        assertEquals("4999950000", assertIs<JsOutcome.Finished>(outcome, "$outcome").resultJson)
    }

    @Test(timeout = 60_000)
    fun `a script that does not stop is stopped at its time limit, counted from its own code`() {
        val start = System.nanoTime()
        val outcome = runBlocking { sandbox(JsLimits(timeoutMs = 500, maxInstructions = Long.MAX_VALUE), slowStartMs = 1_500).run("for (;;) {}", null, null) }
        val took = elapsedMs(start)
        assertEquals("The script ran longer than 500 ms.", assertIs<JsOutcome.Stopped>(outcome).reason)
        assertTrue(took >= 1_900, "stopped after $took ms: before its own time was up")
        assertTrue(took < 1_500 + 500 + JsSandbox.GRACE_MS + 2_000, "stopped after $took ms")
    }

    @Test(timeout = 60_000)
    fun `getting ready has a bound of its own, and a run not ready by then runs nothing`() {
        val host = RecordingHost()
        val events = RecordingEvents()
        val start = System.nanoTime()
        val outcome = runBlocking {
            sandbox(JsLimits(timeoutMs = 5_000, engineStartMs = 500), slowStartMs = 2_000).run("workspace.write('ran.txt', 'x')", null, host, 5_000, events)
        }
        val took = elapsedMs(start)
        val reason = assertIs<JsOutcome.Stopped>(outcome).reason
        assertEquals(JsSandbox.engineSlow(500), reason)
        assertTrue("Nothing of the script ran." in reason, reason)
        assertTrue(took < 1_800, "answered after $took ms")
        assertEquals(listOf("abandoned"), events.seen.toList())
        // The given-up start ends by itself and runs none of the script's code.
        Thread.sleep(2_500)
        assertEquals(emptyList(), host.calls.toList())
        assertEquals(listOf("abandoned"), events.seen.toList())
    }

    @Test(timeout = 60_000)
    fun `the events say when the script's code begins, and when a run is given up while its thread runs`() {
        val events = RecordingEvents()
        val host = RecordingHost(events.seen)
        val finished = runBlocking { JsSandbox(JsLimits()).run("workspace.list(''); 1", null, host, 5_000, events) }
        assertIs<JsOutcome.Finished>(finished)
        // Once, before the script's first host call; a run that ended is not given up.
        assertEquals(listOf("code", "list"), events.seen.toList())

        // A host function deaf to the sandbox's interrupt keeps the thread past the grace: the run is given up.
        val release = CountDownLatch(1)
        val deaf = object : JsHost {
            override val canWrite = false

            override fun read(path: String): String {
                val until = System.nanoTime() + 30_000_000_000L
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
        val given = RecordingEvents()
        try {
            val outcome = runBlocking { JsSandbox(JsLimits()).run("workspace.read('a')", null, deaf, 300, given) }
            assertIs<JsOutcome.Stopped>(outcome)
            assertEquals(listOf("code", "abandoned"), given.seen.toList())
        } finally {
            release.countDown()
        }
    }

    @Test(timeout = 60_000)
    fun `the script's thread carries the sandbox's class loader, also when started from a thread without one`() {
        val loader = AtomicReference<ClassLoader?>()
        val outcome = AtomicReference<JsOutcome>()
        val sandbox = JsSandbox(JsLimits()) { loader.set(Thread.currentThread().contextClassLoader) }
        // A binder thread of the script process has no context class loader (spike F-B).
        val bare = Thread { outcome.set(runBlocking { sandbox.run("/a+/.test('caa')", null, null) }) }
        bare.contextClassLoader = null
        bare.start()
        bare.join(30_000)
        assertEquals("true", assertIs<JsOutcome.Finished>(outcome.get(), "${outcome.get()}").resultJson)
        assertTrue(loader.get() === JsSandbox::class.java.classLoader, "the script thread's loader: ${loader.get()}")
    }

    @Test(timeout = 60_000)
    fun `preparing Rhino on a thread is harmless and can be repeated`() {
        JsSandbox.prepareOnThisThread()
        JsSandbox.prepareOnThisThread()
        assertEquals("2", assertIs<JsOutcome.Finished>(runBlocking { JsSandbox().run("1 + 1", null, null) }).resultJson)
    }

    @Test
    fun `the engine's note follows the answer`() {
        val f = OperatorFixture()
        val engine = object : JsEngine {
            override suspend fun run(code: String, inputJson: String?, host: JsHost?, timeoutMs: Long): JsOutcome =
                JsOutcome.Finished("1", false, listOf("hi"), false).withNote("Ran somewhere else.")
        }
        val tool = OperatorToolset.create(f.dependencies, engine).single { it.spec.id.value == "scripts.run_js" }
        val outcome = runBlocking {
            val arguments = json("code" to "1")
            tool.execute(arguments, f.context, tool.assess(arguments, f.context))
        }
        assertEquals("Result: 1\n--- console ---\nhi\n(Ran somewhere else.)", outcome.content)
        assertEquals(null, JsOutcome.Stopped("r", emptyList(), false).note)
        assertEquals("n", JsOutcome.ScriptError("m", 1, emptyList(), false).withNote("n").note)
    }

    private class RecordingEvents : JsRunEvents {
        val seen = CopyOnWriteArrayList<String>()

        override fun codeStarted() {
            seen += "code"
        }

        override fun abandoned() {
            seen += "abandoned"
        }
    }

    private class RecordingHost(val calls: MutableList<String> = CopyOnWriteArrayList()) : JsHost {
        override val canWrite = true

        override fun read(path: String): String {
            calls += "read"
            return ""
        }

        override fun list(path: String): List<Triple<String, Boolean, Long?>> {
            calls += "list"
            return emptyList()
        }

        override fun write(path: String, text: String): String {
            calls += "write"
            return path
        }
    }
}
