package com.aicontrolcenter.data.recovery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.RecoveryResult
import com.aicontrolcenter.core.security.SecretVaultException
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.security.AndroidKeystoreVaultCipher
import com.aicontrolcenter.data.security.DatabaseKeyStore
import com.aicontrolcenter.data.security.DatabaseKeying
import com.aicontrolcenter.data.security.EncryptedFileSecretVault
import com.aicontrolcenter.data.security.FakeAndroidKeyStore
import com.aicontrolcenter.data.settings.DataStoreSettingsRepository
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Start over (Phase 6, rule 9) on the JVM: the real key handling (generations, sealed keys, the vault) over a fake
 * "AndroidKeyStore"; only the final open is plain SQLite instead of SQLCipher ([DatabaseKeying.PlainForTests]). The same
 * paths run against the real keystore and SQLCipher in `data/src/androidTest/.../recovery/StartOverDeviceTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FileOwnerRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keystore = FakeAndroidKeyStore()
    private val clock = FakeClock(1_795_000_000_000) // 2026-11-18 12:06 in Berlin
    private val zone = ZoneId.of("Europe/Berlin")
    private val open = mutableListOf<DataLayer>()

    @Before
    fun installKeystore() = keystore.install()

    @After
    fun tearDown() {
        runBlocking { open.forEach { it.close() } }
        keystore.uninstall()
    }

    /** The app's data layer, keyed as in the app, opening plain SQLite at the end. A new one is a new process. */
    private fun keyed(): DataLayer = DataLayer(
        context, Dispatchers.IO, AiccDatabase.DEFAULT_NAME, DatabaseEncryption.Keystore, DataStoreSettingsRepository.DEFAULT_FILE_NAME,
        null, clock, { zone }, DatabaseKeying.PlainForTests,
    ).also(open::add)

    /** Ends the "process" of [data]: the next [keyed] is the restarted app. */
    private suspend fun restart(data: DataLayer) {
        data.close()
        open.remove(data)
    }

    private val noBackup: File get() = context.noBackupFilesDir
    private fun database(name: String) = context.getDatabasePath(name)

    /** An app with an agent, a secret and some settings, as the owner left it. */
    private suspend fun lived(): DataLayer {
        val data = keyed()
        data.agents.upsert(Samples.agent())
        data.secretVault.put("provider/p1", "sk-alpha-0123456789")
        data.settings.update {
            it.copy(
                seedVersion = 4, memorySeedVersion = 2, directorAgentId = AgentId("agt_director"), onboardingCompleted = true,
                operatorEnabled = true, accessibilityOperatorEnabled = true, localOnlyMode = true, maxModelCallsPerDay = 50,
            )
        }
        return data
    }

    @Test
    fun `a damaged keystore entry is reported, and Start over gives a fresh start at generation 2 that sets nothing old aside unopenable`() = runTest {
        restart(lived())
        val sealedKey = File(noBackup, "database-keys/aicc.db.key").readBytes()
        keystore.brokenAliases += AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS

        val broken = keyed()
        assertEquals(DatabaseState.KEY_DAMAGED, broken.ownerRecovery.state())
        val done = assertIs<RecoveryResult.Done>(broken.ownerRecovery.startOver())
        restart(broken)

        // Set aside, never deleted: the database, its sealed key and the vault, with a note naming their keystore entries.
        val folder = File(done.setAsideFolder)
        assertEquals(File(noBackup, "set-aside-2026-11-18").canonicalPath, folder.canonicalPath)
        assertTrue(File(folder, "databases/aicc.db").isFile)
        assertEquals(sealedKey.toList(), File(folder, "database-keys/aicc.db.key").readBytes().toList())
        assertTrue(File(folder, "vault/secrets.json").isFile)
        val note = File(folder, "about.txt").readText()
        assertTrue("aicc.database-key.v1" in note && "aicc.secret-vault.v1" in note, note)
        assertFalse("sk-alpha" in note)
        assertFalse(database("aicc.db").exists())
        // The old keystore entries stay, so the set-aside copy stays openable on this phone.
        assertTrue(AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS in keystore.aliases)
        assertTrue(AndroidKeystoreVaultCipher.DEFAULT_KEY_ALIAS in keystore.aliases)
        assertEquals(emptyList(), keystore.deleted)

        // The restarted app is on generation 2: new files, new entries, empty and working.
        val fresh = keyed()
        assertEquals(DatabaseState.OK, fresh.ownerRecovery.state())
        assertEquals(emptyList(), fresh.agents.getAll())
        assertNull(fresh.secretVault.get("provider/p1"))
        fresh.agents.upsert(Samples.agent())
        fresh.secretVault.put("provider/p2", "sk-beta-0123456789")
        assertEquals(2, KeyGenerations(noBackup).current())
        assertTrue(database("aicc.v2.db").isFile)
        assertTrue(File(noBackup, "database-keys/aicc.v2.db.key").isFile)
        assertTrue(File(noBackup, "vault/secrets.v2.json").isFile)
        assertTrue("aicc.database-key.v2" in keystore.aliases && "aicc.secret-vault.v2" in keystore.aliases)
        assertEquals(1, fresh.agents.getAll().size)
    }

    @Test
    fun `a database whose sealed key is gone is reported as missing, and no key is invented for it`() = runTest {
        restart(lived())
        val keyFile = File(noBackup, "database-keys/aicc.db.key")
        keyFile.delete()

        val broken = keyed()
        assertEquals(DatabaseState.KEY_MISSING, broken.ownerRecovery.state())
        assertFalse(keyFile.exists(), "the check creates no key for data it cannot open")
        assertIs<RecoveryResult.Done>(broken.ownerRecovery.startOver())
        restart(broken)
        assertEquals(DatabaseState.OK, keyed().ownerRecovery.state())
    }

    @Test
    fun `a keystore entry that is gone is reported as missing`() = runTest {
        restart(lived())
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry(AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS)
        assertEquals(DatabaseState.KEY_MISSING, keyed().ownerRecovery.state())
    }

    @Test
    fun `while the data opens normally, Start over changes nothing`() = runTest {
        val data = lived()
        val result = data.ownerRecovery.startOver()
        assertEquals(RecoveryResult.Refused(FileOwnerRecovery.OPENS_NORMALLY), result)
        assertEquals(1, KeyGenerations(noBackup).current())
        assertFalse(File(noBackup, KeyGenerations.FILE_NAME).exists())
        assertTrue(noBackup.listFiles().orEmpty().none { it.name.startsWith("set-aside-") })
        assertEquals(4, data.settings.get().seedVersion)
        assertEquals(1, data.agents.getAll().size)
    }

    @Test
    fun `Start over resets the first-run bookkeeping and both screen switches, and keeps every other setting`() = runTest {
        restart(lived())
        keystore.brokenAliases += AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS
        val broken = keyed()
        assertIs<RecoveryResult.Done>(broken.ownerRecovery.startOver())
        val settings = broken.settings.get()
        assertEquals(0, settings.seedVersion)
        assertEquals(0, settings.memorySeedVersion)
        assertNull(settings.directorAgentId)
        assertFalse(settings.onboardingCompleted)
        assertFalse(settings.operatorEnabled)
        assertFalse(settings.accessibilityOperatorEnabled)
        assertTrue(settings.localOnlyMode, "the owner's protection stays")
        assertEquals(50, settings.maxModelCallsPerDay)
    }

    @Test
    fun `Start over releases the folders the owner had shared`() = runTest {
        restart(lived())
        val resolver = context.contentResolver
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(1, resolver.persistedUriPermissions.size)
        keystore.brokenAliases += AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS
        assertIs<RecoveryResult.Done>(keyed().ownerRecovery.startOver())
        assertEquals(emptyList(), resolver.persistedUriPermissions)
    }

    @Test
    fun `after Start over the old process opens nothing, and a second tap does not move on again`() = runTest {
        restart(lived())
        keystore.brokenAliases += AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS
        val broken = keyed()
        val results = listOf(async { broken.ownerRecovery.startOver() }, async { broken.ownerRecovery.startOver() }).awaitAll()
        assertEquals(results[0], results[1])
        assertEquals(2, KeyGenerations(noBackup).current())
        assertTrue(runCatching { broken.agents.getAll() }.isFailure)
        assertFailsWith<SecretVaultException> { broken.secretVault.get("provider/p1") }
        assertFalse(database("aicc.db").exists(), "no empty file was made under the old name")
        assertFalse(database("aicc.v2.db").exists(), "the fresh database is opened by the restarted app, not this one")
    }

    @Test
    fun `a Start over cut off after the next generation was noted still gives a fresh start, and the old files stay as they were`() = runTest {
        restart(lived())
        val oldDatabase = database("aicc.db").readBytes()
        // As if the process died right after the note was written.
        KeyGenerations(noBackup).advance()

        val fresh = keyed()
        assertEquals(DatabaseState.OK, fresh.ownerRecovery.state())
        assertEquals(emptyList(), fresh.agents.getAll())
        assertEquals(oldDatabase.toList(), database("aicc.db").readBytes().toList())
        assertTrue(File(noBackup, "database-keys/aicc.db.key").isFile)
        assertTrue(File(noBackup, "vault/secrets.json").isFile)
    }

    @Test
    fun `a damaged generation note keeps the data closed, and Start over moves above every generation seen`() = runTest {
        restart(lived())
        File(noBackup, KeyGenerations.FILE_NAME).apply { parentFile!!.mkdirs(); writeText("garbage") }
        val broken = keyed()
        assertEquals(DatabaseState.KEY_DAMAGED, broken.ownerRecovery.state())
        assertFalse(database("aicc.v2.db").exists())
        assertIs<RecoveryResult.Done>(broken.ownerRecovery.startOver())
        restart(broken)
        assertEquals(2, KeyGenerations(noBackup).current())
        val fresh = keyed()
        assertEquals(DatabaseState.OK, fresh.ownerRecovery.state())
        fresh.agents.upsert(Samples.agent())
        assertTrue(database("aicc.v2.db").isFile)
    }

    @Test
    fun `data 0_5_2 stored under its own names opens unchanged on generation 1`() = runTest {
        // Written exactly as 0.5.2 wrote it: the sealed key under aicc.database-key.v1 bound to "aicc.db", the vault under
        // aicc.secret-vault.v1 in vault/secrets.json, and the database file aicc.db.
        val keyFile = File(noBackup, "database-keys/aicc.db.key")
        val key = DatabaseKeyStore(keyFile, AndroidKeystoreVaultCipher(AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS), "aicc.db").loadOrCreate(false)
        key.fill(0)
        val sealed = keyFile.readBytes()
        EncryptedFileSecretVault(File(noBackup, "vault/secrets.json"), AndroidKeystoreVaultCipher()).put("provider/p1", "sk-old-0123456789")
        AiccDatabase.create(context, "aicc.db").apply {
            openHelper.writableDatabase
            close()
        }
        val generated = keystore.generated.map { it.keystoreAlias }

        val upgraded = keyed()
        assertEquals(DatabaseState.OK, upgraded.ownerRecovery.state())
        assertEquals("sk-old-0123456789", upgraded.secretVault.get("provider/p1"))
        upgraded.agents.upsert(Samples.agent())
        assertEquals(sealed.toList(), keyFile.readBytes().toList(), "the sealed key was used, not replaced")
        assertEquals(generated, keystore.generated.map { it.keystoreAlias }, "no keystore entry was made")
        assertFalse(File(noBackup, KeyGenerations.FILE_NAME).exists())
        assertFalse(database("aicc.v2.db").exists())
    }
}
