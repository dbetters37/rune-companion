package com.aicontrolcenter.app.routines

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.aicontrolcenter.framework.routines.RoutineAlarmPort

/**
 * The routines' one alarm and their receivers (Phase 6, owner decision Q1, plan Appendix A, Tier B; WP17).
 *
 * One alarm goes off at the earliest next due time of the routines that are on; [RoutineAlarmReceiver] then asks the
 * foreground service to run what is due. When the owner allowed "Alarms and reminders" for the app
 * (`AlarmManager.canScheduleExactAlarms()`, Android 12 and later; always on Android 9 to 11) the alarm is exact
 * (`setExactAndAllowWhileIdle`): it goes off at the routine's time even while the phone dozes, and Android lets the app
 * start its foreground service from it. Otherwise it is inexact (`setAndAllowWhileIdle`): Android may hold it back, and
 * may not let the app start work from it; the run then waits for the owner's tap ("Tap to start", [RoutineNotifications]).
 *
 * The time of the alarm is also kept in a small private file ([PREFERENCES], one number, nothing else), so
 * [BootReceiver] can set it again after the phone restarts without opening the app's data. The two receivers are
 * enabled only while at least one routine exists ([receiversEnabled]): a phone without routines never starts the app for
 * them.
 *
 * @param sdkInt the Android version (injected for tests of other versions).
 */
class AndroidRoutineAlarms(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : RoutineAlarmPort {
    private val alarms: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    /** Whether alarms go off at their exact time: the owner allowed "Alarms and reminders" (Android 12 and later). */
    fun exact(): Boolean {
        if (sdkInt < Build.VERSION_CODES.S) return true
        return try {
            alarms?.canScheduleExactAlarms() == true
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun arm(atMs: Long) {
        remember(atMs)
        set(atMs)
    }

    /** Sets the alarm again at the time last armed (after a restart, or once exact alarms are allowed); none: nothing. */
    fun armAgain() {
        val at = remembered() ?: return
        set(at)
    }

    private fun set(atMs: Long) {
        val manager = alarms ?: return
        val operation = operation(context)
        if (exact()) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
                return
            } catch (_: SecurityException) {
                // The owner took "Alarms and reminders" away between the check and the call: an inexact alarm instead.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
    }

    override fun cancel() {
        forget()
        alarms?.cancel(operation(context))
    }

    override fun receiversEnabled(enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (receiver in RECEIVERS) {
            val component = ComponentName(context, receiver)
            try {
                if (context.packageManager.getComponentEnabledSetting(component) == state) continue
                context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            } catch (_: RuntimeException) {
                // Android refused (it should not for the app's own component); the receiver keeps its earlier state.
            }
        }
    }

    private fun remember(atMs: Long) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putLong(KEY_AT, atMs).apply()
    }

    private fun forget() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(KEY_AT).apply()
    }

    /** The time last armed, or null when no alarm is set. */
    fun remembered(): Long? = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).let { prefs ->
        if (prefs.contains(KEY_AT)) prefs.getLong(KEY_AT, 0) else null
    }

    /** Names. */
    companion object {
        /** The action of the alarm's intent; [RoutineAlarmReceiver] answers nothing else. */
        const val ACTION_ROUTINE_ALARM: String = "com.aicontrolcenter.app.action.ROUTINE_ALARM"

        /** Where the time of the one alarm is kept: one number, never anything about a routine. */
        const val PREFERENCES: String = "routine_alarm"
        private const val KEY_AT = "at"
        private const val REQUEST_CODE = 7_301

        /** The receivers enabled only while a routine exists. */
        val RECEIVERS: List<Class<*>> = listOf(RoutineAlarmReceiver::class.java, BootReceiver::class.java)

        /** The alarm's one operation: the app's own receiver, by its class, with the action and nothing else. */
        fun operation(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, RoutineAlarmReceiver::class.java).setAction(ACTION_ROUTINE_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
