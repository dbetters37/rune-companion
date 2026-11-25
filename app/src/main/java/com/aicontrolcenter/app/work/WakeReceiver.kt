package com.aicontrolcenter.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aicontrolcenter.app.Phase6Stub

/**
 * Wakes waiting work while the phone sleeps (Phase 6, rule 5, F8; WP2 owns this file in wave 2): the foreground
 * service sets one `AlarmManager.setAndAllowWhileIdle` alarm to this receiver for a wait longer than two minutes. It
 * only signals a graph that already exists (rule 1): it never builds one, so it never starts work by itself.
 *
 * Wave 1: no alarm is set, and it does nothing.
 */
@Phase6Stub("WP2")
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
