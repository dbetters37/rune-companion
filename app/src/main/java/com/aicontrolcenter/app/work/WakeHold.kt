package com.aicontrolcenter.app.work

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import com.aicontrolcenter.core.health.TimedWaits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * When the phone must be kept awake (Phase 6, rule 5; WP2 item 1). Pure, so the rule is tested as a table.
 *
 * The phone is kept awake only while the foreground service runs (the caller is the service) and the owner's setting
 * allows it, and only while an agent is working, a chat answer is being written (not waiting for the owner), or a wait
 * ([TimedWaits]: a task waiting for its provider, a provider's re-check) is due within two minutes. Waiting for the
 * owner, for an agent, a paused objective: none keeps it awake.
 *
 * A longer wait gets one wake alarm instead, two minutes before it is due: the alarm's receiver lets the waits look at
 * the wall clock again and tells the service to decide anew, which then keeps the phone awake for the last two
 * minutes. Doze may deliver the alarm late (about once every nine minutes); the waits then go on as soon as it comes.
 */
data class WakeDecision(
    /** Hold the partial wake lock now. */
    val hold: Boolean,
    /** Set one wake alarm for this wall-clock time (ms), or none. */
    val alarmAt: Long?,
    /** Decide again at this wall-clock time (ms), or only when something changes. */
    val recheckAt: Long?,
) {
    /** What the decision is made from. */
    data class Input(
        /** The owner's setting "Keep the phone awake while agents work". */
        val keepAwakeAllowed: Boolean,
        /** An agent is WORKING ([com.aicontrolcenter.core.agents.AgentActivityMonitor]). */
        val agentWorking: Boolean,
        /** A chat turn is live and not waiting for the owner's answer. */
        val chatAnswering: Boolean,
        /** The earliest due time of every [TimedWaits] (ms), or null when nothing waits. */
        val nextDueAt: Long?,
    )

    companion object {
        val NONE: WakeDecision = WakeDecision(hold = false, alarmAt = null, recheckAt = null)

        fun of(input: Input, now: Long): WakeDecision {
            if (!input.keepAwakeAllowed) return NONE
            val due = input.nextDueAt
            val dueSoon = due != null && due - now <= TimedWaits.KEEP_AWAKE_WITHIN_MS
            val hold = input.agentWorking || input.chatAnswering || dueSoon
            if (due == null || dueSoon) return WakeDecision(hold, alarmAt = null, recheckAt = null)
            val from = due - TimedWaits.KEEP_AWAKE_WITHIN_MS
            return WakeDecision(hold, alarmAt = from, recheckAt = from)
        }

        /** The earliest of the due times that are known. */
        fun earliest(vararg dues: Long?): Long? = dues.filterNotNull().minOrNull()
    }
}

/**
 * The partial wake lock "aicc:agents" of the foreground service (rule 5): not reference-counted, taken for
 * [TIMEOUT_MS] at a time and renewed every [RENEW_MS] while it should be held, so a forgotten release cannot keep the
 * phone awake for more than ten minutes. Released when work only waits, when objectives pause, when the setting is
 * switched off, and in the service's `onDestroy`.
 */
class WakeHold(context: Context, private val scope: CoroutineScope) {
    private val lock: PowerManager.WakeLock? =
        context.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply { setReferenceCounted(false) }

    private var renewing: Job? = null

    /** Whether it is held now. */
    val held: Boolean get() = lock?.isHeld == true

    /** Holds or releases. Call on one thread (the service's main thread). */
    fun set(hold: Boolean) {
        if (hold) acquire() else release()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquire() {
        val wakeLock = lock ?: return
        if (renewing?.isActive == true) return
        wakeLock.acquire(TIMEOUT_MS)
        renewing = scope.launch {
            while (isActive) {
                delay(RENEW_MS)
                wakeLock.acquire(TIMEOUT_MS)
            }
        }
    }

    /** Lets the phone sleep again. Safe to call at any time. */
    fun release() {
        renewing?.cancel()
        renewing = null
        val wakeLock = lock ?: return
        if (wakeLock.isHeld) wakeLock.release()
    }

    companion object {
        const val TAG: String = "aicc:agents"
        const val TIMEOUT_MS: Long = 10 * 60_000L
        const val RENEW_MS: Long = 5 * 60_000L
    }
}
