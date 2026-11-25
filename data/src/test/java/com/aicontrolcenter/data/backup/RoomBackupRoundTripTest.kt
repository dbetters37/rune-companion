package com.aicontrolcenter.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupRecord
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.data.security.SoftwareVaultCipher
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backup against Room (Robolectric, `DatabaseEncryption.None`; SQLCipher runs only on a device, where
 * `BackupDeviceTest` repeats the round trip): a whole phone saved and restored into a fresh app, and the restore's one
 * transaction: a file that changes between the check and the writing, or an app that stops being fresh in between,
 * leaves the app exactly as it was, the starter team included.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomBackupRoundTripTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val open = mutableListOf<DataLayer>()
    private val clock = FakeClock()

    private fun layer(name: String) = DataLayer(
        context, databaseName = "$name.db", databaseEncryption = DatabaseEncryption.None, settingsFileName = "$name-settings",
        vaultCipher = SoftwareVaultCipher(), clock = clock,
    ).also(open::add)

    private fun backupOf(data: DataLayer, free: suspend () -> Long = { Long.MAX_VALUE }) = RoomOwnerBackup(
        data.backupStores, LocalMaintenanceGate(), free, BackupFiles.APP, BackupFiles.STARTER,
        toolTitles = ToolTitles(mapOf("tools_open" to "Open more tools")).apply { use(BackupPhone.titles) },
        clock = clock, ids = SequentialIdGenerator(), iterations = BackupFiles.ITERATIONS,
    )

    @After
    fun close() = runBlocking { open.forEach { it.close() } }

    private suspend fun phone(): DataLayer = layer("phone").also { data ->
        BackupPhone.fill(data.agents, data.providers, data.models, data.ownerModelPrices, data.aiApps, data.memories, data.conversations)
        data.settings.update { it.copy(directorAgentId = BackupPhone.ada, seedVersion = 4, memorySeedVersion = 1, localOnlyMode = true) }
        // The key of the xAI provider, in this phone's vault: never in the file.
        data.secretVault.put(BackupPhone.KEY_ALIAS, "xai-0123456789abcdefghijklmnop")
    }

    private suspend fun freshApp(name: String = "fresh"): DataLayer = layer(name).also { data ->
        BackupPhone.fillFresh(data.agents, data.providers, data.models, data.aiApps, data.memories)
        data.settings.update { it.copy(seedVersion = 4, memorySeedVersion = 1, operatorEnabled = true) }
    }

    private suspend fun save(data: DataLayer): ByteArray {
        val out = ByteArrayOutputStream()
        backupOf(data).saveWithNotes(BackupFiles.PASSPHRASE, BackupFiles.PASSPHRASE, { out }).done()
        return out.toByteArray()
    }

    @Test
    fun `a phone saved and restored into a fresh app on Room`() = runBlocking<Unit> {
        val source = phone()
        val file = save(source)
        assertTrue(BackupPhone.KEY_ALIAS !in BackupFiles.plaintext(file).decodeToString())

        val target = freshApp()
        val outcome = backupOf(target).restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done()
        assertEquals(2, outcome.report.counts.agents)
        assertEquals(source.agents.getAll(), target.agents.getAll())
        assertEquals(BackupPhone.adaStats, target.agents.getStats(BackupPhone.ada))
        assertEquals(setOf("prv_xai", "prv_home", "prv_simulator"), target.providers.getAll().map { it.id.value }.toSet())
        val xai = target.providers.get(ProviderId("prv_xai"))!!
        assertNull(xai.apiKeyAlias)
        assertEquals(ConnectionStatus.UNKNOWN, xai.status)
        assertEquals(source.memories.getAll(BackupPhone.memoryIds).toSet(), target.memories.list().toSet())
        assertEquals(listOf("lnk_1"), target.memories.links().map { it.id.value })
        assertEquals(listOf(BackupPhone.space), target.memories.observeSpaces().first())
        assertEquals("app_chat", target.aiApps.getAll().single().id.value)
        assertEquals(BackupPhone.price.inputPerMTokMicros, target.modelPrices.getAll().single().inputPerMTokMicros)
        assertEquals(listOf(BackupPhone.conversation), target.conversations.observeConversations().first())
        assertEquals(BackupPhone.restoredTexts, target.conversations.getEntries(BackupPhone.chat).map { it.message.role to it.message.text })
        // The restored memories have no search vector yet: the memory upkeep at the next start indexes them.
        assertEquals(2, target.memories.missingEmbeddings("hashing").size)
        val settings = target.settings.get()
        assertTrue(settings.localOnlyMode)
        assertEquals(BackupPhone.ada, settings.directorAgentId)
        assertTrue(!settings.operatorEnabled, "the phone operator is off after a restore")
        // The restore is in the log, with counts only.
        val logged = target.activityLog.recent().first { it.title == BackupTexts.LOG_RESTORED }
        assertTrue("2 agents" in logged.detail && "3 chat messages" in logged.detail, logged.detail)
    }

    @Test
    fun `a file that changes between the check and the writing restores nothing`() = runBlocking<Unit> {
        val agent = Samples.agent(id = "agt_one", name = "One", modelId = null).copy(templateId = null)
        val checked = BackupFiles.craft(BackupFiles.framed(listOf(BackupRecord.Agent(agent))))
        // Same header (and so the same keys), another body: more agents, and a chat.
        val chat = Conversation(ConversationId("cnv_x"), agent.id, "x", 1, 1)
        val swapped = BackupFiles.craft(
            BackupFiles.framed(listOf(BackupRecord.Agent(agent), BackupRecord.Agent(agent.copy(id = AgentId("agt_two"), name = "Two")), BackupRecord.Chat(chat))),
        )
        var opens = 0
        val opener: suspend () -> InputStream = { ByteArrayInputStream(if (opens++ == 0) checked else swapped) }
        val target = freshApp()
        val agentsBefore = target.agents.getAll()
        val spacesBefore = target.memories.observeSpaces().first()
        val settingsBefore = target.settings.get()
        assertEquals(BackupProblem.Damaged, backupOf(target).restoreWithNotes(opener, BackupFiles.PASSPHRASE).problem())
        assertEquals(2, opens)
        // Everything as it was: the starter team was not deleted, nothing of either file is there.
        assertEquals(agentsBefore, target.agents.getAll())
        assertEquals(spacesBefore, target.memories.observeSpaces().first())
        assertEquals(emptyList(), target.conversations.observeConversations().first())
        assertEquals(settingsBefore, target.settings.get())
    }

    @Test
    fun `an app that stops being fresh between the check and the writing is checked again inside the transaction`() = runBlocking<Unit> {
        val file = save(phone())
        val target = freshApp("fresh-race")
        val agentsBefore = target.agents.getAll()
        // The free-space check runs after the file was checked and before the transaction: a chat appears then.
        val racing = backupOf(target) {
            target.conversations.upsertConversation(Conversation(ConversationId("cnv_new"), AgentId("agt_fresh_0"), "Hello", 1, 1))
            Long.MAX_VALUE
        }
        assertEquals(BackupProblem.NotEmpty, racing.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).problem())
        assertEquals(agentsBefore, target.agents.getAll())
        assertEquals(listOf("cnv_new"), target.conversations.observeConversations().first().map { it.id.value })
    }

    @Test
    fun `restored chats read back with roles, order and times`() = runBlocking<Unit> {
        val source = phone()
        val target = freshApp("fresh-chat")
        backupOf(target).restoreWithNotes(BackupFiles.opener(save(source)), BackupFiles.PASSPHRASE).done()
        val entries = target.conversations.getEntries(BackupPhone.chat)
        assertEquals(listOf(0, 1, 2), entries.map { it.seq })
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.ASSISTANT), entries.map { it.message.role })
        assertEquals(listOf(3_001L, 3_002L, 3_004L), entries.map { it.at })
        // And the restored app saves again to a file that restores the same.
        val again = freshApp("fresh-again")
        backupOf(again).restoreWithNotes(BackupFiles.opener(save(target)), BackupFiles.PASSPHRASE).done()
        assertEquals(target.agents.getAll(), again.agents.getAll())
        assertEquals(entries.map { it.message.text }, again.conversations.getEntries(BackupPhone.chat).map { it.message.text })
    }
}
