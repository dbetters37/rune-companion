package com.aicontrolcenter.core.routines

import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.pickup.PickUpLimits
import com.aicontrolcenter.core.pickup.PickedUpFile
import com.aicontrolcenter.core.PickUpRuleId
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 6, Appendix A: when routines are due, and the bounds of routines and folder pick-up. */
class RoutineModelTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(text: String, zone: ZoneId = berlin) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a daily routine is due at its local time on its days`() {
        val sevenOnWeekdays = RoutineSchedule.Daily(7 * 60, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        // 2026-11-25 is a Wednesday.
        assertEquals(at("2026-11-25T07:00"), sevenOnWeekdays.nextAfter(at("2026-11-25T06:59"), berlin))
        assertEquals(at("2026-11-26T07:00"), sevenOnWeekdays.nextAfter(at("2026-11-25T07:00"), berlin), "strictly after")
        assertEquals(at("2026-11-30T07:00"), sevenOnWeekdays.nextAfter(at("2026-11-27T08:00"), berlin), "Friday after seven: next Monday")
        val sundaysAtMidnight = RoutineSchedule.Daily(0, setOf(DayOfWeek.SUNDAY))
        assertEquals(at("2026-11-29T00:00"), sundaysAtMidnight.nextAfter(at("2026-11-25T12:00"), berlin))
    }

    @Test
    fun `every few hours restarts each day and keeps an hour apart`() {
        val everyFiveFromSix = RoutineSchedule.EveryHours(hours = 5, startMinuteOfDay = 6 * 60)
        assertEquals(listOf(360, 660, 960, 1260), everyFiveFromSix.minutesOn(java.time.LocalDate.of(2026, 11, 25)))
        assertEquals(at("2026-11-25T11:00"), everyFiveFromSix.nextAfter(at("2026-11-25T06:00"), berlin))
        assertEquals(at("2026-11-26T06:00"), everyFiveFromSix.nextAfter(at("2026-11-25T21:00"), berlin))
        val hourly = RoutineSchedule.EveryHours(1)
        assertEquals(24, hourly.minutesOn(java.time.LocalDate.of(2026, 11, 25)).size)
        assertFailsWith<IllegalArgumentException> { RoutineSchedule.EveryHours(0) }
        assertFailsWith<IllegalArgumentException> { RoutineSchedule.EveryHours(RoutineLimits.MAX_HOURS_APART + 1) }
        assertFailsWith<IllegalArgumentException> { RoutineSchedule.Daily(24 * 60) }
        assertFailsWith<IllegalArgumentException> { RoutineSchedule.Daily(60, emptySet()) }
    }

    @Test
    fun `clock changes move a skipped time forward and run a doubled time once`() {
        val halfPastTwo = RoutineSchedule.Daily(2 * 60 + 30)
        // 2026-03-29: Berlin skips 02:00-03:00. 02:30 does not exist and is due at 03:30 (summer time).
        val spring = halfPastTwo.nextAfter(at("2026-03-29T01:00"), berlin)
        assertEquals(LocalDateTime.parse("2026-03-29T03:30").atZone(berlin).toInstant().toEpochMilli(), spring)
        // 2026-10-25: 02:30 happens twice; the first one counts, and the next is the following day.
        val autumn = halfPastTwo.nextAfter(at("2026-10-25T01:00"), berlin)
        assertEquals(LocalDateTime.parse("2026-10-25T00:30").atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), autumn)
        assertEquals(at("2026-10-26T02:30"), halfPastTwo.nextAfter(autumn, berlin))
        // Hourly on the spring day: 02:00 is skipped onto 03:00, which runs once.
        val hourly = RoutineSchedule.EveryHours(1)
        val afterOne = hourly.nextAfter(at("2026-03-29T01:00"), berlin)
        val afterThat = hourly.nextAfter(afterOne, berlin)
        assertEquals(afterOne + 3_600_000L, afterThat)
    }

    @Test
    fun `routines and their runs are checked when they are built`() {
        val routine = Routine(
            id = RoutineId("rtn_1"), title = "Morning news", objectiveText = "Summarise the news", schedule = RoutineSchedule.Daily(420),
            createdAt = 1, updatedAt = 1,
        )
        assertFalse(routine.allowScreenActions, "screen actions are off unless ticked")
        assertNull(routine.draft.problem())
        assertNotNull(RoutineDraft("", "x", schedule = RoutineSchedule.Daily(1)).problem())
        assertNotNull(RoutineDraft("x".repeat(RoutineLimits.MAX_TITLE_CHARS + 1), "x", schedule = RoutineSchedule.Daily(1)).problem())
        assertNotNull(RoutineDraft("x", " ", schedule = RoutineSchedule.Daily(1)).problem())
        assertNotNull(RoutineDraft("x", "x", schedule = RoutineSchedule.Daily(1), approvalTimeoutMinutes = 241).problem())
        assertFailsWith<IllegalArgumentException> { routine.copy(title = " ") }
        assertFailsWith<IllegalArgumentException> { routine.copy(pausedNote = RoutineLimits.PAUSED_AFTER_FAILURES) }
        routine.copy(enabled = false, pausedNote = RoutineLimits.PAUSED_AFTER_FAILURES, consecutiveFailures = 3)
        assertFailsWith<IllegalArgumentException> {
            RoutineRun(RoutineRunId("rrn_1"), routine.id, dueAt = 5, outcome = RoutineRunOutcome.STARTED, startedAt = 6)
        }
        assertFailsWith<IllegalArgumentException> {
            RoutineRun(RoutineRunId("rrn_1"), routine.id, dueAt = 5, outcome = RoutineRunOutcome.MISSED, startedAt = 6, objectiveId = TaskId("tsk_1"))
        }
        assertEquals(listOf(RoutineRunOutcome.SKIPPED_DIRECTOR_UNAVAILABLE), RoutineRunOutcome.entries.filter { it.countsAsFailure })
        // Stored as JSON by name: the schedule keeps its kind and days.
        val json = Json.encodeToString(Routine.serializer(), routine.copy(schedule = RoutineSchedule.EveryHours(3, 60, setOf(DayOfWeek.SATURDAY))))
        assertEquals(RoutineSchedule.EveryHours(3, 60, setOf(DayOfWeek.SATURDAY)), Json.decodeFromString(Routine.serializer(), json).schedule)
    }

    @Test
    fun `folder pick-up takes text files only, within its bounds`() {
        assertTrue(PickUpLimits.accepts("text/plain"))
        assertTrue(PickUpLimits.accepts("TEXT/CSV; charset=utf-8"))
        assertFalse(PickUpLimits.accepts("text/"))
        assertFalse(PickUpLimits.accepts("application/pdf"))
        assertFalse(PickUpLimits.accepts("image/png"))
        assertFalse(PickUpLimits.accepts(null))
        assertEquals(5L * 1024 * 1024, PickUpLimits.MAX_BYTES_PER_DAY)
        assertFailsWith<IllegalArgumentException> { PickedUpFile(PickUpRuleId("pku_1"), "ABC", "a.txt", 1, 1) }
        assertFailsWith<IllegalArgumentException> { PickedUpFile(PickUpRuleId("pku_1"), "a".repeat(64), "a.txt", -1, 1) }
        PickedUpFile(PickUpRuleId("pku_1"), "0123456789abcdef".repeat(4), "a.txt", 1, 1)
    }
}
