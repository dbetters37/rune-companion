package com.aicontrolcenter.device.health

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.aicontrolcenter.core.health.ExitHistory
import com.aicontrolcenter.core.health.ExitKind
import com.aicontrolcenter.core.health.ExitRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Why the app's main process ended before, as Android keeps it (Phase 6, R1, rule 31; WP2 item 6), read live at start:
 * `ActivityManager.getHistoricalProcessExitReasons` (Android 11, API 30, and later).
 *
 * - Only records of the main process count (`processName == applicationInfo.processName`): the script process
 *   (`<package>:scripts…`) shares the package's history, and its exits are never the app's (rule 31, F6).
 * - [newSince] returns the exits after the given time, oldest first; the caller passes the time of the last exit it
 *   processed, so each is processed once.
 * - Android's description text is never stored or shown (S20). It is read for one thing only: Android 17.0 reports its
 *   memory limiter as `REASON_OTHER` with "MemoryLimiter" in the description (F18).
 * - On Android 9 and 10 there is no such history: nothing is reported ("Android 9 and 10 do not say why").
 *
 * @param sdkInt the phone's API level; injected so the older branch is tested on the JVM (F2).
 */
class AndroidExitHistory(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ExitHistory {
    private val appContext = context.applicationContext

    override suspend fun newSince(ms: Long): List<ExitRecord> {
        if (sdkInt < Build.VERSION_CODES.R) return emptyList()
        return withContext(io) { read(ms) }
    }

    private fun read(ms: Long): List<ExitRecord> {
        val manager = appContext.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val mainProcess = appContext.applicationInfo.processName ?: appContext.packageName
        val exits = try {
            // 0, 0: every process of the package, as many as Android keeps (the script process's push the app's out, so
            // the history is read at every start).
            manager.getHistoricalProcessExitReasons(appContext.packageName, 0, 0)
        } catch (_: RuntimeException) {
            // A security or system failure: nothing is known, which is not a reason to stop starting.
            return emptyList()
        }
        return exits.filter { it.processName == mainProcess && it.timestamp > ms }
            .sortedBy { it.timestamp }
            .map { ExitRecord(at = it.timestamp, kind = kindOf(it.reason, it.description), importance = it.importance, pssKb = it.pss, rssKb = it.rss) }
    }

    companion object {
        /** `ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE` (API 34). */
        const val REASON_PACKAGE_STATE_CHANGE: Int = 15

        /** `ApplicationExitInfo.REASON_PACKAGE_UPDATED` (API 34). */
        const val REASON_PACKAGE_UPDATED: Int = 16

        /** `ApplicationExitInfo.REASON_MEMORY_LIMITER` (SDK 37.2). */
        const val REASON_MEMORY_LIMITER: Int = 17

        /** `ApplicationExitInfo.REASON_ANOMALY` (SDK 37.2): Android stopped the app for using too many resources. */
        const val REASON_ANOMALY: Int = 18

        /** What Android 17.0 writes into the description of a memory-limiter exit reported as `REASON_OTHER`. */
        const val MEMORY_LIMITER_MARK: String = "MemoryLimiter"

        /**
         * The kind for Android's [reason] code. [description] is looked at only for the memory limiter of Android 17.0
         * and is never kept.
         */
        fun kindOf(reason: Int, description: String?): ExitKind = when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> ExitKind.LOW_MEMORY
            ApplicationExitInfo.REASON_CRASH -> ExitKind.CRASH
            ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitKind.CRASH_NATIVE
            ApplicationExitInfo.REASON_ANR -> ExitKind.ANR
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ExitKind.CRASH
            // A swipe from Recents and "Force stop" (F7); before Android 14 also an update.
            ApplicationExitInfo.REASON_USER_REQUESTED -> ExitKind.USER_REQUESTED
            ApplicationExitInfo.REASON_USER_STOPPED -> ExitKind.OTHER
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> ExitKind.PERMISSION_CHANGE
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ExitKind.OTHER
            ApplicationExitInfo.REASON_FREEZER -> ExitKind.FREEZER
            REASON_PACKAGE_UPDATED -> ExitKind.UPDATED
            REASON_PACKAGE_STATE_CHANGE -> ExitKind.OTHER
            REASON_MEMORY_LIMITER -> ExitKind.MEMORY_LIMIT
            REASON_ANOMALY -> ExitKind.OTHER
            ApplicationExitInfo.REASON_OTHER ->
                if (description?.contains(MEMORY_LIMITER_MARK, ignoreCase = true) == true) ExitKind.MEMORY_LIMIT else ExitKind.OTHER
            ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_DEPENDENCY_DIED -> ExitKind.OTHER
            ApplicationExitInfo.REASON_UNKNOWN -> ExitKind.UNKNOWN
            else -> ExitKind.UNKNOWN
        }
    }
}
