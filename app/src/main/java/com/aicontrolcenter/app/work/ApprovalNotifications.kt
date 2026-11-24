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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
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

    /** Returns whether Android took it: false while the owner has not allowed notifications. */
    fun post(context: Context, id: RequestId, notification: Notification): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            NotificationManagerCompat.from(context).notify(id.value, NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // Revoked between the check and the call; the request still waits in the app.
            false
        }
    }

    fun cancel(context: Context, id: RequestId) {
        NotificationManagerCompat.from(context).cancel(id.value, NOTIFICATION_ID)
    }

    /**
     * The requests whose notification Android is showing now. Right after the app starts these
     * are the ones an earlier run of the app left behind: notifications outlive the process.
     */
    fun showing(context: Context): Set<RequestId> {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return emptySet()
        return try {
            manager.activeNotifications
                .filter { it.id == NOTIFICATION_ID && it.notification.channelId == CHANNEL_ID }
                .mapNotNullTo(HashSet()) { shown -> shown.tag?.let(::RequestId) }
        } catch (_: RuntimeException) {
            // Android could not list them; one left behind then goes at its request's expiry.
            emptySet()
        }
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
 * A request whose notification Android did not take (notifications were off) is posted when
 * [enabled] says they are allowed again, so a permission given later still shows every request
 * that waits. It starts from the notifications Android still shows ([leftover]): one left by an
 * earlier run of the app, for a request nothing waits for any more, is removed with the first
 * look at [pending], so "Approval needed" never stands for a question that cannot be answered.
 */
class ApprovalNotifier(
    private val context: Context,
    private val pending: Flow<List<OwnerRequest>>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    /** Whether notifications can be shown now (`NotificationAccess.enabled`). */
    private val enabled: Flow<Boolean> = flowOf(true),
    private val post: (RequestId, OwnerRequest, Long) -> Boolean = { id, request, now ->
        ApprovalNotifications.post(context, id, ApprovalNotifications.build(context, request, now))
    },
    private val cancel: (RequestId) -> Unit = { ApprovalNotifications.cancel(context, it) },
    /** The requests whose notification is showing when this starts. */
    private val leftover: () -> Set<RequestId> = { ApprovalNotifications.showing(context) },
) {
    fun start(): Job = scope.launch {
        ApprovalNotifications.ensureChannel(context)
        var shown = leftover()
        combine(pending, enabled.distinctUntilChanged()) { requests, allowed -> requests to allowed }.collect { (requests, allowed) ->
            val now = clock.now()
            val open = requests.filter { it.isOpenAt(now) }
            val (toPost, toCancel) = changes(shown, open)
            toCancel.forEach(cancel)
            // Only what Android took counts as shown; the rest is posted again once notifications are allowed.
            val posted = if (allowed) toPost.filter { post(it.id, it, now) }.mapTo(HashSet()) { it.id } else emptySet()
            shown = open.mapNotNullTo(HashSet()) { request -> request.id.takeIf { it in shown || it in posted } }
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
