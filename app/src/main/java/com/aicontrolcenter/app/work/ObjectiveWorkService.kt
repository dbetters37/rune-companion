package com.aicontrolcenter.app.work

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.aicontrolcenter.app.AiControlCenterApp
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while objectives run, so the team's work goes on when the owner leaves
 * the app or switches the screen off.
 *
 * It is a foreground service of type "specialUse": none of Android's predefined types fits
 * agents working on the owner's objective, and the subtype in the manifest says what it does.
 * It is started only from the foreground (see [ObjectiveWorkController]), shows an ongoing
 * notification with the oldest running objective, its progress and a Pause button, and stops
 * itself as soon as nothing runs. It is not sticky: if Android kills the process, recovery at
 * the next start marks the interrupted tasks, and nothing restarts in the background.
 *
 * The objectives themselves run in the application scope, not here: this service only holds the
 * process up and reports. Pausing from the notification goes through the orchestrator like the
 * Pause button in the app, which stores the new state before stopping anything.
 */
class ObjectiveWorkService : Service() {
    private val graph: AppGraph get() = (application as AiControlCenterApp).graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var latest: WorkNotice? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WorkNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start through startForegroundService must be answered with startForeground.
        goForeground()
        if (intent?.action == ACTION_PAUSE_ALL) pauseAll()
        if (watching == null) watching = scope.launch { watch() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        val notification = WorkNotifications.build(this, latest)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, WorkNotifications.NOTIFICATION_ID, notification, type)
        } catch (refused: IllegalStateException) {
            // Android refused to make it a foreground service (started from the background through
            // a stale notification). Whatever was asked (a pause) still happens; then it stops.
            if (graph.orchestrator.running.value.isEmpty()) stopSelf()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watch() {
        val orchestrator = graph.orchestrator
        orchestrator.running.flatMapLatest { running -> notices(running) }.distinctUntilChanged().collect { notice ->
            if (notice == null) {
                stop()
            } else {
                latest = notice
                WorkNotifications.post(this, WorkNotifications.build(this, notice))
            }
        }
    }

    private fun notices(running: Set<TaskId>): Flow<WorkNotice?> {
        if (running.isEmpty()) return flowOf(null)
        val trees = running.map { graph.tasks.observeTree(it) }
        return combine(trees) { all -> WorkNotice.of(running, all.toList()) }
    }

    private fun pauseAll() {
        scope.launch {
            for (id in graph.orchestrator.running.value) {
                try {
                    graph.orchestrator.pause(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Gone or already finished: nothing to pause. Anything else is worth a log line.
                    graph.activityRecorder.record(
                        type = ActivityType.ERROR,
                        title = "Could not pause an objective from the notification",
                        detail = failure.message ?: failure.javaClass.simpleName,
                        severity = Severity.WARNING,
                    )
                }
            }
        }
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        /** The notification's Pause button: pauses every running objective. */
        const val ACTION_PAUSE_ALL = "com.aicontrolcenter.app.action.PAUSE_ALL_OBJECTIVES"
    }
}
