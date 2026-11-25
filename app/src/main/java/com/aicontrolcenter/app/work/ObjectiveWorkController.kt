package com.aicontrolcenter.app.work

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.health.BackgroundState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Starts [ObjectiveWorkService] when an objective runs or an agent is answering in a chat (Phase 6, A2), so the work
 * goes on when the owner leaves the app.
 *
 * Android 12 and later refuse to start a foreground service from the background, with exemptions; one of them is an app
 * the owner exempted from battery optimisation (F29, D25). So the service is started while the app is on screen, or,
 * when the owner allowed it in the background check, also from the background (a chat answer or an objective resumed
 * by itself). If Android refuses all the same, the work goes on while the process lives, and the next return to the app
 * starts the service. The service stops itself when nothing runs, so this only ever starts it.
 */
class ObjectiveWorkController(
    private val context: Context,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    /** Whether Android lets the app work in the background (the battery exemption); the graph's check by default. */
    private val health: Flow<BackgroundState> = graph.backgroundHealth.state,
) {

    /** Starts [run] on the scope (tests; the app runs it under `SafetyCollectors`). */
    fun start(): Job = scope.launch { run() }

    suspend fun run() {
        combine(graph.orchestrator.running, graph.agentChat.liveTurns, graph.isInForeground, health) { running, turns, foreground, state ->
            shouldRun(running.isNotEmpty() || turns.isNotEmpty(), foreground, batteryExempt = state.batteryOptimised == false)
        }
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
        fun shouldRun(anythingRunning: Boolean, inForeground: Boolean): Boolean = shouldRun(anythingRunning, inForeground, batteryExempt = false)

        /**
         * The service is wanted when an objective runs or a chat answer is being written, and may be started while the
         * app is on screen or, when the owner exempted the app from battery optimisation, from the background too.
         */
        fun shouldRun(anythingRunning: Boolean, inForeground: Boolean, batteryExempt: Boolean): Boolean =
            anythingRunning && (inForeground || batteryExempt)
    }
}
