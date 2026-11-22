package com.aicontrolcenter.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The production [VaultCipher]: AES-256-GCM with a key that is generated inside the
 * AndroidKeyStore and can never be exported from it. The app only ever holds a handle; the
 * key material stays in the device's secure hardware (StrongBox where the phone has it,
 * otherwise the trusted execution environment).
 *
 * The key does not require user authentication: agents must be able to use an API key while
 * the phone is locked and a long task is running. What protects the secrets is that the vault
 * file is useless without this device's keystore.
 *
 * If the key is ever lost (for example after a factory reset restored the app's files but not
 * the keystore), a new key is created on next use and the old ciphertexts fail to decrypt;
 * the vault reports that as a `SecretVaultException` and the secret has to be entered again.
 *
 * @param keyAlias name of the keystore entry. Changing it abandons everything sealed so far.
 */
class AndroidKeystoreVaultCipher(private val keyAlias: String = DEFAULT_KEY_ALIAS) : VaultCipher {

    private val aesGcm = AesGcmVaultCipher(::key)
    private val keyLock = Any()

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        keystoreCall { aesGcm.encrypt(plaintext, associatedData) }

    override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray =
        keystoreCall { aesGcm.decrypt(sealed, associatedData) }

    /**
     * The keystore reports many failures (hardware busy, key blob invalidated, daemon died) as
     * unchecked [ProviderException]s. They are turned into [GeneralSecurityException] so that
     * callers have one failure type to handle, as [VaultCipher] promises.
     */
    private inline fun <T> keystoreCall(block: () -> T): T =
        try {
            block()
        } catch (failure: ProviderException) {
            throw KeyStoreException("The device keystore could not complete the operation.", failure)
        }

    /**
     * Looked up on every use: a cached handle would go stale if the entry were replaced.
     *
     * An entry that exists but can no longer be loaded is gone for good, and so is everything
     * sealed with it. It is replaced by a new key so that the vault can store secrets again;
     * the old ciphertexts then fail authentication, which is how the loss is reported.
     */
    private fun key(): SecretKey = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = try {
            keyStore.getKey(keyAlias, null) as? SecretKey
        } catch (_: UnrecoverableKeyException) {
            keyStore.deleteEntry(keyAlias)
            null
        }
        existing ?: generateKey()
    }

    private fun generateKey(): SecretKey =
        try {
            generateKey(strongBox = true)
        } catch (unavailable: ProviderException) {
            // StrongBoxUnavailableException, or a vendor keystore signalling the same thing
            // less precisely. A failure of the fallback itself is reported to the caller.
            generateKey(strongBox = false)
        }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(KEY_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "aicc.secret-vault.v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_BITS = 256
    }
}
