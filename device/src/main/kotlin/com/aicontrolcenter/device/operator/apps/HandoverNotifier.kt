package com.aicontrolcenter.device.operator.apps

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aicontrolcenter.core.operator.AppActionResult
import com.aicontrolcenter.device.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offers a hand-over that Android would not let this app start from the background as a
 * notification the owner taps ("Tap to open …"). A start from a notification tap is allowed, and
 * the owner decides whether it happens at all. The notification only opens what was prepared;
 * it has no other action.
 */
internal class HandoverNotifier(context: Context) {
    private val appContext: Context = context.applicationContext ?: context
    private val manager = NotificationManagerCompat.from(appContext)
    private val next = AtomicInteger(0)

    /** Posts the notification for [intent] (an activity start) and says so, or says that nothing could be offered. */
    @SuppressLint("MissingPermission") // Checked in canNotify(); a revoked permission is caught below.
    fun offer(intent: Intent, what: String): AppActionResult.Deferred {
        if (!canNotify()) {
            return AppActionResult.Deferred(
                "Android does not let this app open $what while it is in the background, and its notifications are off, " +
                    "so nothing was offered. Bring the app to the front and ask again.",
            )
        }
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Agent hand-overs")
                .setDescription("Shown when an agent prepared something in another app while this app was in the background.")
                .build(),
        )
        val id = next.incrementAndGet()
        val tap = PendingIntent.getActivity(
            appContext,
            id,
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_operator_handover)
            .setContentTitle("Tap to open $what")
            .setContentText("An agent prepared this while the app was in the background. Nothing happens unless you tap.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        return try {
            manager.notify(TAG, id, notification)
            AppActionResult.Deferred("Android does not let this app open $what while it is in the background; the owner was sent a \"Tap to open\" notification.")
        } catch (_: SecurityException) {
            AppActionResult.Deferred("Android does not let this app open $what while it is in the background, and the notification could not be shown.")
        }
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    /** Constants. */
    companion object {
        const val CHANNEL_ID: String = "operator_handover"
        const val TAG: String = "operator_handover"
    }
}
