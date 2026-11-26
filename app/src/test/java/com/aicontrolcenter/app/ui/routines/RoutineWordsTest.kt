package com.aicontrolcenter.app.ui.routines

import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.routines.RoutineSchedule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Routines screens' words (WP10c): outcomes (handoff-wp17.md §6), schedules, next times and the clock changes. */
class RoutineWordsTest {
    private val utc = ZoneOffset.UTC
    private fun at(text: String, zone: ZoneId = utc) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `every outcome has its words and a tone, so none rests on colour`() {
        assertEquals(
            listOf(
                "Started", "Skipped: the last run is not finished", "Skipped: today's limit", "Skipped: local-only mode",
                "Could not start", "Waiting for you", "Missed",
            ),
            RoutineRunOutcome.entries.map(RoutineWords::outcome),
        )
        assertEquals(RoutineRunOutcome.entries.size, RoutineRunOutcome.entries.map(RoutineWords::tone).size)
    }

    @Test
    fun `schedules read as a sentence`() {
        assertEquals("Every day at 07:00", RoutineWords.schedule(RoutineSchedule.Daily(7 * 60)))
        assertEquals("At weekends at 09:15", RoutineWords.schedule(RoutineSchedule.Daily(9 * 60 + 15, setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))))
        assertEquals(
            "On Mondays and Thursdays at 18:30",
            RoutineWords.schedule(RoutineSchedule.Daily(18 * 60 + 30, setOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY))),
        )
        assertEquals("Every hour from 00:00, every day", RoutineWords.schedule(RoutineSchedule.EveryHours(1)))
        assertEquals("06:00, 11:00, 16:00 and 21:00", RoutineWords.timesOfDay(RoutineSchedule.EveryHours(5, 6 * 60)))
        assertEquals(4, RoutineWords.runsPerDay(RoutineSchedule.EveryHours(5, 6 * 60)))
    }

    @Test
    fun `a time is today, tomorrow, yesterday or a named day`() {
        val now = at("2026-11-14T12:00")
        assertEquals("today at 18:00", RoutineWords.at(at("2026-11-14T18:00"), now, utc))
        assertEquals("tomorrow at 07:00", RoutineWords.at(at("2026-11-15T07:00"), now, utc))
        assertEquals("yesterday at 07:00", RoutineWords.at(at("2026-11-13T07:00"), now, utc))
        assertEquals("Monday 16 Nov at 06:00", RoutineWords.at(at("2026-11-16T06:00"), now, utc))
    }

    @Test
    fun `the clock changes are said for the times they touch, in the owner's zone`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val winter = at("2026-11-14T12:00", berlin)
        assertEquals(
            "On Sunday 28 March the clocks go forward: 02:30 does not happen that night, so it runs at 03:30.",
            RoutineWords.clockChange(RoutineSchedule.Daily(2 * 60 + 30), winter, berlin),
        )
        val summer = at("2027-06-01T12:00", berlin)
        assertEquals(
            "On Sunday 31 October the clocks go back: 02:30 comes twice that night, and it runs only the first time.",
            RoutineWords.clockChange(RoutineSchedule.Daily(2 * 60 + 30), summer, berlin),
        )
        // Every hour from 00:00 meets both nights.
        assertTrue(RoutineWords.clockChange(RoutineSchedule.EveryHours(1), winter, berlin)!!.startsWith("On Sunday 28 March the clocks go forward: 02:00"))
        assertNull(RoutineWords.clockChange(RoutineSchedule.Daily(7 * 60), winter, berlin), "07:00 is never skipped or doubled")
        assertNull(
            RoutineWords.clockChange(RoutineSchedule.Daily(2 * 60 + 30, setOf(DayOfWeek.MONDAY)), winter, berlin),
            "a Monday routine does not run on the Sunday of the change",
        )
        assertNull(RoutineWords.clockChange(RoutineSchedule.Daily(2 * 60 + 30), winter, utc), "UTC never changes")
    }

    @Test
    fun `failures in a row are counted in words`() {
        assertNull(RoutineWords.failures(0))
        assertEquals("1 failed run in a row. After 3 in a row it switches itself off.", RoutineWords.failures(1))
        assertEquals("2 failed runs in a row. After 3 in a row it switches itself off.", RoutineWords.failures(2))
    }
}
