package com.aicontrolcenter.data.security

import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException

/**
 * The production [VaultCipher]: AES-256-GCM with a key that is generated inside the
 * AndroidKeyStore and can never be exported from it. The app only ever holds a handle; the
 * key material stays in the device's trusted execution environment.
 *
 * The key does not require user authentication: agents must be able to use an API key while
 * the phone is locked and a long task is running. What protects the secrets is that the sealed
 * data is useless without this device's keystore.
 *
 * A keystore problem is always reported as a [GeneralSecurityException] and never "repaired":
 * decrypting creates, replaces or deletes nothing, so data that cannot be opened right now is
 * still there when the keystore works again. Only when the entry is truly gone (for example
 * the app's files were restored onto a device that never had the key) does the next
 * [encrypt] create a new key, and the old sealed values then keep failing to open. See
 * [KeystoreVaultKey] for the exact rules.
 */
class AndroidKeystoreVaultCipher internal constructor(private val key: KeystoreVaultKey) : VaultCipher {

    /** @param keyAlias name of the keystore entry. Changing it abandons everything sealed so far. */
    constructor(keyAlias: String = DEFAULT_KEY_ALIAS) : this(KeystoreVaultKey(keyAlias))

    private val aesGcm = AesGcmVaultCipher(key)

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        keystoreCall { aesGcm.encrypt(plaintext, associatedData) }

    override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray =
        keystoreCall { aesGcm.decrypt(sealed, associatedData) }

    /**
     * Deletes the keystore entry, which makes everything sealed with it unreadable for good.
     * This is the only way the key is ever removed and nothing in this module calls it: it is
     * for an explicit "reset" that the owner has confirmed. The next [encrypt] creates a new key.
     */
    @Throws(GeneralSecurityException::class)
    fun discardKey(): Unit = keystoreCall { key.discard() }

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

    companion object {
        /** Keystore entry that seals the secret vault. */
        const val DEFAULT_KEY_ALIAS = "aicc.secret-vault.v1"

        /** Keystore entry that seals the database key; separate, so one can be reset without the other. */
        const val DATABASE_KEY_ALIAS = "aicc.database-key.v1"
    }
}
