package com.aicontrolcenter.app.di.wiring

import android.content.Context
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.di.Ports
import com.aicontrolcenter.app.work.UpkeepStep
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.pickup.OwnerPickUpRules
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.routines.OwnerRoutines
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.data.DataLayer
import kotlinx.coroutines.CoroutineScope

/*
 * Routines (objectives started at set times) and automatic folder pick-up (Phase 6, owner decision Q1 = "Yes, both";
 * plan Appendix A; Tier B). WP17 owns this file, the framework and app routines packages and their tests in wave 2.
 *
 * Wave 1 (WP1d) wires the schema-6 stores and nothing else: no routine runs, no alarm is set, the alarm and boot
 * receivers stay disabled in the manifest, and both master switches (`routinesEnabled`, `folderPickUpEnabled`) are
 * off after installing.
 */

/**
 * The stores of routines and pick-up rules (schema 6). Both are owner-capable (both architecture tests list them as
 * owner-only): only the owner's ports and WP17's runners may hold them, never anything agent-facing.
 */
class RoutinesPorts(
    val routines: RoutineRepository? = null,
    val pickUpRules: PickUpRuleRepository? = null,
)

/**
 * Routines and folder pick-up as the composition root builds them, after the orchestrator (a routine starts an
 * objective through it).
 *
 * @param ports the app's stores, read here only (WP17: `ports.routines`, `ports.usageReports` for the daily limits
 *   checked at run time).
 * @param settings read-only: the runner checks the master switch, local-only mode and the limits at run time.
 * @param orchestrator starts a routine's objective.
 * @param memory the one memory service of the process (folder pick-up stores into it).
 * @param activity where every run and every skipped run is logged.
 * @param appScope lives as long as the process.
 */
@Phase6Stub("WP17")
class RoutinesAssembly(
    @Suppress("UNUSED_PARAMETER") ports: Ports,
    @Suppress("UNUSED_PARAMETER") settings: SettingsView,
    @Suppress("UNUSED_PARAMETER") orchestrator: Orchestrator,
    @Suppress("UNUSED_PARAMETER") memory: MemoryService,
    @Suppress("UNUSED_PARAMETER") activity: ActivityRecorder,
    @Suppress("UNUSED_PARAMETER") appScope: CoroutineScope,
) {
    /** Owner-only: the Routines screen (WP10). Null until WP17 builds it; the screen then stays hidden. */
    val ownerRoutines: OwnerRoutines? = null

    /** Owner-only: the folder pick-up screen (WP10). Null until WP17 builds it; the screen then stays hidden. */
    val ownerPickUpRules: OwnerPickUpRules? = null
}

@Phase6Stub("WP17")
object RoutinesWiring {
    /** The production stores. Called once per process by `createProductionPorts`; opens nothing until first used. */
    fun production(@Suppress("UNUSED_PARAMETER") context: Context, data: DataLayer): RoutinesPorts =
        RoutinesPorts(routines = data.routines, pickUpRules = data.pickUpRules)

    /**
     * Upkeep that folder pick-up adds to the daily upkeep job (Appendix A: "run by the upkeep job's scheduler"). Run
     * by `UpkeepGraph` after its own steps and under the same checks (rule 1: never a model, a tool or a provider).
     * None in wave 1.
     */
    fun upkeepSteps(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") data: DataLayer,
        @Suppress("UNUSED_PARAMETER") memory: MemoryService,
    ): List<UpkeepStep> = emptyList()
}
