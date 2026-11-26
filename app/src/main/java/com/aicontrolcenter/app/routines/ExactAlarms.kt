package com.aicontrolcenter.app.routines

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Whether Android lets routines start at their exact time (Phase 6, owner decision Q1, Appendix A; WP10c): the
 * "Alarms and reminders" permission of Android 12 and later (`SCHEDULE_EXACT_ALARM`, which the owner grants on Android's
 * own page). Without it the routine's alarm is inexact and nothing starts by itself: a "Tap to start" notification comes
 * instead (WP17's [RoutineAlarmReceiver]).
 */
enum class ExactAlarmState {
    /** Android 12 and later, and the owner allowed "Alarms and reminders". */
    ALLOWED,

    /** Android 12 and later, and "Alarms and reminders" is not allowed (the usual state after installing on Android 14 and later). */
    NOT_ALLOWED,

    /** Android 9 to 11: alarms are always exact, there is nothing to allow. */
    ALWAYS,
}

/** Reads [ExactAlarmState] and names Android's page where the owner changes it. */
interface ExactAlarmAccess {
    /** The state now; read again when the owner comes back from Android's page. */
    fun state(): ExactAlarmState

    /** Android's "Alarms and reminders" page for this app, or null when there is none (Android 9 to 11). */
    fun settingsIntent(): Intent?

    /** A fixed state, for tests and previews. */
    class Fixed(var current: ExactAlarmState) : ExactAlarmAccess {
        /** How often the page was asked for. */
        var opened: Int = 0
            private set

        override fun state(): ExactAlarmState = current

        override fun settingsIntent(): Intent? {
            opened++
            return if (current == ExactAlarmState.ALWAYS) null else Intent(TEST_ACTION)
        }

        companion object {
            /** What [Fixed.settingsIntent] returns, so a test can see the page was asked for. */
            const val TEST_ACTION: String = "test.ALARMS_AND_REMINDERS"
        }
    }
}

/**
 * [ExactAlarmAccess] on the phone: `AlarmManager.canScheduleExactAlarms()` (the same check [AndroidRoutineAlarms.exact]
 * makes before it sets the alarm), and `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` for this package. It reads only this
 * app's own permission; nothing of another app.
 *
 * @param sdkInt the Android version (injected for tests of other versions).
 */
class AndroidExactAlarmAccess(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ExactAlarmAccess {
    override fun state(): ExactAlarmState {
        if (sdkInt < Build.VERSION_CODES.S) return ExactAlarmState.ALWAYS
        val allowed = try {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } catch (_: RuntimeException) {
            false
        }
        return if (allowed) ExactAlarmState.ALLOWED else ExactAlarmState.NOT_ALLOWED
    }

    override fun settingsIntent(): Intent? {
        if (sdkInt < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))
    }
}
