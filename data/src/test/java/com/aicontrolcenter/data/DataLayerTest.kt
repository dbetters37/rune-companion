package com.aicontrolcenter.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.security.SecretVaultException
import com.aicontrolcenter.data.repository.RoomAgentRepository
import com.aicontrolcenter.data.security.SoftwareVaultCipher
import com.aicontrolcenter.data.security.VaultCipher
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
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

    private fun dataLayer(vaultCipher: VaultCipher = cipher) =
        DataLayer(context, vaultCipher = vaultCipher).also(open::add)

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

        val first = dataLayer()
        first.agents.upsert(agent)
        first.agents.updateStats(agent.id) { it.copy(tasksCompleted = 3) }
        first.providers.upsert(provider)
        first.models.upsert(model)
        first.aiApps.upsert(app)
        first.activityLog.record(event)
        first.settings.update { it.copy(directorAgentId = agent.id, localOnlyMode = true) }
        first.secretVault.put(provider.apiKeyAlias!!, "sk-home-server")
        first.close()

        val second = dataLayer()
        assertEquals(agent, second.agents.observe(agent.id).first())
        assertEquals(AgentStats(tasksCompleted = 3), second.agents.getStats(agent.id))
        assertEquals(listOf(provider), second.providers.getAll())
        assertEquals(listOf(model), second.models.getByProvider(provider.id))
        assertEquals(app, second.aiApps.getByPackage(app.packageName))
        assertEquals(listOf(event), second.activityLog.recent(ActivityFilter(egressOnly = true)))
        assertEquals(agent.id, second.settings.get().directorAgentId)
        assertTrue(second.settings.get().localOnlyMode)
        assertEquals("sk-home-server", second.secretVault.get(provider.apiKeyAlias!!))
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
        val data = DataLayer(context, ioDispatcher = io, vaultCipher = recordingCipher).also(open::add)

        // The stats transform runs inside the database transaction, so the thread it runs on is
        // the thread doing the database work.
        data.agents.updateStats(AgentId("agt_a")) { recordThread(); it.copy(toolCalls = 1) }
        data.secretVault.put("provider/a", "sk-alpha")
        assertEquals("sk-alpha", data.secretVault.get("provider/a"))

        assertEquals(setOf("data-io"), threads.toSet())
    }

    @Test
    fun `without a device keystore the default vault fails with a vault error instead of crashing`() = runTest {
        // Robolectric has no AndroidKeyStore, which is what a broken keystore looks like to the app.
        val data = DataLayer(context).also(open::add)
        assertFailsWith<SecretVaultException> { data.secretVault.put("provider/a", "sk-alpha") }
        assertNull(data.secretVault.get("provider/a"))
        assertEquals(emptySet(), data.secretVault.aliases())
    }
}
