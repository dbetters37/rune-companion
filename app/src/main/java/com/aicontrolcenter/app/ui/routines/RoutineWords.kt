package com.aicontrolcenter.app.ui.routines

import com.aicontrolcenter.app.ui.components.formatTimeOfDay
import com.aicontrolcenter.app.ui.components.localDateOf
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.routines.RoutineSchedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Everything the Routines screens say about a routine, in plain words (Phase 6, owner decision Q1, Appendix A; WP10c):
 * outcomes of a run (handoff-wp17.md §6), schedules, the next time, and what happens on the nights the clocks change.
 * Pure: the view models and their tests call it.
 */
object RoutineWords {
    /** What became of one due time, as the run history says it (handoff-wp17.md §6). */
    fun outcome(outcome: RoutineRunOutcome): String = when (outcome) {
        RoutineRunOutcome.STARTED -> "Started"
        RoutineRunOutcome.SKIPPED_STILL_RUNNING -> "Skipped: the last run is not finished"
        RoutineRunOutcome.SKIPPED_DAILY_LIMIT -> "Skipped: today's limit"
        RoutineRunOutcome.SKIPPED_LOCAL_ONLY -> "Skipped: local-only mode"
        RoutineRunOutcome.SKIPPED_DIRECTOR_UNAVAILABLE -> "Could not start"
        RoutineRunOutcome.WAITING_FOR_TAP -> "Waiting for you"
        RoutineRunOutcome.MISSED -> "Missed"
    }

    /** The tone (and so the icon) of an outcome: never colour alone, the words above always come with it. */
    fun tone(outcome: RoutineRunOutcome): StatusTone = when (outcome) {
        RoutineRunOutcome.STARTED -> StatusTone.READY
        RoutineRunOutcome.WAITING_FOR_TAP -> StatusTone.WAITING
        RoutineRunOutcome.SKIPPED_DIRECTOR_UNAVAILABLE -> StatusTone.UNAVAILABLE
        RoutineRunOutcome.MISSED -> StatusTone.DISABLED
        RoutineRunOutcome.SKIPPED_STILL_RUNNING, RoutineRunOutcome.SKIPPED_DAILY_LIMIT, RoutineRunOutcome.SKIPPED_LOCAL_ONLY -> StatusTone.ATTENTION
    }

    /** "07:05". */
    fun time(minuteOfDay: Int): String = "%02d:%02d".format(Locale.ROOT, minuteOfDay / 60, minuteOfDay % 60)

    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    /** "Monday". */
    fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    /** "Mon". */
    fun shortDayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    /** "every day", "on weekdays", "at weekends", "on Mondays and Thursdays". */
    fun days(days: Set<DayOfWeek>): String = when (days) {
        DayOfWeek.entries.toSet() -> "every day"
        WEEKDAYS -> "on weekdays"
        WEEKEND -> "at weekends"
        else -> "on " + joinWords(days.sorted().map { dayName(it) + "s" })
    }

    /** "Every day at 07:00", "On weekdays at 07:30", "Every 3 hours from 06:00, every day". */
    fun schedule(schedule: RoutineSchedule): String = when (schedule) {
        is RoutineSchedule.Daily -> days(schedule.days).replaceFirstChar { it.uppercase() } + " at " + time(schedule.minuteOfDay)
        is RoutineSchedule.EveryHours ->
            (if (schedule.hours == 1) "Every hour" else "Every ${schedule.hours} hours") + " from " + time(schedule.startMinuteOfDay) + ", " + days(schedule.days)
    }

    /** "06:00, 11:00, 16:00 and 21:00": the times of one day of an every-few-hours schedule. */
    fun timesOfDay(schedule: RoutineSchedule.EveryHours): String {
        val minutes = (schedule.startMinuteOfDay until RoutineSchedule.MINUTES_PER_DAY step schedule.hours * 60).toList()
        return joinWords(minutes.map(::time))
    }

    /** How many runs a day an every-few-hours schedule makes. */
    fun runsPerDay(schedule: RoutineSchedule.EveryHours): Int =
        (schedule.startMinuteOfDay until RoutineSchedule.MINUTES_PER_DAY step schedule.hours * 60).count()

    private val dayAndDate = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH)
    private val dayAndLongDate = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

    /** "today at 07:00", "tomorrow at 07:00", "Monday 1 Dec at 07:00". */
    fun at(atMs: Long, nowMs: Long, zone: ZoneId): String {
        val date = localDateOf(atMs, zone)
        val today = localDateOf(nowMs, zone)
        val time = formatTimeOfDay(atMs, zone)
        return when (date) {
            today -> "today at $time"
            today.plusDays(1) -> "tomorrow at $time"
            today.minusDays(1) -> "yesterday at $time"
            else -> dayAndDate.format(date) + " at " + time
        }
    }

    /** What the owner reads about the clock changes, whatever the schedule. */
    const val CLOCK_CHANGES: String =
        "Times are your phone's own time. When the clocks change for summer or winter time, a routine keeps its time on the " +
            "clock: a time that the change skips runs an hour later that night, and a time that comes twice runs only the first time."

    /**
     * The next change of the clocks within a year that touches [schedule] in [zone], in one sentence ("On Sunday 28 March
     * the clocks go forward: 02:30 does not happen that night, so it runs at 03:30."), or null when none does.
     */
    fun clockChange(schedule: RoutineSchedule, nowMs: Long, zone: ZoneId): String? {
        val rules = zone.rules
        var from = Instant.ofEpochMilli(nowMs)
        val until = from.plusSeconds(YEAR_SECONDS)
        repeat(MAX_TRANSITIONS) {
            val change = rules.nextTransition(from) ?: return null
            if (change.instant.isAfter(until)) return null
            from = change.instant
            // The day of the change, and the local times of that day the schedule names.
            val date = change.dateTimeBefore.toLocalDate()
            val touched = schedule.minutesOn(date).filter { minute ->
                rules.getValidOffsets(LocalDateTime.of(date, LocalTime.of(minute / 60, minute % 60))).size != 1
            }
            val minute = touched.firstOrNull() ?: return@repeat
            val day = dayAndLongDate.format(date)
            return if (change.isGap) {
                val runs = LocalDateTime.of(date, LocalTime.of(minute / 60, minute % 60)).atZone(zone).toLocalTime()
                "On $day the clocks go forward: ${time(minute)} does not happen that night, so it runs at ${time(runs.hour * 60 + runs.minute)}."
            } else {
                "On $day the clocks go back: ${time(minute)} comes twice that night, and it runs only the first time."
            }
        }
        return null
    }

    /** "a, b and c". */
    fun joinWords(words: List<String>): String = when (words.size) {
        0 -> ""
        1 -> words[0]
        else -> words.dropLast(1).joinToString(", ") + " and " + words.last()
    }

    /** What the screens say about the limits of Appendix A. */
    const val LIMITS: String =
        "You can have up to ${RoutineLimits.MAX_ROUTINES} routines. Runs of one routine are at least an hour apart, and a run " +
            "never starts while the last one is still working."

    /** What happens after failures, in words (Appendix A: pauses itself after 3). */
    fun failures(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 failed run in a row. After ${RoutineLimits.PAUSE_AFTER_FAILURES} in a row it switches itself off."
        else -> "$count failed runs in a row. After ${RoutineLimits.PAUSE_AFTER_FAILURES} in a row it switches itself off."
    }

    private const val YEAR_SECONDS: Long = 366L * 24 * 60 * 60
    private const val MAX_TRANSITIONS = 4
}
