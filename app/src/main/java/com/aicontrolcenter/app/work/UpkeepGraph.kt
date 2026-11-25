package com.aicontrolcenter.app.work

import android.content.Context
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.data.DataLayer

/**
 * One piece of upkeep another package adds to the upkeep job (WP17: folder pick-up). It runs after the job's own
 * steps and under the same checks, and like them never reaches a model, a tool or a provider (rule 1).
 */
fun interface UpkeepStep {
    suspend fun run()
}

/**
 * Everything the upkeep job may reach (Phase 6, rule 1, F3; WP2 owns this file in wave 2), built by the application
 * class without the app's graph: the process's one [DataLayer] (one Room instance on the SQLCipher file, so the
 * graph and the job share one invalidation tracker), the process's one memory service (offline hashing embedder),
 * and the maintenance gate (nothing runs while a backup is restored).
 *
 * @param context the application, for the no-backup folder (the start ledger) and the app's version.
 * @param steps upkeep other packages add, run after the job's own.
 */
@Phase6Stub("WP2")
class UpkeepGraph(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") data: DataLayer,
    @Suppress("UNUSED_PARAMETER") memory: MemoryService,
    @Suppress("UNUSED_PARAMETER") gate: MaintenanceGate,
    @Suppress("UNUSED_PARAMETER") steps: List<UpkeepStep> = emptyList(),
) {
    /** One run of the job. Wave 1: nothing. */
    suspend fun run() {}
}
