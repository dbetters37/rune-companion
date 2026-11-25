package com.aicontrolcenter.app.health

import com.aicontrolcenter.app.di.StartupHooks
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.health.BackgroundHealth
import com.aicontrolcenter.core.health.ExitHistory
import com.aicontrolcenter.core.health.StorageWatch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * What start-up does around its steps on the phone (Phase 6, WP2 items 6, 7, 10, 11; rules 10 and 31):
 * - before the steps: reads the main process's exits since the last one looked at (R1; the script process's never
 *   count, rule 31), notes the start in the [ledger] and decides safe start (rule 10), logs a crash note left by the last
 *   run (class and app frames only), measures the free room and reads the background check;
 * - at the recovery step: the cause of the last exit in app-written words ("Android closed the app to free memory"),
 *   never Android's own text; and whether interrupted work may resume by itself (not while safe start is on);
 * - after the steps: "ok" in the ledger, then the upkeep job's schedule.
 *
 * @param scheduleUpkeep schedules the daily upkeep job (idempotent); called once the start reached "ok".
 * @param recorder the activity log, or null when there is none to write to.
 */
class AndroidStartupHooks(
    private val ledger: StartLedger,
    private val exits: ExitHistory,
    private val storage: StorageWatch,
    private val health: BackgroundHealth,
    private val crashNotes: CrashNotes,
    private val versionCode: Long,
    private val clock: Clock,
    private val scheduleUpkeep: () -> Unit,
    private val recorder: ActivityRecorder?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StartupHooks {
    @Volatile
    private var cause: String? = null

    /** Read from the ledger at [beforeSteps] (never when this is built: that may be on the main thread). */
    private val safe = MutableStateFlow(SafeStartState.OFF)

    /** Safe start for the owner's banner (WP10). */
    val safeStart: SafeStartControl = object : SafeStartControl {
        override val state: StateFlow<SafeStartState> = safe.asStateFlow()

        override suspend fun leave() {
            val entry = withContext(io) { ledger.update(SafeStart::left) }
            safe.value = stateOf(entry)
            recorder?.record(ActivityType.USER_ACTION, "Left safe start", "Interrupted work may resume by itself again at the next start, if your settings allow it.")
        }
    }

    override suspend fun beforeSteps() {
        val before = withContext(io) { ledger.read() }
        val newExits = exits.newSince(before.lastExitSeenAt)
        val now = clock.now()
        val entry = withContext(io) { ledger.update { SafeStart.atStart(it, versionCode, now, newExits) } }
        cause = newExits.lastOrNull()?.kind?.cause
        safe.value = stateOf(entry)
        if (entry.safeStart && !before.safeStart) {
            recorder?.record(
                ActivityType.SYSTEM, "Safe start is on",
                "${entry.safeStartReason?.ownerText ?: ""} Interrupted work waits for you: nothing resumes by itself until you choose.".trim(),
                Severity.WARNING,
            )
        }
        val notes = withContext(io) { crashNotes.since(before.lastStartedAt ?: 0) }
        notes.lastOrNull()?.let { note ->
            recorder?.record(ActivityType.ERROR, "The app stopped because of an error", "Where it failed: ${note.summary}.", Severity.WARNING)
        }
        storage.check()
        health.refresh()
    }

    override suspend fun interruptCause(): String? = cause

    override suspend fun resumeAllowed(): Boolean = !withContext(io) { ledger.read() }.safeStart

    override suspend fun afterSteps() {
        val now = clock.now()
        withContext(io) { ledger.update { SafeStart.atOk(it, versionCode, now) } }
        scheduleUpkeep()
    }

    private fun stateOf(entry: StartLedger.Entry) = if (entry.safeStart) SafeStartState(true, entry.safeStartReason) else SafeStartState.OFF
}
