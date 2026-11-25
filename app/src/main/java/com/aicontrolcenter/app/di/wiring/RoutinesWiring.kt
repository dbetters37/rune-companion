package com.aicontrolcenter.app.di.wiring

import android.content.Context
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.di.Ports
import com.aicontrolcenter.app.work.UpkeepStep
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.pickup.OwnerPickUpRules
import com.aicontrolcenter.core.pickup.PickUpFolders
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.routines.OwnerRoutines
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.data.DataLayer
import kotlinx.coroutines.CoroutineScope
import java.time.ZoneId

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

    /**
     * Runs the routines that are due now (WP17), with every check of Appendix A at run time. Called by the foreground
     * service when a routine's alarm asked for it ([RoutinesWiring.ACTION_RUN_DUE_ROUTINES]); never from a receiver
     * and never from a notification tap (F12: "Tap to start" opens the app, where the owner confirms). Wave 1: nothing.
     */
    suspend fun runDue() {}
}

/**
 * Everything a folder pick-up step may reach while nobody watches (security review of wave 1, F11): the rules and
 * their import records, the owner's folders read-only ([PickUpFolders]: a folder's record, its files, reading one),
 * the one memory service of the process (offline hashing embedder; the pick-up writes into the rule's space as the
 * owner's import, `PickUpSafety.memoryDraft`), the settings read-only (the master switch), the clock and the owner's
 * zone (the daily cap), and the maintenance gate. No data layer, no `Context`, no application object, no graph:
 * `UpkeepIsolationTest` fails on any of them.
 */
class PickUpStepPorts(
    val rules: PickUpRuleRepository,
    val folders: PickUpFolders,
    val memory: MemoryService,
    val settings: SettingsView,
    val clock: Clock,
    val zone: () -> ZoneId,
    val gate: MaintenanceGate,
)

@Phase6Stub("WP17")
object RoutinesWiring {
    /** The production stores. Called once per process by `createProductionPorts`; opens nothing until first used. */
    fun production(@Suppress("UNUSED_PARAMETER") context: Context, data: DataLayer): RoutinesPorts =
        RoutinesPorts(routines = data.routines, pickUpRules = data.pickUpRules)

    /**
     * Upkeep that folder pick-up adds to the daily upkeep job (Appendix A: "run by the upkeep job's scheduler"). Run
     * by `UpkeepGraph` after its own steps and under the same checks (rule 1: never a model, a tool or a provider).
     * Built from [ports] only (F11). None in wave 1.
     */
    fun upkeepSteps(@Suppress("UNUSED_PARAMETER") ports: PickUpStepPorts): List<UpkeepStep> = emptyList()

    /**
     * The intent action with which a routine's alarm receiver asks the foreground service to run the routines that
     * are due (F11, F12): receivers only signal; the service, which may build the graph, calls
     * [RoutinesAssembly.runDue]. WP2 handles it in `ObjectiveWorkService`; WP17 sends it.
     */
    const val ACTION_RUN_DUE_ROUTINES: String = "com.aicontrolcenter.app.action.RUN_DUE_ROUTINES"
}
