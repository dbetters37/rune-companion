package com.aicontrolcenter.app.scripts

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.aicontrolcenter.app.Phase6Stub

/**
 * Runs agents' JavaScript in its own isolated process (Phase 6, D1-D3, rules 11 and 31; WP6 owns this file in wave 2).
 * Declared in the manifest with `android:isolatedProcess="true"` and `android:process=":scripts"`, not exported: only
 * this app binds it, and the process it runs in has no access to the app's files, database or network.
 *
 * Wave 1: nothing binds it, and a bind gets no binder.
 */
@Phase6Stub("WP6")
class ScriptProcessService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
