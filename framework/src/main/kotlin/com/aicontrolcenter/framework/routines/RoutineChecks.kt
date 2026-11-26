package com.aicontrolcenter.framework.routines

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.agents.resolveAvailability
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.registry.AgentDirectory
import com.aicontrolcenter.core.registry.ModelDirectory
import com.aicontrolcenter.core.registry.ProviderDirectory
import com.aicontrolcenter.core.routines.RoutineRun
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.core.usage.counted
import com.aicontrolcenter.framework.llm.DailyUse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.ZoneId

/*
 * The checks a routine run makes at run time (Phase 6, plan Appendix A; WP17) and the small ports the runner reports
 * through. Each check reads afresh at every run: settings, today's use and the registry can change at any moment.
 */

/** Whether today's daily limits leave room for a routine's objective (rule 6, Q2). */
fun interface DailyLimitCheck {
    /** The limit's sentence when one is reached, or null when work may start. Throws when today's use cannot be read. */
    suspend fun reached(settings: FrameworkSettings): String?
}

/**
 * Today's daily limits as the gateway's limiter counts them ([DailyUse] over today's usage records and the owner's
 * prices). The gateway refuses every call over the limit anyway; checking before a run keeps a routine from starting
 * an objective that would pause at its first call.
 */
class UsageDailyLimitCheck(
    private val reports: UsageReports,
    private val prices: ModelPrices,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : DailyLimitCheck {
    override suspend fun reached(settings: FrameworkSettings): String? {
        val now = clock.now()
        // The same rule as the limiter and every screen (D3A-8): only prices in US$ count, and the sentence says US$.
        val priced = prices.getAll().counted()
        val use = DailyUse.of(reports.byModel(UsageRange.day(now, zone())), priced.associateBy { it.modelId })
        val limits = settings.dailyLimits
        val kind = limits.reachedBy(use.calls, use.tokens, use.spendMicros) ?: return null
        return limits.sentence(kind)
    }
}

/** Whether local-only mode keeps the Director from working (its model would send data off the phone). */
fun interface LocalOnlyCheck {
    suspend fun blocksDirector(settings: FrameworkSettings): Boolean
}

/** [LocalOnlyCheck] over the registry, read only: the designated Director's availability as the orchestrator resolves it. */
class RegistryLocalOnlyCheck(
    private val agents: AgentDirectory,
    private val providers: ProviderDirectory,
    private val models: ModelDirectory,
) : LocalOnlyCheck {
    override suspend fun blocksDirector(settings: FrameworkSettings): Boolean {
        if (!settings.localOnlyMode) return false
        val director = settings.directorAgentId?.let { agents.get(it) } ?: return false
        val availability = resolveAvailability(director, models.getAll().associateBy { it.id }, providers.getAll().associateBy { it.id }, settings)
        return availability is AgentAvailability.Unavailable && availability.reason == UnavailableReason.BLOCKED_BY_LOCAL_ONLY_MODE
    }
}

/**
 * How the owner hears of a run that waits for a tap while the app is not on screen (the app's "Tap to start"
 * notification). App-written text and the routine's title only; no action on it starts anything: it opens the app,
 * where the owner taps Start.
 */
interface RoutineNotices {
    /** A run of the routine called [routineTitle] waits for the owner's tap. */
    fun waitingForTap(routineTitle: String)

    /** No run waits any more: take the notice away. */
    fun noneWaiting()

    /** Tells nobody (tests, and where notifications are not available). */
    object None : RoutineNotices {
        override fun waitingForTap(routineTitle: String) {}

        override fun noneWaiting() {}
    }
}

/**
 * Turns on and off the app's one alarm for routines and the receivers that only exist for them (the alarm's and the
 * one that sets it again after a restart), so a phone without routines never starts the app for them.
 */
interface RoutineAlarmPort {
    /** Sets the one alarm to go off at [atMs] (epoch ms), replacing an earlier one. */
    fun arm(atMs: Long)

    /** No alarm. */
    fun cancel()

    /** Whether the alarm and restart receivers may run: only while at least one routine exists. */
    fun receiversEnabled(enabled: Boolean)

    /** No alarm at all (tests, and where Android's alarms are not available). */
    object None : RoutineAlarmPort {
        override fun arm(atMs: Long) {}

        override fun cancel() {}

        override fun receiversEnabled(enabled: Boolean) {}
    }
}

/** A run that waits for the owner's tap, as the "is waiting to start" banner shows it. */
data class WaitingRoutine(
    val routineId: RoutineId,
    val runId: RoutineRunId,
    /** The owner's own title for the routine. */
    val title: String,
    /** What the team will be asked to do, as the owner wrote it. */
    val objectiveText: String,
    val dueAt: Long,
    /** The routine's "May use other apps' screens". */
    val allowScreenActions: Boolean,
)

/**
 * The owner's side of runs that wait for a tap (the in-app banner "Routine '<title>' is waiting to start"). Owner-only:
 * starting one starts work. Nothing here is reached from a notification or a receiver: the banner calls it after the
 * owner tapped Start and confirmed once more.
 */
interface WaitingRoutines {
    /** Runs waiting for a tap now, oldest due first. */
    fun observeWaiting(): Flow<List<WaitingRoutine>>

    /**
     * The owner is looking at the app: due times that passed without a run (the alarm was held back, the phone was off,
     * Android refused to start the work) become runs waiting for a tap, or missed ones when too old. Starts nothing.
     */
    suspend fun catchUp()

    /** Starts exactly that waiting run, after every check a routine run makes; refused with a sentence otherwise. */
    suspend fun startWaiting(routineId: RoutineId, runId: RoutineRunId): OwnerResult<RoutineRun>

    /** The owner chose not to start it: the run is recorded as missed. False when it no longer waited. */
    suspend fun dismissWaiting(routineId: RoutineId, runId: RoutineRunId): Boolean

    /** Nothing waits (no routines in this build). */
    object None : WaitingRoutines {
        override fun observeWaiting(): Flow<List<WaitingRoutine>> = flowOf(emptyList())

        override suspend fun catchUp() {}

        override suspend fun startWaiting(routineId: RoutineId, runId: RoutineRunId): OwnerResult<RoutineRun> =
            OwnerResult.Refused(RoutineRunner.NOT_WAITING)

        override suspend fun dismissWaiting(routineId: RoutineId, runId: RoutineRunId): Boolean = false
    }
}
