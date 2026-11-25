package com.aicontrolcenter.data.security

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/**
 * Thrown when the key of the encrypted database cannot be provided. The database is then not
 * opened at all; nothing is deleted or replaced, so it opens again once the cause is gone.
 *
 * @property problem what went wrong, for the Recovery screen (Phase 6, R5): the database check
 *   (`data/recovery/DatabaseHealth`) turns it into the owner's sentence. Never a secret.
 */
class DatabaseKeyException(
    message: String,
    cause: Throwable? = null,
    val problem: KeyProblem = KeyProblem.OTHER,
) : IllegalStateException(message, cause)

/** Why the database key could not be provided (Phase 6, R5). */
enum class KeyProblem {
    /** There is data but no sealed key, or the keystore has no entry for the sealed key. */
    MISSING,

    /** The sealed key or its keystore entry is there but cannot be used: altered, wrong format, or an entry that does not load. */
    DAMAGED,

    /** The keystore itself did not answer (busy, restarting, or not there at all). */
    KEYSTORE_UNAVAILABLE,

    /** Anything else: the file could not be read or written. */
    OTHER,
    ;

    companion object {
        /** The problem a keystore or cipher failure stands for. */
        fun of(failure: GeneralSecurityException): KeyProblem = when (failure) {
            is KeystoreKeyMissingException -> MISSING
            is UnrecoverableKeyException -> DAMAGED
            is AEADBadTagException -> DAMAGED
            is KeyStoreException -> KEYSTORE_UNAVAILABLE
            // An unknown algorithm or provider: the keystore is not usable on this phone right now.
            else -> if (failure.message?.startsWith(NOT_SEALED_PREFIX) == true) DAMAGED else KEYSTORE_UNAVAILABLE
        }

        /** How [AesGcmVaultCipher] reports a blob that is not in its format. */
        private const val NOT_SEALED_PREFIX = "Not a sealed vault value"
    }
}

/**
 * Keeps the key of the encrypted database: 32 random bytes that never leave the device and are
 * stored only in sealed form, in a file of their own.
 *
 * The database is worthless without this exact key, so the key is treated as more precious
 * than anything it protects:
 * - it is created only when there is neither a sealed key nor a database yet;
 * - a sealed key that exists is never replaced. If it cannot be read or unsealed (a keystore
 *   failure, a damaged file), that is reported as [DatabaseKeyException] and the file is left
 *   as it is. A new key would open nothing and would make the old database unrecoverable even
 *   after the keystore works again;
 * - a new key is handed out only after its sealed copy has been written durably, read back
 *   and unsealed, so no database is ever created under a key that cannot be recovered.
 *
 * @param file where the sealed key is stored. It belongs under `noBackupFilesDir`: the sealed
 *   key only opens with this device's keystore, so backing it up would be pointless.
 * @param cipher seals the key; in the app, a keystore-backed cipher.
 * @param databaseName bound into the sealed key, so a key file cannot be swapped for the one
 *   of another database.
 */
class DatabaseKeyStore(
    file: File,
    private val cipher: VaultCipher,
    databaseName: String,
    private val random: SecureRandom = SecureRandom(),
) {
    private val durable = DurableFile(file)
    private val associatedData = (ASSOCIATED_DATA_PREFIX + databaseName).toByteArray(Charsets.UTF_8)

    /**
     * Returns the database key, creating and storing it on first use. The caller owns the
     * returned array and should overwrite it when done.
     *
     * @param databaseExists whether the database this key is for already holds data. With
     *   data but without a sealed key the data cannot be read; that is reported instead of
     *   being hidden behind a new key.
     * @throws DatabaseKeyException if the key cannot be read, unsealed, created or stored.
     */
    fun loadOrCreate(databaseExists: Boolean): ByteArray = synchronized(lock) {
        val stored = read()
        if (stored != null) return unseal(stored)
        if (databaseExists) {
            throw DatabaseKeyException("The database exists but its key is missing, so it cannot be opened.", problem = KeyProblem.MISSING)
        }
        create()
    }

    private fun read(): ByteArray? =
        try {
            durable.readOrNull()
        } catch (failure: IOException) {
            throw DatabaseKeyException("The database key could not be read from storage.", failure, KeyProblem.OTHER)
        }

    private fun create(): ByteArray {
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            val sealed = try {
                cipher.encrypt(key, associatedData)
            } catch (failure: GeneralSecurityException) {
                throw DatabaseKeyException("A database key could not be sealed with the device keystore.", failure, KeyProblem.of(failure))
            }
            try {
                durable.replace(byteArrayOf(FORMAT_V1) + sealed)
            } catch (failure: IOException) {
                throw DatabaseKeyException("The database key could not be stored.", failure, KeyProblem.OTHER)
            }
            val readBack = unseal(read() ?: throw DatabaseKeyException("The database key was stored but is not there.", problem = KeyProblem.OTHER))
            val intact = MessageDigest.isEqual(key, readBack)
            readBack.fill(0)
            if (!intact) throw DatabaseKeyException("The database key did not survive being stored.", problem = KeyProblem.OTHER)
            return key
        } catch (failure: DatabaseKeyException) {
            key.fill(0)
            throw failure
        }
    }

    private fun unseal(stored: ByteArray): ByteArray {
        if (stored.isEmpty() || stored[0] != FORMAT_V1) {
            throw DatabaseKeyException(
                "The stored database key is damaged or in a format this version of the app cannot read.",
                problem = KeyProblem.DAMAGED,
            )
        }
        val key = try {
            cipher.decrypt(stored.copyOfRange(1, stored.size), associatedData)
        } catch (failure: GeneralSecurityException) {
            throw DatabaseKeyException(
                "The database key could not be unsealed. It was altered, or the device keystore cannot be used right now.",
                failure,
                KeyProblem.of(failure),
            )
        }
        if (key.size != KEY_BYTES) {
            key.fill(0)
            throw DatabaseKeyException("The stored database key has the wrong length.", problem = KeyProblem.DAMAGED)
        }
        return key
    }

    companion object {
        /** Length of the database key: 256 bits, used by SQLCipher as its raw AES key material. */
        const val KEY_BYTES = 32
        private const val FORMAT_V1: Byte = 1
        private const val ASSOCIATED_DATA_PREFIX = "aicc.database-key.v1:"

        /**
         * One lock for the whole process: if two key stores over the same file both found "no
         * key yet", the second would store its own key over the first, whose database could
         * then never be opened again.
         */
        private val lock = Any()
    }
}
