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
 * The AES-256 key behind [AndroidKeystoreVaultCipher]: generated inside the AndroidKeyStore,
 * never exportable, and only ever handed out as a handle.
 *
 * The rules that keep stored secrets recoverable:
 * - Looking a key up never deletes, replaces or creates anything. The keystore reports a busy
 *   or restarting daemon with the same exception as a key that is really gone, so "the key
 *   could not be loaded" is always treated as temporary: the lookup is repeated a few times
 *   and then fails, and everything sealed with the key stays where it is.
 * - A key is generated in exactly one situation: something is about to be sealed and the
 *   keystore has no entry under the alias. An entry that exists but does not load is an error,
 *   because generating under the same alias would overwrite it.
 * - The only way an entry is removed is [discard], which nothing calls on its own.
 *
 * The key is kept in the trusted execution environment and StrongBox is not requested. Agents
 * read an API key for every model call, several at a time, and StrongBox chips are slow and
 * allow very few operations at once; the TEE has the same "key never leaves secure hardware"
 * property without that bottleneck. The backing cannot be changed later without re-sealing
 * everything, so this is decided once, here.
 *
 * @param pause waits between lookup attempts; replaced in tests.
 */
internal class KeystoreVaultKey(
    private val keyAlias: String,
    private val pause: (millis: Long) -> Unit = Thread::sleep,
) : VaultKeySource {

    override fun keyForDecryption(): SecretKey = synchronized(lock) {
        val keyStore = keyStore()
        lookUp(keyStore) ?: throw if (keyStore.containsAlias(keyAlias)) {
            // There, but it answers without a key: as damaged as an entry that does not load (Phase 6, R5).
            UnrecoverableKeyException("The device keystore has no usable key '$keyAlias'.")
        } else {
            KeystoreKeyMissingException("The device keystore has no key '$keyAlias'.")
        }
    }

    override fun keyForEncryption(): SecretKey = synchronized(lock) {
        val keyStore = keyStore()
        lookUp(keyStore) ?: run {
            if (keyStore.containsAlias(keyAlias)) {
                throw UnrecoverableKeyException("The device keystore has an entry '$keyAlias' that could not be loaded.")
            }
            generate()
        }
    }

    /**
     * Removes the key for good. Everything sealed with it becomes unreadable, so this belongs
     * only behind an explicit, confirmed "reset" by the owner. No-op if there is no such entry.
     */
    @Throws(GeneralSecurityException::class)
    fun discard(): Unit = synchronized(lock) {
        keyStore().deleteEntry(keyAlias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /**
     * Returns the key, or null when the keystore answers without one. Looked up on every use,
     * since a handle kept across calls would go stale if the entry were replaced.
     */
    private fun lookUp(keyStore: KeyStore): SecretKey? {
        var attempt = 1
        while (true) {
            try {
                return keyStore.getKey(keyAlias, null) as? SecretKey
            } catch (failure: UnrecoverableKeyException) {
                if (attempt == LOOKUP_ATTEMPTS) throw failure
            } catch (failure: ProviderException) {
                if (attempt == LOOKUP_ATTEMPTS) {
                    throw KeyStoreException("The device keystore could not load the key '$keyAlias'.", failure)
                }
            }
            pause(RETRY_PAUSE_MILLIS * attempt)
            attempt++
        }
    }

    private fun generate(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(KEY_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_BITS = 256
        const val LOOKUP_ATTEMPTS = 3
        const val RETRY_PAUSE_MILLIS = 50L

        /**
         * One lock for the whole process, not one per instance: two ciphers over the same alias
         * must not both find "no key yet" and generate, because the second key would replace
         * the first and orphan whatever was sealed in between.
         */
        private val lock = Any()
    }
}

/**
 * The keystore has no entry at all under the alias (Phase 6, R5): everything sealed with it was sealed on another phone,
 * or the entry was removed. Told apart from an entry that is there but does not load, which reads as damaged.
 */
internal class KeystoreKeyMissingException(message: String) : UnrecoverableKeyException(message)
