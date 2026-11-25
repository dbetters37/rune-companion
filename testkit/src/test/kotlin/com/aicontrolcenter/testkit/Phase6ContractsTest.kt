package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.operator.FrontState
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.testkit.contract.ActivityPageContract
import com.aicontrolcenter.testkit.contract.ConversationTurnContract
import com.aicontrolcenter.testkit.contract.MemoryCountContract
import com.aicontrolcenter.testkit.contract.MessageHeaderContract
import com.aicontrolcenter.testkit.contract.ModelPricesContract
import com.aicontrolcenter.testkit.contract.OwnerPickUpRulesContract
import com.aicontrolcenter.testkit.contract.OwnerQuestionContract
import com.aicontrolcenter.testkit.contract.OwnerRoutinesContract
import com.aicontrolcenter.testkit.contract.PickUpRuleRepositoryContract
import com.aicontrolcenter.testkit.contract.RequestStores
import com.aicontrolcenter.testkit.contract.RoutineRepositoryContract
import com.aicontrolcenter.testkit.contract.SettingsRoundTripContract
import com.aicontrolcenter.testkit.contract.TaskSummaryContract
import com.aicontrolcenter.testkit.contract.UsageLedgerContract
import com.aicontrolcenter.testkit.contract.UsageStores
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * The Phase 6 contract suites against the in-memory reference implementations. The in-memory
 * task, message, conversation, memory and activity stores keep the interfaces' default methods
 * for summaries, headers, sequence reads, counts and pages, so these runs also check the defaults.
 */

class InMemoryTaskSummaryTest : TaskSummaryContract() {
    override fun createRepository() = InMemoryTaskRepository()
}

class InMemoryMessageHeaderTest : MessageHeaderContract() {
    override fun createRepository() = InMemoryMessageRepository()
}

class InMemoryConversationTurnTest : ConversationTurnContract() {
    override fun createRepository() = InMemoryConversationRepository()
}

class InMemoryActivityPageTest : ActivityPageContract() {
    override fun createLog() = InMemoryActivityLog()
}

class InMemoryMemoryCountTest : MemoryCountContract() {
    override fun createRepository() = InMemoryMemoryRepository()
}

class InMemorySettingsRoundTripTest : SettingsRoundTripContract() {
    override fun createRepository() = InMemorySettingsRepository()
}

class InMemoryOwnerQuestionTest : OwnerQuestionContract() {
    override fun createStores(): RequestStores {
        val grants = InMemoryPermissionRepository()
        return RequestStores(InMemoryOwnerRequestRepository(grants), grants)
    }
}

class InMemoryUsageLedgerTest : UsageLedgerContract() {
    override fun createStores(clock: FakeClock, zone: ZoneId): UsageStores {
        val store = InMemoryUsageStore(clock) { zone }
        return UsageStores(store, store, store, store)
    }
}

class InMemoryModelPricesTest : ModelPricesContract() {
    override fun createStores(clock: FakeClock) = InMemoryModelPrices(clock).let { it to it }
}

class InMemoryRoutineRepositoryTest : RoutineRepositoryContract() {
    override fun createRepository() = InMemoryRoutineRepository()
}

class ReferenceOwnerRoutinesTest : OwnerRoutinesContract() {
    override fun createRepository() = InMemoryRoutineRepository()

    override fun createOwnerRoutines(repository: RoutineRepository, clock: FakeClock, ids: IdGenerator, zone: ZoneId) =
        ReferenceOwnerRoutines(repository, clock, ids) { zone }
}

class InMemoryPickUpRuleRepositoryTest : PickUpRuleRepositoryContract() {
    override fun createRepository() = InMemoryPickUpRuleRepository()
}

class ReferenceOwnerPickUpRulesTest : OwnerPickUpRulesContract() {
    override fun createRepository() = InMemoryPickUpRuleRepository()

    override fun createFolders() = InMemorySharedFolderRepository()

    override fun createMemory() = InMemoryMemoryRepository()

    override fun createOwnerPickUpRules(
        repository: PickUpRuleRepository,
        folders: SharedFolderRepository,
        memory: MemoryRepository,
        clock: FakeClock,
        ids: IdGenerator,
    ) = ReferenceOwnerPickUpRules(repository, folders, memory, clock, ids)
}

/** The Phase 6 fakes behave as their ports say. */
class Phase6FakesTest {
    @Test
    fun `the screen fake keeps holds until released or stopped, and can hide the front window`() = runTest {
        val screen = FakeScreenOperator(screen = FakeScreen("com.example.notes", title = "Notes", controls = emptyList()))
        screen.holdSession("req_1", "Operator", "com.example.notes")
        screen.holdSession("req_2", "Operator", "com.example.notes")
        screen.releaseSession("req_1")
        assertEquals(setOf("req_2"), screen.holds.keys)
        screen.stop()
        assertTrue(screen.holds.isEmpty(), "Stop ends every hold")
        assertTrue(screen.frontState() is FrontState.App)
        screen.frontHidden = true
        assertEquals(FrontState.Hidden, screen.frontState())
        val control = screen.asControl()
        screen.switchedOffByAndroid.value = true
        assertTrue(control.switchedOffByAndroid.value)
    }

    @Test
    fun `health fakes report what the test sets`() = runTest {
        val waits = MutableTimedWaits()
        waits.due.value = 5
        waits.wake()
        assertEquals(5L, waits.nextDueAt.value)
        assertEquals(1, waits.wakes)
        val storage = FakeStorageWatch(freeBytes = 50L * 1024 * 1024)
        assertEquals(com.aicontrolcenter.core.health.StorageLevel.CRITICAL, storage.check())
        assertEquals(com.aicontrolcenter.core.health.StorageLevel.CRITICAL, storage.level.value)
        val exits = FakeExitHistory(
            com.aicontrolcenter.core.health.ExitRecord(20, com.aicontrolcenter.core.health.ExitKind.CRASH, 100, 1, 1),
            com.aicontrolcenter.core.health.ExitRecord(10, com.aicontrolcenter.core.health.ExitKind.LOW_MEMORY, 400, 1, 1),
        )
        assertEquals(listOf(10L, 20L), exits.newSince(5).map { it.at })
        assertEquals(listOf(20L), exits.newSince(10).map { it.at })
        val recovery = FakeOwnerRecovery()
        recovery.startOver()
        assertEquals(1, recovery.startOvers.get())
    }
}
