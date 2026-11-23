package com.aicontrolcenter.app.work

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Starts [ObjectiveWorkService] when an objective is running while the app is on screen.
 *
 * Android 12 and later refuse to start a foreground service from the background, so the start
 * is tied to [AppGraph.isInForeground]: an objective is always started by the owner from the
 * app, and one resumed automatically after a crash is picked up as soon as the app is shown.
 * The service stops itself when nothing runs, so this only ever starts it.
 */
class ObjectiveWorkController(private val context: Context, private val graph: AppGraph, private val scope: CoroutineScope) {

    fun start(): Job = scope.launch {
        combine(graph.orchestrator.running, graph.isInForeground) { running, foreground -> shouldRun(running.isNotEmpty(), foreground) }
            .distinctUntilChanged()
            .filter { it }
            .collect { startService() }
    }

    private suspend fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, ObjectiveWorkService::class.java))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: IllegalStateException) {
            // Android refused (the app left the screen at that instant). Work goes on while the
            // process lives, and the next return to the app starts the service.
            graph.activityRecorder.record(
                type = ActivityType.SYSTEM,
                title = "Could not keep objectives running in the background",
                detail = refused.message ?: "Android refused to start the foreground service.",
                severity = Severity.WARNING,
            )
        }
    }

    companion object {
        /** The service is wanted when something runs, and may only be started from the foreground. */
        fun shouldRun(anythingRunning: Boolean, inForeground: Boolean): Boolean = anythingRunning && inForeground
    }
}
