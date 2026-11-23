package com.aicontrolcenter.app.di

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.framework.memory.MemorySeeder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The work the app does when its process starts, kept out of the Application class so it runs
 * identically against in-memory ports in tests.
 *
 * Every step is independent: a failure in one is written to the activity log and does not stop
 * the others, because a broken package scan must never prevent the owner from seeing agents.
 *
 * Memory upkeep ([MemoryService.maintain]: deleting expired short-term memories and computing
 * missing search vectors) runs once after the start-up steps, again whenever local-only mode is
 * switched off (an off-device embedder may then fill in what it was refused), and every
 * [MEMORY_MAINTENANCE_INTERVAL_MS] while the app is in the foreground. It never runs twice at once.
 */
class AppStartup(
    private val scope: CoroutineScope,
    private val agentManager: AgentManager,
    private val aiAppRegistry: AiAppRegistry,
    private val providerManager: ProviderManager,
    private val memorySeeder: MemorySeeder,
    private val memory: MemoryService,
    private val settings: SettingsRepository,
    private val activityLog: ActivityLog,
    private val activityRecorder: ActivityRecorder,
    private val packageChanges: Flow<Unit>,
    private val clock: Clock,
) {
    private var initialJob: Job? = null
    private var foregroundRefresh: Job? = null
    private var providerCheck: Job? = null
    private var memoryMaintenance: Job? = null
    private var foregroundMaintenanceLoop: Job? = null

    /** When memory upkeep last started, or null before the first time. */
    @Volatile
    private var lastMemoryMaintenanceAt: Long? = null

    /** When the last check of every provider started, or null before the first one. */
    @Volatile
    private var lastProviderCheckAt: Long? = null

    @Synchronized
    fun start(): Job {
        initialJob?.let { return it }
        val job = scope.launch {
            // First, so the simulator is there for the starter team and for "Try with the simulator".
            step("Could not register the built-in simulator") { providerManager.ensureBuiltIns() }
            step("Could not create the starter team") { agentManager.ensureDefaults() }
            // After the team: the starter space is shared with the starter agents.
            step("Could not set up shared memory") { memorySeeder.ensureDefaults() }
            step("Could not refresh AI app statuses") { aiAppRegistry.refreshStatuses() }
        }
        initialJob = job
        maintainMemory(after = job)
        scope.launch {
            // Off again: vectors an off-device embedder was refused can be computed now.
            settings.settings.map { it.localOnlyMode }.distinctUntilChanged().drop(1).filter { on -> !on }.collect {
                maintainMemory(after = job, force = true)
            }
        }
        // A stored status can be days old, and an offline or rejected one keeps agents from running
        // until something re-checks it. Network calls can take long, so they never hold up the start.
        checkProviders(after = job)
        // Conflated: a bulk update fires many broadcasts, and one re-check after them is enough.
        scope.launch {
            packageChanges.conflate().collect {
                step("Could not refresh AI app statuses") { aiAppRegistry.refreshStatuses() }
            }
        }
        scope.launch {
            settings.settings.map { it.activityRetentionDays }.distinctUntilChanged().collectLatest { days ->
                step("Could not apply the activity retention setting") { pruneActivity(days) }
            }
        }
        return job
    }

    /**
     * Re-checks registered apps unless a re-check from an earlier foregrounding is still running,
     * and re-checks the providers when their last check is older than [PROVIDER_RECHECK_MS].
     */
    @Synchronized
    fun onAppForegrounded() {
        val initial = initialJob ?: return
        if (!initial.isCompleted) return
        if (foregroundRefresh?.isActive != true) {
            foregroundRefresh = scope.launch {
                step("Could not refresh AI app statuses") { aiAppRegistry.refreshStatuses() }
            }
        }
        val last = lastProviderCheckAt
        if (last == null || clock.now() - last >= PROVIDER_RECHECK_MS) checkProviders(after = initial)
        maintainMemory(after = initial)
        if (foregroundMaintenanceLoop?.isActive != true) {
            foregroundMaintenanceLoop = scope.launch {
                while (true) {
                    delay(MEMORY_MAINTENANCE_INTERVAL_MS)
                    maintainMemory(after = initial)
                }
            }
        }
    }

    /** Stops the periodic memory upkeep until the app is in the foreground again. */
    @Synchronized
    fun onAppBackgrounded() {
        foregroundMaintenanceLoop?.cancel()
        foregroundMaintenanceLoop = null
    }

    /**
     * Starts memory upkeep after [after], unless it is running or ran less than
     * [MEMORY_MAINTENANCE_INTERVAL_MS] ago. [force] skips the time check, not the running check.
     */
    @Synchronized
    private fun maintainMemory(after: Job, force: Boolean = false) {
        if (memoryMaintenance?.isActive == true) return
        val last = lastMemoryMaintenanceAt
        // The first run (at start) always happens; later ones respect the interval.
        if (!force && last != null && clock.now() - last < MEMORY_MAINTENANCE_INTERVAL_MS) return
        lastMemoryMaintenanceAt = clock.now()
        memoryMaintenance = scope.launch {
            after.join()
            step("Could not tidy up memory") { memory.maintain() }
        }
    }

    @Synchronized
    private fun checkProviders(after: Job) {
        if (providerCheck?.isActive == true) return
        lastProviderCheckAt = clock.now()
        providerCheck = scope.launch {
            after.join()
            step("Could not check the model providers") { providerManager.checkAllProviders() }
        }
    }

    private suspend fun pruneActivity(retentionDays: Int) {
        if (retentionDays <= 0) return
        activityLog.prune(olderThan = clock.now() - retentionDays * MILLIS_PER_DAY)
    }

    private suspend fun step(failureTitle: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportFailure(failureTitle, failure)
        }
    }

    private suspend fun reportFailure(title: String, failure: Exception) {
        try {
            activityRecorder.record(
                type = ActivityType.ERROR,
                title = title,
                detail = failure.message ?: failure.javaClass.simpleName,
                severity = Severity.ERROR,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The log itself is unavailable; there is nowhere left to report to.
        }
    }

    companion object {
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /** Provider status older than this is checked again when the app returns to the foreground. */
        const val PROVIDER_RECHECK_MS = 10L * 60 * 1000

        /** How often memory upkeep runs while the app is in the foreground. */
        const val MEMORY_MAINTENANCE_INTERVAL_MS = 30L * 60 * 1000
    }
}
