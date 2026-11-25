package com.aicontrolcenter.app.work

import android.app.Application
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * What the application class does at the three moments of the main process that Phase 6 hooks into (WP1d; WP2 owns
 * this file in wave 2). None of them runs in the isolated script process: `AiControlCenterApp.onCreate` returns
 * before them there.
 */
@Phase6Stub("WP2")
object AppProcessHooks {
    /**
     * Right after `Application.onCreate` in the main process (WP2: the crash notes handler). It must not build the
     * graph or open the database: a start for a broadcast or a job must stay cheap.
     */
    fun onCreate(@Suppress("UNUSED_PARAMETER") app: Application) {}

    /**
     * Added to the process scope when it is first used (WP2: `AppScopeExceptionHandler`). It must touch no system
     * service, file or database.
     */
    fun scopeContext(@Suppress("UNUSED_PARAMETER") app: Application): CoroutineContext = EmptyCoroutineContext

    /** Once, right after the production graph is built (never for a graph a test installs). */
    fun onGraphBuilt(app: Application, graph: AppGraph, scope: CoroutineScope) {
        GraphServices.start(app, graph, scope)
    }
}
