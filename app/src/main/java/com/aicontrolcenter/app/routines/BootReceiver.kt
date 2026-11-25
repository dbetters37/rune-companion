package com.aicontrolcenter.app.routines

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aicontrolcenter.app.Phase6Stub

/**
 * Sets the routines' alarms again after the phone restarts, or after the owner allows exact alarms (Phase 6, owner
 * decision Q1, plan Appendix A, Tier B; WP17 owns this file in wave 2). Android clears every alarm at a restart.
 * Declared disabled and not exported (system broadcasts still reach it): WP17 enables it once the owner has made a
 * routine and disables it when the last one is deleted, so a phone without routines never starts the app at boot.
 *
 * Wave 1: disabled, and it does nothing.
 */
@Phase6Stub("WP17")
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Only the two system broadcasts of its manifest filter count; anything else is ignored.
        if (intent.action !in HANDLED) return
    }

    private companion object {
        /** `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (Android 12 and later), spelled out for Android 9-11. */
        const val EXACT_ALARMS_ALLOWED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        val HANDLED = setOf(Intent.ACTION_BOOT_COMPLETED, EXACT_ALARMS_ALLOWED)
    }
}
