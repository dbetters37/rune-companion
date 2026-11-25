package com.aicontrolcenter.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one wake alarm of the foreground service (Phase 6, rule 5, F8; WP2 item 1): for a wait due later than two
 * minutes, `AlarmManager.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, …)` to the app's own, non-exported
 * [WakeReceiver] (no permission needed). It fires even in Doze, though Doze may hold it back to about once every nine
 * minutes. Setting it again replaces it (one `PendingIntent`); [cancel] removes it.
 *
 * @param elapsedNow `SystemClock.elapsedRealtime`, which counts deep sleep; tests replace it.
 */
class WakeAlarms(
    private val context: Context,
    private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
) {
    private val manager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    /** Sets the alarm for the wall-clock time [at] ([now] is the wall clock now). */
    fun set(at: Long, now: Long) {
        val alarms = manager ?: return
        val inMs = (at - now).coerceAtLeast(0)
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedNow() + inMs, intent())
        } catch (_: RuntimeException) {
            // Too many alarms (Android limits them per app): the waits then go on when the phone wakes by itself.
        }
    }

    fun cancel() {
        manager?.cancel(intent())
    }

    private fun intent(): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, WakeReceiver::class.java).setAction(WakeReceiver.ACTION_WAKE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private companion object {
        const val REQUEST_CODE = 4_401
    }
}

/**
 * Tells the foreground service to decide again whether to keep the phone awake (the wake alarm fired). In-process only;
 * it carries nothing but a count.
 */
object WakeSignals {
    private val pokes = MutableStateFlow(0L)

    val signals: StateFlow<Long> = pokes.asStateFlow()

    fun poke() {
        pokes.value = pokes.value + 1
    }
}
