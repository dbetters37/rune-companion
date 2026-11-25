package com.aicontrolcenter.app.work

import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.usage.UsageHousekeeping
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
 * the job could reach the owner's stores or build the agents' graph. WP2 adds what the database check and the start
 * ledger need here as narrow ports of their own (`UpkeepIsolationTest` lists the field types it allows).
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
 * The upkeep job's work (Phase 6, rule 1; WP2 owns this file in wave 2): memory upkeep, activity pruning and usage
 * housekeeping through [ports], then [steps] (folder pick-up, WP17), all only while the database is fine, no backup
 * is being restored and the app started once at this version.
 *
 * @param steps upkeep other packages add, run after the job's own.
 */
@Phase6Stub("WP2")
class UpkeepGraph(
    @Suppress("UNUSED_PARAMETER") ports: UpkeepPorts,
    @Suppress("UNUSED_PARAMETER") steps: List<UpkeepStep> = emptyList(),
) {
    /** One run of the job. Wave 1: nothing. */
    suspend fun run() {}
}
