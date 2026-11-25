package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.health.BackgroundHealth
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.ExitHistory
import com.aicontrolcenter.core.health.ExitRecord
import com.aicontrolcenter.core.health.NetworkStatus
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.health.RecoveryResult
import com.aicontrolcenter.core.health.StorageLevel
import com.aicontrolcenter.core.health.StorageWatch
import com.aicontrolcenter.core.health.TimedWaits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/*
 * Settable stand-ins for the Phase 6 health ports (K12, K14), for tests of the code that reads
 * them (the background service, the limiter, the screens).
 */

/** [TimedWaits] a test drives: set [due]; [wakes] counts the calls to [wake]. */
class MutableTimedWaits(initial: Long? = null) : TimedWaits {
    val due = MutableStateFlow(initial)
    private val wakeCount = AtomicInteger()

    override val nextDueAt: StateFlow<Long?> = due.asStateFlow()

    val wakes: Int get() = wakeCount.get()

    override fun wake() {
        wakeCount.incrementAndGet()
    }
}

/** [NetworkStatus] a test switches: set [online]. */
class FakeNetworkStatus(initial: Boolean = true) : NetworkStatus {
    val online = MutableStateFlow(initial)

    override val available: StateFlow<Boolean> = online.asStateFlow()
}

/** [ExitHistory] over a list a test fills; [newSince] returns the exits after the time, oldest first. */
class FakeExitHistory(vararg exits: ExitRecord) : ExitHistory {
    val exits = CopyOnWriteArrayList(exits.toList())

    override suspend fun newSince(ms: Long): List<ExitRecord> = exits.filter { it.at > ms }.sortedBy { it.at }
}

/** [BackgroundHealth] whose state a test sets; [refreshes] counts [refresh]. */
class FakeBackgroundHealth(initial: BackgroundState = BackgroundState.UNKNOWN) : BackgroundHealth {
    val current = MutableStateFlow(initial)
    private val refreshCount = AtomicInteger()

    override val state: StateFlow<BackgroundState> = current.asStateFlow()

    val refreshes: Int get() = refreshCount.get()

    override suspend fun refresh() {
        refreshCount.incrementAndGet()
    }
}

/** [StorageWatch] whose free bytes a test sets; [check] publishes the level of [freeBytes]. */
class FakeStorageWatch(@Volatile var freeBytes: Long? = 10L * 1024 * 1024 * 1024) : StorageWatch {
    private val current = MutableStateFlow(StorageLevel.UNKNOWN)

    override val level: StateFlow<StorageLevel> = current.asStateFlow()

    override suspend fun check(): StorageLevel = StorageLevel.of(freeBytes).also { current.value = it }
}

/** [OwnerRecovery] a test scripts: [databaseState] is what [state] says; [startOver] records the call and answers [result]. */
class FakeOwnerRecovery(
    @Volatile var databaseState: DatabaseState = DatabaseState.OK,
    @Volatile var result: RecoveryResult = RecoveryResult.Done("set-aside-test"),
) : OwnerRecovery {
    val startOvers = AtomicInteger()

    override suspend fun state(): DatabaseState = databaseState

    override suspend fun startOver(): RecoveryResult {
        startOvers.incrementAndGet()
        return result
    }
}
