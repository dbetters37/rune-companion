package com.aicontrolcenter.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aicontrolcenter.app.ui.shell.AiControlCenterRoot
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId

/** The single activity: hosts the Compose shell and tells the graph about process and foreground starts. */
class MainActivity : ComponentActivity() {
    private val graph get() = (application as AiControlCenterApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Idempotent: seeds the starter team once and begins following package changes.
        graph.start()
        if (savedInstanceState == null) openRequestedObjective(intent)
        setContent { AiControlCenterRoot(graph) }
    }

    /** A notification (objectives, an approval) was tapped while the activity existed. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequestedObjective(intent)
    }

    private fun openRequestedObjective(intent: Intent?) {
        intent?.getStringExtra(EXTRA_OBJECTIVE_ID)?.let { graph.requestObjective(TaskId(it)) }
        // Only opens the request's Approval screen: answering always takes the owner's tap there.
        intent?.getStringExtra(EXTRA_REQUEST_ID)?.let { graph.requestApproval(RequestId(it)) }
    }

    override fun onStart() {
        super.onStart()
        // Package broadcasts are not delivered reliably to a stopped app, so re-check on return.
        graph.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        graph.onAppBackgrounded()
    }

    companion object {
        /** Opens this objective once the app is shown (from the objectives notification). */
        const val EXTRA_OBJECTIVE_ID = "com.aicontrolcenter.app.extra.OBJECTIVE_ID"

        /** Opens this request's Approval screen once the app is shown (from its notification). */
        const val EXTRA_REQUEST_ID = "com.aicontrolcenter.app.extra.REQUEST_ID"
    }
}
