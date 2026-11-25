package com.aicontrolcenter.app.work

import android.content.Context
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.device.screen.ScreenOperatorLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter

/**
 * The services the production graph gets once it is built (moved from `AiControlCenterApp.graph` in Phase 6, WP1d; WP2
 * owns this file): the controller that keeps objectives and chat answers running in the background, a notification for
 * every request waiting for the owner, the "is operating" notification with its Stop while an agent drives another app's
 * screen, the result notifications, and a fresh background check whenever the app comes to the front.
 *
 * All run under [SafetyCollectors] (rule 26): a failed one is restarted with a growing wait and logged; if the "is
 * operating" notification cannot be kept, screen control stops; if the controller cannot be kept, running objectives pause.
 */
object GraphServices {
    /** Starts the collectors on [scope]; [context] is the application. Called once per process. */
    fun start(context: Context, graph: AppGraph, scope: CoroutineScope) {
        val safety = SafetyCollectors(scope, graph.activityRecorder, graph.clock)
        val controller = ObjectiveWorkController(context, graph, scope)
        safety.supervise(CONTROLLER, onRepeatedFailure = { pauseRunning(graph) }) { controller.run() }
        // Re-posts the requests still waiting once the owner allows notifications.
        val approvals = ApprovalNotifier(context, graph.ownerRequests.pending, graph.clock, scope, graph.notifications.enabled)
        safety.supervise(APPROVALS) { approvals.run() }
        val screens = ScreenSessionNotifier(context, graph.screenSessions.sessions, { ScreenOperatorLayer.stopPendingIntent(context) }, scope)
        safety.supervise(SCREEN, onRepeatedFailure = { graph.screenControl.stop() }) { screens.run() }
        val results = ResultNotifier(
            context, graph.tasks.observeObjectiveRows(ResultNotifier.ROWS), graph.isInForeground, graph.settingsView,
            interruptCause = { graph.ports.background.startup.interruptCause() },
        )
        safety.supervise(RESULTS) { results.run() }
        // Whether Android lets the app work in the background can change while it is away (A3): read it on every return.
        safety.supervise(BACKGROUND_CHECK) { graph.isInForeground.filter { it }.collect { graph.backgroundHealth.refresh() } }
    }

    /** The controller's fail-stop: pause what runs, so nothing goes on unseen without its notification. */
    private suspend fun pauseRunning(graph: AppGraph) {
        for (id in graph.orchestrator.running.value) {
            try {
                graph.orchestrator.pause(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Already over.
            }
        }
    }

    const val CONTROLLER: String = "Keeping objectives running in the background"
    const val APPROVALS: String = "The notifications for requests waiting for you"
    const val SCREEN: String = "The \"is operating\" notification"
    const val RESULTS: String = "The notifications for finished work"
    const val BACKGROUND_CHECK: String = "The background check"
}
