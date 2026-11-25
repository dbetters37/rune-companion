package com.aicontrolcenter.app.di.wiring

import android.content.Context
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.backup.OwnerBackup
import com.aicontrolcenter.data.DataLayer

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
     * @param gate the process's one maintenance gate: a restore runs inside [MaintenanceGate.runExclusive], and
     *   starting an objective, sending a chat message and the upkeep job wait for it (WP7, WP2).
     */
    fun production(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") data: DataLayer,
        @Suppress("UNUSED_PARAMETER") gate: MaintenanceGate,
    ): OwnerBackup? = null
}
