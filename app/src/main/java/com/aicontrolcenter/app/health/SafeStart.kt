package com.aicontrolcenter.app.health

import com.aicontrolcenter.core.health.ExitKind
import com.aicontrolcenter.core.health.ExitRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why safe start went on (Phase 6, rule 10). */
enum class SafeStartReason(
    /** A plain sentence for the banner (WP10). */
    val ownerText: String,
) {
    STARTS_WITHOUT_OK("The app did not finish starting twice in a row."),
    CRASHES("The app stopped because of an error three times within 15 minutes."),
}

/**
 * Safe start (Phase 6, R2, rule 10; Tier B): after two starts in a row that never reached "ok", or three crashes of the
 * main process (CRASH, CRASH_NATIVE, ANR) within the 15 minutes before a start, nothing resumes by itself (interrupted objectives wait for the
 * owner, the upkeep job does nothing) until the owner chooses. Exits of the script process never count (rule 31): the
 * exit history reports the main process only. Pure: decided from the ledger and the new exits.
 */
object SafeStart {
    /** Starts in a row without "ok" before this one that turn safe start on. */
    const val STARTS_WITHOUT_OK: Int = 2

    /** Crashes within [CRASH_WINDOW_MS] that turn safe start on. */
    const val CRASHES: Int = 3

    const val CRASH_WINDOW_MS: Long = 15 * 60_000L

    /** The exit kinds that count as crashes. */
    val CRASH_KINDS: Set<ExitKind> = setOf(ExitKind.CRASH, ExitKind.CRASH_NATIVE, ExitKind.ANR)

    /**
     * The ledger at the start of a run of [versionCode] at [now], given the main-process exits Android reported since the
     * last one looked at ([newExits], oldest first): this start counts as started (not yet "ok"), the exits as seen, the
     * crashes among them as recent crashes, and safe start goes on if either rule says so (it never goes off here).
     */
    fun atStart(entry: StartLedger.Entry, versionCode: Long, now: Long, newExits: List<ExitRecord>): StartLedger.Entry {
        val crashes = (entry.recentCrashes + newExits.filter { it.kind in CRASH_KINDS }.map { it.at }).sorted().takeLast(10)
        val reason = when {
            entry.safeStart -> entry.safeStartReason
            entry.startsWithoutOk >= STARTS_WITHOUT_OK -> SafeStartReason.STARTS_WITHOUT_OK
            crashBurst(crashes, now) -> SafeStartReason.CRASHES
            else -> null
        }
        return entry.copy(
            lastStartedVersion = versionCode,
            lastStartedAt = now,
            startsWithoutOk = entry.startsWithoutOk + 1,
            safeStart = entry.safeStart || reason != null,
            safeStartReason = reason,
            lastExitSeenAt = maxOf(entry.lastExitSeenAt, newExits.maxOfOrNull { it.at } ?: 0),
            recentCrashes = crashes,
        )
    }

    /** The ledger once the start-up steps of [versionCode] are done ("ok"). Safe start stays as it is. */
    fun atOk(entry: StartLedger.Entry, versionCode: Long, now: Long): StartLedger.Entry =
        entry.copy(startsWithoutOk = 0, lastOkVersion = versionCode, lastOkAt = now)

    /** The owner chose: safe start goes off, and the crashes that turned it on are forgotten. */
    fun left(entry: StartLedger.Entry): StartLedger.Entry =
        entry.copy(safeStart = false, safeStartReason = null, startsWithoutOk = 0, recentCrashes = emptyList())

    /**
     * True when [CRASHES] of [times] fall within the [CRASH_WINDOW_MS] before [now]: the app is crashing again and again
     * right now. Older bursts (say, in the history Android kept from before an update) do not count.
     */
    fun crashBurst(times: List<Long>, now: Long): Boolean = times.count { it in (now - CRASH_WINDOW_MS)..now } >= CRASHES
}

/** What the safe-start banner shows (WP10): off, or on with its reason. */
data class SafeStartState(val on: Boolean, val reason: SafeStartReason? = null) {
    companion object {
        val OFF: SafeStartState = SafeStartState(on = false)
    }
}

/**
 * Safe start for the owner's banner (WP10, Tier B): whether it is on, and the owner's choice to leave it. Owner-only in
 * spirit: only the banner holds it; agents never see it.
 */
interface SafeStartControl {
    val state: StateFlow<SafeStartState>

    /** The owner chose: interrupted work may resume by itself again at the next start (if the settings allow it). */
    suspend fun leave()

    /** Never on. */
    object None : SafeStartControl {
        override val state: StateFlow<SafeStartState> = MutableStateFlow(SafeStartState.OFF).asStateFlow()

        override suspend fun leave() {}
    }
}
