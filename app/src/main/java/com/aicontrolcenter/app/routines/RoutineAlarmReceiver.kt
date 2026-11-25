package com.aicontrolcenter.app.routines

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aicontrolcenter.app.Phase6Stub

/**
 * Receives a routine's alarm (Phase 6, owner decision Q1, plan Appendix A, Tier B; WP17 owns this file in wave 2).
 * Declared disabled and not exported: only the app's own alarms (and its own "Tap to start" notification) reach it,
 * and WP17 enables it once the owner has made a routine.
 *
 * Wave 1: disabled, and it does nothing.
 */
@Phase6Stub("WP17")
class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
