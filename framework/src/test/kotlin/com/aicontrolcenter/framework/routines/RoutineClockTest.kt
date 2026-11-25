package com.aicontrolcenter.framework.routines

import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.testkit.FakeClock
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Due times in the owner's zone: daylight-saving changes, a change of zone, weekdays, and runs missed in a row. */
class RoutineClockTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun at(text: String, zone: ZoneId = berlin): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun clockAt(text: String, zone: () -> ZoneId = { berlin }) = RoutineClock(FakeClock(at(text)), zone)

    @Test
    fun `a daily routine stays at its local time when the clocks change`() {
        val clock = clockAt("2026-03-28T08:00")
        val daily = RoutineSchedule.Daily(7 * 60)
        // Saturday 28 March 2026 07:00 CET, then Sunday 29 March 07:00 CEST: 23 hours apart, both 7:00 on the wall.
        val sunday = clock.nextDue(daily, at("2026-03-28T07:00"))
        assertEquals(at("2026-03-29T07:00"), sunday)
        assertEquals(23 * 3_600_000L, sunday - at("2026-03-28T07:00"))
        // And back in October: 25 hours apart.
        assertEquals(at("2026-10-25T07:00"), clock.nextDue(daily, at("2026-10-24T07:00")))
        assertEquals(25 * 3_600_000L, at("2026-10-25T07:00") - at("2026-10-24T07:00"))
    }

    @Test
    fun `a skipped local time runs after the skipped hour, a doubled one only the first time`() {
        val clock = clockAt("2026-03-28T08:00")
        val night = RoutineSchedule.Daily(2 * 60 + 30)
        // 29 March 2026: 02:00 to 03:00 does not exist in Berlin.
        assertEquals(LocalDateTime.parse("2026-03-29T03:30").atZone(berlin).toInstant().toEpochMilli(), clock.nextDue(night, at("2026-03-28T03:00")))
        // 25 October 2026: 02:00 to 03:00 happens twice; 02:30 is due the first time only.
        val first = clock.nextDue(night, at("2026-10-24T03:00"))
        val second = clock.nextDue(night, first)
        assertEquals(at("2026-10-26T02:30"), second, "the doubled 02:30 runs once; the next is the following night")
    }

    @Test
    fun `every few hours starts again each day and keeps to the chosen weekdays`() {
        val clock = clockAt("2026-11-23T05:00")
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        val schedule = RoutineSchedule.EveryHours(5, startMinuteOfDay = 6 * 60, days = weekdays)
        val times = generateSequence(clock.nextDue(schedule, at("2026-11-23T05:00"))) { clock.nextDue(schedule, it) }.take(6).toList()
        assertEquals(
            listOf("2026-11-23T06:00", "2026-11-23T11:00", "2026-11-23T16:00", "2026-11-23T21:00", "2026-11-25T06:00", "2026-11-25T11:00").map { at(it) },
            times,
        )
    }

    @Test
    fun `missed due times are counted, with the first and the latest`() {
        val clock = clockAt("2026-11-25T09:10")
        val hourly = RoutineSchedule.EveryHours(1)
        assertNull(clock.overdue(hourly, at("2026-11-25T10:00"), at("2026-11-25T09:10")), "not due yet")
        val overdue = clock.overdue(hourly, at("2026-11-25T06:00"), at("2026-11-25T09:10"))!!
        assertEquals(RoutineClock.Overdue(count = 4, first = at("2026-11-25T06:00"), latest = at("2026-11-25T09:00")), overdue)
        // Months away: the count stops, the latest is still right.
        val long = clock.overdue(hourly, at("2025-01-01T00:00"), at("2026-11-25T09:10"))!!
        assertEquals(RoutineClock.MAX_COUNTED, long.count)
        assertEquals(at("2026-11-25T09:00"), long.latest)
    }

    @Test
    fun `a next due time made in another zone is moved to the routine's local time`() {
        var zone = berlin
        val clock = RoutineClock(FakeClock(at("2026-11-25T05:00")), { zone })
        val daily = RoutineSchedule.Daily(7 * 60)
        val routine = Routine(
            id = RoutineId("rtn_1"), title = "Morning news", objectiveText = "Summarise the news", schedule = daily,
            createdAt = 0, updatedAt = 0, nextRunAt = at("2026-11-25T07:00"),
        )
        assertNull(clock.realigned(routine, clock.now()), "same zone: nothing to move")
        // The owner flies to Tokyo: 7:00 there is the next 7:00 after now.
        zone = tokyo
        assertEquals(at("2026-11-26T07:00", tokyo), clock.realigned(routine, clock.now()))
        assertNull(clock.realigned(routine.copy(enabled = false, nextRunAt = null), clock.now()))
    }

    @Test
    fun `the one alarm is for the earliest routine that is on, and none while routines are switched off`() {
        fun routine(id: String, next: Long?, enabled: Boolean = true) = Routine(
            id = RoutineId(id), title = id, objectiveText = "x", schedule = RoutineSchedule.Daily(0), enabled = enabled,
            createdAt = 0, updatedAt = 0, nextRunAt = next,
        )
        val list = listOf(routine("a", 300), routine("b", 200), routine("c", null, enabled = false))
        assertEquals(200L, RoutineClock.alarmAt(list, masterSwitchOn = true))
        assertNull(RoutineClock.alarmAt(list, masterSwitchOn = false))
        assertNull(RoutineClock.alarmAt(emptyList(), masterSwitchOn = true))
    }
}
