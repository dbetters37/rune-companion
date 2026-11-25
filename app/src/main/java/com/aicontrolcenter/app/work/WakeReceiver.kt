package com.aicontrolcenter.app.work

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.aicontrolcenter.app.AiControlCenterApp

/**
 * Wakes waiting work while the phone sleeps (Phase 6, rule 5, F8; WP2 item 1): the foreground service sets one
 * [WakeAlarms] alarm to this (non-exported) receiver for a wait longer than two minutes. It only signals a graph that
 * already exists (rule 1): the orchestrator's and the provider re-checks' waits look at the wall clock again, and the
 * service decides again whether to keep the phone awake. It never builds the graph, so it never starts work by itself,
 * and it keeps the phone awake for at most [HOLD_MS] while the waits look. The approval reminders' alarm
 * ([ReminderAlarm], [ACTION_REMIND]) reaches it too: the reminders then look at the wall clock again (AND-W2B-3).
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKE && intent.action != ACTION_REMIND) return
        val graph = (context.applicationContext as? AiControlCenterApp)?.graphIfBuilt() ?: return
        holdBriefly(context)
        if (intent.action == ACTION_WAKE) {
            graph.orchestrator.timedWaits.wake()
            graph.providerRechecks.timedWaits.wake()
        }
        // The approval reminders look at the wall clock again ([ReminderAlarm]).
        WakeSignals.poke()
    }

    @SuppressLint("WakelockTimeout")
    private fun holdBriefly(context: Context) {
        try {
            context.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply {
                setReferenceCounted(false)
                acquire(HOLD_MS)
            }
        } catch (_: RuntimeException) {
            // Without it the waits still look again; the phone may just sleep sooner.
        }
    }

    companion object {
        const val ACTION_WAKE: String = "com.aicontrolcenter.app.action.WAKE_WAITS"

        /** An approval reminder is due ([ReminderAlarm]). */
        const val ACTION_REMIND: String = "com.aicontrolcenter.app.action.REMIND_APPROVALS"
        const val TAG: String = "aicc:wake"
        const val HOLD_MS: Long = 30_000
    }
}
