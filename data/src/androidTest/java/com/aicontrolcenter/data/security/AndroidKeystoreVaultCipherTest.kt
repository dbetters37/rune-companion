package com.aicontrolcenter.data.security

import android.content.Context
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.security.SecretVaultException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlin.experimental.xor
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs on a device or emulator: the AndroidKeyStore does not exist on the JVM, so this is the
 * only place the real key handling is exercised. Everything above the key (framing, vault
 * file, tamper detection) is also covered by the JVM tests with a software key.
 *
 * Each test uses its own keystore alias and removes it afterwards.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreVaultCipherTest {
    private val keyAlias = "aicc.test.vault." + UUID.randomUUID()
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val cipher = AndroidKeystoreVaultCipher(keyAlias)
    private val aad = "provider/a".toByteArray()
    private val vaultFile = File(
        ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir,
        "vault-test-${UUID.randomUUID()}/secrets.json",
    )

    @After
    fun tearDown() {
        keyStore.deleteEntry(keyAlias)
        vaultFile.parentFile?.deleteRecursively()
    }

    private fun keyInfo(): KeyInfo {
        val key = assertNotNull(keyStore.getKey(keyAlias, null) as? SecretKey, "no key under $keyAlias")
        return SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }

    @Test
    fun roundTripsDataOfAnyLengthIncludingEmpty() {
        for (length in listOf(0, 1, 16, 5_000)) {
            val plaintext = ByteArray(length) { (it * 7).toByte() }
            assertContentEquals(plaintext, cipher.decrypt(cipher.encrypt(plaintext, aad), aad), "length $length")
        }
    }

    @Test
    fun createsTheKeyOnFirstUseAndReusesIt() {
        assertFalse(keyStore.containsAlias(keyAlias))
        val sealed = cipher.encrypt("secret".toByteArray(), aad)
        assertTrue(keyStore.containsAlias(keyAlias))
        val created = keyStore.getCreationDate(keyAlias)

        // A second cipher object, as after an app restart, finds the same key.
        val restarted = AndroidKeystoreVaultCipher(keyAlias)
        assertContentEquals("secret".toByteArray(), restarted.decrypt(sealed, aad))
        restarted.encrypt("another".toByteArray(), aad)
        assertEquals(created, keyStore.getCreationDate(keyAlias))
    }

    @Test
    fun keyIsAes256GcmNonExportableAndNeedsNoUserAuthentication() {
        cipher.encrypt("secret".toByteArray(), aad)
        val key = keyStore.getKey(keyAlias, null) as SecretKey
        assertNull(key.encoded, "key material must not be readable by the app")

        val info = keyInfo()
        assertEquals(256, info.keySize)
        assertContentEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), info.blockModes)
        assertContentEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), info.encryptionPaddings)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, info.purposes)
        assertEquals(KeyProperties.ORIGIN_GENERATED, info.origin)
        assertFalse(info.isUserAuthenticationRequired)
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        val plaintext = "same secret".toByteArray()
        val sealed = List(20) { cipher.encrypt(plaintext, aad) }
        assertEquals(20, sealed.map { it.copyOfRange(1, 13).toList() }.toSet().size)
    }

    @Test
    fun rejectsOtherAssociatedDataAndModifiedCiphertext() {
        val sealed = cipher.encrypt("secret".toByteArray(), aad)
        assertFailsWith<GeneralSecurityException> { cipher.decrypt(sealed, "provider/b".toByteArray()) }
        for (position in listOf(1, 13, sealed.lastIndex)) {
            val tampered = sealed.copyOf().also { it[position] = it[position] xor 0x01 }
            assertFailsWith<GeneralSecurityException>("tampering at byte $position") { cipher.decrypt(tampered, aad) }
        }
        assertFailsWith<GeneralSecurityException> { cipher.decrypt(ByteArray(5), aad) }
    }

    @Test
    fun decryptingNeverCreatesAKey() {
        // Well-formed but sealed with a key this keystore never had.
        val foreign = ByteArray(64).also { it[0] = 1 }
        assertFailsWith<GeneralSecurityException> { cipher.decrypt(foreign, aad) }
        assertFalse(keyStore.containsAlias(keyAlias))
    }

    @Test
    fun onlyAnExplicitDiscardRemovesTheKey() {
        val sealed = cipher.encrypt("secret".toByteArray(), aad)
        // Failed reads of every kind leave the entry alone.
        assertFailsWith<GeneralSecurityException> { cipher.decrypt(sealed, "provider/b".toByteArray()) }
        assertFailsWith<GeneralSecurityException> { cipher.decrypt(ByteArray(5), aad) }
        assertTrue(keyStore.containsAlias(keyAlias))

        cipher.discardKey()
        assertFalse(keyStore.containsAlias(keyAlias))
        assertFailsWith<GeneralSecurityException> { cipher.decrypt(sealed, aad) }
        cipher.discardKey() // nothing left to remove
    }

    @Test
    fun afterTheKeyIsLostOldValuesFailAndNewOnesWork() {
        val sealed = cipher.encrypt("secret".toByteArray(), aad)
        keyStore.deleteEntry(keyAlias)

        assertFailsWith<GeneralSecurityException> { cipher.decrypt(sealed, aad) }
        assertFalse(keyStore.containsAlias(keyAlias), "a failed read must not create a key")
        val resealed = cipher.encrypt("secret again".toByteArray(), aad)
        assertContentEquals("secret again".toByteArray(), cipher.decrypt(resealed, aad))
    }

    @Test
    fun vaultStoresReadsAndForgetsSecretsWithTheKeystoreKey(): Unit = runTest {
        val secret = "sk-live-0123456789-ключ-🔑"
        val vault = EncryptedFileSecretVault(vaultFile, cipher)
        vault.put("provider/a", secret)
        vault.put("provider/empty", "")

        assertFalse(secret in vaultFile.readText(), "plaintext secret found in the vault file")

        val restarted = EncryptedFileSecretVault(vaultFile, AndroidKeystoreVaultCipher(keyAlias))
        assertEquals(secret, restarted.get("provider/a"))
        assertEquals("", restarted.get("provider/empty"))
        assertEquals(setOf("provider/a", "provider/empty"), restarted.aliases())

        restarted.delete("provider/a")
        assertNull(EncryptedFileSecretVault(vaultFile, cipher).get("provider/a"))
    }

    @Test
    fun vaultReportsALostKeyAsAVaultError(): Unit = runTest {
        val vault = EncryptedFileSecretVault(vaultFile, cipher)
        vault.put("provider/a", "sk-alpha")
        keyStore.deleteEntry(keyAlias)

        assertFailsWith<SecretVaultException> { vault.get("provider/a") }
        vault.put("provider/a", "sk-re-entered")
        assertEquals("sk-re-entered", vault.get("provider/a"))
    }
}
