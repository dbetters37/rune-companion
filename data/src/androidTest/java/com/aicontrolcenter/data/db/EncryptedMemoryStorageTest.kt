package com.aicontrolcenter.data.db

import android.content.Context
import android.database.SQLException
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.data.DataLayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Memory in the production database: SQLCipher with a key sealed by the device keystore, on a
 * device or emulator. The JVM tests cover the same repository on plain SQLite; this checks what
 * only the real configuration can show: that memory content is not readable from the files,
 * that embeddings survive as blobs, and that SQLCipher's connections enforce the foreign keys.
 *
 * Method names are plain so they can be dexed for every supported API level.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedMemoryStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "encrypted-memory-test-${UUID.randomUUID()}.db"
    private val keyFile = File(context.noBackupFilesDir, DataLayer.databaseKeyFile(databaseName))
    private val open = mutableListOf<DataLayer>()

    private val agent = MemoryScope.Agent(AgentId("agt_researcher"))
    private val space = KnowledgeSpace(SpaceId("spc_home"), "Home", "Things about the house", createdAt = 100, updatedAt = 100)

    private fun encryptedLayer() = DataLayer(context, databaseName = databaseName).also(open::add)

    private fun memory(id: String, scope: MemoryScope, updatedAt: Long, title: String, content: String) = MemoryRecord(
        id = MemoryId(id), scope = scope, kind = MemoryKind.FACT, tier = MemoryTier.LONG_TERM, title = title, content = content,
        tags = setOf("home"), importance = 0.8, confidence = 0.9,
        provenance = Provenance(ProvenanceOrigin.AGENT, agentId = AgentId("agt_researcher"), source = "Tool: Notes", recordedAt = updatedAt),
        createdAt = updatedAt, updatedAt = updatedAt,
    )

    private fun link(id: String, from: String, to: String) = MemoryLink(
        MemoryLinkId(id), MemoryId(from), MemoryId(to), LinkRelation.SUPPORTS, weight = 0.5,
        provenance = Provenance(ProvenanceOrigin.SYSTEM, recordedAt = 1), createdAt = 1,
    )

    private fun databaseFiles(): List<File> {
        val main = context.getDatabasePath(databaseName)
        return listOf(main, File(main.path + "-wal"), File(main.path + "-journal")).filter { it.isFile }
    }

    private fun File.contains(text: String): Boolean = String(readBytes(), Charsets.UTF_8).contains(text)

    private fun DataLayer.count(table: String): Int =
        database.openHelper.writableDatabase.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table")).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @After
    fun tearDown() {
        runBlocking { open.forEach { it.close() } }
        context.deleteDatabase(databaseName)
        keyFile.delete()
    }

    @Test
    fun memoriesWithEmbeddingsAreStoredEncryptedSearchedAndDeleted(): Unit = runTest {
        val boiler = memory("mem_boiler", agent, 300, "Boiler", "Der Kessel im Keller über der Tür heißt $MARKER")
        val alarm = memory("mem_alarm", MemoryScope.Shared(space.id), 200, "Alarm code", "Ask the owner, never store it")
        val task = memory("mem_task", MemoryScope.Task(TaskId("tsk_1"), AgentId("agt_researcher")), 100, "Step", "Check the boiler")
        val vector = FloatArray(384) { index -> (index - 192) / 384f }

        val first = encryptedLayer()
        first.memories.upsertSpace(space)
        listOf(boiler, alarm, task).forEach { first.memories.upsert(it) }
        first.memories.upsertLink(link("lnk_1", "mem_task", "mem_boiler"))
        first.memories.putEmbedding(boiler.id, "hash-v1", vector)
        first.memories.putEmbedding(alarm.id, "hash-v1", floatArrayOf(1f, -0f))
        first.close()

        for (file in databaseFiles()) {
            assertFalse(file.contains(MARKER), "${file.name} contains memory content in the clear")
            assertFalse(file.contains("Kessel"), "${file.name} contains memory content in the clear")
        }

        val second = encryptedLayer()
        assertEquals(listOf(boiler, alarm, task), second.memories.list())
        assertEquals(listOf(space), second.memories.observeSpaces().first())
        // Case (and accents) are ignored beyond ASCII: the search column is folded on write.
        assertEquals(listOf(boiler), second.memories.searchText("KESSEL ÜBER TÜR $MARKER"))
        assertEquals(listOf(boiler, task), second.memories.searchText("BOILER"))
        assertEquals(listOf(task), second.memories.searchText("boiler", MemoryFilter(scopes = setOf(task.scope))))
        assertTrue(second.memories.searchText("100%").isEmpty())
        assertContentEquals(vector, second.memories.embeddings("hash-v1").getValue(boiler.id))
        assertContentEquals(floatArrayOf(1f, -0f), second.memories.embeddings("hash-v1").getValue(alarm.id))
        assertEquals(listOf(task.id), second.memories.missingEmbeddings("hash-v1"))

        second.memories.delete(boiler.id)
        assertNull(second.memories.get(boiler.id))
        assertTrue(second.memories.linksOf(boiler.id).isEmpty())
        assertEquals(setOf(alarm.id), second.memories.embeddings("hash-v1").keys)

        assertEquals(1, second.memories.deleteSpace(space.id))
        assertNull(second.memories.getSpace(space.id))
        assertEquals(listOf(task), second.memories.list())
        assertEquals(0, second.count("memory_embeddings"))
    }

    @Test
    fun foreignKeysAreEnforcedOnTheEncryptedDatabase(): Unit = runTest {
        val data = encryptedLayer()
        data.memories.upsert(memory("mem_a", agent, 100, "A", "First"))
        data.memories.upsert(memory("mem_b", agent, 200, "B", "Second"))
        val sqlite = data.database.openHelper.writableDatabase

        // Past the repository's own checks, only the database refuses a dangling link.
        assertFailsWith<SQLException> {
            sqlite.execSQL(
                "INSERT INTO memory_links (id, from_id, to_id, relation, weight, provenance_json, created_at) " +
                    "VALUES ('lnk_x', 'mem_a', 'mem_missing', 'RELATES_TO', 1.0, '{}', 1)",
            )
        }
        assertFailsWith<IllegalArgumentException> { data.memories.upsertLink(link("lnk_y", "mem_missing", "mem_a")) }

        data.memories.upsertLink(link("lnk_ab", "mem_a", "mem_b"))
        data.memories.upsertLink(link("lnk_ba", "mem_b", "mem_a"))
        data.memories.putEmbedding(MemoryId("mem_a"), "hash-v1", floatArrayOf(1f))
        // Removing only the memory row, which the app never does, still removes what refers to it.
        sqlite.execSQL("DELETE FROM memories WHERE id = 'mem_a'")
        assertEquals(0, data.count("memory_links"))
        assertEquals(0, data.count("memory_embeddings"))
        assertEquals(1, data.count("memories"))
    }

    private companion object {
        const val MARKER = "ZZ-MEMORY-MARKER-91c4"
    }
}
