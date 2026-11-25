package com.aicontrolcenter.data.backup

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.backup.BackupResult
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.data.DataLayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A backup saved from a SQLCipher database (the production configuration, the device keystore) through a file URI and
 * restored into a fresh SQLCipher database, with the real key derivation (600,000 iterations); times are logged (tag
 * `BackupDevice`). The JVM tests cover the format and the rules; this is the same path on the phone's own SQLite,
 * keystore, crypto provider and content resolver. Method names are camelCase (API levels below 30).
 */
@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val run = UUID.randomUUID().toString()
    private val sourceName = "backup-source-$run.db"
    private val targetName = "backup-target-$run.db"
    private val file = File(context.cacheDir, "backup-$run.aiccbackup")
    private val uri: Uri = Uri.fromFile(file)
    private val open = mutableListOf<DataLayer>()
    private val passphrase = "7KQ2-M9XD-4RTB-8HJN-3VCP"

    private fun layer(name: String) = DataLayer(context, databaseName = name, settingsFileName = "$name-settings").also(open::add)

    private fun backupOf(data: DataLayer) = RoomOwnerBackup(
        data.backupStores, LocalMaintenanceGate(), { Long.MAX_VALUE },
        BackupAppInfo("0.6.0", 9, 6),
        StarterContent(setOf("director", "researcher", "analyst", "writer", "reviewer", "operator"), setOf(ProviderId("prv_simulator"))),
        toolTitles = ToolTitles(mapOf("screen_read" to "Read the screen")),
    )

    @After
    fun tearDown() {
        runBlocking { open.forEach { it.close() } }
        for (name in listOf(sourceName, targetName)) {
            context.deleteDatabase(name)
            File(context.noBackupFilesDir, DataLayer.databaseKeyFile(name)).delete()
            File(context.filesDir, "datastore/$name-settings.preferences_pb").delete()
        }
        file.delete()
    }

    private val team = SpaceId("spc_team")

    private fun agent(id: String, name: String) = AgentProfile(
        id = AgentId(id), name = name, kind = AgentKind.SPECIALIST, description = "On the phone",
        memory = MemoryPolicy(readableSpaces = setOf(team), writableSpaces = setOf(team)), createdAt = 1_000, updatedAt = 1_000,
    )

    private fun memory(id: String, scope: MemoryScope, text: String) = MemoryRecord(
        id = MemoryId(id), scope = scope, kind = MemoryKind.FACT, tier = MemoryTier.LONG_TERM, title = text.take(40), content = text,
        provenance = Provenance(ProvenanceOrigin.OWNER, recordedAt = 1_000), createdAt = 1_000, updatedAt = 1_000,
    )

    @Test
    fun aPhoneSavedThroughAFileUriRestoresIntoAFreshEncryptedApp(): Unit = runBlocking {
        val source = layer(sourceName)
        val ada = agent("agt_ada", "Ada")
        source.agents.upsert(ada)
        source.agents.updateStats(ada.id) { AgentStats(tasksCompleted = 4) }
        source.providers.upsert(
            ModelProvider(
                id = ProviderId("prv_xai"), name = "xAI", kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = "https://api.x.ai/v1",
                apiKeyAlias = "provider/prv_xai/1", extraHeaders = mapOf("X-Title" to "AICC", "X-Token" to "HEADER-SECRET-123"),
                locality = Locality.CLOUD, createdAt = 1_000, updatedAt = 1_000,
            ),
        )
        source.memories.upsertSpace(KnowledgeSpace(team, "Team", "", 1_000, 1_000))
        source.memories.upsertAll(
            listOf(
                memory("mem_own", MemoryScope.Agent(ada.id), "Ada's own note"),
                memory("mem_team", MemoryScope.Shared(team), "The team meets on Mondays"),
                memory("mem_task", MemoryScope.Task(TaskId("tsk_1"), ada.id), "TASK-MEMORY-SECRET"),
            ),
        )
        val chat = Conversation(ConversationId("cnv_1"), ada.id, "Screen", 2_000, 2_000)
        source.conversations.upsertConversation(chat)
        source.conversations.appendEntry(ConversationEntry(chat.id, 0, ChatMessage.user("What is on screen?"), 2_001))
        source.conversations.appendEntry(
            ConversationEntry(chat.id, 1, ChatMessage.assistant("Looking.", listOf(ToolCallRequest("c1", "screen_read", JsonObject(emptyMap())))), 2_002),
        )
        source.conversations.appendEntry(ConversationEntry(chat.id, 2, ChatMessage.toolResult("c1", "screen_read", "SCREEN-RESULT-SECRET"), 2_003))

        val saveStarted = SystemClock.elapsedRealtime()
        val saved = backupOf(source).saveWithNotes(passphrase, passphrase, { context.contentResolver.openOutputStream(uri)!! })
        Log.i("BackupDevice", "save, 600,000 iterations included: ${SystemClock.elapsedRealtime() - saveStarted} ms, ${file.length()} bytes")
        assertTrue(saved is BackupResult.Done, "save: $saved")
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        for (secret in listOf("Ada's own note", "HEADER-SECRET-123", "SCREEN-RESULT-SECRET", "TASK-MEMORY-SECRET", "provider/prv_xai/1")) {
            assertFalse(secret in raw, "\"$secret\" is readable in the file")
        }

        val target = layer(targetName)
        val restoreStarted = SystemClock.elapsedRealtime()
        val restored = backupOf(target).restoreWithNotes({ context.contentResolver.openInputStream(uri)!! }, passphrase)
        Log.i("BackupDevice", "restore, 600,000 iterations included: ${SystemClock.elapsedRealtime() - restoreStarted} ms")
        assertTrue(restored is BackupResult.Done, "restore: $restored")
        assertEquals(listOf(ada), target.agents.getAll())
        assertEquals(AgentStats(tasksCompleted = 4), target.agents.getStats(ada.id))
        val xai = target.providers.get(ProviderId("prv_xai"))!!
        assertNull(xai.apiKeyAlias)
        assertEquals(mapOf("X-Title" to "AICC"), xai.extraHeaders)
        assertEquals(setOf("mem_own", "mem_team"), target.memories.list().map { it.id.value }.toSet())
        assertEquals(listOf(team), target.memories.observeSpaces().first().map { it.id })
        assertEquals(
            listOf("What is on screen?", "Looking.\nA tool was used here: Read the screen. Its result is not in the backup."),
            target.conversations.getEntries(chat.id).map { it.message.text },
        )
    }
}
