package com.aicontrolcenter.app.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aicontrolcenter.app.MainActivity
import com.aicontrolcenter.app.R
import com.aicontrolcenter.app.ui.approvals.visible
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.ObjectiveRow
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * How an objective ended, as the owner reads it in a notification (Phase 6, A6, rule 4): app-written words and the
 * owner's own objective title, nothing an agent wrote (never a task's status text, error or result).
 */
data class WorkResult(
    val objectiveId: TaskId,
    val kind: Kind,
    /** "Finished: Compare note apps", "Stopped: it ran out of time". */
    val title: String,
    val text: String,
) {
    enum class Kind(
        /** Shown on the lock screen instead of [title]. */
        val publicTitle: String,
        @DrawableRes val icon: Int,
    ) {
        FINISHED("An objective is finished", R.drawable.ic_stat_done),
        STOPPED("An objective stopped", R.drawable.ic_stat_stopped),
        PAUSED("An objective is paused", R.drawable.ic_stat_pause),
        INTERRUPTED("An objective was interrupted", R.drawable.ic_stat_stopped),
    }

    companion object {
        /** Work that is still going on: a change from one of these to an end is worth a notification. */
        private val GOING = setOf(
            TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.WAITING_FOR_AGENT, TaskStatus.WAITING_FOR_OWNER, TaskStatus.IN_REVIEW,
        )

        /** "Paused: the phone is almost full" (WP2 item 10): what the service says when it pauses objectives. */
        const val STORAGE_FULL: String = "Paused: the phone is almost full"

        /** The objective's own status text when the service paused it for room (wave 3: not "Paused by the owner."). */
        const val STORAGE_PAUSED_DETAIL: String = "Paused: the phone is almost full. Free some room, then resume it."

        /**
         * The result for [row], which was [before] at the last look, or null when nothing is worth saying (still going,
         * paused by the owner, or it was not going before). [interruptCause] is the app-written cause of the last
         * interruption ("Android closed the app to free memory").
         */
        fun of(row: ObjectiveRow, before: TaskStatus, interruptCause: String?): WorkResult? {
            if (before !in GOING || row.status == before) return null
            val name = visible(row.title)
            return when (row.status) {
                TaskStatus.COMPLETED -> WorkResult(row.rootId, Kind.FINISHED, "Finished: $name", "Open the app to see the result.")
                TaskStatus.FAILED -> stopped(row, "it could not be finished")
                TaskStatus.TIMED_OUT -> stopped(row, "it ran out of time")
                TaskStatus.CANCELLED -> stopped(row, "it was cancelled")
                TaskStatus.INTERRUPTED -> WorkResult(
                    row.rootId, Kind.INTERRUPTED, "Interrupted: ${interruptCause ?: "the app was stopped"}", "$name. Open the app to resume it.",
                )
                // Only today's limit, recognised by its exact app-written text: a pause by the owner needs no notice.
                TaskStatus.PAUSED -> if (row.statusDetailShort == DailyLimits.PAUSED_TEXT) {
                    WorkResult(row.rootId, Kind.PAUSED, "Paused: today's limit is reached", "$name. Resume after midnight or raise the limit in Settings.")
                } else {
                    null
                }
                else -> null
            }
        }

        /** What the service posts when it paused [title] because the phone is almost full. */
        fun storageFull(objectiveId: TaskId, title: String): WorkResult =
            WorkResult(objectiveId, Kind.PAUSED, STORAGE_FULL, "${visible(title)}. Free some room on the phone, then resume it in the app.")

        private fun stopped(row: ObjectiveRow, reason: String) =
            WorkResult(row.rootId, Kind.STOPPED, "Stopped: $reason", "${visible(row.title)}. Open the app to see what happened.")
    }
}

/**
 * The "Results" notifications (Phase 6, A6, rule 4; WP2 item 5): one per objective that ended while the app was not on
 * screen. They have no actions; a tap opens the objective. Private on the lock screen, with a public version that names
 * nothing.
 */
internal object ResultNotifications {
    const val CHANNEL_ID = "results"

    /** With the objective's id as the tag: one notification per objective, replaced by its next result. */
    const val NOTIFICATION_ID = 4_301

    /** Default importance: the owner wants to know, but nothing waits for an answer. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Results", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When an objective is finished, stops, is paused or was interrupted while you are away from the app."
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, result: WorkResult): Notification {
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(result.kind.icon)
            .setContentTitle(result.kind.publicTitle)
            .setContentText("Open the app to see it.")
            .build()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(result.kind.icon)
            .setContentTitle(result.title)
            .setContentText(result.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(result.text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setAllowSystemGeneratedContextualActions(false)
            .setContentIntent(openObjective(context, result.objectiveId))
            .build()
    }

    /** Returns whether Android took it. */
    fun post(context: Context, result: WorkResult): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        ensureChannel(context)
        return try {
            NotificationManagerCompat.from(context).notify(result.objectiveId.value, NOTIFICATION_ID, build(context, result))
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Opens the objective, through the extra the objectives notification already uses (no new entry point, rule 23). */
    private fun openObjective(context: Context, id: TaskId): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OBJECTIVE_ID, id.value)
        return PendingIntent.getActivity(context, id.value.hashCode() xor RESULT_CODES, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private const val RESULT_CODES = 0x5245_5355
}

/**
 * Posts a result notification when an objective ends while the app is not on screen and the owner's setting "Notify me
 * when work ends" is on (Phase 6, A6). It watches objective rows (summaries, rule 16) and compares each with what it
 * was at the last look; nothing is said about what had already ended when it started.
 *
 * @param rows the newest objectives' rows (`TaskRepository.observeObjectiveRows`).
 * @param inForeground whether the app is on screen now.
 * @param interruptCause the app-written cause of the last interruption, for "Interrupted: …".
 */
class ResultNotifier(
    private val context: Context,
    private val rows: Flow<List<ObjectiveRow>>,
    private val inForeground: StateFlow<Boolean>,
    private val settings: SettingsView,
    private val interruptCause: suspend () -> String?,
    private val post: (WorkResult) -> Unit = { ResultNotifications.post(context, it) },
) {
    /** Starts [run] on [scope] (tests). */
    fun start(scope: CoroutineScope): Job = scope.launch { run() }

    suspend fun run() {
        ResultNotifications.ensureChannel(context)
        var known: Map<TaskId, TaskStatus>? = null
        rows.collect { list ->
            val before = known
            known = list.associate { it.rootId to it.status }
            if (before == null || inForeground.value) return@collect
            val changed = list.mapNotNull { row -> before[row.rootId]?.let { row to it } }.filter { (row, was) -> row.status != was }
            if (changed.isEmpty() || !settings.get().notifyWhenWorkEnds) return@collect
            for ((row, was) in changed) {
                val cause = if (row.status == TaskStatus.INTERRUPTED) interruptCause() else null
                WorkResult.of(row, was, cause)?.let(post)
            }
        }
    }

    companion object {
        /** How many of the newest objectives are watched. */
        const val ROWS: Int = 30
    }
}
