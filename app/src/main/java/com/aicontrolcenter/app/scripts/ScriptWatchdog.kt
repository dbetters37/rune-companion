package com.aicontrolcenter.app.scripts

import com.aicontrolcenter.framework.operator.JsSandbox

/**
 * The script process's own watchdog, one per run (Phase 6; spike F-G: one shared watchdog ended a later, legitimate run
 * when an earlier run's thread was still alive at the earlier deadline).
 *
 * A run's deadline is getting ready ([com.aicontrolcenter.framework.operator.JsLimits.engineStartMs]) until the
 * script's own code begins, then its time limit from there (D2), plus the sandbox's grace and [EXTRA_MS]. A run that
 * has not ended cleanly by then (its thread was given up, or nothing answered at all) is told it was stopped, and the
 * process ends itself: within about 0.1 s the app hears of it (spike Q3), and Android starts a fresh process for the
 * next run. A run that ended cleanly stands its watchdog down.
 *
 * A normal-priority thread, so a script thread given up at the lowest priority cannot starve it.
 */
internal class ScriptWatchdog(
    private val run: Watched,
    private val endProcess: () -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** What the watchdog watches: the run's moving deadline, whether it ended cleanly, and how to tell it it was stopped. */
    interface Watched {
        val deadlineMs: Long
        val endedCleanly: Boolean

        /** The run is overdue: tell the app (it may not hear it; the process ends right after). */
        fun overdue()
    }

    fun start(name: String) {
        Thread({ watch() }, name).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }.start()
    }

    private fun watch() {
        while (!run.endedCleanly) {
            val left = run.deadlineMs - nowMs()
            if (left <= 0) {
                runCatching { run.overdue() }
                endProcess()
                return
            }
            try {
                Thread.sleep(minOf(left, STEP_MS))
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    companion object {
        /** After the sandbox's grace: the app hears the sandbox's own answer first, and this only when there was none. */
        const val EXTRA_MS = 3_000L

        /** How long past a bound (a time limit, or getting ready) the process ends itself: the grace and [EXTRA_MS]. */
        const val PAST_BOUND_MS = JsSandbox.GRACE_MS + EXTRA_MS

        private const val STEP_MS = 250L
    }
}
