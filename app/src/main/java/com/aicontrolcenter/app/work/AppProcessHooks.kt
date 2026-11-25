package com.aicontrolcenter.app.work

import android.app.Application
import com.aicontrolcenter.app.AiControlCenterApp
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.health.AppScopeExceptionHandler
import com.aicontrolcenter.app.health.CrashNotes
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

/**
 * What the application class does at the three moments of the main process that Phase 6 hooks into (WP1d; WP2 owns
 * this file in wave 2). None of them runs in the isolated script process: `AiControlCenterApp.onCreate` returns
 * before them there.
 */
object AppProcessHooks {
    /**
     * Right after `Application.onCreate` in the main process: the crash notes' handler (R2), chained to the one before
     * it. It builds no graph and opens no database: a start for a broadcast or a job stays cheap.
     */
    fun onCreate(app: Application) {
        if (!isMainProcess(app)) return
        CrashNotes.install(CrashNotes.inFolder(app.noBackupFilesDir))
    }

    /**
     * Added to the process scope when it is first used: [AppScopeExceptionHandler] (G7), which logs a failure nothing
     * else caught and keeps the process for an exception. It touches no system service, file or database when made;
     * it logs only through a graph that already exists, never building one.
     */
    fun scopeContext(app: Application): CoroutineContext =
        AppScopeExceptionHandler(recorder = { (app as? AiControlCenterApp)?.graphIfBuilt()?.activityRecorder })

    /** Once, right after the production graph is built (never for a graph a test installs). */
    fun onGraphBuilt(app: Application, graph: AppGraph, scope: CoroutineScope) {
        GraphServices.start(app, graph, scope)
    }

    private fun isMainProcess(app: Application): Boolean {
        val name = Application.getProcessName() ?: return true
        return name == (app.applicationInfo.processName ?: app.packageName)
    }
}
