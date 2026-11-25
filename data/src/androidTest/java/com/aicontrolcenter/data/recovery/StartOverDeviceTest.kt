package com.aicontrolcenter.data.recovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.RecoveryResult
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.SqlCipher
import com.aicontrolcenter.data.repository.RoomAgentRepository
import com.aicontrolcenter.data.security.AndroidKeystoreVaultCipher
import com.aicontrolcenter.data.security.DatabaseKeyStore
import com.aicontrolcenter.data.security.EncryptedFileSecretVault
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Start over and key generations (Phase 6, rule 9) on a device, with the real AndroidKeyStore and SQLCipher. The JVM
 * twin with a fake keystore is `FileOwnerRecoveryTest`. Each test uses its own database name (and so its own
 * generation note) and its own settings file, and removes what it made; the keystore entries of generation 2 it may
 * create are removed too.
 */
@RunWith(AndroidJUnit4::class)
class StartOverDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val base = "startover-${UUID.randomUUID()}.db"
    private val settingsFile = "startover_settings_${UUID.randomUUID().toString().replace("-", "")}"
    private val noBackup: File get() = context.noBackupFilesDir
    private val open = mutableListOf<DataLayer>()
    private val setAside = mutableListOf<File>()

    private val agent = AgentProfile(
        id = AgentId("agt_startover"), name = "Kept aside", kind = AgentKind.SPECIALIST,
        description = "Written before Start over.", createdAt = 1_000, updatedAt = 1_000,
    )

    private fun layer(): DataLayer = DataLayer(context, databaseName = base, settingsFileName = settingsFile).also(open::add)

    private suspend fun close(data: DataLayer) {
        data.close()
        open.remove(data)
    }

    private fun names(generation: Int) = KeyGenerations.names(base, generation)

    @After
    fun tearDown() {
        runBlocking { open.forEach { it.close() } }
        for (generation in 1..3) {
            val names = names(generation)
            context.deleteDatabase(names.databaseFile)
            File(noBackup, names.databaseKeyFile).delete()
        }
        File(noBackup, KeyGenerations.fileFor(base)).delete()
        File(noBackup, names(2).vaultFile).delete()
        setAside.forEach { it.deleteRecursively() }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry(names(2).databaseKeyAlias)
        keyStore.deleteEntry(names(2).vaultKeyAlias)
    }

    @Test
    fun sealedKeyRemovedIsReportedAsMissingAndNoKeyIsInvented(): Unit = runTest(timeout = 2.minutes) {
        val first = layer()
        first.agents.upsert(agent)
        close(first)
        val keyFile = File(noBackup, names(1).databaseKeyFile)
        assertTrue(keyFile.delete())

        val second = layer()
        assertEquals(DatabaseState.KEY_MISSING, second.ownerRecovery.state())
        assertFalse(keyFile.exists())
        assertTrue(context.getDatabasePath(base).length() > 0, "the data is still there")
    }

    @Test
    fun startOverWithTheRealKeystoreAndSqlCipherGivesAFreshStartAndKeepsTheOldCopyOpenable(): Unit = runTest(timeout = 2.minutes) {
        val first = layer()
        first.agents.upsert(agent)
        close(first)
        val keyFile = File(noBackup, names(1).databaseKeyFile)
        val intactSealedKey = keyFile.readBytes()
        // Damage the sealed key in place: it no longer unseals.
        keyFile.writeBytes(intactSealedKey.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() })

        val broken = layer()
        assertEquals(DatabaseState.KEY_DAMAGED, broken.ownerRecovery.state())
        val done = assertIs<RecoveryResult.Done>(broken.ownerRecovery.startOver())
        close(broken)
        val folder = File(done.setAsideFolder).also(setAside::add)
        val asideDatabase = File(folder, "databases/$base")
        assertTrue(asideDatabase.isFile)
        assertTrue(File(folder, "database-keys/$base.key").isFile)
        assertFalse(context.getDatabasePath(base).exists())

        // The fresh start: generation 2, its own file, sealed key and keystore entry.
        val fresh = layer()
        assertEquals(DatabaseState.OK, fresh.ownerRecovery.state())
        assertEquals(emptyList(), fresh.agents.getAll())
        fresh.agents.upsert(agent.copy(id = AgentId("agt_fresh")))
        assertEquals(2, KeyGenerations(noBackup, base).current())
        assertTrue(context.getDatabasePath(names(2).databaseFile).length() > 0)
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(names(2).databaseKeyAlias))

        // The set-aside copy still opens with the intact sealed key under generation 1's keystore entry, which stayed.
        val sealedCopy = File(context.cacheDir, "sealed-${UUID.randomUUID()}").apply { writeBytes(intactSealedKey) }
        val key = DatabaseKeyStore(sealedCopy, AndroidKeystoreVaultCipher(AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS), base)
            .loadOrCreate(databaseExists = true)
        val database = AiccDatabase.create(context, asideDatabase.path, SqlCipher.openHelperFactory(key))
        key.fill(0)
        try {
            assertEquals(listOf(agent), RoomAgentRepository(database).getAll())
        } finally {
            database.close()
            sealedCopy.delete()
        }
    }

    @Test
    fun generationOneDataWrittenThe052WayStillOpensAfterTheUpgrade(): Unit = runTest(timeout = 2.minutes) {
        // Exactly as 0.5.2 stored it: no generation note, the sealed key under aicc.database-key.v1 bound to the
        // database's name, SQLCipher with that key, the vault under aicc.secret-vault.v1 in vault/secrets.json.
        val keyFile = File(noBackup, DataLayer.databaseKeyFile(base))
        val key = DatabaseKeyStore(keyFile, AndroidKeystoreVaultCipher(AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS), base)
            .loadOrCreate(databaseExists = false)
        val database = AiccDatabase.create(context, base, SqlCipher.openHelperFactory(key))
        key.fill(0)
        RoomAgentRepository(database).upsert(agent)
        database.close()
        val sealed = keyFile.readBytes()
        val alias = "provider/startover-${UUID.randomUUID()}"
        val oldVault = EncryptedFileSecretVault(File(noBackup, DataLayer.VAULT_FILE), AndroidKeystoreVaultCipher())
        oldVault.put(alias, "sk-kept-0123456789")

        val upgraded = layer()
        try {
            assertEquals(DatabaseState.OK, upgraded.ownerRecovery.state())
            assertEquals(listOf(agent), upgraded.agents.getAll())
            assertEquals("sk-kept-0123456789", upgraded.secretVault.get(alias))
            assertContentEquals(sealed, keyFile.readBytes())
            assertFalse(File(noBackup, KeyGenerations.fileFor(base)).exists())
            assertNull(upgraded.secretVault.get("$alias-other"))
        } finally {
            upgraded.secretVault.delete(alias)
        }
    }
}
