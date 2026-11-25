package com.aicontrolcenter.app.health

import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The process scope's last word on a failure nothing else caught (Phase 6, G7; WP2 item 8). Without it, an exception
 * escaping any coroutine of the application scope ends the whole app. With it:
 * - an [Exception] is written to the activity log as "Something in the app failed" (its class only, never its message,
 *   which can hold anything) and the process goes on: the other coroutines of the scope are unaffected (it is a
 *   supervisor scope);
 * - an [Error] (out of memory, a broken class) is not something to carry on after: it is thrown on, and the crash notes
 *   and Android record it.
 *
 * Built when the application scope is first used; it touches no system service, file or database then (spike item 1).
 *
 * @param recorder where to log, or null when nothing can be logged yet (the app's data has not opened).
 */
class AppScopeExceptionHandler(
    private val recorder: () -> ActivityRecorder?,
    /** Where the log line is written; its own scope, so a failure there cannot come back here. */
    private val logScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        if (exception is Error) throw exception
        val log = try {
            recorder()
        } catch (_: Exception) {
            null
        } ?: return
        logScope.launch {
            try {
                log.record(
                    type = ActivityType.ERROR,
                    title = TITLE,
                    detail = detailFor(exception),
                    severity = Severity.WARNING,
                )
            } catch (_: Exception) {
                // The log itself is unavailable; there is nowhere left to report to.
            }
        }
    }

    companion object {
        const val TITLE: String = "Something in the app failed"

        /** The class and the first place in the app's own code: never the message. */
        fun detailFor(exception: Throwable): String {
            val note = CrashNotes.noteOf(exception, 0)
            return "The app carried on. What failed: ${note.summary}."
        }
    }
}
