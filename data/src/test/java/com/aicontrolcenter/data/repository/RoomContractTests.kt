package com.aicontrolcenter.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.registry.AiAppRepository
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.data.testing.InMemoryDatabaseRule
import com.aicontrolcenter.testkit.contract.ActivityLogContract
import com.aicontrolcenter.testkit.contract.AgentRepositoryContract
import com.aicontrolcenter.testkit.contract.AiAppRepositoryContract
import com.aicontrolcenter.testkit.contract.ConversationRepositoryContract
import com.aicontrolcenter.testkit.contract.MemoryRepositoryContract
import com.aicontrolcenter.testkit.contract.ModelRepositoryContract
import com.aicontrolcenter.testkit.contract.ProviderRepositoryContract
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/*
 * The Room implementations run against the same contract suites as the in-memory reference
 * implementations in :testkit, on a real SQLite database.
 */

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomAgentRepositoryContractTest : AgentRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): AgentRepository = RoomAgentRepository(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomProviderRepositoryContractTest : ProviderRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): ProviderRepository = RoomProviderRepository(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomModelRepositoryContractTest : ModelRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): ModelRepository = RoomModelRepository(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomAiAppRepositoryContractTest : AiAppRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): AiAppRepository = RoomAiAppRepository(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomActivityLogContractTest : ActivityLogContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createLog(): ActivityLog = RoomActivityLog(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomConversationRepositoryContractTest : ConversationRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): ConversationRepository = RoomConversationRepository(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomMemoryRepositoryContractTest : MemoryRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): MemoryRepository = RoomMemoryRepository(db.database)
}
