package com.aicontrolcenter.data.db

import android.content.Context
import android.database.SQLException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ImageContent
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.data.repository.RoomAgentRepository
import com.aicontrolcenter.data.security.AndroidKeystoreVaultCipher
import com.aicontrolcenter.data.security.DatabaseKeyException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Runs on a device or emulator: the SQLCipher native library and the AndroidKeyStore do not
 * exist on the JVM, so this is the only place the encrypted database is really opened. The key
 * handling above it (creating, sealing, never replacing) is also covered by `DatabaseKeyStoreTest`
 * on the JVM with a software cipher.
 *
 * Each test uses its own database and removes it, with its sealed key, afterwards.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "encrypted-test-${UUID.randomUUID()}.db"
    private val plainName = "plain-test-${UUID.randomUUID()}.db"
    private val keyFile = File(context.noBackupFilesDir, DataLayer.databaseKeyFile(databaseName))
    private val open = mutableListOf<DataLayer>()

    private val agent = AgentProfile(
        id = AgentId("agt_encrypted"), name = MARKER, kind = AgentKind.SPECIALIST,
        description = "Stored in an encrypted database.", createdAt = 1_000, updatedAt = 1_000,
    )

    /** The production configuration: SQLCipher with a key sealed by the device keystore. */
    private fun encryptedLayer() = DataLayer(context, databaseName = databaseName).also(open::add)

    private fun databaseFiles(name: String): List<File> {
        val main = context.getDatabasePath(name)
        return listOf(main, File(main.path + "-wal"), File(main.path + "-journal")).filter { it.isFile }
    }

    private fun File.startsWithSqliteHeader(): Boolean =
        length() >= SQLITE_HEADER.size && inputStream().use { it.readNBytesCompat(SQLITE_HEADER.size) }.contentEquals(SQLITE_HEADER)

    private fun java.io.InputStream.readNBytesCompat(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = read(buffer, filled, count - filled)
            if (read < 0) break
            filled += read
        }
        return buffer.copyOf(filled)
    }

    private fun File.contains(text: String): Boolean = String(readBytes(), Charsets.ISO_8859_1).contains(text)

    @After
    fun tearDown() {
        runBlocking { open.forEach { it.close() } }
        context.deleteDatabase(databaseName)
        context.deleteDatabase(plainName)
        keyFile.delete()
    }

    @Test
    fun dataWrittenToTheEncryptedDatabaseIsThereAfterReopening(): Unit = runTest {
        val first = encryptedLayer()
        first.agents.upsert(agent)
        first.agents.updateStats(agent.id) { it.copy(tasksCompleted = 3) }
        first.close()

        assertTrue(keyFile.isFile, "the sealed database key should be stored in $keyFile")

        val second = encryptedLayer()
        assertEquals(listOf(agent), second.agents.getAll())
        assertEquals(AgentStats(tasksCompleted = 3), second.agents.getStats(agent.id))
    }

    @Test
    fun theDatabaseFileIsNotPlainSqliteAndDoesNotRevealItsContent(): Unit = runTest {
        val encrypted = encryptedLayer()
        encrypted.agents.upsert(agent)
        encrypted.close()

        // The same content in a plain database, to prove that these checks can tell the difference.
        val plain = DataLayer(context, databaseName = plainName, databaseEncryption = DatabaseEncryption.None).also(open::add)
        plain.agents.upsert(agent)
        plain.close()
        assertTrue(context.getDatabasePath(plainName).startsWithSqliteHeader())
        assertTrue(databaseFiles(plainName).any { it.contains(MARKER) })

        val files = databaseFiles(databaseName)
        assertTrue(context.getDatabasePath(databaseName).length() > 0, "the encrypted database file is empty")
        assertFalse(context.getDatabasePath(databaseName).startsWithSqliteHeader(), "the file starts with the plain SQLite header")
        for (file in files) {
            assertFalse(file.contains(MARKER), "${file.name} contains stored text in the clear")
            assertFalse(file.contains("CREATE TABLE"), "${file.name} contains the schema in the clear")
        }
    }

    @Test
    fun conversationsWithLargeMessagesAreStoredEncryptedAndRemovedWithTheirEntries(): Unit = runTest(timeout = 5.minutes) {
        val conversation = Conversation(ConversationId("cnv_encrypted"), agent.id, MARKER, createdAt = 1_000, updatedAt = 1_000)
        // The size of a photo attached to a message: more than fits into one cursor window.
        val photo = ImageContent("image/jpeg", "QUJD".repeat(1_000_000))
        val first = encryptedLayer()
        first.conversations.upsertConversation(conversation)
        val stored = listOf(
            first.conversations.appendEntry(ConversationEntry(conversation.id, seq = 0, ChatMessage.user(MARKER, listOf(photo)), at = 1_100)),
            first.conversations.appendEntry(
                ConversationEntry(conversation.id, seq = 0, ChatMessage.assistant("Seen."), at = 1_200, modelName = "model-x"),
            ),
        )
        first.close()

        for (file in databaseFiles(databaseName)) {
            assertFalse(file.contains(MARKER), "${file.name} contains stored text in the clear")
        }

        val second = encryptedLayer()
        assertEquals(listOf(conversation), second.conversations.observeConversations().first())
        assertEquals(stored, second.conversations.getEntries(conversation.id))

        second.conversations.deleteConversation(conversation.id)
        assertNull(second.conversations.getConversation(conversation.id))
        assertEquals(emptyList(), second.conversations.getEntries(conversation.id))
    }

    @Test
    fun openingWithAWrongKeyFailsAndLeavesTheDatabaseReadable(): Unit = runTest {
        val rightKey = ByteArray(32) { (it + 1).toByte() }
        val wrongKey = ByteArray(32) { (it + 2).toByte() }

        val created = AiccDatabase.create(context, databaseName, SqlCipher.openHelperFactory(rightKey))
        try {
            RoomAgentRepository(created).upsert(agent)
        } finally {
            created.close()
        }
        val sizeBefore = context.getDatabasePath(databaseName).length()

        val wrong = AiccDatabase.create(context, databaseName, SqlCipher.openHelperFactory(wrongKey))
        try {
            assertFailsWith<SQLException> { RoomAgentRepository(wrong).getAll() }
        } finally {
            wrong.close()
        }

        // A wrong key must not be mistaken for corruption and "repaired" by deleting the file.
        assertEquals(sizeBefore, context.getDatabasePath(databaseName).length())
        val reopened = AiccDatabase.create(context, databaseName, SqlCipher.openHelperFactory(rightKey))
        try {
            assertEquals(listOf(agent), RoomAgentRepository(reopened).getAll())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun aLostKeystoreKeyIsReportedAndNeitherKeyNorDatabaseIsReplaced(): Unit = runTest {
        val first = encryptedLayer()
        first.agents.upsert(agent)
        first.close()
        val sealedKey = keyFile.readBytes()
        val databaseSize = context.getDatabasePath(databaseName).length()

        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS)

        val second = encryptedLayer()
        assertFailsWith<DatabaseKeyException> { second.agents.getAll() }
        assertFailsWith<DatabaseKeyException> { second.agents.upsert(agent.copy(name = "Replaced")) }
        assertContentEquals(sealedKey, keyFile.readBytes())
        assertEquals(databaseSize, context.getDatabasePath(databaseName).length())
    }

    @Test
    fun aDatabaseWhoseSealedKeyIsMissingIsReportedAndNoNewKeyIsCreated(): Unit = runTest {
        val first = encryptedLayer()
        first.agents.upsert(agent)
        first.close()
        val databaseSize = context.getDatabasePath(databaseName).length()
        assertTrue(keyFile.delete())

        val second = encryptedLayer()
        assertFailsWith<DatabaseKeyException> { second.agents.getAll() }
        assertFalse(keyFile.exists())
        assertEquals(databaseSize, context.getDatabasePath(databaseName).length())
    }

    @Test
    fun encryptedDatabasesWithDifferentNamesHaveDifferentKeys(): Unit = runTest {
        val first = encryptedLayer()
        first.agents.upsert(agent)
        first.close()

        // The other database's sealed key does not open this one, even on the same device.
        val otherName = "encrypted-other-${UUID.randomUUID()}.db"
        val otherKeyFile = File(context.noBackupFilesDir, DataLayer.databaseKeyFile(otherName))
        try {
            val other = DataLayer(context, databaseName = otherName).also(open::add)
            other.agents.upsert(agent)
            other.close()
            assertFalse(keyFile.readBytes().contentEquals(otherKeyFile.readBytes()))

            keyFile.writeBytes(otherKeyFile.readBytes())
            assertFailsWith<DatabaseKeyException> { encryptedLayer().agents.getAll() }
        } finally {
            context.deleteDatabase(otherName)
            otherKeyFile.delete()
        }
    }

    private companion object {
        const val MARKER = "ZZ-PLAINTEXT-MARKER-7f3a"
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
