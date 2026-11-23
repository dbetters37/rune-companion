package com.aicontrolcenter.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.registry.AiAppRepository
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.data.testing.InMemoryDatabaseRule
import com.aicontrolcenter.testkit.contract.ActivityLogContract
import com.aicontrolcenter.testkit.contract.AgentRepositoryContract
import com.aicontrolcenter.testkit.contract.AiAppRepositoryContract
import com.aicontrolcenter.testkit.contract.ConversationRepositoryContract
import com.aicontrolcenter.testkit.contract.MemoryRepositoryContract
import com.aicontrolcenter.testkit.contract.MessageRepositoryContract
import com.aicontrolcenter.testkit.contract.ModelRepositoryContract
import com.aicontrolcenter.testkit.contract.OperatorAppRepositoryContract
import com.aicontrolcenter.testkit.contract.OwnerRequestRepositoryContract
import com.aicontrolcenter.testkit.contract.PermissionRepositoryContract
import com.aicontrolcenter.testkit.contract.ProviderRepositoryContract
import com.aicontrolcenter.testkit.contract.RequestStores
import com.aicontrolcenter.testkit.contract.SharedFolderRepositoryContract
import com.aicontrolcenter.testkit.contract.TaskRepositoryContract
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomTaskRepositoryContractTest : TaskRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): TaskRepository = RoomTaskRepository(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomMessageRepositoryContractTest : MessageRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): MessageRepository = RoomMessageRepository(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomPermissionRepositoryContractTest : PermissionRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): PermissionRepository = RoomPermissionRepository(db.database)
}

/** The requests and the grants their answers write live in the same database, as in the app. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomOwnerRequestRepositoryContractTest : OwnerRequestRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createStores() = RequestStores(RoomOwnerRequestRepository(db.database), RoomPermissionRepository(db.database))
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomSharedFolderRepositoryContractTest : SharedFolderRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): SharedFolderRepository = RoomSharedFolderRepository(db.database)
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomOperatorAppRepositoryContractTest : OperatorAppRepositoryContract() {
    @get:Rule val db = InMemoryDatabaseRule()
    override fun createRepository(): OperatorAppRepository = RoomOperatorAppRepository(db.database)
}
