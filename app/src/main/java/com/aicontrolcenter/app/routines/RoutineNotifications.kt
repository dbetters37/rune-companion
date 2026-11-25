package com.aicontrolcenter.app.routines

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
import com.aicontrolcenter.framework.routines.RoutineNotices

/**
 * "Tap to start" (Phase 6, owner decision Q1, plan Appendix A; security review of wave 1, F12; rules 4 and 23): the one
 * notification routines post, when a run waits for the owner because Android did not let the app start the work by
 * itself, or let it run too late.
 *
 * It decides nothing and starts nothing: it has **no actions**, Android is told not to add suggested ones, and tapping
 * it only opens the app ([MainActivity], by its class, with **no extra**: no new entry point). In the app the banner
 * "Routine '<title>' is waiting to start" ([WaitingRoutinesBanner]) has the Start button, which asks once more. The text
 * is app-written plus the routine's title, which the owner wrote; on the lock screen only the public version shows
 * ("A routine is waiting to start."), and a tap there asks Android to unlock first, like any notification that opens an
 * app.
 */
object RoutineNotifications {
    const val CHANNEL_ID: String = "routines"
    const val NOTIFICATION_ID: Int = 4_301

    const val TITLE: String = "A routine is waiting to start"
    const val PUBLIC_TEXT: String = "A routine is waiting to start."

    /** When the alarm's receiver does not start the work itself (no exact alarm, or Android refused) and does not know which routine it was. */
    const val DUE_TEXT: String = "A routine is due. Open the app to start it; nothing starts until you tap Start there."

    /** The text for a known routine. */
    fun waitingText(routineTitle: String): String =
        "Routine \"${routineTitle.trim().take(MAX_TITLE)}\" is waiting to start. Open the app to start it; nothing starts until you tap Start there."

    private const val MAX_TITLE = 80

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Routines", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A routine is due and waits for you to start it in the app. The notification only opens the app."
        }
        manager.createNotificationChannel(channel)
    }

    /** The notification: [text], no actions, opening the app and nothing else. */
    fun build(context: Context, text: String): Notification {
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_director)
            .setContentTitle(TITLE)
            .setContentText(PUBLIC_TEXT)
            .build()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_director)
            .setContentTitle(TITLE)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setAllowSystemGeneratedContextualActions(false)
            .setContentIntent(openApp(context))
            .build()
    }

    /** Posts [text]; false while the owner has not allowed notifications. */
    fun post(context: Context, text: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        ensureChannel(context)
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, text))
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Opens the app as the launcher does: [MainActivity] by its class, no extra, no data. */
    fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private const val REQUEST_CODE = 7_302
}

/** [RoutineNotices] as Android notifications ([RoutineNotifications]). */
class AndroidRoutineNotices(private val context: Context) : RoutineNotices {
    override fun waitingForTap(routineTitle: String) {
        RoutineNotifications.post(context, RoutineNotifications.waitingText(routineTitle))
    }

    override fun noneWaiting() {
        RoutineNotifications.cancel(context)
    }
}
