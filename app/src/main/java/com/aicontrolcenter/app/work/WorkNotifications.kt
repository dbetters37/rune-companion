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

/**
 * The ongoing "objectives at work" notification: its channel, how a [WorkNotice] looks, and
 * posting it only when the owner allows notifications.
 */
internal object WorkNotifications {
    const val CHANNEL_ID = "objectives"
    const val NOTIFICATION_ID = 4_001

    /** Low importance: it updates as tasks finish and must never make a sound. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Objectives at work", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the Director and its team work on an objective, with a button to pause."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** The notification for [notice]; null shows the moment before anything is known. */
    fun build(context: Context, notice: WorkNotice?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_director)
            .setContentTitle(notice?.title ?: "Director is working")
            .setContentText(notice?.text ?: "Starting…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Shown at once rather than after Android's ten-second grace period for short jobs.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context, notice))
            .addAction(R.drawable.ic_stat_pause, "Pause", pauseAll(context))
        val progress = notice?.progress
        if (progress == null || progress.isPlanning) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(progress.planned, progress.completed, false)
        }
        return builder.build()
    }

    /** Updates the shown notification, if the owner allows notifications at all. */
    fun post(context: Context, notification: Notification) {
        // On Android 12 and older this checks whether the app's notifications are switched on.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Revoked between the check and the call; the service keeps running without it.
        }
    }

    private fun openApp(context: Context, notice: WorkNotice?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        notice?.let { intent.putExtra(MainActivity.EXTRA_OBJECTIVE_ID, it.objectiveId.value) }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun pauseAll(context: Context): PendingIntent {
        val intent = Intent(context, ObjectiveWorkService::class.java).setAction(ObjectiveWorkService.ACTION_PAUSE_ALL)
        return PendingIntent.getService(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
