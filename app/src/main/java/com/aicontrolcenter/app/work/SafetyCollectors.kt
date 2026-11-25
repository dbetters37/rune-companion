package com.aicontrolcenter.app.work

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the app's safety collectors so that they fail stop, never silent (Phase 6, rule 26, N10; WP2 item 8). A collector
 * is a long-running loop that keeps a promise to the owner (a notification for every request waiting for them, the
 * "is operating · Stop" notification, the service that keeps objectives running). If one fails it is restarted after a
 * wait that doubles each time (1, 2, 4 … 60 seconds), and a WARNING is written to the activity log. When one fails
 * [FAILS_BEFORE_STOP] times within [WINDOW_MS], its fail-stop action runs: screen control is stopped if the "is operating"
 * notification cannot be kept, running objectives are paused if the controller cannot be kept.
 *
 * @param recorder where the warnings go (the activity log).
 * @param pause waits between restarts; tests replace it.
 */
class SafetyCollectors(
    private val scope: CoroutineScope,
    private val recorder: ActivityRecorder,
    private val clock: Clock,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Runs [collector] on the scope until the scope ends, restarting it when it fails or ends. [title] names it in the
     * log in plain words ("The notifications for requests waiting for you"). [onRepeatedFailure] is its fail-stop action.
     */
    fun supervise(title: String, onRepeatedFailure: (suspend () -> Unit)? = null, collector: suspend () -> Unit): Job = scope.launch {
        var backoff = FIRST_BACKOFF_MS
        val failures = ArrayDeque<Long>()
        while (true) {
            val started = clock.now()
            val problem: Exception? = try {
                collector()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure
            }
            val now = clock.now()
            // A collector that worked for a while starts again quickly.
            if (now - started > MAX_BACKOFF_MS) backoff = FIRST_BACKOFF_MS
            failures.addLast(now)
            while (failures.isNotEmpty() && now - failures.first() > WINDOW_MS) failures.removeFirst()
            val stopping = onRepeatedFailure != null && failures.size >= FAILS_BEFORE_STOP
            note(title, problem, backoff, stopping)
            if (stopping) {
                failures.clear()
                try {
                    onRepeatedFailure?.invoke()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The fail-stop action itself failed; the warning above is already written.
                }
            }
            pause(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun note(title: String, problem: Exception?, backoffMs: Long, stopping: Boolean) {
        val what = problem?.let { "(${it.javaClass.simpleName})" } ?: "(it ended)"
        val detail = buildString {
            append("$title stopped working $what and is started again in ${backoffMs / 1000} s.")
            if (stopping) append(" It failed $FAILS_BEFORE_STOP times within 5 minutes, so the app stopped what depends on it to stay safe.")
        }
        try {
            recorder.record(ActivityType.SYSTEM, "$title had to be restarted", detail, Severity.WARNING)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The log itself is unavailable.
        }
    }

    companion object {
        const val FIRST_BACKOFF_MS: Long = 1_000
        const val MAX_BACKOFF_MS: Long = 60_000
        const val FAILS_BEFORE_STOP: Int = 3
        const val WINDOW_MS: Long = 5 * 60_000L
    }
}
