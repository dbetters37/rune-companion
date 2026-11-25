package com.aicontrolcenter.app.scripts

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.aicontrolcenter.framework.operator.JsSandbox

/**
 * Runs agents' JavaScript in its own isolated process (Phase 6, D1-D3, rules 11 and 31). Declared in the manifest with
 * `android:isolatedProcess="true"` and `android:process=":scripts"`, not exported: only this app binds it
 * ([IsolatedJsEngine]), and the process it runs in has no access to the app's files, database, keys or network. Android
 * ends the process when the app lets go of it, without ending the app (spike Q2, Q3).
 *
 * The application object of this process returns from its `onCreate` at once (`Process.isIsolated()`), so nothing of
 * the app is built here.
 */
class ScriptProcessService : Service() {
    private var runner: ScriptRunner? = null

    override fun onCreate() {
        super.onCreate()
        // Spike F-B: Rhino finds its regular expressions with the loading thread's class loader; binder threads have none.
        JsSandbox.prepareOnThisThread()
        val made = ScriptRunner(ScriptPipes.Android, endProcess = ::endThisProcess, pid = Process::myPid)
        runner = made
        // Only in the isolated process (never in a test's process): an error nothing caught tells the runs and ends the
        // process quietly, with no crash record and no 30-minute wait before Android starts the service again (spike Q8).
        if (Process.isIsolated()) quietEnd(made)
    }

    override fun onBind(intent: Intent?): IBinder? = runner

    internal companion object {
        fun endThisProcess() {
            Process.killProcess(Process.myPid())
        }

        /** Installs the quiet end of the process: [runner]'s runs are told, then the process ends. */
        fun quietEnd(runner: ScriptRunner?) {
            Thread.setDefaultUncaughtExceptionHandler { _, error ->
                try {
                    runner?.failAll(error)
                } catch (t: Throwable) {
                    // Out of memory even for that: the app learns of the end from the binder's death.
                } finally {
                    endThisProcess()
                }
            }
        }
    }
}
