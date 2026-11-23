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
import com.aicontrolcenter.app.ui.approvals.isOpenAt
import com.aicontrolcenter.app.ui.approvals.title
import com.aicontrolcenter.app.ui.approvals.visible
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.operator.OwnerRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The "Approvals" notifications: one per question waiting for the owner. A notification only
 * opens the request's Approval screen. It carries no Approve or Allow action, and Android is
 * told not to add suggested actions, so the owner answers in the app and nowhere else.
 */
internal object ApprovalNotifications {
    const val CHANNEL_ID = "approvals"

    /** With the request id as the tag, so each request has its own notification. */
    const val NOTIFICATION_ID = 4_101

    /** High importance: an agent is blocked until the owner answers. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "An agent is waiting for your answer: a permission, or an action that is hard to undo. " +
                "The notification only opens the app; you answer there."
        }
        manager.createNotificationChannel(channel)
    }

    /** The notification for [request], removed by Android at its expiry if nothing else removed it first. */
    fun build(context: Context, request: OwnerRequest, now: Long): Notification {
        val agent = visible(request.agentName ?: "An agent")
        val text = "$agent: ${visible(request.summary)}"
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_approval)
            .setContentTitle(request.kind.title)
            .setContentText("An agent is waiting for your answer.")
            .build()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_approval)
            .setContentTitle(request.kind.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\nOpen the app to see exactly what it would do and answer."))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Hidden on the lock screen: what an agent wants to do is the owner's business.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setAllowSystemGeneratedContextualActions(false)
            .setWhen(request.createdAt)
            .setShowWhen(true)
            .setTimeoutAfter((request.expiresAt - now).coerceAtLeast(1))
            .setContentIntent(openRequest(context, request.id))
            .build()
    }

    fun post(context: Context, id: RequestId, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            NotificationManagerCompat.from(context).notify(id.value, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Revoked between the check and the call; the request still waits in the app.
        }
    }

    fun cancel(context: Context, id: RequestId) {
        NotificationManagerCompat.from(context).cancel(id.value, NOTIFICATION_ID)
    }

    /** Opens the app on the request's Approval screen. Nothing else: no answer travels in it. */
    private fun openRequest(context: Context, id: RequestId): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_REQUEST_ID, id.value)
        // A request code per request, so two notifications do not share (and overwrite) one intent.
        return PendingIntent.getActivity(context, id.value.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/**
 * Keeps one notification per open request: posted when a request starts waiting, removed when
 * it is answered, expires or is withdrawn. [pending] is `OwnerRequests.pending`, which emits
 * on every change to the stored requests (the start-up sweep closes those past their expiry).
 */
class ApprovalNotifier(
    private val context: Context,
    private val pending: Flow<List<OwnerRequest>>,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    fun start(): Job = scope.launch {
        ApprovalNotifications.ensureChannel(context)
        var shown = emptySet<RequestId>()
        pending.collect { requests ->
            val now = clock.now()
            val open = requests.filter { it.isOpenAt(now) }
            val (post, cancel) = changes(shown, open)
            cancel.forEach { ApprovalNotifications.cancel(context, it) }
            post.forEach { ApprovalNotifications.post(context, it.id, ApprovalNotifications.build(context, it, now)) }
            shown = open.mapTo(HashSet()) { it.id }
        }
    }

    companion object {
        /** What to post (requests not shown yet) and what to remove (shown, no longer open), given what is shown and what is open. */
        fun changes(shown: Set<RequestId>, open: List<OwnerRequest>): Pair<List<OwnerRequest>, List<RequestId>> {
            val openIds = open.mapTo(HashSet()) { it.id }
            return open.filter { it.id !in shown } to shown.filter { it !in openIds }
        }
    }
}
