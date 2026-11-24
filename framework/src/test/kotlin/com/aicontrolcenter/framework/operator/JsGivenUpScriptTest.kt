package com.aicontrolcenter.framework.operator

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pass 4b, H3 (the rest of J1 in the fourth verification): a script thread that did not stop after its time limit is
 * given up. While two such threads are alive, a new script is refused, and the refusal says so without promising that
 * waiting helps (a thread caught in a loop of built-ins never ends by itself; docs/LIMITATIONS.md). A thread that was
 * given up runs at the lowest priority from then on.
 *
 * TEST SAFETY: no script here loops. A run is kept from ending by a host function of this test that waits for the
 * test and does not listen to the sandbox's interrupt; the test lets it go at its end, also when an assertion fails,
 * and the wait has a bound of its own.
 */
class JsGivenUpScriptTest {
    @Test(timeout = 60_000)
    fun `after two scripts that did not stop a new one is refused with the plain text, and the given-up threads run at the lowest priority`() {
        val release = CountDownLatch(1)
        val threads = CopyOnWriteArrayList<Thread>()
        val host = object : JsHost {
            override val canWrite = false

            override fun read(path: String): String {
                threads += Thread.currentThread()
                val until = System.nanoTime() + 40_000_000_000L
                while (release.count > 0 && System.nanoTime() < until) {
                    try {
                        release.await(50, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        // Deaf to the sandbox giving the run up: this host function does not stop.
                    }
                }
                return "x"
            }

            override fun list(path: String): List<Triple<String, Boolean, Long?>> = emptyList()

            override fun write(path: String, text: String): String = path
        }
        // A fresh JVM takes a while over its first script; the host function must be reached within the time limit.
        assertIs<JsOutcome.Finished>(runBlocking { JsSandbox(JsLimits(timeoutMs = 20_000)).run("1 + 1", null, null) })
        val sandbox = JsSandbox(JsLimits(timeoutMs = 1_000))
        try {
            repeat(2) { n ->
                val outcome = runBlocking { sandbox.run("workspace.read('a')", null, host) }
                assertIs<JsOutcome.Stopped>(outcome, "run ${n + 1}")
                assertEquals(n + 1, threads.size, "run ${n + 1} reached the host function: $outcome")
                // Given up: from now on it gets the processor only when nothing else wants it.
                assertEquals(Thread.MIN_PRIORITY, threads[n].priority, "the priority of the thread of run ${n + 1}, given up")
            }
            assertEquals(
                JsOutcome.Stopped(
                    "Two earlier scripts have not stopped, so no new script is started. If this is still so in a minute, close and reopen the app.",
                    emptyList(),
                    false,
                ),
                runBlocking { sandbox.run("1 + 1", null, null) },
            )
        } finally {
            release.countDown()
        }
        threads.forEach { it.join(20_000) }
        // Once they have ended, scripts run again, at the usual priority.
        assertEquals("2", assertIs<JsOutcome.Finished>(runBlocking { sandbox.run("1 + 1", null, null) }).resultJson)
    }

    @Test(timeout = 60_000)
    fun `a script that ends within its time limit keeps the usual priority to its end`() {
        var priority = -1
        val host = object : JsHost {
            override val canWrite = false

            override fun read(path: String): String {
                priority = Thread.currentThread().priority
                return "x"
            }

            override fun list(path: String): List<Triple<String, Boolean, Long?>> = emptyList()

            override fun write(path: String, text: String): String = path
        }
        val outcome = runBlocking { JsSandbox(JsLimits(timeoutMs = 20_000)).run("workspace.read('a')", null, host) }
        assertEquals("\"x\"", assertIs<JsOutcome.Finished>(outcome).resultJson)
        assertEquals(Thread.NORM_PRIORITY - 1, priority)
    }
}
