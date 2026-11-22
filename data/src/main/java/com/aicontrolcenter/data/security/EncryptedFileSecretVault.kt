package com.aicontrolcenter.data.security

import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.security.SecretVaultException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * [SecretVault] that keeps every secret encrypted in one app-private file.
 *
 * - Each secret is sealed on its own by [cipher] with the alias as associated data. A sealed
 *   value therefore only opens under the alias it was stored for: moving ciphertext from one
 *   alias to another inside the file is detected exactly like modifying it.
 * - Plaintext exists only inside [put] and [get]; nothing decrypted is cached, and no secret,
 *   ciphertext or key ever appears in a log line or exception message.
 * - Changes are serialised and written atomically, so concurrent writers cannot lose each
 *   other's entries and a crash cannot leave a half-written file.
 *
 * One instance must own [file]; two instances over the same file would not see each other's
 * writes.
 *
 * Failures of the cipher or the file surface as [SecretVaultException], and a failure never
 * costs a stored secret:
 * - a secret that fails to decrypt stays stored until it is overwritten or deleted, and other
 *   aliases are unaffected;
 * - a vault file that cannot be read is an error on every call, never an empty vault, so a
 *   later [put] cannot overwrite secrets that are still on disk;
 * - a [put] or [delete] that did not reach the disk throws, and the vault then reads the file
 *   again instead of trusting what it believed was stored.
 */
class EncryptedFileSecretVault(
    file: File,
    private val cipher: VaultCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SecretVault {

    private val store = VaultFileStore(file)
    private val mutex = Mutex()

    /**
     * Alias to Base64 sealed value, as last successfully read from or written to disk; null
     * until then and after any failed write. Guarded by [mutex].
     */
    private var cached: Map<String, String>? = null

    override suspend fun put(alias: String, secret: String) = withContext(ioDispatcher) {
        val sealed = seal(alias, secret)
        change { entries -> entries + (alias to sealed) }
    }

    override suspend fun get(alias: String): String? = withContext(ioDispatcher) {
        entries()[alias]?.let { sealed -> open(alias, sealed) }
    }

    override suspend fun contains(alias: String): Boolean = withContext(ioDispatcher) {
        alias in entries()
    }

    override suspend fun delete(alias: String) = withContext(ioDispatcher) {
        change { entries -> if (alias in entries) entries - alias else entries }
    }

    override suspend fun aliases(): Set<String> = withContext(ioDispatcher) {
        entries().keys.toSet()
    }

    private suspend fun entries(): Map<String, String> = mutex.withLock { load() }

    /** Applies [transform] to the index and persists the result before anyone else can see it. */
    private suspend fun change(transform: (Map<String, String>) -> Map<String, String>) = mutex.withLock {
        val current = load()
        val next = transform(current)
        if (next !== current) {
            try {
                store.write(next)
            } catch (failure: SecretVaultException) {
                cached = null
                throw failure
            }
            cached = next
        }
    }

    /** Only a completed read is remembered; a read that throws leaves the next call to try again. */
    private fun load(): Map<String, String> = cached ?: store.read().also { cached = it }

    private fun seal(alias: String, secret: String): String {
        val plaintext = secret.toByteArray(Charsets.UTF_8)
        try {
            return Base64.getEncoder().encodeToString(cipher.encrypt(plaintext, associatedData(alias)))
        } catch (failure: GeneralSecurityException) {
            throw SecretVaultException("The secret for '$alias' could not be encrypted.", failure)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun open(alias: String, sealed: String): String {
        val plaintext = try {
            cipher.decrypt(Base64.getDecoder().decode(sealed), associatedData(alias))
        } catch (failure: GeneralSecurityException) {
            throw SecretVaultException(UNREADABLE.format(alias), failure)
        } catch (failure: IllegalArgumentException) {
            throw SecretVaultException(UNREADABLE.format(alias), failure)
        }
        try {
            return String(plaintext, Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    /** The domain prefix keeps these blobs from being valid in any other use of the same key. */
    private fun associatedData(alias: String): ByteArray = (ASSOCIATED_DATA_PREFIX + alias).toByteArray(Charsets.UTF_8)

    private companion object {
        const val ASSOCIATED_DATA_PREFIX = "aicc.secret-vault.v1:"
        const val UNREADABLE =
            "The secret for '%s' could not be decrypted. It was altered, or the device key that protects it is gone."
    }
}
