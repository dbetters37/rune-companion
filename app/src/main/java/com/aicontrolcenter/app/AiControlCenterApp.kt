package com.aicontrolcenter.app

import android.app.Application
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.di.createProductionPorts
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

    /** The composition root; created from the production ports unless one was installed. */
    val graph: AppGraph
        get() = installedGraph ?: synchronized(this) {
            installedGraph ?: AppGraph(createProductionPorts(this), appScope).also { installedGraph = it }
        }

    /** Replaces the composition root. Meant for tests and must happen before the first screen is shown. */
    fun installGraph(graph: AppGraph) {
        synchronized(this) { installedGraph = graph }
    }
}
