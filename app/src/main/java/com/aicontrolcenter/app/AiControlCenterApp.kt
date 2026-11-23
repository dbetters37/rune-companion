package com.aicontrolcenter.app

import android.app.Application
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.di.createProductionPorts
import com.aicontrolcenter.app.work.AndroidNotificationAccess
import com.aicontrolcenter.app.work.ApprovalNotifier
import com.aicontrolcenter.app.work.ObjectiveWorkController
import com.aicontrolcenter.app.work.ScreenSessionNotifier
import com.aicontrolcenter.device.screen.ScreenOperatorLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the process-wide [AppGraph].
 *
 * The graph is built on first use rather than in [onCreate], for two reasons: opening the
 * database and the keystore is then paid for by the first screen that needs them instead of by
 * every process start (including starts for a broadcast), and a test can [installGraph] one
 * built on in-memory ports before anything asks for the production one.
 */
class AiControlCenterApp : Application() {
    /** Lives as long as the process. A failing child never cancels the others. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var installedGraph: AppGraph? = null

    /**
     * The composition root; created from the production ports unless one was installed. The
     * production graph also gets the controller that keeps objectives running in the background,
     * a notification for every request waiting for the owner, and the "is operating" notification
     * with its Stop while an agent drives another app's screen.
     */
    val graph: AppGraph
        get() = installedGraph ?: synchronized(this) {
            installedGraph ?: AppGraph(createProductionPorts(this), appScope, notifications = AndroidNotificationAccess(this)).also {
                installedGraph = it
                ObjectiveWorkController(this, it, appScope).start()
                ApprovalNotifier(this, it.ownerRequests.pending, it.clock, appScope).start()
                val context = this
                ScreenSessionNotifier(this, it.screenSessions.sessions, { ScreenOperatorLayer.stopPendingIntent(context) }, appScope).start()
            }
        }

    /** Replaces the composition root. Meant for tests and must happen before the first screen is shown. */
    fun installGraph(graph: AppGraph) {
        synchronized(this) { installedGraph = graph }
    }
}
