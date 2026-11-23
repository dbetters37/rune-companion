package com.aicontrolcenter.core.tasks

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WaitClockTest {
    @Test
    fun `time spent waiting for the owner does not count against the limit`() = runTest {
        // Task limit one minute; the owner answers after five.
        val result = withPausableTimeout(60_000, testTimeSource) {
            delay(40_000)
            WaitClock.pausedWhile { delay(5 * 60_000) }
            delay(19_000)
            "done"
        }
        assertEquals("done", result)
    }

    @Test
    fun `unpaused time still runs out, counted before and after a pause`() = runTest {
        val late = withPausableTimeout(60_000, testTimeSource) {
            delay(40_000)
            WaitClock.pausedWhile { delay(300_000) }
            delay(21_000)
            "late"
        }
        assertNull(late)
        assertEquals(360_000, testScheduler.currentTime)
        assertNull(withPausableTimeout(1_000, testTimeSource) { delay(1_001) })
        assertNull(withPausableTimeout(0, testTimeSource) { "never runs" })
    }

    @Test
    fun `a pause inside nested limits pauses every one of them, and is reported`() = runTest {
        var paused = -1L
        val result = withPausableTimeout(120_000, testTimeSource) {
            withPausableTimeout(60_000, testTimeSource) {
                delay(30_000)
                WaitClock.pausedWhile { delay(300_000) }
                paused = WaitClock.current()!!.pausedMs()
                delay(25_000)
                "ran"
            }
        }
        assertEquals("ran", result)
        assertEquals(300_000, paused)
    }

    @Test
    fun `outside any limit a pause just runs, and failures pass through`() = runTest {
        assertEquals(3, WaitClock.pausedWhile { 3 })
        assertNull(WaitClock.current())
        assertFailsWith<IllegalStateException> { withPausableTimeout(10_000, testTimeSource) { error("boom") } }
        assertFailsWith<IllegalStateException> { withPausableTimeout(10_000, testTimeSource) { WaitClock.pausedWhile { error("boom") } } }
        // The clock is not left paused by a failure.
        assertEquals("next", withPausableTimeout(10_000, testTimeSource) { delay(9_000); "next" })
    }

    @Test
    fun `a time source that does not match the delays can only lengthen the limit`() = runTest {
        // Monotonic real time against virtual delays: pauses count as nothing, yet the limit still ends.
        assertEquals("ok", withPausableTimeout(60_000) { WaitClock.pausedWhile { delay(300_000) }; delay(59_000); "ok" })
        assertNull(withPausableTimeout(60_000) { delay(61_000) })
    }
}
