package com.aicontrolcenter.app.di.wiring

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.BuildConfig
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.RandomIdGenerator
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.backup.BackupChunks
import com.aicontrolcenter.core.backup.BackupCounts
import com.aicontrolcenter.core.backup.BackupHeader
import com.aicontrolcenter.core.backup.BackupKeys
import com.aicontrolcenter.core.backup.BackupPreamble
import com.aicontrolcenter.core.backup.BackupRecord
import com.aicontrolcenter.core.backup.BackupRecords
import com.aicontrolcenter.core.backup.BackupResult
import com.aicontrolcenter.core.backup.BackupSettings
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.data.backup.DetailedOwnerBackup
import com.aicontrolcenter.data.backup.RoomOwnerBackup
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.framework.agents.AgentTemplates
import com.aicontrolcenter.framework.llm.ProviderConnections
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.framework.tools.ClockTool
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.memory.DefaultMemoryService
import com.aicontrolcenter.memory.HashingEmbedder
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.FakeProviderAdapterFactory
import com.aicontrolcenter.testkit.InMemorySecretVault
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The production backup port as `createProductionPorts` builds it, over Room (Robolectric, unencrypted): it knows this
 * build's starter content, a key the vault holds under a file's alias is never used after a restore, private memories
 * stay private, and tool lines carry the registry's titles once the graph gave it (rule 8; WP3).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackupWiringProductionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val layers = mutableListOf<DataLayer>()

    private fun layer(name: String) =
        DataLayer(context, databaseName = "$name.db", databaseEncryption = DatabaseEncryption.None, settingsFileName = "$name-settings").also(layers::add)

    private fun backupOf(data: DataLayer): DetailedOwnerBackup = assertNotNull(BackupWiring.production(data.backupStores, LocalMaintenanceGate()) { Long.MAX_VALUE })

    @After
    fun close() = runBlocking { layers.forEach { it.close() } }

    private val passphrase = "7KQ2-M9XD-4RTB-8HJN-3VCP"

    /** A one-chunk file holding [body] between its manifest and its end record, made with core's functions only. */
    private fun file(body: List<BackupRecord>): ByteArray = runBlocking {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val header = BackupHeader.forSave(AiccDatabase.VERSION, "0.6.0", 9, 1_000, salt, iterations = 100_000)
        val keys = BackupKeys.derive(passphrase, salt, 100_000)
        val counts = BackupCounts(
            agents = body.count { it is BackupRecord.Agent }, providers = body.count { it is BackupRecord.Provider },
            spaces = body.count { it is BackupRecord.Space }, memories = body.count { it is BackupRecord.Memory },
        )
        val records = listOf<BackupRecord>(BackupRecord.Manifest(counts, BackupSettings())) + body + BackupRecord.End(body.size + 1)
        val plaintext = ByteArrayOutputStream()
        for (record in records) {
            val json = BackupRecords.encode(record).encodeToByteArray()
            plaintext.write(ByteBuffer.allocate(4).putInt(json.size).array())
            plaintext.write(json)
        }
        val preamble = BackupPreamble.write(header, keys)
        val hash = (BackupPreamble.read(ByteArrayInputStream(preamble)) as BackupResult.Done).value.headerHash
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        preamble + BackupChunks.seal(keys, hash, 0, last = true, plaintext = plaintext.toByteArray(), nonce = nonce)
    }

    private fun done(result: BackupResult<*>) {
        if (result is BackupResult.Failed) throw AssertionError(result.problem.text)
    }

    private fun agent(id: String, name: String, readable: Set<SpaceId> = emptySet()) = AgentProfile(
        id = AgentId(id), name = name, kind = AgentKind.SPECIALIST, memory = MemoryPolicy(readableSpaces = readable), createdAt = 1, updatedAt = 1,
    )

    private fun memory(id: String, scope: MemoryScope, text: String) = MemoryRecord(
        id = MemoryId(id), scope = scope, kind = MemoryKind.FACT, tier = MemoryTier.LONG_TERM, title = text, content = text,
        provenance = Provenance(ProvenanceOrigin.OWNER, recordedAt = 1), createdAt = 1, updatedAt = 1,
    )

    @Test
    fun `the production backup knows this build's starter content and version`() {
        val backup = backupOf(layer("wiring-facts"))
        assertIs<RoomOwnerBackup>(backup)
        assertEquals(AgentTemplates.starterTeam.toSet(), BackupWiring.STARTER.agentTemplates)
        assertEquals(setOf(Simulator.PROVIDER_ID), BackupWiring.STARTER.builtInProviders)
        assertEquals(BuildConfig.VERSION_NAME, BackupWiring.APP.appVersion)
        assertEquals(BuildConfig.VERSION_CODE, BackupWiring.APP.appVersionCode)
        assertEquals(AiccDatabase.VERSION, BackupWiring.APP.schemaVersion)
        assertEquals("Open more tools", BackupWiring.FIXED_TITLES["tools_open"])
    }

    @Test
    fun `a key in the vault under the file's alias is never used after a restore`() = runBlocking<Unit> {
        val data = layer("wiring-vault")
        // The vault of this phone holds a key under the alias the file names; the file points the provider elsewhere.
        val vault = InMemorySecretVault()
        vault.put("provider/prv_p1/1", "sk-this-phones-key-0123456789")
        val tampered = ModelProvider(
            id = ProviderId("prv_p1"), name = "Mine", kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = "https://other.example/v1",
            apiKeyAlias = "provider/prv_p1/1", locality = Locality.LOCAL_NETWORK, createdAt = 1, updatedAt = 1,
        )
        val bytes = file(listOf(BackupRecord.Provider(tampered)))
        backupOf(data).restoreWithNotes({ ByteArrayInputStream(bytes) }, passphrase).let { done(it) }
        val stored = data.providers.get(ProviderId("prv_p1"))!!
        assertNull(stored.apiKeyAlias)
        assertEquals(Locality.CLOUD, stored.locality, "the farther of the file's and the address's")
        // The connection the gateway would use carries no key: no request to other.example can carry this phone's key.
        val factory = FakeProviderAdapterFactory(ProviderKind.OPENAI_COMPATIBLE, FakeProviderAdapter())
        ProviderConnections(data.providers, vault, listOf(factory), WallClock).acquire(stored)
        assertNull(factory.connections.single().apiKey)
        assertEquals("sk-this-phones-key-0123456789", vault.get("provider/prv_p1/1"), "the restore never touched the vault")
    }

    @Test
    fun `private memories of one agent are not readable by another after a restore`() = runBlocking<Unit> {
        val data = layer("wiring-memory")
        val team = SpaceId("spc_team")
        val body = listOf(
            BackupRecord.Agent(agent("agt_a", "A", setOf(team))),
            BackupRecord.Agent(agent("agt_b", "B", setOf(team))),
            BackupRecord.Space(KnowledgeSpace(team, "Team", "", 1, 1)),
            BackupRecord.Memory(memory("mem_a", MemoryScope.Agent(AgentId("agt_a")), "The private launch codeword is pelican")),
            BackupRecord.Memory(memory("mem_team", MemoryScope.Shared(team), "The team meets on Mondays")),
        )
        val bytes = file(body)
        backupOf(data).restoreWithNotes({ ByteArrayInputStream(bytes) }, passphrase).let { done(it) }
        val memory = DefaultMemoryService(
            repository = data.memories, agents = data.agents, settings = ReadOnlySettings(data.settings), embedder = HashingEmbedder(),
            activity = ActivityRecorder(data.activityLog, RandomIdGenerator, WallClock), ids = RandomIdGenerator, clock = WallClock,
        )
        val a = MemoryActor.Agent(data.agents.get(AgentId("agt_a"))!!)
        val b = MemoryActor.Agent(data.agents.get(AgentId("agt_b"))!!)
        assertNotNull(memory.get(a, MemoryId("mem_a")))
        assertNull(memory.get(b, MemoryId("mem_a")))
        assertEquals(setOf("mem_a", "mem_team"), memory.observe(a).first().map { it.id.value }.toSet())
        assertEquals(listOf("mem_team"), memory.observe(b).first().map { it.id.value })
        val hits = memory.recall(b, RecallQuery("private launch codeword pelican", minScore = 0.0, recordAccess = false))
        assertTrue(hits.none { it.record.id.value == "mem_a" }, hits.toString())
    }

    @Test
    fun `tool lines carry the registry's titles once the graph gives it`() = runBlocking<Unit> {
        val source = layer("wiring-tools-source")
        val agent = agent("agt_a", "A")
        source.agents.upsert(agent)
        val chat = Conversation(ConversationId("cnv_1"), agent.id, "Time", 1, 1)
        source.conversations.upsertConversation(chat)
        val calls = listOf(ToolCallRequest("c1", "clock_now", JsonObject(emptyMap())), ToolCallRequest("c2", "tools_open", JsonObject(emptyMap())))
        source.conversations.appendEntry(ConversationEntry(chat.id, 0, ChatMessage.assistant("Checking.", calls), 2))
        val backup = backupOf(source)
        val registry = DefaultToolRegistry().apply { register(ClockTool(WallClock)) }
        BackupWiring.useToolTitles(backup, registry)
        val out = ByteArrayOutputStream()
        done(backup.saveWithNotes(passphrase, passphrase, { out }))

        val target = layer("wiring-tools-target")
        done(backupOf(target).restoreWithNotes({ ByteArrayInputStream(out.toByteArray()) }, passphrase))
        assertEquals(
            "Checking.\nA tool was used here: Current time. Its result is not in the backup.\nA tool was used here: Open more tools. Its result is not in the backup.",
            target.conversations.getEntries(chat.id).single().message.text,
        )
    }
}
