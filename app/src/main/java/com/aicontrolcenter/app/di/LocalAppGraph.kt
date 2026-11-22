package com.aicontrolcenter.app.di

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The composition root as seen from Compose. Only "Route" composables read it, to hand the
 * interfaces a view model needs to its constructor; screens below a route never see the graph.
 */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("No AppGraph provided. Wrap the content in AiControlCenterRoot or provide LocalAppGraph.")
}
