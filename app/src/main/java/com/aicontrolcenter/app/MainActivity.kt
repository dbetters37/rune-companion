package com.aicontrolcenter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aicontrolcenter.app.ui.shell.AiControlCenterRoot

/** The single activity: hosts the Compose shell and tells the graph about process and foreground starts. */
class MainActivity : ComponentActivity() {
    private val graph get() = (application as AiControlCenterApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Idempotent: seeds the starter team once and begins following package changes.
        graph.start()
        setContent { AiControlCenterRoot(graph) }
    }

    override fun onStart() {
        super.onStart()
        // Package broadcasts are not delivered reliably to a stopped app, so re-check on return.
        graph.onAppForegrounded()
    }
}
