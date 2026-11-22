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
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.data.db.entity.ConversationEntryEntity
import com.aicontrolcenter.data.repository.RoomActivityLog
import com.aicontrolcenter.data.repository.RoomAgentRepository
import com.aicontrolcenter.data.repository.RoomAiAppRepository
import com.aicontrolcenter.data.repository.RoomConversationRepository
import com.aicontrolcenter.data.repository.RoomModelRepository
import com.aicontrolcenter.data.repository.RoomProviderRepository
import com.aicontrolcenter.data.security.SoftwareVaultCipher
import com.aicontrolcenter.data.testing.VersionOneData
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Upgrades of a database that already holds the owner's data. Version 1 is on a real phone, so
 * every test starts from a database created from the exported schema of that version (never
 * from today's entity classes) and filled with [VersionOneData].
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

    private suspend fun assertVersionOneDataIsThere(database: AiccDatabase) {
        val agents = RoomAgentRepository(database)
        assertEquals(listOf(VersionOneData.agent), agents.getAll())
        assertEquals(VersionOneData.stats, agents.getStats(VersionOneData.agent.id))
        assertEquals(listOf(VersionOneData.provider), RoomProviderRepository(database).getAll())
        assertEquals(listOf(VersionOneData.model), RoomModelRepository(database).getAll())
        assertEquals(listOf(VersionOneData.app), RoomAiAppRepository(database).getAll())
        assertEquals(VersionOneData.events.reversed(), RoomActivityLog(database).recent())
    }

    @Test
    fun `a version 1 database with data in every table migrates to the exported schema and keeps its data`() = runTest {
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
    fun `the app opening a version 1 database upgrades it in place and can store conversations in it`() = runTest {
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
        } finally {
            data.close()
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
