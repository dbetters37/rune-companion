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
import com.aicontrolcenter.core.operator.OwnerRequestKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    /**
     * The notification for [request], removed by Android at its expiry if nothing else removed it first. [reminder]: the
     * one reminder [REMINDER_BEFORE_MS] before the request expires, which alerts once more.
     *
     * A QUESTION (Phase 6, rule 4) carries only app-written words and the agent's name: "A question from Researcher. Open
     * the app to read it." Never the question, its suggested answers, the request's summary or the task's status text,
     * which an agent wrote.
     */
    fun build(context: Context, request: OwnerRequest, now: Long, reminder: Boolean = false): Notification {
        val agent = visible(request.agentName ?: "An agent")
        val question = request.kind == OwnerRequestKind.QUESTION
        val title = if (question) "A question from $agent" else request.kind.title
        val text = when {
            question && reminder -> "A question from $agent is still waiting. About 5 minutes are left; then $agent decides itself."
            question -> questionText(agent)
            reminder -> "$agent is still waiting for your answer. About 5 minutes are left: ${visible(request.summary)}"
            else -> "$agent: ${visible(request.summary)}"
        }
        val more = if (question) text else "$text\n\nOpen the app to see exactly what it would do and answer."
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (question) R.drawable.ic_stat_question else R.drawable.ic_stat_approval)
            .setContentTitle(if (question) "An agent has a question" else request.kind.title)
            .setContentText(if (question) "Open the app to read it." else "An agent is waiting for your answer.")
            .build()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (question) R.drawable.ic_stat_question else R.drawable.ic_stat_approval)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(more))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Hidden on the lock screen: what an agent wants to do is the owner's business.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            // The reminder alerts once more; otherwise an update never makes a sound again.
            .setOnlyAlertOnce(!reminder)
            .setAutoCancel(false)
            .setAllowSystemGeneratedContextualActions(false)
            .setWhen(request.createdAt)
            .setShowWhen(true)
            .setTimeoutAfter((request.expiresAt - now).coerceAtLeast(1))
            .setContentIntent(openRequest(context, request.id))
            .build()
    }

    /** What a question's notification says: app-written words and the agent's name only (rule 4). */
    fun questionText(agent: String): String = "A question from $agent. Open the app to read it."

    /** The one reminder comes this long before a request expires (WP2 item 5). */
    const val REMINDER_BEFORE_MS: Long = 5 * 60_000L

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
    /** The one reminder before a request expires (WP2 item 5); posted only while the request is still open. */
    private val remind: (RequestId, OwnerRequest, Long) -> Boolean = { id, request, now ->
        ApprovalNotifications.post(context, id, ApprovalNotifications.build(context, request, now, reminder = true))
    },
    /** Wakes the phone at the next reminder's wall-clock time, which a delay does not do while it sleeps (AND-W2B-3). */
    private val reminderAlarm: ReminderAlarm = ReminderAlarm.Android(context),
    /** Emits when the phone was woken for the waits or the reminders: the reminders look at the wall clock again. */
    private val wakes: Flow<Long> = WakeSignals.signals,
) {
    /** Starts [run] on the scope (tests; the app runs it under `SafetyCollectors`). */
    fun start(): Job = scope.launch { run() }

    /**
     * Keeps the notifications in step with the waiting requests until cancelled.
     *
     * One reminder per shown request, 5 minutes before it expires by the wall clock; none for one that goes sooner. It
     * is checked against the wall clock whenever anything changes, when a timer for the next one ends (the phone awake),
     * and when [reminderAlarm] woke the phone for it ([wakes]): a delay alone stops while the phone sleeps (AND-W2B-3).
     */
    suspend fun run() = coroutineScope {
        ApprovalNotifications.ensureChannel(context)
        var shown = leftover()
        // Per shown request, the wall-clock time of its reminder still to come; and those reminded, or with no room for it.
        val planned = HashMap<RequestId, Long>()
        val settled = HashSet<RequestId>()
        val ticks = MutableStateFlow(0L)
        var timer: Job? = null
        var alarmAt: Long? = null
        try {
            combine(pending, enabled.distinctUntilChanged(), wakes, ticks) { requests, allowed, _, _ -> requests to allowed }.collect { (requests, allowed) ->
                val now = clock.now()
                val open = requests.filter { it.isOpenAt(now) }
                val (toPost, toCancel) = changes(shown, open)
                toCancel.forEach(cancel)
                // Only what Android took counts as shown; the rest is posted again once notifications are allowed.
                val posted = if (allowed) toPost.filter { post(it.id, it, now) }.mapTo(HashSet()) { it.id } else emptySet()
                shown = open.mapNotNullTo(HashSet()) { request -> request.id.takeIf { it in shown || it in posted } }
                val shownNow = shown
                planned.keys.retainAll(shownNow)
                settled.retainAll(shownNow)
                for (request in open) {
                    if (request.id !in shownNow || request.id in planned || request.id in settled) continue
                    val at = request.expiresAt - ApprovalNotifications.REMINDER_BEFORE_MS
                    if (at <= now) settled += request.id else planned[request.id] = at
                }
                // Due by the wall clock, and the request still open: reminded, once.
                for (request in open) {
                    val at = planned[request.id] ?: continue
                    if (at > now) continue
                    planned.remove(request.id)
                    settled += request.id
                    remind(request.id, request, now)
                }
                val next = planned.values.minOrNull()
                if (next != alarmAt) {
                    alarmAt = next
                    if (next == null) reminderAlarm.cancel() else reminderAlarm.set(next)
                }
                timer?.cancel()
                timer = next?.let { at -> launch { delay(at - now); ticks.value += 1 } }
            }
        } finally {
            timer?.cancel()
            if (alarmAt != null) reminderAlarm.cancel()
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
