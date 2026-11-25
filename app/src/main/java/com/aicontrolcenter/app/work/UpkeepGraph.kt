package com.aicontrolcenter.app.work

import com.aicontrolcenter.app.health.StartLedger
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.usage.UsageHousekeeping
import com.aicontrolcenter.data.recovery.DatabaseHealth
import com.aicontrolcenter.framework.llm.UsageRetention
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.time.ZoneId

/**
 * One piece of upkeep another package adds to the upkeep job (WP17: folder pick-up). It runs after the job's own
 * steps and under the same checks, and like them never reaches a model, a tool or a provider (rule 1). A step is
 * built from narrow ports only (for pick-up, `RoutinesWiring.PickUpStepPorts`): never the data layer, the
 * application, a context or the app's graph (security review of wave 1, F11; `UpkeepIsolationTest` checks it).
 */
fun interface UpkeepStep {
    suspend fun run()
}

/**
 * Everything the upkeep job may reach (Phase 6, rule 1, F3; security review of wave 1, F11), each a narrow port the
 * application class builds without the app's graph: the process's one memory service (offline hashing embedder),
 * the activity log (pruning), usage housekeeping (retention; never today's rows), the settings read-only, the
 * maintenance gate (nothing runs while a backup is restored), the clock and the owner's zone, and where the start
 * ledger lives and which version this is (the job does nothing after an update until the app started once).
 *
 * No [com.aicontrolcenter.data.DataLayer], no `Context`, no application object and no graph: through any of them
 * the job could reach the owner's stores or build the agents' graph. The database check and the start ledger are
 * narrow ports of their own (`UpkeepIsolationTest` lists the field types it allows).
 */
class UpkeepPorts(
    val memory: MemoryService,
    val activityLog: ActivityLog,
    val usageHousekeeping: UsageHousekeeping,
    val settings: SettingsView,
    val gate: MaintenanceGate,
    val clock: Clock,
    val zone: () -> ZoneId,
    /** `noBackupFilesDir`: where the start ledger is (WP2). */
    val noBackupFilesDir: File,
    /** This build's versionCode: the job does nothing when the last successful start was another (WP2). */
    val versionCode: Long,
    /**
     * Whether the database opens (rule 1, R5). By default one read of the activity log, which opens the database
     * exactly as the app's own check does (Room runs the same open and the same migrations), sorted the same way.
     */
    val databaseCheck: DatabaseHealth = DatabaseHealth { activityLog.recent(limit = 1) },
    /** The start ledger: the version of the last start that reached "ok", and safe start. */
    val startLedger: StartLedger = StartLedger.inFolder(noBackupFilesDir),
)

/**
 * Where the upkeep job finds its [UpkeepGraph]: the application implements it, and the job reads it as
 * `(application as UpkeepHost).upkeep`, so the job's source never names the application class, through which it could
 * build the agents' graph (security review of wave 1, F11; `UpkeepIsolationTest` scans for it).
 */
interface UpkeepHost {
    val upkeep: UpkeepGraph
}

/**
 * The upkeep job's work (Phase 6, R9, D36, rule 1; WP2 item 11): memory upkeep (deleting expired short-term memories,
 * computing missing search vectors with the offline embedder), activity pruning (the owner's retention setting) and
 * usage retention ([UsageRetention]; never today's rows), then [steps] (folder pick-up, WP17). Never a model, a tool or a
 * provider check.
 *
 * It does nothing, and returns at once, when:
 * - the last start that reached "ok" was another version (after an update the app opens the database first, with its
 *   migrations, never the job), or no start reached "ok" yet;
 * - safe start is on (nothing runs by itself until the owner chooses);
 * - a backup is being restored ([MaintenanceGate.busy]), checked again before every step;
 * - the database does not open.
 *
 * A step that fails is skipped; the others still run.
 *
 * @param steps upkeep other packages add, run after the job's own.
 */
class UpkeepGraph(
    private val ports: UpkeepPorts,
    private val steps: List<UpkeepStep> = emptyList(),
) {
    /** Why the last [run] did nothing, or null when it ran its steps; for tests and the log. */
    @Volatile
    var lastSkip: Skip? = null
        private set

    /** Why a run did nothing. */
    enum class Skip { NOT_STARTED_AT_THIS_VERSION, SAFE_START, RESTORING, DATABASE }

    /** One run of the job. */
    suspend fun run() {
        lastSkip = whyNot()
        if (lastSkip != null) return
        step { ports.memory.maintain() }
        step {
            val days = ports.settings.get().activityRetentionDays
            if (days > 0) ports.activityLog.prune(olderThan = ports.clock.now() - days * MILLIS_PER_DAY)
        }
        step { UsageRetention(ports.usageHousekeeping, ports.settings, ports.clock, ports.zone).prune() }
        for (extra in steps) step { extra.run() }
    }

    private suspend fun whyNot(): Skip? {
        val ledger = withContext(Dispatchers.IO) { ports.startLedger.read() }
        if (ledger.lastOkVersion != ports.versionCode) return Skip.NOT_STARTED_AT_THIS_VERSION
        if (ledger.safeStart) return Skip.SAFE_START
        if (ports.gate.busy.value) return Skip.RESTORING
        if (ports.databaseCheck.check() != DatabaseState.OK) return Skip.DATABASE
        return null
    }

    private suspend fun step(block: suspend () -> Unit) {
        if (ports.gate.busy.value) return
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // One step failing never stops the others; the next run tries again.
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
