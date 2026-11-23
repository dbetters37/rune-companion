package com.aicontrolcenter.device.screen

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Stop action of a notification shown while agents operate the screen. Not exported: only
 * this app's own [pendingIntent] reaches it. Stop is the only thing it does; resuming is the
 * owner's, in the app.
 */
class ScreenStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        val write = StopSwitch.process(context).stopAndSave()
        // Keep the process alive until the Stop is on disk, so it holds after a restart. (Null when
        // not delivered by the system, as in a direct call.)
        val pending: PendingResult = goAsync() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                write.join()
            } finally {
                pending.finish()
            }
        }
    }

    /** The intent that stops screen operation. */
    companion object {
        const val ACTION_STOP = "com.aicontrolcenter.device.action.STOP_SCREEN_OPERATOR"
        private const val REQUEST_CODE = 4_101

        /** For a notification's "Stop" action. Works from the lock screen; it can only stop, never resume. */
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ScreenStopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
