package com.aicontrolcenter.app.routines

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.pickup.PickUpFolders
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.framework.pickup.DefaultOwnerPickUpRules
import com.aicontrolcenter.framework.routines.DefaultOwnerRoutines
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.contract.OwnerPickUpRulesContract
import com.aicontrolcenter.testkit.contract.OwnerRoutinesContract
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

/*
 * WP17's owner ports over the Room stores of schema 6 (the production pair), passing the contracts of Appendix A
 * (the caps under concurrency, one rule per folder, and F1's read-only folder rules included).
 */

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomOwnerRoutinesContractTest : OwnerRoutinesContract() {
    private val data = DataLayer(
        ApplicationProvider.getApplicationContext<Application>(), databaseName = "owner-routines-contract.db",
        databaseEncryption = DatabaseEncryption.None,
    )

    @After
    fun close() = runBlocking { data.close() }

    override fun createRepository(): RoutineRepository = data.routines

    override fun createOwnerRoutines(repository: RoutineRepository, clock: FakeClock, ids: IdGenerator, zone: ZoneId) =
        DefaultOwnerRoutines(repository, ActivityRecorder(data.activityLog, ids, clock), clock, ids) { zone }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomOwnerPickUpRulesContractTest : OwnerPickUpRulesContract() {
    private val data = DataLayer(
        ApplicationProvider.getApplicationContext<Application>(), databaseName = "owner-pickup-contract.db",
        databaseEncryption = DatabaseEncryption.None,
    )

    @After
    fun close() = runBlocking { data.close() }

    override fun createRepository(): PickUpRuleRepository = data.pickUpRules

    override fun createFolders(): SharedFolderRepository = data.sharedFolders

    override fun createMemory(): MemoryRepository = data.memories

    override fun createOwnerPickUpRules(repository: PickUpRuleRepository, folders: PickUpFolders, memory: MemoryRepository, clock: FakeClock, ids: IdGenerator) =
        DefaultOwnerPickUpRules(repository, folders, { memory.getSpace(it) != null }, ActivityRecorder(data.activityLog, ids, clock), clock, ids)
}
