package com.aicontrolcenter.data.security

import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.security.SecretVaultException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The keystore key handling of [AndroidKeystoreVaultCipher], run on the JVM against
 * [FakeAndroidKeyStore]. What matters most here is what must *not* happen: no failure of the
 * keystore may ever delete or replace the key that existing secrets were sealed with.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidKeystoreVaultCipherTest {
    @get:Rule val folder = TemporaryFolder()

    private val keystore = FakeAndroidKeyStore()
    private val pauses = mutableListOf<Long>()
    private val aad = "provider/a".toByteArray()
    private val secret = "sk-secret".toByteArray()

    /** A new cipher object over the same keystore entry, as after an app restart. */
    private fun cipher(alias: String = ALIAS) = AndroidKeystoreVaultCipher(KeystoreVaultKey(alias) { pauses += it })

    private fun busy() = UnrecoverableKeyException("Failed to obtain information about key")

    @Before
    fun installKeystore() = keystore.install()

    @After
    fun removeKeystore() = keystore.uninstall()

    @Test
    fun `the first encryption creates one hardware-bound AES-256-GCM key that needs no user presence`() {
        cipher().encrypt(secret, aad)

        val spec = keystore.generated.single()
        assertEquals(ALIAS, spec.keystoreAlias)
        assertEquals(256, spec.keySize)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, spec.purposes)
        assertContentEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes)
        assertContentEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), spec.encryptionPaddings)
        assertTrue(spec.isRandomizedEncryptionRequired)
        assertFalse(spec.isUserAuthenticationRequired)
        // The trusted execution environment, not StrongBox: see KeystoreVaultKey.
        assertFalse(spec.isStrongBoxBacked)
    }

    @Test
    fun `the key is created once and found again by later calls and by a new cipher object`() {
        val sealed = cipher().encrypt(secret, aad)
        val restarted = cipher()
        assertContentEquals(secret, restarted.decrypt(sealed, aad))
        assertContentEquals(secret, restarted.decrypt(restarted.encrypt(secret, aad), aad))

        assertEquals(1, keystore.generated.size)
        assertEquals(emptyList(), keystore.deleted)
        assertEquals(emptyList(), pauses)
    }

    @Test
    fun `ciphers with different aliases have different keys`() {
        val sealed = cipher("alias.one").encrypt(secret, aad)
        cipher("alias.two").encrypt(secret, aad)
        assertEquals(setOf("alias.one", "alias.two"), keystore.aliases)
        assertFailsWith<GeneralSecurityException> { cipher("alias.two").decrypt(sealed, aad) }
        assertContentEquals(secret, cipher("alias.one").decrypt(sealed, aad))
    }

    @Test
    fun `decrypting never creates a key`() {
        val sealedElsewhere = SoftwareVaultCipher().encrypt(secret, aad)

        assertFailsWith<GeneralSecurityException> { cipher().decrypt(sealedElsewhere, aad) }

        assertEquals(emptyList(), keystore.generated)
        assertEquals(emptySet(), keystore.aliases)
    }

    @Test
    fun `a keystore that is busy for a moment is asked again and nothing is lost`() {
        val sealed = cipher().encrypt(secret, aad)
        keystore.failGetKey(times = 2, ::busy)

        assertContentEquals(secret, cipher().decrypt(sealed, aad))

        assertEquals(listOf(50L, 100L), pauses)
        assertEquals(1, keystore.generated.size)
        assertEquals(emptyList(), keystore.deleted)
    }

    @Test
    fun `a keystore that stays unavailable fails the read and leaves the key in place`() {
        val cipher = cipher()
        val sealed = cipher.encrypt(secret, aad)
        val callsBefore = keystore.getKeyCalls
        keystore.failGetKey(times = 3, ::busy)

        assertFailsWith<UnrecoverableKeyException> { cipher.decrypt(sealed, aad) }

        assertEquals(3, keystore.getKeyCalls - callsBefore)
        assertEquals(emptyList(), keystore.deleted)
        assertEquals(1, keystore.generated.size)
        // The keystore works again: the value sealed before the outage still opens.
        assertContentEquals(secret, cipher.decrypt(sealed, aad))
        assertContentEquals(secret, cipher().decrypt(sealed, aad))
    }

    @Test
    fun `a keystore that stays unavailable fails a write without replacing the key`() {
        val cipher = cipher()
        val sealed = cipher.encrypt(secret, aad)
        keystore.failGetKey(times = 3, ::busy)

        assertFailsWith<GeneralSecurityException> { cipher.encrypt("another".toByteArray(), aad) }

        assertEquals(1, keystore.generated.size)
        assertEquals(emptyList(), keystore.deleted)
        assertContentEquals(secret, cipher.decrypt(sealed, aad))
    }

    @Test
    fun `an entry that exists but is not handed out is an error, not a reason to generate over it`() {
        val cipher = cipher()
        val sealed = cipher.encrypt(secret, aad)
        keystore.hideKeys = true

        assertFailsWith<GeneralSecurityException> { cipher.encrypt("another".toByteArray(), aad) }
        assertFailsWith<GeneralSecurityException> { cipher.decrypt(sealed, aad) }
        assertEquals(1, keystore.generated.size)
        assertEquals(emptyList(), keystore.deleted)

        keystore.hideKeys = false
        assertContentEquals(secret, cipher.decrypt(sealed, aad))
    }

    @Test
    fun `unchecked keystore failures are retried and then reported as a security failure`() {
        val cipher = cipher()
        val sealed = cipher.encrypt(secret, aad)

        keystore.failGetKey(times = 1) { ProviderException("Keystore operation failed") }
        assertContentEquals(secret, cipher.decrypt(sealed, aad))

        keystore.failGetKey(times = 3) { ProviderException("Keystore operation failed") }
        val failure = assertFailsWith<GeneralSecurityException> { cipher.decrypt(sealed, aad) }
        assertTrue(failure.cause is ProviderException)
        assertEquals(emptyList(), keystore.deleted)
        assertContentEquals(secret, cipher.decrypt(sealed, aad))
    }

    @Test
    fun `a failing key generation is reported as a security failure and can be repeated`() {
        keystore.failGeneration = ProviderException("Failed to generate key")
        val failure = assertFailsWith<GeneralSecurityException> { cipher().encrypt(secret, aad) }
        assertTrue(failure.cause is ProviderException)
        assertEquals(emptySet(), keystore.aliases)

        keystore.failGeneration = null
        val cipher = cipher()
        assertContentEquals(secret, cipher.decrypt(cipher.encrypt(secret, aad), aad))
    }

    @Test
    fun `only an explicit discard removes the key, and the next encryption then starts a new one`() {
        val cipher = cipher()
        val sealed = cipher.encrypt(secret, aad)

        cipher.discardKey()

        assertEquals(listOf(ALIAS), keystore.deleted)
        assertFailsWith<GeneralSecurityException> { cipher.decrypt(sealed, aad) }
        assertEquals(1, keystore.generated.size)
        val resealed = cipher.encrypt(secret, aad)
        assertEquals(2, keystore.generated.size)
        assertContentEquals(secret, cipher.decrypt(resealed, aad))
        assertFailsWith<GeneralSecurityException> { cipher.decrypt(sealed, aad) }
    }

    @Test
    fun `a vault read during a keystore outage fails without costing the secret`() = runTest {
        val file = File(folder.newFolder("vault"), "secrets.json")
        val vault = EncryptedFileSecretVault(file, cipher())
        vault.put("provider/a", "sk-alpha")
        val stored = file.readText()

        keystore.failGetKey(times = 3, ::busy)
        assertFailsWith<SecretVaultException> { vault.get("provider/a") }
        keystore.failGetKey(times = 3, ::busy)
        assertFailsWith<SecretVaultException> { vault.put("provider/b", "sk-beta") }

        assertEquals(stored, file.readText())
        assertEquals(emptyList(), keystore.deleted)
        assertEquals("sk-alpha", vault.get("provider/a"))
        assertEquals("sk-alpha", EncryptedFileSecretVault(file, cipher()).get("provider/a"))
    }

    private companion object {
        const val ALIAS = "aicc.test.vault"
    }
}
