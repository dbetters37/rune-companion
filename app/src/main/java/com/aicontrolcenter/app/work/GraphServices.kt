package com.aicontrolcenter.app.work

import android.content.Context
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.device.screen.ScreenOperatorLayer
import kotlinx.coroutines.CoroutineScope

/**
 * The services the production graph gets once it is built (moved from `AiControlCenterApp.graph` in Phase 6, WP1d;
 * WP2 owns this file in wave 2 and runs them under `SafetyCollectors`, rule 26): the controller that keeps
 * objectives running in the background, a notification for every request waiting for the owner, and the
 * "is operating" notification with its Stop while an agent drives another app's screen.
 */
object GraphServices {
    /** Starts the three collectors on [scope]; [context] is the application. Called once per process. */
    fun start(context: Context, graph: AppGraph, scope: CoroutineScope) {
        ObjectiveWorkController(context, graph, scope).start()
        // Re-posts the requests still waiting once the owner allows notifications.
        ApprovalNotifier(context, graph.ownerRequests.pending, graph.clock, scope, graph.notifications.enabled).start()
        ScreenSessionNotifier(context, graph.screenSessions.sessions, { ScreenOperatorLayer.stopPendingIntent(context) }, scope).start()
    }
}
