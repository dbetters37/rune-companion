package com.aicontrolcenter.app.routines

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Sets the routines' alarm again after the phone restarts, or after the owner allows exact alarms (Phase 6, owner
 * decision Q1, plan Appendix A, Tier B; WP17). Android clears every alarm at a restart. Declared not exported (system
 * broadcasts still reach it) and enabled only while a routine exists, so a phone without routines never starts the app
 * at boot.
 *
 * It only signals: it sets the one alarm again at the time the app last chose ([AndroidRoutineAlarms.armAgain], a
 * number kept in a small private file), and starts nothing. It never names the application, the app's object graph or
 * the data layer (`UpkeepIsolationTest`). A routine that was due while the phone was off is handled when that alarm
 * goes off at once: it is recorded as missed, or waits for the owner's tap; it never starts long after its time by
 * itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Only the two system broadcasts of its manifest filter count; anything else is ignored.
        if (intent.action !in HANDLED) return
        AndroidRoutineAlarms(context).armAgain()
    }

    private companion object {
        /** `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (Android 12 and later), spelled out for Android 9-11. */
        const val EXACT_ALARMS_ALLOWED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        val HANDLED = setOf(Intent.ACTION_BOOT_COMPLETED, EXACT_ALARMS_ALLOWED)
    }
}
