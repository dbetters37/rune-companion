package com.aicontrolcenter.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.GeneralSecurityException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The life of the database key, with a software cipher in place of the device keystore. The
 * rule under test: a key is created exactly once, and a sealed key that exists is never
 * replaced, whatever goes wrong while reading it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DatabaseKeyStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val cipher = SoftwareVaultCipher()
    private val file: File by lazy { File(folder.newFolder("no-backup"), "database-keys/aicc.db.key") }

    /** A new key store over the same file, as after an app restart. */
    private fun keys(cipher: VaultCipher = this.cipher, databaseName: String = "aicc.db") =
        DatabaseKeyStore(file, cipher, databaseName)

    private fun assertRefusedAndUntouched(keys: DatabaseKeyStore = keys()) {
        val before = file.readBytes()
        for (databaseExists in listOf(true, false)) {
            assertFailsWith<DatabaseKeyException> { keys.loadOrCreate(databaseExists) }
        }
        assertContentEquals(before, file.readBytes(), "the sealed key was replaced")
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun `the first use creates a random 256-bit key and stores it sealed`() {
        val key = keys().loadOrCreate(databaseExists = false)

        assertEquals(32, key.size)
        assertEquals(DatabaseKeyStore.KEY_BYTES, key.size)
        assertTrue(file.isFile)
        val stored = file.readBytes()
        assertEquals(1, stored[0], "format byte")
        assertFalse(String(stored, Charsets.ISO_8859_1).contains(String(key, Charsets.ISO_8859_1)), "the key is stored in the clear")
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList(), "no side file may be left behind")
    }

    @Test
    fun `later uses return the same key, also after a restart and once the database exists`() {
        val keys = keys()
        val created = keys.loadOrCreate(databaseExists = false)
        val stored = file.readBytes()

        assertContentEquals(created, keys.loadOrCreate(databaseExists = false))
        assertContentEquals(created, keys().loadOrCreate(databaseExists = true))
        assertContentEquals(stored, file.readBytes(), "reading must not rewrite the sealed key")
    }

    @Test
    fun `the caller may wipe the key it was given`() {
        val keys = keys()
        val created = keys.loadOrCreate(databaseExists = false)
        val copy = created.copyOf()
        created.fill(0)
        assertContentEquals(copy, keys.loadOrCreate(databaseExists = true))
    }

    @Test
    fun `every database gets its own key`() {
        val one = DatabaseKeyStore(File(file.parentFile, "one.key"), cipher, "one.db").loadOrCreate(databaseExists = false)
        val two = DatabaseKeyStore(File(file.parentFile, "two.key"), cipher, "two.db").loadOrCreate(databaseExists = false)
        assertFalse(one.contentEquals(two))
    }

    @Test
    fun `many threads asking at once all get the one key`() {
        val keys = keys()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(List(32) { Callable { keys.loadOrCreate(databaseExists = false).toList() } }).map { it.get() }
            assertEquals(1, results.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a database without its key is reported, and no key is invented for it`() {
        val failure = assertFailsWith<DatabaseKeyException> { keys().loadOrCreate(databaseExists = true) }
        assertTrue("key is missing" in failure.message.orEmpty(), failure.message)
        assertFalse(file.exists())
    }

    @Test
    fun `a sealed key that cannot be unsealed is an error and is never replaced`() {
        keys().loadOrCreate(databaseExists = false)

        // Another cipher key: what a lost or temporarily unusable keystore key looks like.
        assertRefusedAndUntouched(keys(cipher = SoftwareVaultCipher()))
        val failing = object : VaultCipher by cipher {
            override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray =
                throw GeneralSecurityException("keystore unavailable")
        }
        val failure = assertFailsWith<DatabaseKeyException> { keys(cipher = failing).loadOrCreate(databaseExists = true) }
        assertTrue(failure.cause is GeneralSecurityException)
        assertRefusedAndUntouched(keys(cipher = failing))

        // With the right cipher back, the same key opens the database again.
        assertEquals(32, keys().loadOrCreate(databaseExists = true).size)
    }

    @Test
    fun `a damaged key file is an error and is never replaced`() {
        keys().loadOrCreate(databaseExists = false)
        val intact = file.readBytes()
        val damagedVariants = listOf(
            ByteArray(0),
            byteArrayOf(1),
            intact.copyOf(intact.size - 1),
            intact.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() },
            byteArrayOf(2) + intact.copyOfRange(1, intact.size),
            "not a key".toByteArray(),
        )
        for (damaged in damagedVariants) {
            file.writeBytes(damaged)
            assertRefusedAndUntouched()
        }
    }

    @Test
    fun `a key sealed for another database is refused`() {
        keys(databaseName = "other.db").loadOrCreate(databaseExists = false)
        assertRefusedAndUntouched(keys(databaseName = "aicc.db"))
    }

    @Test
    fun `a key of the wrong length is refused`() {
        val oddCipher = object : VaultCipher by cipher {
            override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray = ByteArray(16)
        }
        keys().loadOrCreate(databaseExists = false)
        assertRefusedAndUntouched(keys(cipher = oddCipher))
    }

    @Test
    fun `a key file that cannot be opened is an error, not a reason to create a key`() {
        file.parentFile!!.mkdirs()
        // A directory in the file's place cannot be opened as a file, whoever runs the test.
        assertTrue(file.mkdir())

        for (databaseExists in listOf(true, false)) {
            assertFailsWith<DatabaseKeyException> { keys().loadOrCreate(databaseExists) }
        }
        assertTrue(file.isDirectory)
    }

    @Test
    fun `when the key cannot be sealed nothing is stored`() {
        val failing = object : VaultCipher by cipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
                throw GeneralSecurityException("keystore unavailable")
        }
        val failure = assertFailsWith<DatabaseKeyException> { keys(cipher = failing).loadOrCreate(databaseExists = false) }
        assertTrue(failure.cause is GeneralSecurityException)
        assertFalse(file.exists())

        // Nothing was half-created, so the next attempt with a working keystore succeeds.
        assertEquals(32, keys().loadOrCreate(databaseExists = false).size)
    }

    @Test
    fun `when the key cannot be stored it is not handed out`() {
        val occupied = folder.newFile("occupied")
        val keys = DatabaseKeyStore(File(occupied, "aicc.db.key"), cipher, "aicc.db")
        assertFailsWith<DatabaseKeyException> { keys.loadOrCreate(databaseExists = false) }
        assertTrue(occupied.isFile)
    }

    @Test
    fun `a new key is handed out only if its stored copy can be unsealed again`() {
        // Seals fine but cannot open what it sealed: a database created under such a key
        // could never be opened again, so the key must not be used at all.
        val oneWay = object : VaultCipher by cipher {
            override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray =
                throw GeneralSecurityException("cannot unseal")
        }
        assertFailsWith<DatabaseKeyException> { keys(cipher = oneWay).loadOrCreate(databaseExists = false) }

        val forgetful = object : VaultCipher by cipher {
            override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray = ByteArray(32)
        }
        file.delete()
        assertFailsWith<DatabaseKeyException> { keys(cipher = forgetful).loadOrCreate(databaseExists = false) }
    }
}
