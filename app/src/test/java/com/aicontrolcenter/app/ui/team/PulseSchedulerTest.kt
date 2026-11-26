package com.aicontrolcenter.app.ui.team

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Dots travel once per new message, at most 8 at a time, and none for what was there when the screen opened (N2). */
class PulseSchedulerTest {
    private fun pulse(key: String) = TeamPulse(key, "a", "b", "a|b", LinkKind.ASKS)

    @Test
    fun `history never travels, a new message travels once and is gone after its time`() {
        val scheduler = PulseScheduler()
        assertEquals(0, scheduler.offer(listOf(pulse("m1"), pulse("m2")), at = 0), "what was there when the screen opened")
        assertFalse(scheduler.isMoving(0))
        assertEquals(1, scheduler.offer(listOf(pulse("m1"), pulse("m2"), pulse("m3")), at = 100))
        assertEquals(listOf("m3" to 0f), scheduler.positions(100).map { it.first.pulse.key to it.second })
        assertEquals(0.5f, scheduler.positions(700).single().second, 0.001f)
        assertTrue(scheduler.isMoving(1_300))
        assertFalse(scheduler.isMoving(1_301), "arrived: the frame loop stops")
        assertEquals(emptyList(), scheduler.positions(1_400))
        assertEquals(0, scheduler.offer(listOf(pulse("m3")), at = 2_000), "a message travels only once")
    }

    @Test
    fun `at most eight in the air, later ones wait their turn, and are dropped after two seconds`() {
        val scheduler = PulseScheduler()
        scheduler.offer(emptyList(), at = 0)
        val burst = (1..20).map { pulse("m$it") }
        assertEquals(16, scheduler.offer(burst, at = 1_000), "8 leave now, 8 when the first ones arrive, 4 dropped")
        assertEquals(4, scheduler.dropped)
        assertEquals(8, scheduler.positions(1_000).size)
        assertEquals(8, scheduler.positions(2_200).size, "the first eight still arriving")
        assertEquals(8, scheduler.positions(2_300).size, "the next eight on their way")
        assertTrue(scheduler.positions(2_300).all { it.first.pulse.key.removePrefix("m").toInt() in 9..16 })
        assertEquals(16, scheduler.pending(1_000))
        assertFalse(scheduler.isMoving(3_502))
    }

    @Test
    fun `under Calm nothing travels, and nothing seen then travels later`() {
        val scheduler = PulseScheduler()
        scheduler.skip(listOf(pulse("m1")))
        scheduler.skip(listOf(pulse("m1"), pulse("m2")))
        assertEquals(0, scheduler.offer(listOf(pulse("m1"), pulse("m2")), at = 10))
        assertEquals(1, scheduler.offer(listOf(pulse("m1"), pulse("m2"), pulse("m3")), at = 20))
    }
}
