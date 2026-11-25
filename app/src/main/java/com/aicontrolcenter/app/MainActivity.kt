package com.aicontrolcenter.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aicontrolcenter.app.health.GateHost
import com.aicontrolcenter.app.ui.recovery.RecoveryHost
import com.aicontrolcenter.app.ui.shell.AiControlCenterRoot
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId

/**
 * The single activity: hosts the Compose shell and tells the graph about process and foreground starts.
 *
 * It touches the graph only once the app's gate says the data opened (Phase 6, R5): until then nothing builds the
 * graph, and when the data cannot be opened the Recovery screen shows instead of the shell. No intent opens Recovery,
 * Backup or Start over (rule 23): the gate alone decides.
 */
class MainActivity : ComponentActivity() {
    private val app get() = application as AiControlCenterApp
    private val graph get() = app.graph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val gate = app.gate
        val firstCreation = savedInstanceState == null
        gate.whenOpen {
            // Idempotent: seeds the starter team once and begins following package changes.
            graph.start()
            if (firstCreation) openRequestedObjective(intent)
        }
        setContent {
            GateHost(gate, recovery = { state -> RecoveryHost(state, app.recovery) }) { AiControlCenterRoot(graph) }
        }
    }

    /** A notification (objectives, an approval) was tapped while the activity existed. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        app.gate.whenOpen { openRequestedObjective(intent) }
    }

    private fun openRequestedObjective(intent: Intent?) {
        intent?.getStringExtra(EXTRA_OBJECTIVE_ID)?.let { graph.requestObjective(TaskId(it)) }
        // Only opens the request's Approval screen: answering always takes the owner's tap there.
        intent?.getStringExtra(EXTRA_REQUEST_ID)?.let { graph.requestApproval(RequestId(it)) }
    }

    override fun onStart() {
        super.onStart()
        // Package broadcasts are not delivered reliably to a stopped app, so re-check on return.
        app.gate.whenOpen { graph.onAppForegrounded() }
    }

    override fun onStop() {
        super.onStop()
        // Never builds the graph: when the gate never opened, there is nothing to tell.
        app.graphIfBuilt()?.onAppBackgrounded()
    }

    companion object {
        /** Opens this objective once the app is shown (from the objectives notification). */
        const val EXTRA_OBJECTIVE_ID = "com.aicontrolcenter.app.extra.OBJECTIVE_ID"

        /** Opens this request's Approval screen once the app is shown (from its notification). */
        const val EXTRA_REQUEST_ID = "com.aicontrolcenter.app.extra.REQUEST_ID"
    }
}
