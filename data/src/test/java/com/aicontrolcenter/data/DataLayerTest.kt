package com.aicontrolcenter.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.security.SecretVaultException
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskUsage
import com.aicontrolcenter.data.repository.RoomAgentRepository
import com.aicontrolcenter.data.security.DatabaseKeyException
import com.aicontrolcenter.data.security.SoftwareVaultCipher
import com.aicontrolcenter.data.security.VaultCipher
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.contract.MemorySamples
import com.aicontrolcenter.testkit.contract.TaskSamples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.GeneralSecurityException
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DataLayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cipher = SoftwareVaultCipher()
    private val open = mutableListOf<DataLayer>()
    private val executors = mutableListOf<ExecutorCoroutineDispatcher>()

    /** Unencrypted: the SQLCipher native library cannot be loaded on the JVM. */
    private fun dataLayer(vaultCipher: VaultCipher = cipher) =
        DataLayer(context, databaseEncryption = DatabaseEncryption.None, vaultCipher = vaultCipher).also(open::add)

    @After
    fun tearDown() {
        runBlocking { open.forEach { it.close() } }
        executors.forEach { it.close() }
    }

    @Test
    fun `each member is created once and all repositories share one database`() = runTest {
        val data = dataLayer()
        assertSame(data.database, data.database)
        assertSame(data.agents, data.agents)
        assertSame(data.providers, data.providers)
        assertSame(data.models, data.models)
        assertSame(data.aiApps, data.aiApps)
        assertSame(data.activityLog, data.activityLog)
        assertSame(data.conversations, data.conversations)
        assertSame(data.memories, data.memories)
        assertSame(data.tasks, data.tasks)
        assertSame(data.messages, data.messages)
        assertSame(data.settings, data.settings)
        assertSame(data.secretVault, data.secretVault)

        val agent = Samples.agent()
        data.agents.upsert(agent)
        assertEquals(listOf(agent), RoomAgentRepository(data.database).getAll())
    }

    @Test
    fun `every port works and keeps its data when the layer is closed and opened again`() = runTest {
        val agent = Samples.agent()
        val provider = Samples.provider()
        val model = Samples.model()
        val app = Samples.aiApp()
        val event = Samples.event()
        val conversation = Conversation(ConversationId("cnv_1"), agent.id, "First chat", createdAt = 2_000, updatedAt = 2_500)
        val question = ConversationEntry(conversation.id, seq = 0, ChatMessage.user("What time is it?"), at = 2_100)
        val answer = ConversationEntry(
            conversation.id, seq = 1, ChatMessage.assistant("Ten o'clock."), at = 2_200, modelName = "model-x", usage = TokenUsage(120, 30),
        )

        val first = dataLayer()
        first.agents.upsert(agent)
        first.agents.updateStats(agent.id) { it.copy(tasksCompleted = 3) }
        first.providers.upsert(provider)
        first.models.upsert(model)
        first.aiApps.upsert(app)
        first.activityLog.record(event)
        first.conversations.upsertConversation(conversation)
        assertEquals(listOf(question, answer), listOf(question, answer).map { first.conversations.appendEntry(it) })
        val memory = MemorySamples.memory("mem_1", MemorySamples.researcher)
        val linked = MemorySamples.memory("mem_2", MemorySamples.commons)
        first.memories.upsert(memory)
        first.memories.upsert(linked)
        first.memories.upsertLink(MemorySamples.link("lnk_1", "mem_1", "mem_2"))
        first.memories.putEmbedding(memory.id, "emb", floatArrayOf(0.6f, 0.8f))
        val objective = TaskSamples.objective()
        val work = TaskSamples.work("tsk_1", status = TaskStatus.RUNNING)
        first.tasks.upsertAll(listOf(objective, work))
        val assignment = TaskSamples.message("msg_1", at = 2_000)
        first.messages.append(assignment)
        first.settings.update { it.copy(directorAgentId = agent.id, localOnlyMode = true, memorySeedVersion = 1) }
        first.secretVault.put(provider.apiKeyAlias!!, "sk-home-server")
        first.close()

        val second = dataLayer()
        assertEquals(agent, second.agents.observe(agent.id).first())
        assertEquals(AgentStats(tasksCompleted = 3), second.agents.getStats(agent.id))
        assertEquals(listOf(provider), second.providers.getAll())
        assertEquals(listOf(model), second.models.getByProvider(provider.id))
        assertEquals(app, second.aiApps.getByPackage(app.packageName))
        assertEquals(listOf(event), second.activityLog.recent(ActivityFilter(egressOnly = true)))
        assertEquals(listOf(conversation), second.conversations.observeConversations(agent.id).first())
        assertEquals(listOf(question, answer), second.conversations.observeEntries(conversation.id).first())
        // Numbering carries on where it stopped before the restart.
        assertEquals(2, second.conversations.appendEntry(question.copy(at = 2_300)).seq)
        assertEquals(listOf(memory, linked), second.memories.list())
        assertEquals(listOf("lnk_1"), second.memories.linksOf(MemoryId("mem_1")).map { it.id.value })
        assertEquals(listOf(0.6f, 0.8f), second.memories.embeddings("emb").getValue(memory.id).toList())
        assertEquals(listOf(memory), second.memories.searchText("CONTENT mem_1"))
        // What was running when the app stopped is found again after the restart.
        assertEquals(listOf(objective, work), second.tasks.withStatus(setOf(TaskStatus.RUNNING)))
        assertEquals(listOf(objective), second.tasks.observeObjectives().first())
        assertEquals(listOf(assignment), second.messages.list(objective.id))
        assertEquals(1, second.settings.get().memorySeedVersion)
        assertEquals(agent.id, second.settings.get().directorAgentId)
        assertTrue(second.settings.get().localOnlyMode)
        assertEquals("sk-home-server", second.secretVault.get(provider.apiKeyAlias!!))
    }

    @Test
    fun `entries appended from many threads to the database file get consecutive numbers`() = runTest {
        // The file-backed database has a pool of connections, unlike the in-memory one of the contract suite.
        val data = dataLayer()
        val id = ConversationId("cnv_1")
        data.conversations.upsertConversation(Conversation(id, AgentId("agt_a"), "Chat", createdAt = 1, updatedAt = 1))
        withContext(Dispatchers.Default) {
            (1..80).map { n ->
                launch { data.conversations.appendEntry(ConversationEntry(id, seq = 0, ChatMessage.user("m$n"), at = n.toLong())) }
            }.joinAll()
        }
        val entries = data.conversations.getEntries(id)
        assertEquals((0..79).toList(), entries.map { it.seq })
        assertEquals((1..80).map { "m$it" }.toSet(), entries.map { it.message.text }.toSet())
    }

    @Test
    fun `updates of one task from many threads to the database file are all applied`() = runTest {
        // The file-backed database has a pool of connections, unlike the in-memory one of the contract suite.
        val data = dataLayer()
        val id = TaskId("tsk_1")
        data.tasks.upsert(TaskSamples.work(id.value).copy(usage = TaskUsage()))
        withContext(Dispatchers.Default) {
            (1..80).map { n ->
                launch {
                    data.tasks.update(id) { it.copy(usage = it.usage + TaskUsage(modelCalls = 1, messagesSent = n)) }
                    data.messages.append(TaskSamples.message("msg_$n", at = n.toLong(), type = MessageType.STATUS))
                }
            }.joinAll()
        }
        assertEquals(TaskUsage(modelCalls = 80, messagesSent = (1..80).sum()), data.tasks.get(id)?.usage)
        assertEquals(80, data.messages.count(TaskSamples.objective().id))
    }

    @Test
    fun `everything is stored in app-private locations and the vault is excluded from backup`() = runTest {
        val data = dataLayer()
        data.agents.upsert(Samples.agent())
        data.settings.update { it.copy(seedVersion = 1) }
        data.secretVault.put("provider/a", "sk-alpha")

        val appData = context.dataDir.canonicalPath
        val database = context.getDatabasePath("aicc.db")
        val settings = context.preferencesDataStoreFile("aicc_settings")
        val vault = File(context.noBackupFilesDir, "vault/secrets.json")
        for (file in listOf(database, settings, vault)) {
            assertTrue(file.isFile, "expected $file to exist")
            assertTrue(file.canonicalPath.startsWith(appData), "$file is outside the app's private storage")
        }
        assertFalse("sk-alpha" in vault.readText())
    }

    @Test
    fun `nothing is created until it is used and an unused layer closes cleanly`() = runTest {
        val data = dataLayer()
        data.close()
        assertFalse(context.getDatabasePath("aicc.db").exists())
        assertFalse(File(context.noBackupFilesDir, "vault/secrets.json").exists())
    }

    @Test
    fun `database and vault work runs on the supplied dispatcher, not on the calling thread`() = runTest {
        val io = Executors.newFixedThreadPool(2) { task -> Thread(task, "data-io") }.asCoroutineDispatcher().also(executors::add)
        val threads = Collections.synchronizedSet(mutableSetOf<String>())
        // Coroutine debugging appends " @coroutine#n" to thread names; only the thread matters here.
        fun recordThread() = threads.add(Thread.currentThread().name.substringBefore(" @"))
        val recordingCipher = object : VaultCipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
                recordThread()
                return cipher.encrypt(plaintext, associatedData)
            }

            override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray {
                recordThread()
                return cipher.decrypt(sealed, associatedData)
            }
        }
        val data = DataLayer(context, ioDispatcher = io, databaseEncryption = DatabaseEncryption.None, vaultCipher = recordingCipher)
            .also(open::add)

        // The stats transform runs inside the database transaction, so the thread it runs on is
        // the thread doing the database work.
        val agent = Samples.agent()
        data.agents.upsert(agent)
        var transformRan = false
        data.agents.updateStats(agent.id) { recordThread(); transformRan = true; it.copy(toolCalls = 1) }
        assertTrue(transformRan)
        data.secretVault.put("provider/a", "sk-alpha")
        assertEquals("sk-alpha", data.secretVault.get("provider/a"))

        assertEquals(setOf("data-io"), threads.toSet())
    }

    @Test
    fun `without a device keystore the default vault fails with a vault error instead of crashing`() = runTest {
        // Robolectric has no AndroidKeyStore, which is what a broken keystore looks like to the app.
        val data = DataLayer(context, databaseEncryption = DatabaseEncryption.None).also(open::add)
        assertFailsWith<SecretVaultException> { data.secretVault.put("provider/a", "sk-alpha") }
        assertNull(data.secretVault.get("provider/a"))
        assertEquals(emptySet(), data.secretVault.aliases())
    }

    @Test
    fun `by default the database is encrypted, and without a usable keystore it is not opened at all`() = runTest {
        val data = DataLayer(context, vaultCipher = cipher).also(open::add)

        // Building the repositories touches neither the keystore nor a file.
        val agents = data.agents
        assertFalse(context.getDatabasePath("aicc.db").exists())

        val failure = assertFailsWith<DatabaseKeyException> { agents.getAll() }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is GeneralSecurityException })
        assertFailsWith<DatabaseKeyException> { agents.upsert(Samples.agent()) }

        // No unencrypted database was created as a fallback, and no key was left behind.
        assertFalse(context.getDatabasePath("aicc.db").exists())
        assertFalse(File(context.noBackupFilesDir, DataLayer.databaseKeyFile("aicc.db")).exists())
    }

    @Test
    fun `the sealed database key has its own file outside of backups`() {
        assertEquals("database-keys/aicc.db.key", DataLayer.databaseKeyFile("aicc.db"))
        assertEquals("database-keys/other.db.key", DataLayer.databaseKeyFile("other.db"))
    }
}
