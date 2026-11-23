package com.aicontrolcenter.app.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aicontrolcenter.app.MainActivity
import com.aicontrolcenter.app.R
import com.aicontrolcenter.app.operator.ScreenSession
import com.aicontrolcenter.app.ui.approvals.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The ongoing "<agent> is operating <app>" notification, shown for as long as an agent drives
 * an app's screen, next to the device's own on-screen banner. Its one action is Stop, which
 * stops screen operation at once through the device's Stop receiver (it can only stop; Resume
 * is in the app). Tapping the notification opens the app.
 */
internal object ScreenSessionNotifications {
    const val CHANNEL_ID = "screen_control"
    const val NOTIFICATION_ID = 4_201

    /** Low importance: silent, but in the status bar and the shade for as long as it lasts. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Screen control", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while an agent operates an app on your screen, with a button to stop it at once."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** "Operator is operating Notes", or "2 agents are operating apps" with one line each. */
    fun title(sessions: List<ScreenSession>): String = when (sessions.size) {
        1 -> sessions.single().let { "${visible(it.agentName)} is operating ${visible(it.appLabel)}" }
        else -> "${sessions.size} agents are operating apps"
    }

    fun build(context: Context, sessions: List<ScreenSession>, stop: PendingIntent): Notification {
        val text = "Stop ends it at once. Agents cannot use the screen again until you resume screen control in the app."
        val lines = sessions.joinToString("\n") { "${visible(it.agentName)} is operating ${visible(it.appLabel)}" }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_touch)
            .setContentTitle(title(sessions))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (sessions.size > 1) "$lines\n\n$text" else text))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAllowSystemGeneratedContextualActions(false)
            .setContentIntent(openApp(context))
            .addAction(R.drawable.ic_stat_stop, "Stop", stop)
            .build()
    }

    fun post(context: Context, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Revoked between the check and the call; the on-screen banner still shows, with its Stop.
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/**
 * Shows the "is operating" notification while [sessions] is not empty and removes it when the
 * last session ends (its agent finished, went idle, or the owner pressed Stop).
 *
 * @param stop the device's Stop: `ScreenOperatorLayer.stopPendingIntent`, which can only stop.
 */
class ScreenSessionNotifier(
    private val context: Context,
    private val sessions: Flow<List<ScreenSession>>,
    private val stop: () -> PendingIntent,
    private val scope: CoroutineScope,
) {
    fun start(): Job = scope.launch {
        ScreenSessionNotifications.ensureChannel(context)
        sessions.distinctUntilChanged().collect { now ->
            if (now.isEmpty()) {
                ScreenSessionNotifications.cancel(context)
            } else {
                ScreenSessionNotifications.post(context, ScreenSessionNotifications.build(context, now, stop()))
            }
        }
    }
}
