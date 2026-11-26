package com.aicontrolcenter.framework.orchestration

import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave 3 hand-off (WP8; handoff-wp11a.md item 3): the words that open the orchestrator's status lines are public
 * constants, which the app's screens read ("Waiting to try again", "Stopped", the plan's verdict badges), so the two can
 * never drift apart; the lines the framework writes open with them.
 */
class TaskStatusLinesTest {
    @Test
    fun `the status lines the framework writes open with the public words`() {
        assertEquals("Waiting:", TaskStatusLines.WAITING)
        assertEquals("Was waiting:", TaskStatusLines.WAS_WAITING)
        assertEquals("Verdict: ", TaskStatusLines.VERDICT)

        val line = ProviderWaits.text("xAI", null, at = 10 * 60 * 60_000L + 45 * 60_000L, zone = ZoneOffset.UTC)
        assertEquals("Waiting: xAI is not answering; trying again at 10:45.", line)
        assertTrue(line.startsWith(TaskStatusLines.WAITING))
        assertEquals("xAI is not answering", ProviderWaits.waitingFor(line))
        assertEquals(TaskStatusLines.WAITING, ProviderWaits.WAITING)
    }
}
