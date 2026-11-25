package com.aicontrolcenter.app.health

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Process
import com.aicontrolcenter.app.MainActivity
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.health.RecoveryResult

/**
 * What the Recovery screen (WP10) does with the owner's choice (Phase 6, rules 9 and 30). Never touches the app's graph:
 * the Recovery path is reached before the graph is built, and the data layer and the graph are single instances of the
 * process, so after Start over (and after a restore, WP3) the only clean state is a new process.
 */
object RecoveryActions {
    /**
     * Rule 30: opens [MainActivity] as a new task from the visible [activity], then ends this process. Android starts a
     * new process for the activity, which opens the fresh data (or the restored one) from the start.
     *
     * @param end ends the process; tests replace it.
     */
    fun restartApp(activity: Activity, end: () -> Unit = { Process.killProcess(Process.myPid()) }) {
        activity.startActivity(restartIntent(activity))
        activity.finishAffinity()
        end()
    }

    /** The intent [restartApp] starts: the main activity, as a fresh task, with no extra (rule 23). */
    fun restartIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    /**
     * Start over (after the owner typed [OwnerRecovery.CONFIRM_WORD]) and, when it is done, restart. Returns the result;
     * a refusal changes nothing and the screen shows its sentence. Call from the main thread's scope.
     */
    suspend fun startOverAndRestart(activity: Activity, recovery: OwnerRecovery, end: () -> Unit = { Process.killProcess(Process.myPid()) }): RecoveryResult {
        val result = recovery.startOver()
        if (result is RecoveryResult.Done) restartApp(activity, end)
        return result
    }
}
