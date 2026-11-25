package com.aicontrolcenter.core.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * What the app knows about its own health on the phone (Phase 6, K12): why Android ended its
 * process, whether Android lets it work in the background, how much room is left, whether there
 * is a network, and when the next timed wait is due. Implemented in `:device` (WP2, WP4); every
 * port has a `None` (or `AlwaysOn`) value for tests and for builds without the Android side.
 */

/** Why the app's main process last ended, as Android reports it (`ApplicationExitInfo.REASON_*`). */
enum class ExitKind(
    /** An app-written phrase for the owner: "Interrupted: Android closed the app to free memory." */
    val cause: String,
) {
    LOW_MEMORY("Android closed the app to free memory"),
    CRASH("the app stopped because of an error"),
    CRASH_NATIVE("the app stopped because of an error in the phone's system code"),
    ANR("the app stopped answering and Android closed it"),
    USER_REQUESTED("the app was closed from the phone's recent apps or its settings"),
    UPDATED("the app was updated"),
    MEMORY_LIMIT("the app used more memory than Android allows"),
    PERMISSION_CHANGE("a permission of the app was changed"),
    FREEZER("Android froze the app in the background"),
    OTHER("Android closed the app"),
    UNKNOWN("the app stopped; Android does not say why"),
}

/**
 * One end of the app's main process. Numbers only: Android's description text is never stored
 * or shown (S20). [importance] is `ApplicationExitInfo.getImportance()`; sizes in kilobytes.
 */
data class ExitRecord(
    val at: Long,
    val kind: ExitKind,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
)

/**
 * Android's record of how the app ended (API 30+), read live at start (R1). Only exits of the
 * main process (`processName == applicationInfo.processName`) are reported, never those of the
 * script process (rule 31), and each one once: [newSince] returns the exits after [ms], oldest first.
 */
interface ExitHistory {
    suspend fun newSince(ms: Long): List<ExitRecord>

    /** Knows no exits (Android 9 and 10 do not say why). */
    object None : ExitHistory {
        override suspend fun newSince(ms: Long): List<ExitRecord> = emptyList()
    }
}

/** Android's app standby buckets (`UsageStatsManager.STANDBY_BUCKET_*`, API 28). */
enum class StandbyBucket { ACTIVE, WORKING_SET, FREQUENT, RARE, RESTRICTED, EXEMPTED, UNKNOWN }

/**
 * Whether Android lets the app work in the background (A3). A null means "not known on this
 * Android version". The Background check shows one line per field with a button that opens
 * Android's own screen; the owner decides there.
 */
data class BackgroundState(
    val notificationsAllowed: Boolean,
    /** True while battery optimisation applies to the app (it is not exempt). */
    val batteryOptimised: Boolean?,
    /** True when the owner restricted the app's background use. */
    val backgroundRestricted: Boolean?,
    val standbyBucket: StandbyBucket?,
) {
    /** Warn only for the rare and restricted buckets. */
    val standbyWarning: Boolean get() = standbyBucket == StandbyBucket.RARE || standbyBucket == StandbyBucket.RESTRICTED

    /** True when every known item lets background work go on. */
    val allClear: Boolean get() = notificationsAllowed && batteryOptimised != true && backgroundRestricted != true && !standbyWarning

    /** Constants. */
    companion object {
        /** What is assumed before the first check: nothing known, nothing to warn about. */
        val UNKNOWN: BackgroundState = BackgroundState(notificationsAllowed = true, batteryOptimised = null, backgroundRestricted = null, standbyBucket = null)
    }
}

/** Reads [BackgroundState] from Android; [refresh] reads it again (the app comes to the front, the owner returns from a settings screen). */
interface BackgroundHealth {
    val state: StateFlow<BackgroundState>

    suspend fun refresh()

    /** Always [BackgroundState.UNKNOWN]. */
    object None : BackgroundHealth {
        override val state: StateFlow<BackgroundState> = MutableStateFlow(BackgroundState.UNKNOWN).asStateFlow()

        override suspend fun refresh() {}
    }
}

/** How much room is left for the app's data (R8). */
enum class StorageLevel {
    OK,

    /** Under [LOW_BYTES]: a Dashboard warning. */
    LOW,

    /** Under [CRITICAL_BYTES]: running objectives pause ("Paused: the phone is almost full") and new ones are refused. */
    CRITICAL,

    /** Not measured yet, or Android did not say. */
    UNKNOWN,
    ;

    /** Thresholds. */
    companion object {
        const val LOW_BYTES: Long = 500L * 1024 * 1024
        const val CRITICAL_BYTES: Long = 100L * 1024 * 1024

        /** The level for [allocatableBytes] free (`StorageManager.getAllocatableBytes`), UNKNOWN for null. */
        fun of(allocatableBytes: Long?): StorageLevel = when {
            allocatableBytes == null -> UNKNOWN
            allocatableBytes < CRITICAL_BYTES -> CRITICAL
            allocatableBytes < LOW_BYTES -> LOW
            else -> OK
        }
    }
}

/** Watches the free room: at start, before an objective starts, and every 5 minutes while one runs. */
interface StorageWatch {
    val level: StateFlow<StorageLevel>

    /** Measures now, publishes and returns the level. */
    suspend fun check(): StorageLevel

    /** Always UNKNOWN. */
    object None : StorageWatch {
        override val level: StateFlow<StorageLevel> = MutableStateFlow(StorageLevel.UNKNOWN).asStateFlow()

        override suspend fun check(): StorageLevel = StorageLevel.UNKNOWN
    }
}

/** Whether a network is available (`ConnectivityManager` default-network callback); provider re-checks start when it comes back. */
interface NetworkStatus {
    val available: StateFlow<Boolean>

    /** Always available: tests and builds without the Android side. */
    object AlwaysOn : NetworkStatus {
        override val available: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    }
}

/**
 * Something that waits until a time and then tries again: the orchestrator's tasks with a
 * `retryAt`, the provider manager's re-checks (Phase 6, rule 5, F8). The background service keeps
 * the phone awake while [nextDueAt] is within 2 minutes and sets one wake alarm for a later one;
 * the alarm's receiver calls [wake], after which the waiter looks again at the wall clock.
 */
interface TimedWaits {
    /** When the next wait is due (epoch ms), or null when nothing waits. */
    val nextDueAt: StateFlow<Long?>

    /** Look again now: waits that are due go on. Safe to call at any time, from any thread. */
    fun wake()

    /** Nothing waits. */
    object None : TimedWaits {
        override val nextDueAt: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()

        override fun wake() {}
    }

    /** Rule 5. */
    companion object {
        /** A wait due within this time keeps the phone awake; a later one gets a wake alarm. */
        const val KEEP_AWAKE_WITHIN_MS: Long = 2 * 60_000L
    }
}
