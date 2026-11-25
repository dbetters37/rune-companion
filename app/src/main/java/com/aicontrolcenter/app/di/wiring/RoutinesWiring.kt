package com.aicontrolcenter.app.di.wiring

import android.content.Context
import com.aicontrolcenter.app.di.Ports
import com.aicontrolcenter.app.operator.PickUpFoldersView
import com.aicontrolcenter.app.routines.AndroidRoutineAlarms
import com.aicontrolcenter.app.routines.AndroidRoutineNotices
import com.aicontrolcenter.app.work.SafetyCollectors
import com.aicontrolcenter.app.work.UpkeepStep
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.pickup.OwnerPickUpRules
import com.aicontrolcenter.core.pickup.PickUpFolders
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.pickup.PickedUpFile
import com.aicontrolcenter.core.registry.ReadOnlyRegistry
import com.aicontrolcenter.core.routines.OwnerRoutines
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.framework.pickup.DefaultOwnerPickUpRules
import com.aicontrolcenter.framework.pickup.PickUpRunner
import com.aicontrolcenter.framework.routines.DefaultOwnerRoutines
import com.aicontrolcenter.framework.routines.RegistryLocalOnlyCheck
import com.aicontrolcenter.framework.routines.RoutineAlarmPort
import com.aicontrolcenter.framework.routines.RoutineClock
import com.aicontrolcenter.framework.routines.RoutineNotices
import com.aicontrolcenter.framework.routines.RoutineRunner
import com.aicontrolcenter.framework.routines.UsageDailyLimitCheck
import com.aicontrolcenter.framework.routines.WaitingRoutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZoneId

/*
 * Routines (objectives started at set times) and automatic folder pick-up (Phase 6, owner decision Q1 = "Yes, both";
 * plan Appendix A; Tier B). WP17 owns this file, the framework and app routines packages and their tests in wave 2.
 *
 * Both master switches (`routinesEnabled`, `folderPickUpEnabled`) are off after installing: until the owner switches
 * one on, no routine runs and no folder is picked up. The alarm and restart receivers stay disabled while no routine
 * exists.
 */

/**
 * The stores of routines and pick-up rules (schema 6), and the Android side of routines. Both stores are owner-capable
 * (both architecture tests list them as owner-only): only the owner's ports and WP17's runners may hold them, never
 * anything agent-facing.
 */
class RoutinesPorts(
    val routines: RoutineRepository? = null,
    val pickUpRules: PickUpRuleRepository? = null,
    /** The routines' one alarm and their receivers; none in tests unless one is given. */
    val alarms: RoutineAlarmPort = RoutineAlarmPort.None,
    /** "Tap to start" while the app is not on screen; none in tests unless one is given. */
    val notices: RoutineNotices = RoutineNotices.None,
    /** The owner's time zone, read at each use (due times are local times). */
    val zone: () -> ZoneId = { ZoneId.systemDefault() },
)

/**
 * Routines and folder pick-up as the composition root builds them, after the orchestrator (a routine starts an
 * objective through it). Everything here is the owner's: no agent-facing class holds this assembly or anything in it.
 *
 * Built here: the owner's Routines and Folder pick-up ports (for WP10's screens), the [RoutineRunner] (the alarm's
 * service calls [runDue]; the owner's banner uses [waitingRoutines]), and, on [appScope], what keeps Android in step
 * with the stored routines: the one alarm at the earliest due time while the master switch is on, the two receivers
 * enabled only while a routine exists, due times moved on when the master switch is switched on (nothing that was due
 * while it was off runs), due times realigned to the phone's time zone, and "Tap to start" taken away once nothing waits.
 *
 * @param ports the app's stores, read here: `ports.routines`, the tasks (whether a routine's objective is unfinished),
 *   today's usage and the prices (daily limits at run time), the registry read only (local-only mode), the maintenance
 *   gate, the folders read only (a rule's files seen when it is switched on).
 * @param settings read-only: the master switches, local-only mode and the limits, at run time.
 * @param orchestrator starts a routine's objective, with `Routine.objectiveOptions()`.
 * @param memory the one memory service of the process (the spaces a pick-up rule may point at).
 * @param activity where every change and every run is logged.
 * @param appScope lives as long as the process.
 * @param safeStartOn whether the app is in safe start (rule 10; wave 3): a routine due on time then waits for the
 *   owner's tap instead of starting from its alarm.
 */
class RoutinesAssembly(
    ports: Ports,
    settings: SettingsView,
    orchestrator: Orchestrator,
    memory: MemoryService,
    activity: ActivityRecorder,
    appScope: CoroutineScope,
    safeStartOn: () -> Boolean = { false },
) {
    private val store: RoutineRepository? = ports.routines.routines
    private val alarms: RoutineAlarmPort = ports.routines.alarms
    private val notices: RoutineNotices = ports.routines.notices
    private val clock = RoutineClock(ports.clock, ports.routines.zone)

    /** Owner-only: the Routines screen (WP10). Null without a store; the screen then stays hidden. */
    val ownerRoutines: OwnerRoutines? = store?.let { DefaultOwnerRoutines(it, activity, ports.clock, ports.idGenerator, ports.routines.zone) }

    /** Owner-only: the folder pick-up screen (WP10). Null without a store; the screen then stays hidden. */
    val ownerPickUpRules: OwnerPickUpRules? = ports.routines.pickUpRules?.let { rules ->
        DefaultOwnerPickUpRules(
            repository = rules,
            folders = PickUpFoldersView(ports.operator.sharedFolders, ports.operator.folderAccess),
            spaceExists = { id -> memory.observeSpaces().first().any { it.id == id } },
            activity = activity, clock = ports.clock, ids = ports.idGenerator,
        )
    }

    /** Runs routines at their due times with every check of Appendix A. Null without a store. */
    internal val runner: RoutineRunner? = store?.let {
        RoutineRunner(
            routines = it,
            tasks = ports.tasks,
            orchestrator = orchestrator,
            settings = settings,
            dailyLimits = UsageDailyLimitCheck(ports.usageReports, ports.modelPrices, ports.clock, ports.routines.zone),
            localOnly = RegistryLocalOnlyCheck(ReadOnlyRegistry.agents(ports.agents), ReadOnlyRegistry.providers(ports.providers), ReadOnlyRegistry.models(ports.models)),
            gate = ports.maintenanceGate,
            activity = activity,
            clock = clock,
            ids = ports.idGenerator,
            notices = notices,
            safeStartOn = safeStartOn,
        )
    }

    /** Owner-only, read only: what each pick-up rule imported or saw, for the Folder pick-up screen (wave 3). */
    val pickUpImports: PickUpImports? = ports.routines.pickUpRules?.let(::PickUpImportsView)

    /**
     * Owner-only: the runs waiting for the owner's tap, for the in-app banner (`app/routines/WaitingRoutinesBanner`):
     * "Routine '<title>' is waiting to start", Start asks once more, then starts exactly that run.
     */
    val waitingRoutines: WaitingRoutines = runner ?: WaitingRoutines.None

    init {
        val runner = runner
        val store = store
        if (runner != null && store != null) {
            // Supervised (rule 26, wave 3): a failing read restarts the collector with a growing wait and logs a warning,
            // instead of leaving the alarm and the receivers out of step until the next start.
            val safety = SafetyCollectors(appScope, activity, ports.clock)
            safety.supervise(KEEPING_IN_STEP) { keepAndroidInStep(runner, store, settings) }
            safety.supervise(WAITING_NOTICE) {
                runner.observeWaiting().map { it.isEmpty() }.distinctUntilChanged().collect { none -> if (none) notices.noneWaiting() }
            }
        }
    }

    private suspend fun keepAndroidInStep(runner: RoutineRunner, store: RoutineRepository, settings: SettingsView) {
        runner.realign()
        var wasOn: Boolean? = null
        combine(store.observeAll(), settings.settings.map { it.routinesEnabled }.distinctUntilChanged()) { routines, on -> routines to on }
            .collect { (listed, on) ->
                var routines = listed
                if (on && wasOn == false) {
                    // Switched on just now: what was due while it was off is not run.
                    runner.masterSwitchedOn()
                    routines = store.getAll()
                }
                wasOn = on
                alarms.receiversEnabled(routines.isNotEmpty())
                val at = RoutineClock.alarmAt(routines, on)
                if (at == null) alarms.cancel() else alarms.arm(at)
            }
    }

    /**
     * Runs the routines that are due now, with every check of Appendix A at run time. Called by the foreground service
     * when a routine's alarm asked for it ([RoutinesWiring.ACTION_RUN_DUE_ROUTINES]); never from a receiver and never
     * from a notification tap (F12: "Tap to start" opens the app, where the owner confirms).
     */
    suspend fun runDue() {
        runner?.runDue()
    }

    /**
     * The owner is looking at the app (wave 3: `AppGraph.onAppForegrounded`, and the banner itself): due times that
     * passed without a run wait for a tap, or are missed when too old. Starts nothing.
     */
    suspend fun catchUp() {
        runner?.catchUp()
    }

    companion object {
        /** The collectors' names in the log, in plain words. */
        const val KEEPING_IN_STEP: String = "Keeping the routines' alarm in step"
        const val WAITING_NOTICE: String = "The \"routine waiting to start\" notice"
    }
}

/**
 * Owner-only, read only (wave 3, WP8): the files a folder pick-up rule imported, or saw when it was switched on, newest
 * first. For the Folder pick-up screen; it cannot change a rule or an import.
 */
interface PickUpImports {
    fun observeImports(ruleId: PickUpRuleId, limit: Int): Flow<List<PickedUpFile>>
}

/** [PickUpImports] over the store: its reading of imports alone. */
class PickUpImportsView(private val store: PickUpRuleRepository) : PickUpImports {
    override fun observeImports(ruleId: PickUpRuleId, limit: Int): Flow<List<PickedUpFile>> = store.observeImports(ruleId, limit)
}

/**
 * Everything a folder pick-up step may reach while nobody watches (security review of wave 1, F11): the rules and
 * their import records, the owner's folders read-only ([PickUpFolders]: a folder's record, its files, reading one),
 * the one memory service of the process (offline hashing embedder; the pick-up writes into the rule's space as the
 * owner's import, `PickUpSafety.memoryDraft`), the settings read-only (the master switch), the clock and the owner's
 * zone (the daily cap), the maintenance gate, and where runs are logged. No data layer, no `Context`, no application
 * object, no graph: `UpkeepIsolationTest` fails on any of them.
 */
class PickUpStepPorts(
    val rules: PickUpRuleRepository,
    val folders: PickUpFolders,
    val memory: MemoryService,
    val settings: SettingsView,
    val clock: Clock,
    val zone: () -> ZoneId,
    val gate: MaintenanceGate,
    /** Where a run that imported something or stopped a rule is logged; null: not logged. */
    val activity: ActivityRecorder? = null,
)

object RoutinesWiring {
    /** The production stores and Android's alarm and notification. Called once per process by `createProductionPorts`; opens nothing until first used. */
    fun production(context: Context, data: DataLayer): RoutinesPorts {
        val app = context.applicationContext ?: context
        return RoutinesPorts(
            routines = data.routines,
            pickUpRules = data.pickUpRules,
            alarms = AndroidRoutineAlarms(app),
            notices = AndroidRoutineNotices(app),
        )
    }

    /**
     * Upkeep that folder pick-up adds to the daily upkeep job (Appendix A: "run by the upkeep job's scheduler"). Run
     * by `UpkeepGraph` after its own steps and under the same checks (rule 1: never a model, a tool or a provider).
     * Built from [ports] only (F11): one [PickUpRunner] over the rules, the folders read only and the memory service.
     */
    fun upkeepSteps(ports: PickUpStepPorts): List<UpkeepStep> {
        val runner = PickUpRunner(ports.rules, ports.folders, ports.memory, ports.settings, ports.clock, ports.zone, ports.gate, ports.activity)
        return listOf(
            UpkeepStep {
                try {
                    runner.runAll()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed pick-up stores nothing more; the next upkeep run tries again. It never stops the job's other steps.
                }
            },
        )
    }

    /**
     * The intent action with which a routine's alarm receiver asks the foreground service to run the routines that
     * are due (F11, F12): receivers only signal; the service, which may build the graph, calls
     * [RoutinesAssembly.runDue]. WP2 handles it in `ObjectiveWorkService`; WP17 sends it.
     */
    const val ACTION_RUN_DUE_ROUTINES: String = "com.aicontrolcenter.app.action.RUN_DUE_ROUTINES"
}
