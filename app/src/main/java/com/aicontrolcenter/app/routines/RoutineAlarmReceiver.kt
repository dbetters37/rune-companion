package com.aicontrolcenter.app.routines

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aicontrolcenter.app.Phase6Stub

/**
 * Receives a routine's alarm (Phase 6, owner decision Q1, plan Appendix A, Tier B; WP17 owns this file in wave 2).
 * Declared disabled and not exported: only the app's own alarms reach it, and WP17 enables it once the owner has made
 * a routine.
 *
 * It only signals (security review of wave 1, F11, F12): it starts the foreground service with
 * `RoutinesWiring.ACTION_RUN_DUE_ROUTINES`, which runs the due routines through the graph. It never names the
 * application, the graph or the data layer (`UpkeepIsolationTest` scans this folder), and no notification action
 * reaches it: "Tap to start" opens the app on a confirmation the owner taps (rule 4: notifications never start work).
 *
 * Wave 1: disabled, and it does nothing.
 */
@Phase6Stub("WP17")
class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
