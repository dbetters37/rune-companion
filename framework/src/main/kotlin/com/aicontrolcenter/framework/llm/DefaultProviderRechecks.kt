package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.health.NetworkStatus
import com.aicontrolcenter.core.health.TimedWaits
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.ProviderRechecks
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderDirectory
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.settings.SettingsView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Looks again at providers that stopped answering (Phase 6, D28; K15; security review of wave 1, F3).
 *
 * Before this, one failed check kept a provider's agents blocked until the app started again, came back after ten
 * minutes, or the owner tapped "Check now". Now:
 *  - A provider stored as not answering (OFFLINE) or failing (ERROR) is checked again 1, 2, 5 and 10 minutes after
 *    this noticed it, then every 10 minutes, for at most an hour ([RecheckSchedule]). Work that is about to wait for
 *    it asks again ([recheckIfStale]), which starts the hour again.
 *  - When the phone has a network again ([NetworkStatus]), every such provider is checked at once.
 *  - Never a provider whose key was rejected (AUTH_ERROR: the owner must fix it), a disabled one, or, while
 *    local-only mode is on, one off this phone (a check sends the key to it). While the phone has no network,
 *    scheduled checks of providers off the phone are skipped (they could not succeed); the network's return checks them.
 *
 * Time: the schedule runs on [timeSource] (monotonic: a change of the phone's clock moves nothing). [timedWaits]
 * publishes the next check as a wall-clock time ([clock]) for the background service (rule 5); when the phone slept
 * through a wait, its wake alarm calls [TimedWaits.wake], and then every check whose time has come by the wall clock
 * runs too. Slots that passed while nothing ran are not made up. The wall clock is read only while a provider is in
 * trouble.
 *
 * It holds neither the owner's provider manager nor the vault nor the provider store: it reads providers through the
 * read-only [ProviderDirectory] and checks them through [ProviderChecks] over [ProviderConnections], the one holder
 * of a provider's key, which stores nothing but the status a check learned.
 *
 * @param scope lives as long as the process: the schedule runs in it ([start]), and so do checks, so a check that
 *   several callers wait for is made once and finishes even when a caller stops waiting.
 */
class DefaultProviderRechecks(
    private val providers: ProviderDirectory,
    private val settings: SettingsView,
    connections: ProviderConnections,
    activity: ActivityRecorder,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val network: NetworkStatus = NetworkStatus.AlwaysOn,
    private val schedule: RecheckSchedule = RecheckSchedule(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ProviderRechecks {
    private val checks = ProviderChecks(settings, connections, activity)

    /**
     * A provider in trouble: when this noticed it (or [recheckIfStale] asked again), on the wall clock ([startedAt])
     * and on the schedule's time source ([started]); the next slot of the schedule ([next]); [now] when it is to be
     * checked at once (the network came back).
     */
    private class Trouble(var startedAt: Long, var started: TimeMark, var next: Int = 0, var now: Boolean = false)

    private val lock = Mutex()
    private val troubles = HashMap<ProviderId, Trouble>()
    private val running = HashMap<ProviderId, Deferred<ProviderHealth?>>()
    private val due = MutableStateFlow<Long?>(null)

    /** Something changed: look at the schedule again. */
    private val changed = Channel<Unit>(Channel.CONFLATED)

    /** The wake alarm rang: run what is due by the wall clock. */
    private val woken = Channel<Unit>(Channel.CONFLATED)

    override val timedWaits: TimedWaits = object : TimedWaits {
        override val nextDueAt: StateFlow<Long?> = due.asStateFlow()

        override fun wake() {
            woken.trySend(Unit)
        }
    }

    /**
     * Starts following the providers, the settings and the network, and the schedule. Call once, at start-up of the
     * process; the returned job ends with [scope].
     */
    fun start(): Job = scope.launch {
        launch { followProviders() }
        launch { followNetwork() }
        runSchedule()
    }

    override suspend fun recheckIfStale(providerId: ProviderId, olderThanMs: Long): ConnectionStatus? {
        val provider = providers.get(providerId) ?: return null
        if (!checkable(provider)) return null
        val last = provider.lastCheckedAt
        if (last != null && clock.now() - last < olderThanMs) return null
        // Asked again: the hour of scheduled checks starts again from now.
        lock.withLock {
            troubles[providerId]?.let {
                it.startedAt = clock.now()
                it.started = timeSource.markNow()
                it.next = 0
                publish()
            }
        }
        changed.trySend(Unit)
        check(providerId) ?: return null
        return providers.get(providerId)?.status
    }

    /** Can this provider be checked by itself now? Never a rejected key, a disabled provider, or one local-only mode keeps away. */
    private suspend fun checkable(provider: ModelProvider): Boolean =
        provider.enabled && provider.status != ConnectionStatus.AUTH_ERROR && checks.blockedByLocalOnly(provider) == null

    /**
     * Checks [id] once, within [RecheckSchedule.CHECK_BOUND_MS]; callers asking while a check of it runs wait for
     * that one. Null when it was not checked (gone, or no longer checkable when its turn came).
     */
    private suspend fun check(id: ProviderId): ProviderHealth? {
        val job = lock.withLock {
            running[id]?.takeIf { it.isActive } ?: scope.async {
                try {
                    val current = providers.get(id)?.takeIf { checkable(it) } ?: return@async null
                    withTimeoutOrNull(RecheckSchedule.CHECK_BOUND_MS) {
                        checks.check(current, RecheckSchedule.PROBE_TIMEOUT_MS)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A check that could not be made changes nothing; the next one is due as scheduled.
                    null
                }
            }.also { running[id] = it }
        }
        return try {
            job.await()
        } catch (e: CancellationException) {
            // The caller was stopped: let it through. Otherwise the check itself was (the process scope ended).
            currentCoroutineContext().ensureActive()
            null
        } finally {
            withContext(NonCancellable) { lock.withLock { if (running[id] === job && !job.isActive) running.remove(id) } }
        }
    }

    /** Tracks which providers are in trouble, as the store and the settings say. */
    private suspend fun followProviders() {
        combine(providers.observeAll(), settings.settings) { all, current ->
            all.filter { p ->
                p.enabled && (p.status == ConnectionStatus.OFFLINE || p.status == ConnectionStatus.ERROR) &&
                    !(current.localOnlyMode && p.effectiveLocality.leavesDevice)
            }.map { it.id }.toSet()
        }
            .distinctUntilChanged()
            .collect { inTrouble ->
                lock.withLock {
                    troubles.keys.retainAll(inTrouble)
                    val added = inTrouble - troubles.keys
                    if (added.isNotEmpty()) {
                        val startedAt = clock.now()
                        val started = timeSource.markNow()
                        for (id in added) troubles[id] = Trouble(startedAt, started)
                    }
                    publish()
                }
                changed.trySend(Unit)
            }
    }

    /** When the phone has a network again, every provider in trouble is checked at once. */
    private suspend fun followNetwork() {
        var had = network.available.value
        network.available.collect { has ->
            if (has && !had) {
                lock.withLock {
                    troubles.values.forEach { it.now = true }
                    publish()
                }
                changed.trySend(Unit)
            }
            had = has
        }
    }

    /**
     * Runs the checks as they come due on the schedule's time source, or by the wall clock after [TimedWaits.wake],
     * and waits for the next one or a change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runSchedule() {
        var byWallClock = false
        while (true) {
            val wallNow = if (byWallClock) clock.now() else null
            byWallClock = false
            val dueNow = lock.withLock { troubles.filterValues { isDue(it, wallNow) }.keys.toList() }
            for (id in dueNow) {
                val provider = providers.get(id)
                val immediate = lock.withLock { troubles[id]?.now == true }
                val unreachable = !network.available.value && provider?.effectiveLocality?.leavesDevice == true
                if (provider != null && (immediate || !unreachable)) check(id)
                lock.withLock { troubles[id]?.let { pastSlots(it) } }
            }
            val wait = lock.withLock {
                publish()
                troubles.values.mapNotNull { remainingMs(it) }.minOrNull()
            }
            select {
                changed.onReceive { }
                woken.onReceive { byWallClock = true }
                if (wait != null) onTimeout(wait.coerceAtLeast(0)) { }
            }
        }
    }

    /** Under the lock: is [trouble]'s next check due, by the schedule's time or, after a wake, by the wall clock? */
    private fun isDue(trouble: Trouble, wallNow: Long?): Boolean {
        if (trouble.now) return true
        val offset = schedule.offsetsMs.getOrNull(trouble.next) ?: return false
        return trouble.started.elapsedNow().inWholeMilliseconds >= offset || (wallNow != null && wallNow - trouble.startedAt >= offset)
    }

    /** Under the lock: after a check, the next slot is the first still ahead by both clocks; missed ones are not made up. */
    private fun pastSlots(trouble: Trouble) {
        trouble.now = false
        val elapsed = maxOf(trouble.started.elapsedNow().inWholeMilliseconds, clock.now() - trouble.startedAt)
        while (trouble.next < schedule.offsetsMs.size && schedule.offsetsMs[trouble.next] <= elapsed) trouble.next++
    }

    /** Under the lock: how long until [trouble]'s next check on the schedule's time, or null when its hour is over. */
    private fun remainingMs(trouble: Trouble): Long? {
        if (trouble.now) return 0
        val offset = schedule.offsetsMs.getOrNull(trouble.next) ?: return null
        return offset - trouble.started.elapsedNow().inWholeMilliseconds
    }

    /** Under the lock: publishes the wall-clock time of the next check, or null when nothing is due. */
    private fun publish() {
        val immediate = troubles.values.any { it.now }
        val scheduled = troubles.values.mapNotNull { t -> schedule.offsetsMs.getOrNull(t.next)?.let { t.startedAt + it } }.minOrNull()
        due.value = if (immediate) clock.now() else scheduled
    }
}

/**
 * When providers that stopped answering are checked again (D28): [offsetsMs] after this noticed it, by default 1, 2,
 * 5 and 10 minutes, then every 10 minutes up to an hour.
 */
data class RecheckSchedule(val offsetsMs: List<Long> = DEFAULT_OFFSETS_MS) {
    init {
        require(offsetsMs.zipWithNext().all { (a, b) -> a < b } && offsetsMs.all { it > 0 }) { "Offsets must be positive and increasing." }
    }

    /** Defaults. */
    companion object {
        private const val MINUTE: Long = 60_000L

        val DEFAULT_OFFSETS_MS: List<Long> = listOf(1L, 2L, 5L, 10L, 20L, 30L, 40L, 50L, 60L).map { it * MINUTE }

        /** How long one check may take in all, and so [ProviderRechecks.recheckIfStale] at most (D28: 10 s). */
        const val CHECK_BOUND_MS: Long = 10_000L

        /** How long the provider has to answer the check; the rest of [CHECK_BOUND_MS] is for storing what it said. */
        const val PROBE_TIMEOUT_MS: Long = 9_000L
    }
}
