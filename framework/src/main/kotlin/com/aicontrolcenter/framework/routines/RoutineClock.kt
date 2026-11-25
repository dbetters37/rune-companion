package com.aicontrolcenter.framework.routines

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.routines.RoutineSchedule
import java.time.ZoneId

/**
 * When routines are due (Phase 6, plan Appendix A; WP17), in the owner's time zone as it is now.
 *
 * Due times are local times, so a routine "at 7:00" stays at 7:00 across daylight-saving changes and when the owner
 * travels. The arithmetic is [RoutineSchedule.nextAfter]'s: a local time the clocks skip (02:30 on the night they go
 * forward) is due when the skipped hour is over (03:30); a local time that happens twice (the night they go back) is
 * due the first time only. A stored next due time is an instant, so when the zone changes ([zone] reads the phone's
 * zone each time) [realigned] moves it to the routine's local time in the new zone.
 */
class RoutineClock(private val clock: Clock, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    fun now(): Long = clock.now()

    fun zone(): ZoneId = zone.invoke()

    /** The first due time of [schedule] strictly after [afterMs]. */
    fun nextDue(schedule: RoutineSchedule, afterMs: Long): Long = schedule.nextAfter(afterMs, zone())

    /**
     * The due times of [schedule] that have come by [untilMs], starting at [firstDue] (a due time itself, the routine's
     * stored next due time): how many, the first and the latest. Null when [firstDue] is still to come.
     */
    fun overdue(schedule: RoutineSchedule, firstDue: Long, untilMs: Long): Overdue? {
        if (firstDue > untilMs) return null
        val zone = zone()
        var count = 1
        var latest = firstDue
        var next = schedule.nextAfter(latest, zone)
        while (next <= untilMs && count < MAX_COUNTED) {
            count++
            latest = next
            next = schedule.nextAfter(latest, zone)
        }
        if (next <= untilMs) {
            // Months without a run: the latest due time is found from a week back, the count stops at "many".
            var from = schedule.nextAfter(untilMs - WEEK_MS, zone)
            while (true) {
                val after = schedule.nextAfter(from, zone)
                if (after > untilMs) break
                from = after
            }
            latest = from
        }
        return Overdue(count = count, first = firstDue, latest = latest)
    }

    /**
     * The next due time [routine] should have at [nowMs] when its stored one is still to come but was computed in another
     * time zone, or null when it is right as it is (or the routine is off, or due already).
     */
    fun realigned(routine: Routine, nowMs: Long): Long? {
        val stored = routine.nextRunAt ?: return null
        if (!routine.enabled || stored <= nowMs) return null
        val expected = nextDue(routine.schedule, nowMs)
        return expected.takeIf { it != stored }
    }

    /** Due times that came while no run happened: [count] of them (at most [MAX_COUNTED]), from [first] to [latest]. */
    data class Overdue(val count: Int, val first: Long, val latest: Long)

    /** Bounds. */
    companion object {
        /**
         * How late a run the alarm brings may still start by itself. Later than this (the phone was off, or Android held
         * the alarm back) it waits for the owner's tap instead: work starting long after its time would surprise.
         */
        const val LATE_START_MS: Long = 30 * 60_000L

        /** How long a run that waits for the owner's tap can still be started; then it is missed. */
        const val TAP_WINDOW_MS: Long = 3 * 60 * 60_000L

        /** Due times counted one by one; beyond this the count stops. */
        const val MAX_COUNTED: Int = 1_000

        private const val WEEK_MS: Long = 7 * 24 * 60 * 60_000L

        /**
         * When the routines' one alarm should go off: the earliest next due time of the routines that are on (at most
         * [RoutineLimits.MAX_ROUTINES], oldest first), or null when the master switch is off or none is due.
         */
        fun alarmAt(routines: List<Routine>, masterSwitchOn: Boolean): Long? {
            if (!masterSwitchOn) return null
            return routines.take(RoutineLimits.MAX_ROUTINES).filter { it.enabled }.mapNotNull { it.nextRunAt }.minOrNull()
        }
    }
}
