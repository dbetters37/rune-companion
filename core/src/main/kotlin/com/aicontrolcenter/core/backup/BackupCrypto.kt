package com.aicontrolcenter.core.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The cryptographic core of the backup file, format `aicc-backup` v1 (Phase 6, K13; frozen after
 * the security review of wave 1, F5, F6). Pure and complete: everything but streaming chunks to
 * and from a file and the records inside them. WP3 (data/backup) writes [BackupPreamble.write],
 * then one [BackupChunks.seal] per chunk; it reads with [BackupPreamble.read], [BackupPreamble.unlock]
 * and one [BackupChunks.open] per chunk. The byte layout is in `phase6-contracts.md` section 4.
 */

/** The two keys of one file, split from the passphrase's PBKDF2 output with HKDF-SHA256. Call [clear] when done. */
class BackupKeys internal constructor(
    /** AES-256-GCM key of the chunks. */
    internal val encryption: ByteArray,
    /** HMAC-SHA256 key of the key check. */
    internal val check: ByteArray,
) {
    /** Overwrites both keys. */
    fun clear() {
        encryption.fill(0)
        check.fill(0)
    }

    companion object {
        /** HKDF "info" of the chunk key. */
        const val ENCRYPTION_INFO: String = "aicc-backup v1 encryption key"

        /** HKDF "info" of the key-check key. */
        const val CHECK_INFO: String = "aicc-backup v1 check key"

        /** What the key check is an HMAC of, before the header hash. */
        const val KEY_CHECK_LABEL: String = "aicc-backup v1 key check"

        /** How many PBKDF2 rounds run between two looks at cancellation. */
        private const val ROUNDS_PER_CANCEL_CHECK = 4_096

        /** The bytes the key is derived from: UTF-8 of [Passphrases.normalise]. Never through a provider's char conversion. */
        fun passphraseBytes(passphrase: CharSequence): ByteArray = Passphrases.normalise(passphrase).encodeToByteArray()

        /**
         * The keys of a file with [salt] and [iterations], from [passphrase]: M = PBKDF2-HMAC-SHA256
         * over [passphraseBytes] (32 bytes); PRK = HMAC-SHA256(key = salt, M) (HKDF-Extract);
         * encryption = HKDF-Expand(PRK, [ENCRYPTION_INFO], 32), check = HKDF-Expand(PRK, [CHECK_INFO], 32).
         * Cancellable: the KDF looks at cancellation every few thousand rounds.
         * @throws IllegalArgumentException for a passphrase that normalises to nothing, a salt that
         *   is not [BackupFormat.SALT_BYTES] long, or iterations outside the accepted range.
         */
        suspend fun derive(passphrase: CharSequence, salt: ByteArray, iterations: Int): BackupKeys {
            require(salt.size == BackupFormat.SALT_BYTES) { "A backup's salt is ${BackupFormat.SALT_BYTES} bytes." }
            require(iterations in BackupFormat.MIN_ITERATIONS..BackupFormat.MAX_ITERATIONS) { "Iterations out of range." }
            val password = passphraseBytes(passphrase)
            require(password.isNotEmpty()) { "A passphrase cannot be empty." }
            val master = pbkdf2HmacSha256(password, salt, iterations, BackupFormat.KEY_BYTES)
            password.fill(0)
            val prk = hmac(salt, master)
            master.fill(0)
            val keys = BackupKeys(hkdfExpand(prk, ENCRYPTION_INFO.encodeToByteArray(), 32), hkdfExpand(prk, CHECK_INFO.encodeToByteArray(), 32))
            prk.fill(0)
            return keys
        }

        /**
         * PBKDF2 (RFC 8018) with HMAC-SHA256 over exactly [password]'s bytes, [length] bytes out.
         * Written out with one [Mac] rather than `PBEKeySpec`, whose conversion of characters to
         * bytes is the provider's; cancellable between rounds.
         */
        suspend fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
            require(iterations >= 1 && length >= 1)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
            val hLen = mac.macLength
            val out = ByteArray(length)
            val u = ByteArray(hLen)
            val t = ByteArray(hLen)
            var block = 1
            var offset = 0
            while (offset < length) {
                mac.update(salt)
                mac.update(ByteBuffer.allocate(4).putInt(block).array())
                mac.doFinal(u, 0)
                u.copyInto(t)
                for (round in 2..iterations) {
                    if (round % ROUNDS_PER_CANCEL_CHECK == 0) currentCoroutineContext().ensureActive()
                    mac.update(u)
                    mac.doFinal(u, 0)
                    for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
                val n = minOf(hLen, length - offset)
                t.copyInto(out, offset, 0, n)
                offset += n
                block++
            }
            u.fill(0)
            t.fill(0)
            return out
        }

        /** HKDF-Expand (RFC 5869) with HMAC-SHA256. */
        fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
            require(length in 1..255 * 32)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            val out = ByteArrayOutputStream(length)
            var previous = ByteArray(0)
            var counter = 1
            while (out.size() < length) {
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                out.write(previous, 0, minOf(previous.size, length - out.size()))
                counter++
            }
            return out.toByteArray()
        }

        /** HMAC-SHA256 of [data] with [key] (also HKDF-Extract, with the salt as key). */
        fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            data.forEach(mac::update)
            return mac.doFinal()
        }
    }

    /** The key check written after the header: HMAC-SHA256(check key, [KEY_CHECK_LABEL] · [headerHash]). */
    fun keyCheck(headerHash: ByteArray): ByteArray = hmac(check, KEY_CHECK_LABEL.encodeToByteArray(), headerHash)

    /** True when [stored] is the key check of [headerHash] under these keys, compared in constant time. */
    fun checks(headerHash: ByteArray, stored: ByteArray): Boolean = MessageDigest.isEqual(keyCheck(headerHash), stored)
}

/** The KDF parameters in the header. */
@Serializable
data class BackupKdf(val name: String, val iterations: Int, val salt: String)

/**
 * The header of a backup file (UTF-8 JSON, at most [BackupFormat.MAX_HEADER_BYTES]). Before the key
 * check has passed only [format], [version] and [kdf] are used ([preCheckProblem]); every other
 * field only after it ([postCheckProblem]).
 */
@Serializable
data class BackupHeader(
    val format: String = BackupFormat.NAME,
    val version: Int = BackupFormat.VERSION,
    val schemaVersion: Int,
    val appVersion: String,
    val appVersionCode: Int,
    val createdAt: Long,
    val kdf: BackupKdf,
    val cipher: String = BackupFormat.CIPHER_NAME,
    val chunkBytes: Int = BackupFormat.CHUNK_BYTES,
) {
    /** The salt's bytes, or null when it is not base64 of [BackupFormat.SALT_BYTES] bytes. */
    fun saltBytes(): ByteArray? = try {
        Base64.getDecoder().decode(kdf.salt).takeIf { it.size == BackupFormat.SALT_BYTES }
    } catch (_: IllegalArgumentException) {
        null
    }

    /** What makes the fields used before the key check unusable (the file is damaged), or null. */
    fun preCheckProblem(): BackupProblem? = when {
        format != BackupFormat.NAME || version != BackupFormat.VERSION -> BackupProblem.Damaged
        kdf.name != BackupFormat.KDF -> BackupProblem.Damaged
        kdf.iterations !in BackupFormat.MIN_ITERATIONS..BackupFormat.MAX_ITERATIONS -> BackupProblem.Damaged
        saltBytes() == null -> BackupProblem.Damaged
        else -> null
    }

    /** What makes the other fields unusable, checked only once the key check passed, or null. */
    fun postCheckProblem(): BackupProblem? = when {
        cipher != BackupFormat.CIPHER_NAME || chunkBytes != BackupFormat.CHUNK_BYTES -> BackupProblem.Damaged
        schemaVersion < 1 || appVersionCode < 1 || createdAt < 0 -> BackupProblem.Damaged
        appVersion.isBlank() || appVersion.length > MAX_APP_VERSION_CHARS -> BackupProblem.Damaged
        else -> null
    }

    companion object {
        const val MAX_APP_VERSION_CHARS: Int = 32

        /** How the header is written and read: unknown keys ignored, every field written. */
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
        }

        /** A new header for a save now: [BackupFormat.DEFAULT_ITERATIONS], a fresh [salt]. */
        fun forSave(schemaVersion: Int, appVersion: String, appVersionCode: Int, createdAt: Long, salt: ByteArray, iterations: Int = BackupFormat.DEFAULT_ITERATIONS) =
            BackupHeader(
                schemaVersion = schemaVersion, appVersion = appVersion, appVersionCode = appVersionCode, createdAt = createdAt,
                kdf = BackupKdf(BackupFormat.KDF, iterations, Base64.getEncoder().encodeToString(salt)),
            )
    }
}

/**
 * Everything before the first chunk: magic (8) · version (u16) · header length (u32) · header ·
 * key check (32) · damage digest (32). [headerHash] H = SHA-256 of magic to the end of the header:
 * what the key check covers and what every chunk's AAD starts with.
 */
class BackupPreamble internal constructor(
    val header: BackupHeader,
    /** SHA-256 of every byte from the magic to the end of the header. */
    val headerHash: ByteArray,
    private val keyCheck: ByteArray,
) {
    /**
     * The keys of this file from [passphrase]: [BackupProblem.WrongPassphrase] when the key check
     * fails (before any chunk is read); [BackupProblem.Damaged] when it passes and a field used
     * only after it is wrong. Cancellable (the KDF).
     */
    suspend fun unlock(passphrase: CharSequence): BackupResult<BackupKeys> {
        if (Passphrases.normalise(passphrase).isEmpty()) return BackupResult.Failed(BackupProblem.WrongPassphrase)
        val salt = header.saltBytes() ?: return BackupResult.Failed(BackupProblem.Damaged)
        val keys = BackupKeys.derive(passphrase, salt, header.kdf.iterations)
        if (!keys.checks(headerHash, keyCheck)) {
            keys.clear()
            return BackupResult.Failed(BackupProblem.WrongPassphrase)
        }
        header.postCheckProblem()?.let {
            keys.clear()
            return BackupResult.Failed(it)
        }
        return BackupResult.Done(keys)
    }

    companion object {
        /** Bytes of the key check and of the damage digest. */
        const val CHECK_BYTES: Int = 32

        /** The bytes of a preamble for [header], with the key check of [keys]. */
        fun write(header: BackupHeader, keys: BackupKeys): ByteArray {
            require(header.preCheckProblem() == null && header.postCheckProblem() == null) { "Not a valid header." }
            val json = BackupHeader.JSON.encodeToString(BackupHeader.serializer(), header).encodeToByteArray()
            require(json.size in 1..BackupFormat.MAX_HEADER_BYTES) { "The header is too long." }
            val prefix = ByteBuffer.allocate(BackupFormat.MAGIC.length + 2 + 4 + json.size)
                .put(BackupFormat.MAGIC.encodeToByteArray()).putShort(BackupFormat.VERSION.toShort()).putInt(json.size).put(json).array()
            val check = keys.keyCheck(sha256(prefix))
            val digest = sha256(prefix, check)
            return prefix + check + digest
        }

        /**
         * Reads the preamble from [input], leaving it at the first chunk. [BackupProblem.NotABackup]
         * for another magic, [BackupProblem.NewerFormat] for a later version, [BackupProblem.Damaged]
         * when the digest does not match (a bit flipped in the header or the key check), the file
         * ends early or a field used before the key check is wrong. No other header field is used.
         */
        fun read(input: InputStream): BackupResult<BackupPreamble> {
            val data = DataInputStream(input)
            return try {
                val magic = ByteArray(BackupFormat.MAGIC.length).also(data::readFully)
                if (!magic.contentEquals(BackupFormat.MAGIC.encodeToByteArray())) return BackupResult.Failed(BackupProblem.NotABackup)
                val version = data.readUnsignedShort()
                if (version > BackupFormat.VERSION) return BackupResult.Failed(BackupProblem.NewerFormat(version))
                if (version != BackupFormat.VERSION) return BackupResult.Failed(BackupProblem.Damaged)
                val length = data.readInt()
                if (length !in 1..BackupFormat.MAX_HEADER_BYTES) return BackupResult.Failed(BackupProblem.Damaged)
                val json = ByteArray(length).also(data::readFully)
                val check = ByteArray(CHECK_BYTES).also(data::readFully)
                val digest = ByteArray(CHECK_BYTES).also(data::readFully)
                val prefix = ByteBuffer.allocate(magic.size + 2 + 4 + length).put(magic).putShort(version.toShort()).putInt(length).put(json).array()
                if (!MessageDigest.isEqual(sha256(prefix, check), digest)) return BackupResult.Failed(BackupProblem.Damaged)
                val header = try {
                    BackupHeader.JSON.decodeFromString(BackupHeader.serializer(), json.decodeToString(throwOnInvalidSequence = true))
                } catch (_: SerializationException) {
                    return BackupResult.Failed(BackupProblem.Damaged)
                } catch (_: IllegalArgumentException) {
                    return BackupResult.Failed(BackupProblem.Damaged)
                } catch (_: CharacterCodingException) {
                    return BackupResult.Failed(BackupProblem.Damaged)
                }
                header.preCheckProblem()?.let { return BackupResult.Failed(it) }
                BackupResult.Done(BackupPreamble(header, sha256(prefix), check))
            } catch (_: EOFException) {
                BackupResult.Failed(BackupProblem.Damaged)
            } catch (_: IOException) {
                BackupResult.Failed(BackupProblem.CannotOpenFile)
            }
        }

        internal fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
            parts.forEach(::update)
            digest()
        }
    }
}

/**
 * One chunk: flags (u8; bit 0 = last, other bits 0) · ciphertext length (u32) · nonce (12, fresh
 * from a secure random source per chunk) · AES-256-GCM ciphertext with its 16-byte tag. Every chunk
 * but the last holds exactly [BackupFormat.CHUNK_BYTES] plaintext bytes; the last holds 1 to
 * [BackupFormat.CHUNK_BYTES]. AAD of chunk [index] = header hash (32) · index (u64) · flags (u8).
 */
object BackupChunks {
    /** The AAD of chunk [index] with [flags]. */
    fun aad(headerHash: ByteArray, index: Long, flags: Int): ByteArray =
        ByteBuffer.allocate(headerHash.size + 8 + 1).put(headerHash).putLong(index).put(flags.toByte()).array()

    /** The chunk [index] holding [plaintext], sealed with [nonce] (12 fresh random bytes): its full bytes, flags first. */
    fun seal(keys: BackupKeys, headerHash: ByteArray, index: Long, last: Boolean, plaintext: ByteArray, nonce: ByteArray): ByteArray {
        require(nonce.size == BackupFormat.NONCE_BYTES)
        require(if (last) plaintext.size in 1..BackupFormat.CHUNK_BYTES else plaintext.size == BackupFormat.CHUNK_BYTES) { "Wrong chunk size." }
        require(index in 0 until BackupFormat.MAX_CHUNKS)
        val flags = if (last) BackupFormat.FLAG_LAST else 0
        val cipher = Cipher.getInstance(BackupFormat.CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encryption, "AES"), GCMParameterSpec(BackupFormat.TAG_BITS, nonce))
        cipher.updateAAD(aad(headerHash, index, flags))
        val sealed = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + 4 + nonce.size + sealed.size).put(flags.toByte()).putInt(sealed.size).put(nonce).put(sealed).array()
    }

    /** One chunk as read: its flags and plaintext. */
    class Opened(val last: Boolean, val plaintext: ByteArray)

    /**
     * Reads and opens chunk [index] from [input]. [BackupProblem.Damaged] for a file that ends
     * before or inside it, unknown flag bits, a wrong length, a non-last chunk that is not full, or
     * a failed tag (a changed, moved, repeated or swapped chunk). After the last chunk the caller
     * checks that [input] is at its end (bytes after it are damage too).
     */
    fun open(keys: BackupKeys, headerHash: ByteArray, index: Long, input: InputStream): BackupResult<Opened> {
        val data = DataInputStream(input)
        return try {
            if (index !in 0 until BackupFormat.MAX_CHUNKS) return BackupResult.Failed(BackupProblem.TooLarge)
            val flags = data.readUnsignedByte()
            if (flags and BackupFormat.FLAG_LAST.inv() != 0) return BackupResult.Failed(BackupProblem.Damaged)
            val last = flags and BackupFormat.FLAG_LAST != 0
            val length = data.readInt()
            val tagBytes = BackupFormat.TAG_BITS / 8
            val fits = if (last) length in (1 + tagBytes)..(BackupFormat.CHUNK_BYTES + tagBytes) else length == BackupFormat.CHUNK_BYTES + tagBytes
            if (!fits) return BackupResult.Failed(BackupProblem.Damaged)
            val nonce = ByteArray(BackupFormat.NONCE_BYTES).also(data::readFully)
            val sealed = ByteArray(length).also(data::readFully)
            val cipher = Cipher.getInstance(BackupFormat.CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.encryption, "AES"), GCMParameterSpec(BackupFormat.TAG_BITS, nonce))
            cipher.updateAAD(aad(headerHash, index, flags))
            val plaintext = try {
                cipher.doFinal(sealed)
            } catch (_: GeneralSecurityException) {
                return BackupResult.Failed(BackupProblem.Damaged)
            }
            BackupResult.Done(Opened(last, plaintext))
        } catch (_: EOFException) {
            BackupResult.Failed(BackupProblem.Damaged)
        } catch (_: IOException) {
            BackupResult.Failed(BackupProblem.CannotOpenFile)
        }
    }
}
