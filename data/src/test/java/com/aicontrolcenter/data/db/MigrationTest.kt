package com.aicontrolcenter.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.GrantQuery
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerDecision
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.data.db.entity.ConversationEntryEntity
import com.aicontrolcenter.data.repository.RoomActivityLog
import com.aicontrolcenter.data.repository.RoomAgentRepository
import com.aicontrolcenter.data.repository.RoomAiAppRepository
import com.aicontrolcenter.data.repository.RoomConversationRepository
import com.aicontrolcenter.data.repository.RoomMemoryRepository
import com.aicontrolcenter.data.repository.RoomMessageRepository
import com.aicontrolcenter.data.repository.RoomModelRepository
import com.aicontrolcenter.data.repository.RoomOperatorAppRepository
import com.aicontrolcenter.data.repository.RoomOwnerRequestRepository
import com.aicontrolcenter.data.repository.RoomPermissionRepository
import com.aicontrolcenter.data.repository.RoomProviderRepository
import com.aicontrolcenter.data.repository.RoomSharedFolderRepository
import com.aicontrolcenter.data.repository.RoomTaskRepository
import com.aicontrolcenter.data.security.SoftwareVaultCipher
import com.aicontrolcenter.data.testing.VersionFourData
import com.aicontrolcenter.data.testing.VersionOneData
import com.aicontrolcenter.data.testing.VersionThreeData
import com.aicontrolcenter.data.testing.VersionTwoData
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.contract.MemorySamples
import com.aicontrolcenter.testkit.contract.OperatorSamples
import com.aicontrolcenter.testkit.contract.TaskSamples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Upgrades of a database that already holds the owner's data. Versions 1 to 4 are on real
 * phones, so every test starts from a database created from the exported schema of one of them
 * (never from today's entity classes) and filled with [VersionOneData] and, for the later
 * versions, [VersionTwoData], [VersionThreeData] and [VersionFourData].
 *
 * Runs on plain SQLite: the SQLCipher native library only exists on a device. Encryption does
 * not change the SQL that a migration executes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AiccDatabase::class.java)

    private fun createVersionOneDatabase() {
        helper.createDatabase(NAME, 1).use { VersionOneData.insertInto(it) }
    }

    /** What release 0.2.0 holds: data in every table of schema version 2. */
    private fun createVersionTwoDatabase() {
        helper.createDatabase(NAME, 2).use {
            VersionOneData.insertInto(it)
            VersionTwoData.insertInto(it)
        }
    }

    /** What release 0.3.0 holds: data in every table of schema version 3. */
    private fun createVersionThreeDatabase() {
        helper.createDatabase(NAME, 3).use {
            VersionOneData.insertInto(it)
            VersionTwoData.insertInto(it)
            VersionThreeData.insertInto(it)
        }
    }

    /** What release 0.4.0 holds: data in every table of schema version 4. */
    private fun createVersionFourDatabase() {
        helper.createDatabase(NAME, 4).use {
            VersionOneData.insertInto(it)
            VersionTwoData.insertInto(it)
            VersionThreeData.insertInto(it)
            VersionFourData.insertInto(it)
        }
    }

    private suspend fun assertVersionFourDataIsThere(database: AiccDatabase) {
        assertVersionThreeDataIsThere(database, apps = listOf(VersionOneData.app) + VersionFourData.apps)
        assertEquals(VersionFourData.tasks, RoomTaskRepository(database).getTree(VersionFourData.objective.id))
        assertEquals(VersionFourData.messages, RoomMessageRepository(database).list(VersionFourData.objective.id))
    }

    /**
     * The AI apps that were authorised for accessibility operation, as operator apps: package,
     * label and certificate of the oldest authorised row per package, all authorised at the
     * moment of the migration, which happened between [before] and [after].
     */
    private suspend fun assertAuthorisedAppsWereCopied(database: AiccDatabase, expected: List<AiApp>, before: Long, after: Long) {
        val copied = RoomOperatorAppRepository(database).getAll()
        val at = copied.firstOrNull()?.authorisedAt ?: 0
        assertTrue(at in before..after, "authorised at $at, migrated between $before and $after")
        assertEquals(expected.map { OperatorApp(it.packageName, it.label, at, it.signingCertSha256) }, copied)
        // Apps carried over from 0.4 ask before every screen action, like a newly authorised one.
        assertTrue(copied.all { it.policy == ScreenActionPolicy.ASK_EVERY_ACTION }, copied.toString())
    }

    /**
     * Uses every table schema 5 adds the way the app does: a folder and an operator app, a
     * permission request answered with a grant (written in the same transaction), an approval
     * answered, a grant used, and the start-up sweep.
     */
    private suspend fun assertOperatorRecordsCanBeStored(
        permissions: PermissionRepository,
        requests: OwnerRequestRepository,
        folders: SharedFolderRepository,
        apps: OperatorAppRepository,
    ) {
        val s = OperatorSamples
        val folder = SharedFolder(FolderId("fld_reports"), "content://com.android.externalstorage.documents/tree/primary%3AReports", "Reports", 100)
        folders.upsert(folder)
        assertEquals(listOf(folder), folders.getAll())
        val app = OperatorApp("com.example.calendar", "Calendar", 200, "ef".repeat(32))
        apps.upsert(app)
        assertEquals(app, apps.get(app.packageName))

        val permission = s.request("req_perm", at = 1_000)
        val approval = s.request("req_appr", OwnerRequestKind.APPROVAL, at = 1_100)
        val left = s.request("req_left", at = 1_200)
        listOf(permission, approval, left).forEach { requests.create(it) }
        val granted = requests.decide(permission.id, OwnerDecision.Grant(GrantScope.ALWAYS, "Only Reports"), 1_500, GrantId("grt_1"))
        assertIs<DecideResult.Decided>(granted)
        assertEquals(granted.grant, permissions.findByRequest(permission.id))
        val query = GrantQuery(s.agent, OperatorCapabilities.FOLDERS_WRITE, s.folder, taskId = null, at = 1_600)
        assertEquals(listOfNotNull(granted.grant), permissions.findMatching(query))
        assertIs<DecideResult.Decided>(requests.decide(approval.id, OwnerDecision.Approve(), 1_500, GrantId("grt_unused")))
        assertEquals(listOf(left.id), requests.cancelAllPending(2_000, "The app restarted.").map { it.id })
        assertEquals(OwnerRequestStatus.CANCELLED, requests.get(left.id)?.status)
        assertEquals(listOf(left, approval, permission).map { it.id }, requests.observeRecent(10).first().map { it.id })
        assertEquals(1, permissions.revokeAllWithConstraint(s.folder, 3_000))
        assertTrue(permissions.findMatching(query.copy(at = 3_100)).isEmpty())
    }

    private suspend fun assertVersionThreeDataIsThere(database: AiccDatabase, apps: List<AiApp> = listOf(VersionOneData.app)) {
        assertVersionTwoDataIsThere(database, apps)
        val memories = RoomMemoryRepository(database)
        assertEquals(VersionThreeData.memories, memories.list())
        assertEquals(listOf(VersionThreeData.space), memories.observeSpaces().first())
        assertEquals(listOf(VersionThreeData.link), memories.links())
        assertContentEquals(
            VersionThreeData.embedding,
            memories.embeddings(VersionThreeData.EMBEDDER).getValue(VersionThreeData.embeddingOf),
        )
    }

    /** Stores, reads, updates and removes an objective with its tasks and messages. */
    private suspend fun assertTasksAndMessagesCanBeStored(tasks: TaskRepository, messages: MessageRepository) {
        assertEquals(emptyList(), tasks.observeObjectives().first())
        val root = TaskSamples.objective()
        val work = TaskSamples.work("tsk_1", at = 2_000, status = TaskStatus.RUNNING)
        val review = TaskSamples.work("tsk_2", at = 3_000, kind = TaskKind.REVIEW)
        tasks.upsertAll(listOf(review, work, root))
        val assignment = TaskSamples.message("msg_1", at = 2_000)
        val findings = TaskSamples.message("msg_2", at = 2_500, type = MessageType.FINDINGS)
        messages.append(findings)
        messages.append(assignment)

        assertEquals(listOf(root), tasks.observeObjectives().first())
        assertEquals(listOf(root, work, review), tasks.getTree(root.id))
        assertEquals(listOf(root, work), tasks.withStatus(setOf(TaskStatus.RUNNING)))
        assertEquals(TaskStatus.COMPLETED, tasks.update(work.id) { it.copy(status = TaskStatus.COMPLETED) }?.status)
        assertEquals(listOf(assignment, findings), messages.list(root.id))

        tasks.deleteTree(root.id)
        messages.deleteFor(root.id)
        assertEquals(emptyList(), tasks.getTree(root.id))
        assertEquals(0, messages.count(root.id))
    }

    private suspend fun assertVersionTwoDataIsThere(database: AiccDatabase, apps: List<AiApp> = listOf(VersionOneData.app)) {
        assertVersionOneDataIsThere(database, apps)
        val conversations = RoomConversationRepository(database)
        assertEquals(listOf(VersionTwoData.conversation), conversations.observeConversations().first())
        assertEquals(VersionTwoData.entries, conversations.getEntries(VersionTwoData.conversation.id))
    }

    /** Stores and reads back memory of every kind of scope, with a link, an embedding and a space. */
    private suspend fun assertMemoryCanBeStored(memories: MemoryRepository) {
        val s = MemorySamples
        assertEquals(emptyList(), memories.list())
        val space = KnowledgeSpace(SpaceId("spc_commons"), "Commons", createdAt = 1, updatedAt = 1)
        memories.upsertSpace(space)
        val records = listOf(s.memory("mem_a", s.researcher, 300), s.memory("mem_b", s.commons, 200), s.memory("mem_c", s.task, 100))
        records.forEach { memories.upsert(it) }
        memories.upsertLink(s.link("lnk_1", "mem_a", "mem_b"))
        memories.putEmbedding(MemoryId("mem_a"), "emb", floatArrayOf(0.6f, 0.8f))

        assertEquals(records, memories.list())
        assertTrue(records.all { it.history.isNotEmpty() }, "the samples carry a history, which must survive")
        assertEquals(listOf(space), memories.observeSpaces().first())
        assertEquals(listOf("mem_a"), memories.searchText("title MEM_A").map { it.id.value })
        assertContentEquals(floatArrayOf(0.6f, 0.8f), memories.embeddings("emb").getValue(MemoryId("mem_a")))
        assertEquals(1, memories.deleteSpace(space.id))
        assertEquals(emptyList(), memories.links())
        assertEquals(listOf("mem_a", "mem_c"), memories.list().map { it.id.value })
    }

    /** [apps]: the AI apps stored, oldest first; release 0.4.0 data adds some to [VersionOneData.app]. */
    private suspend fun assertVersionOneDataIsThere(database: AiccDatabase, apps: List<AiApp> = listOf(VersionOneData.app)) {
        val agents = RoomAgentRepository(database)
        assertEquals(listOf(VersionOneData.agent), agents.getAll())
        assertEquals(VersionOneData.stats, agents.getStats(VersionOneData.agent.id))
        assertEquals(listOf(VersionOneData.provider), RoomProviderRepository(database).getAll())
        assertEquals(listOf(VersionOneData.model), RoomModelRepository(database).getAll())
        assertEquals(apps, RoomAiAppRepository(database).getAll())
        assertEquals(VersionOneData.events.reversed(), RoomActivityLog(database).recent())
    }

    @Test
    fun `a version 1 database with data in every table migrates to the exported schema 2 and keeps its data`() = runTest {
        createVersionOneDatabase()

        // Fails if the migrated tables, indexes or foreign keys differ from the exported schema 2.
        helper.runMigrationsAndValidate(NAME, 2, true, *AiccMigrations.all).close()

        val migrated = AiccDatabase.create(context, NAME)
        try {
            assertVersionOneDataIsThere(migrated)

            // The insertion sequence of the activity log carries on, so equal timestamps stay ordered.
            val log = RoomActivityLog(migrated)
            log.record(Samples.event(id = "evt_3", at = 200))
            assertEquals(listOf("evt_3", "evt_2", "evt_1"), log.recent().map { it.id.value })
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `the app opening a version 1 database upgrades it in place and can store conversations, tasks and messages in it`() = runTest {
        createVersionOneDatabase()

        // The way the app opens it, except for encryption: Room finds and runs the migration itself.
        val data = DataLayer(
            context, databaseName = NAME, databaseEncryption = DatabaseEncryption.None, vaultCipher = SoftwareVaultCipher(),
        )
        try {
            assertVersionOneDataIsThere(data.database)
            assertEquals(AiccDatabase.VERSION, data.database.openHelper.readableDatabase.version)

            assertEquals(emptyList(), data.conversations.observeConversations().first())
            val conversation = Conversation(ConversationId("cnv_1"), VersionOneData.agent.id, "First chat", createdAt = 500, updatedAt = 500)
            data.conversations.upsertConversation(conversation)
            val stored = data.conversations.appendEntry(ConversationEntry(conversation.id, seq = 0, ChatMessage.user("Hello"), at = 510))
            assertEquals(listOf(conversation), data.conversations.observeConversations(VersionOneData.agent.id).first())
            assertEquals(listOf(stored), data.conversations.getEntries(conversation.id))
            assertTasksAndMessagesCanBeStored(data.tasks, data.messages)
        } finally {
            data.close()
        }
    }

    @Test
    fun `a version 1 database migrates through version 2 to the exported schema 3 and keeps the data of both`() = runTest {
        createVersionOneDatabase()
        // Release 0.2.0 upgraded it to version 2 and then stored conversations in it.
        helper.runMigrationsAndValidate(NAME, 2, true, *AiccMigrations.all).use { VersionTwoData.insertInto(it) }

        // Fails if the migrated tables, indexes or foreign keys differ from the exported schema 3.
        helper.runMigrationsAndValidate(NAME, 3, true, *AiccMigrations.all).close()

        val migrated = AiccDatabase.create(context, NAME)
        try {
            assertVersionTwoDataIsThere(migrated)
            assertMemoryCanBeStored(RoomMemoryRepository(migrated))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `a version 2 database with data in every table migrates to the exported schema 3 and keeps its data`() = runTest {
        createVersionTwoDatabase()

        helper.runMigrationsAndValidate(NAME, 3, true, *AiccMigrations.all).close()

        val migrated = AiccDatabase.create(context, NAME)
        try {
            assertVersionTwoDataIsThere(migrated)
            for (table in listOf("knowledge_spaces", "memories", "memory_links", "memory_embeddings")) {
                assertEquals(0, migrated.count(table), table)
            }
            // Conversations keep numbering where release 0.2.0 stopped.
            val next = RoomConversationRepository(migrated)
                .appendEntry(ConversationEntry(VersionTwoData.conversation.id, seq = 0, ChatMessage.user("Again"), at = 400))
            assertEquals(2, next.seq)
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `the app opening a version 2 database upgrades it in place and can store memory in it`() = runTest {
        createVersionTwoDatabase()

        val data = DataLayer(
            context, databaseName = NAME, databaseEncryption = DatabaseEncryption.None, vaultCipher = SoftwareVaultCipher(),
        )
        try {
            assertVersionTwoDataIsThere(data.database)
            assertEquals(AiccDatabase.VERSION, data.database.openHelper.readableDatabase.version)
            assertMemoryCanBeStored(data.memories)
            assertEquals(1, data.memories.deleteScope(MemoryScope.Task(MemorySamples.task.taskId, MemorySamples.task.agentId)))
        } finally {
            data.close()
        }
    }

    @Test
    fun `a version 3 database with data in every table migrates to the exported schema 4 and keeps its data`() = runTest {
        createVersionThreeDatabase()

        // Fails if the migrated tables or indexes differ from the exported schema 4.
        helper.runMigrationsAndValidate(NAME, 4, true, *AiccMigrations.all).close()

        val migrated = AiccDatabase.create(context, NAME)
        try {
            assertVersionThreeDataIsThere(migrated)
            assertEquals(0, migrated.count("tasks"))
            assertEquals(0, migrated.count("agent_messages"))
            assertTasksAndMessagesCanBeStored(RoomTaskRepository(migrated), RoomMessageRepository(migrated))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `a version 1 database migrates through every release to the exported schema 4 and keeps the data of each`() = runTest {
        createVersionOneDatabase()
        // Each release upgraded the database and then stored data of its own in it.
        helper.runMigrationsAndValidate(NAME, 2, true, *AiccMigrations.all).use { VersionTwoData.insertInto(it) }
        helper.runMigrationsAndValidate(NAME, 3, true, *AiccMigrations.all).use { VersionThreeData.insertInto(it) }

        helper.runMigrationsAndValidate(NAME, 4, true, *AiccMigrations.all).close()

        val migrated = AiccDatabase.create(context, NAME)
        try {
            assertVersionThreeDataIsThere(migrated)
            assertTasksAndMessagesCanBeStored(RoomTaskRepository(migrated), RoomMessageRepository(migrated))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `a version 1 database with data migrates straight to the exported schema 4`() = runTest {
        createVersionOneDatabase()

        // One upgrade over three versions, as on a phone that skipped releases 0.2.0 and 0.3.0.
        helper.runMigrationsAndValidate(NAME, 4, true, *AiccMigrations.all).close()

        val migrated = AiccDatabase.create(context, NAME)
        try {
            assertVersionOneDataIsThere(migrated)
            assertTasksAndMessagesCanBeStored(RoomTaskRepository(migrated), RoomMessageRepository(migrated))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `the app opening a version 3 database upgrades it in place and can store tasks and messages in it`() = runTest {
        createVersionThreeDatabase()

        val data = DataLayer(
            context, databaseName = NAME, databaseEncryption = DatabaseEncryption.None, vaultCipher = SoftwareVaultCipher(),
        )
        try {
            assertVersionThreeDataIsThere(data.database)
            assertEquals(AiccDatabase.VERSION, data.database.openHelper.readableDatabase.version)
            assertTasksAndMessagesCanBeStored(data.tasks, data.messages)
            // The data of the earlier releases is untouched by everything done with tasks.
            assertVersionThreeDataIsThere(data.database)
        } finally {
            data.close()
        }
    }

    @Test
    fun `a version 4 database with data in every table migrates to the exported schema 5, keeps its data and copies the authorised apps`() = runTest {
        createVersionFourDatabase()

        // Fails if the migrated tables or indexes differ from the exported schema 5.
        val before = System.currentTimeMillis()
        helper.runMigrationsAndValidate(NAME, 5, true, *AiccMigrations.all).close()
        val after = System.currentTimeMillis()

        val migrated = AiccDatabase.create(context, NAME)
        try {
            assertVersionFourDataIsThere(migrated)
            // The oldest authorised row of each package, with its certificate; the unauthorised mail app stays out.
            assertAuthorisedAppsWereCopied(migrated, listOf(VersionOneData.app, VersionFourData.notes), before, after)
            for (table in listOf("permission_grants", "owner_requests", "shared_folders")) {
                assertEquals(0, migrated.count(table), table)
            }
            assertOperatorRecordsCanBeStored(
                RoomPermissionRepository(migrated), RoomOwnerRequestRepository(migrated),
                RoomSharedFolderRepository(migrated), RoomOperatorAppRepository(migrated),
            )
            // The AI apps themselves are untouched, their own authorisation flag included.
            assertEquals(listOf(VersionOneData.app) + VersionFourData.apps, RoomAiAppRepository(migrated).getAll())
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `a version 1 database migrates through every release to the exported schema 5 and keeps the data of each`() = runTest {
        createVersionOneDatabase()
        // Each release upgraded the database and then stored data of its own in it.
        helper.runMigrationsAndValidate(NAME, 2, true, *AiccMigrations.all).use { VersionTwoData.insertInto(it) }
        helper.runMigrationsAndValidate(NAME, 3, true, *AiccMigrations.all).use { VersionThreeData.insertInto(it) }
        helper.runMigrationsAndValidate(NAME, 4, true, *AiccMigrations.all).use { VersionFourData.insertInto(it) }

        val before = System.currentTimeMillis()
        helper.runMigrationsAndValidate(NAME, 5, true, *AiccMigrations.all).close()
        val after = System.currentTimeMillis()

        val migrated = AiccDatabase.create(context, NAME)
        try {
            assertVersionFourDataIsThere(migrated)
            assertAuthorisedAppsWereCopied(migrated, listOf(VersionOneData.app, VersionFourData.notes), before, after)
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `a version 1 database with data migrates straight to the exported schema 5`() = runTest {
        createVersionOneDatabase()

        // One upgrade over four versions, as on a phone that skipped every release since the first.
        val before = System.currentTimeMillis()
        helper.runMigrationsAndValidate(NAME, 5, true, *AiccMigrations.all).close()
        val after = System.currentTimeMillis()

        val migrated = AiccDatabase.create(context, NAME)
        try {
            assertVersionOneDataIsThere(migrated)
            assertAuthorisedAppsWereCopied(migrated, listOf(VersionOneData.app), before, after)
            assertTasksAndMessagesCanBeStored(RoomTaskRepository(migrated), RoomMessageRepository(migrated))
            assertOperatorRecordsCanBeStored(
                RoomPermissionRepository(migrated), RoomOwnerRequestRepository(migrated),
                RoomSharedFolderRepository(migrated), RoomOperatorAppRepository(migrated),
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `a database without authorised AI apps migrates to version 5 with no operator apps`() = runTest {
        helper.createDatabase(NAME, 4).use {
            VersionOneData.insertInto(it)
            // The owner withdrew the authorisation under release 0.4.0.
            it.execSQL("UPDATE ai_apps SET accessibility_authorized = 0")
        }

        helper.runMigrationsAndValidate(NAME, 5, true, *AiccMigrations.all).close()

        val migrated = AiccDatabase.create(context, NAME)
        try {
            assertEquals(emptyList(), RoomOperatorAppRepository(migrated).getAll())
            assertEquals(listOf(VersionOneData.app.copy(accessibilityAuthorized = false)), RoomAiAppRepository(migrated).getAll())
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `the app opening a version 4 database upgrades it in place and can store the operator's records in it`() = runTest {
        createVersionFourDatabase()

        val before = System.currentTimeMillis()
        val data = DataLayer(
            context, databaseName = NAME, databaseEncryption = DatabaseEncryption.None, vaultCipher = SoftwareVaultCipher(),
        )
        try {
            assertVersionFourDataIsThere(data.database)
            val after = System.currentTimeMillis()
            assertEquals(AiccDatabase.VERSION, data.database.openHelper.readableDatabase.version)
            assertAuthorisedAppsWereCopied(data.database, listOf(VersionOneData.app, VersionFourData.notes), before, after)
            assertOperatorRecordsCanBeStored(data.permissions, data.ownerRequests, data.sharedFolders, data.operatorApps)
            // The data of the earlier releases is untouched by everything done with the operator's records.
            assertVersionFourDataIsThere(data.database)
        } finally {
            data.close()
        }
    }

    @Test
    fun `the operator tables added by the migration have their indexes and hold one folder per tree`() = runTest {
        createVersionFourDatabase()
        helper.runMigrationsAndValidate(NAME, 5, true, *AiccMigrations.all).close()
        val migrated = AiccDatabase.create(context, NAME)
        try {
            val sqlite = migrated.openHelper.writableDatabase
            val indexes = sqlite.query(
                SimpleSQLiteQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'index' " +
                        "AND tbl_name IN ('permission_grants', 'owner_requests', 'shared_folders', 'operator_apps')",
                ),
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertEquals(
                setOf(
                    "index_permission_grants_agent_id_capability_constraint_key", "index_permission_grants_request_id",
                    "index_owner_requests_status_created_at_id", "index_owner_requests_conversation_id_created_at_id",
                    "index_owner_requests_root_id_created_at_id", "index_owner_requests_task_id", "index_owner_requests_created_at_id",
                    "index_shared_folders_tree_uri",
                ),
                indexes.filterNot { it.startsWith("sqlite_autoindex") }.toSet(),
            )
            sqlite.execSQL("INSERT INTO shared_folders (id, tree_uri, display_name, added_at, read_only) VALUES ('fld_a', 'content://tree/a', 'A', 1, 0)")
            assertFailsWith<SQLiteConstraintException> {
                sqlite.execSQL("INSERT INTO shared_folders (id, tree_uri, display_name, added_at, read_only) VALUES ('fld_b', 'content://tree/a', 'B', 2, 0)")
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `the task tables added by the migration have their indexes and take tasks in any order`() = runTest {
        createVersionThreeDatabase()
        helper.runMigrationsAndValidate(NAME, 4, true, *AiccMigrations.all).close()
        val migrated = AiccDatabase.create(context, NAME)
        try {
            val indexes = migrated.openHelper.readableDatabase
                .query(SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name IN ('tasks', 'agent_messages')"))
                .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertEquals(
                setOf(
                    "index_tasks_kind_created_at_id", "index_tasks_root_id_created_at_id", "index_tasks_status_created_at_id",
                    "index_agent_messages_root_id_at_id", "index_agent_messages_at_id",
                ),
                indexes.filterNot { it.startsWith("sqlite_autoindex") }.toSet(),
            )
            // No foreign keys: a child may be stored before its objective, and a message before its task.
            val tasks = RoomTaskRepository(migrated)
            tasks.upsert(TaskSamples.work("tsk_child"))
            RoomMessageRepository(migrated).append(TaskSamples.message("msg_early"))
            assertEquals(listOf("tsk_child"), tasks.getTree(TaskSamples.objective().id).map { it.id.value })
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `the memory tables added by the migration enforce their foreign keys and have their indexes`() = runTest {
        createVersionTwoDatabase()
        val migrated = AiccDatabase.create(context, NAME)
        try {
            val sqlite = migrated.openHelper.writableDatabase
            val memories = RoomMemoryRepository(migrated)
            memories.upsert(MemorySamples.memory("mem_a"))
            assertFailsWith<SQLiteConstraintException> {
                sqlite.execSQL(
                    "INSERT INTO memory_links (id, from_id, to_id, relation, weight, provenance_json, created_at) " +
                        "VALUES ('lnk_x', 'mem_a', 'mem_missing', 'RELATES_TO', 1.0, '{}', 1)",
                )
            }
            memories.upsert(MemorySamples.memory("mem_b"))
            memories.upsertLink(MemorySamples.link("lnk_1", "mem_b", "mem_a"))
            memories.putEmbedding(MemoryId("mem_a"), "emb", floatArrayOf(1f))
            sqlite.execSQL("DELETE FROM memories WHERE id = 'mem_a'")
            assertEquals(0, migrated.count("memory_links"))
            assertEquals(0, migrated.count("memory_embeddings"))

            val indexes = sqlite.query(SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name LIKE 'memor%'"))
                .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertTrue(
                indexes.containsAll(
                    setOf(
                        "index_memories_updated_at_id", "index_memories_scope_agent_id_scope_type_scope_task_id",
                        "index_memories_scope_agent_id_scope_type_updated_at_id",
                        "index_memories_scope_space_id", "index_memories_kind_updated_at", "index_memories_expires_at",
                        "index_memory_links_from_id", "index_memory_links_to_id", "index_memory_embeddings_embedder_id",
                    ),
                ),
                "$indexes",
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `the tables added by the migration behave like those of a new installation`() = runTest {
        createVersionOneDatabase()
        val migrated = AiccDatabase.create(context, NAME)
        try {
            val dao = migrated.conversationDao()
            val orphan = ConversationEntryEntity("cnv_missing", seq = 0, at = 1, modelName = null, messageJson = "{}", usageJson = null, error = null)
            assertFailsWith<SQLiteConstraintException> { dao.insertEntry(orphan) }

            val conversations = RoomConversationRepository(migrated)
            conversations.upsertConversation(Conversation(ConversationId("cnv_1"), AgentId("agt_gone"), "Chat", 1, 1))
            conversations.appendEntry(ConversationEntry(ConversationId("cnv_1"), seq = 0, ChatMessage.user("Hello"), at = 2))
            // Removing only the conversation row, as nothing in the app does, still takes the entries along.
            dao.deleteConversationRow("cnv_1")
            assertEquals(0, migrated.count("conversation_entries"))

            val indexes = migrated.openHelper.readableDatabase
                .query(SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'conversations'"))
                .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertTrue(indexes.containsAll(setOf("index_conversations_updated_at", "index_conversations_agent_id_updated_at")), "$indexes")
        } finally {
            migrated.close()
        }
    }

    private fun AiccDatabase.count(table: String): Int =
        openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table")).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
