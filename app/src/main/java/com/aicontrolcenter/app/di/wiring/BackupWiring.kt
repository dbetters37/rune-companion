package com.aicontrolcenter.app.di.wiring

import com.aicontrolcenter.app.BuildConfig
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.backup.OwnerBackup
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.data.BackupStores
import com.aicontrolcenter.data.backup.BackupAppInfo
import com.aicontrolcenter.data.backup.DetailedOwnerBackup
import com.aicontrolcenter.data.backup.RoomOwnerBackup
import com.aicontrolcenter.data.backup.StarterContent
import com.aicontrolcenter.data.backup.ToolTitles
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.framework.agents.AgentTemplates
import com.aicontrolcenter.framework.agents.ToolMenu
import com.aicontrolcenter.framework.llm.Simulator

/*
 * The backup file (Phase 6, Tier B, WP3 owns this file in wave 2; rules 8, 21, 27, 30).
 *
 * Null means "no backup in this build": the Settings section that offers it stays hidden (WP10). If the backup is cut
 * after the review (Tier B), `production` returns null again and nothing else changes.
 */

object BackupWiring {
    /**
     * The owner's backup port (owner-only; both architecture tests list [OwnerBackup]). Called once per process by
     * `createProductionPorts`. It is a [DetailedOwnerBackup]: the screens (WP10) also need the save's notes, the
     * restore's settings kept back and a refusal known before the owner confirms.
     *
     * @param stores what a backup saves and restores, and nothing else (security review of wave 1, F13): never the key
     *   vault, the grants, the requests to the owner, the shared folders or the authorised screen apps, so rule 8's
     *   exclusions do not rest on WP3's code alone (`BackupWiringTest` walks it).
     * @param gate the process's one maintenance gate: a restore runs inside [MaintenanceGate.runExclusive], and
     *   starting an objective, sending a chat message and the upkeep job wait for it (WP7, WP2).
     * @param freeBytes how many bytes the app may still write (a restore needs twice the file's size first).
     */
    fun production(stores: BackupStores, gate: MaintenanceGate, freeBytes: suspend () -> Long): DetailedOwnerBackup? =
        RoomOwnerBackup(stores, gate, freeBytes, app = APP, starter = STARTER, toolTitles = ToolTitles(FIXED_TITLES))

    /** What a backup's header says about this build. */
    val APP = BackupAppInfo(appVersion = BuildConfig.VERSION_NAME, appVersionCode = BuildConfig.VERSION_CODE, schemaVersion = AiccDatabase.VERSION)

    /** What a fresh app holds: the starter team's templates and the built-in simulator (a restore replaces the first, keeps the second). */
    val STARTER = StarterContent(agentTemplates = AgentTemplates.starterTeam.toSet(), builtInProviders = setOf(Simulator.PROVIDER_ID))

    /** Tools the registry does not hold: `tools_open` is offered by the runner, not registered (WP15). */
    val FIXED_TITLES: Map<String, String> = mapOf(ToolMenu.FUNCTION_NAME to ToolMenu.TITLE)

    /**
     * From now on [backup] names tools by the titles [registry] gives ("A tool was used here: Read the screen."); until
     * then, by the function name. Called once the graph's tool registry exists (WP8: one line in AppGraph or
     * `AppProcessHooks.onGraphBuilt`). Gives the backup a way to look titles up, nothing else of the registry.
     */
    fun useToolTitles(backup: OwnerBackup?, registry: ToolRegistry) {
        (backup as? RoomOwnerBackup)?.toolTitles?.use { name -> registry.byFunctionName(name)?.spec?.title }
    }
}
