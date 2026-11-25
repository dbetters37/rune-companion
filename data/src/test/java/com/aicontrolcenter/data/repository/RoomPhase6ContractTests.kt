package com.aicontrolcenter.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.pickup.PickUpFolders
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.data.settings.SettingsStores
import com.aicontrolcenter.data.testing.InMemoryDatabaseRule
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.ReferenceOwnerPickUpRules
import com.aicontrolcenter.testkit.ReferenceOwnerRoutines
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
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

/*
 * Phase 6 (WP1b): the Room implementations of schema 6 run the contract suites of WP1a, the same
 * the in-memory references of :testkit pass, on a real SQLite database. The owner's ports of
 * Appendix A (WP17's) are not built yet; their reference implementations run here over the Room
 * stores, so the stores are known to give the owner's side what it relies on (the caps under
 * concurrency included).
 */

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomTaskSummaryContractTest : TaskSummaryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository() = RoomTaskRepository(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomMessageHeaderContractTest : MessageHeaderContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository() = RoomMessageRepository(db.database)
}

/** Questions and the grants an answer must never write live in the same database, as in the app. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomOwnerQuestionContractTest : OwnerQuestionContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createStores() = RequestStores(RoomOwnerRequestRepository(db.database), RoomPermissionRepository(db.database))
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomConversationTurnContractTest : ConversationTurnContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository() = RoomConversationRepository(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomActivityPageContractTest : ActivityPageContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createLog() = RoomActivityLog(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomMemoryCountContractTest : MemoryCountContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository() = RoomMemoryRepository(db.database)
}

/** The four usage ports over one database, as `DataLayer` wires them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomUsageLedgerContractTest : UsageLedgerContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createStores(clock: FakeClock, zone: ZoneId) = UsageStores(
        ledger = RoomUsageLedger(db.database),
        reports = RoomUsageReports(db.database),
        owner = RoomOwnerUsage(db.database, clock, { zone }),
        housekeeping = RoomUsageHousekeeping(db.database, clock, { zone }),
    )
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomModelPricesContractTest : ModelPricesContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createStores(clock: FakeClock) = RoomModelPrices(db.database, clock).let { it to it }
}

/** The DataStore codec stores every Phase 6 setting (it reads and writes each field on its own). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DataStoreSettingsRoundTripContractTest : SettingsRoundTripContract() {
    private val stores = SettingsStores()

    override fun createRepository(): SettingsRepository = stores.repository()

    @After
    fun tearDown() = stores.closeAll()
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomRoutineRepositoryContractTest : RoutineRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): RoutineRepository = RoomRoutineRepository(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomPickUpRuleRepositoryContractTest : PickUpRuleRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): PickUpRuleRepository = RoomPickUpRuleRepository(db.database)
}

/** The reference owner's side of routines over the Room store (WP17 builds the real one). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomBackedOwnerRoutinesContractTest : OwnerRoutinesContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): RoutineRepository = RoomRoutineRepository(db.database)
    override fun createOwnerRoutines(repository: RoutineRepository, clock: FakeClock, ids: IdGenerator, zone: ZoneId) =
        ReferenceOwnerRoutines(repository, clock, ids) { zone }
}

/** The reference owner's side of folder pick-up over the Room stores (WP17 builds the real one). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomBackedOwnerPickUpRulesContractTest : OwnerPickUpRulesContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): PickUpRuleRepository = RoomPickUpRuleRepository(db.database)
    override fun createFolders(): SharedFolderRepository = RoomSharedFolderRepository(db.database)
    override fun createMemory(): MemoryRepository = RoomMemoryRepository(db.database)
    override fun createOwnerPickUpRules(
        repository: PickUpRuleRepository,
        folders: PickUpFolders,
        memory: MemoryRepository,
        clock: FakeClock,
        ids: IdGenerator,
    ) = ReferenceOwnerPickUpRules(repository, folders, memory, clock, ids)
}
