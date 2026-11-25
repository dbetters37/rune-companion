package com.aicontrolcenter.app.di.wiring

import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.backup.OwnerBackup
import com.aicontrolcenter.data.BackupStores

/*
 * The backup file (Phase 6, Tier B, WP3 owns this file in wave 2; rules 8, 21, 27, 30).
 *
 * Null means "no backup in this build": the Settings section that offers it stays hidden (WP10).
 */

@Phase6Stub("WP3")
object BackupWiring {
    /**
     * The owner's backup port (owner-only; both architecture tests list [OwnerBackup]). Called once per process by
     * `createProductionPorts`.
     *
     * @param stores what a backup saves and restores, and nothing else (security review of wave 1, F13): never the key
     *   vault, the grants, the requests to the owner, the shared folders or the authorised screen apps, so rule 8's
     *   exclusions do not rest on WP3's code alone (`BackupWiringTest` walks it).
     * @param gate the process's one maintenance gate: a restore runs inside [MaintenanceGate.runExclusive], and
     *   starting an objective, sending a chat message and the upkeep job wait for it (WP7, WP2).
     * @param freeBytes how many bytes the app may still write (WP3: a restore needs twice the file's size first).
     */
    fun production(
        @Suppress("UNUSED_PARAMETER") stores: BackupStores,
        @Suppress("UNUSED_PARAMETER") gate: MaintenanceGate,
        @Suppress("UNUSED_PARAMETER") freeBytes: suspend () -> Long,
    ): OwnerBackup? = null
}
