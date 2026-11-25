package com.aicontrolcenter.app.routines

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.aicontrolcenter.app.di.wiring.RoutinesWiring
import com.aicontrolcenter.app.work.ObjectiveWorkService

/**
 * Receives a routine's alarm (Phase 6, owner decision Q1, plan Appendix A, Tier B; WP17). Declared not exported and
 * without an intent filter, and enabled only while a routine exists: only the app's own alarm reaches it
 * ([AndroidRoutineAlarms.operation]).
 *
 * It only signals (security review of wave 1, F11, F12): it starts the foreground service with
 * [RoutinesWiring.ACTION_RUN_DUE_ROUTINES] and nothing else (no extra), and the service runs what is due, with every
 * check of a routine run. It never names the application, the app's object graph or the data layer
 * (`UpkeepIsolationTest` scans this folder), and decides nothing itself.
 *
 * When Android does not let the app start a foreground service now (an inexact alarm on Android 12 and later, without
 * the battery exemption), nothing starts: it posts "Tap to start" ([RoutineNotifications]), which only opens the app;
 * there the due time waits for the owner's Start.
 */
class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidRoutineAlarms.ACTION_ROUTINE_ALARM) return
        if (!startService(context)) RoutineNotifications.post(context, RoutineNotifications.DUE_TEXT)
    }

    /** Names. */
    companion object {
        /** The service start this receiver sends: the explicit service, the one action, no extra. */
        fun runDueIntent(context: Context): Intent =
            Intent(context, ObjectiveWorkService::class.java).setAction(RoutinesWiring.ACTION_RUN_DUE_ROUTINES)

        /** Asks the foreground service to run what is due. False when Android refused to start it now. */
        fun startService(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, runDueIntent(context))
            true
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12 and later) is one.
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
