package com.aicontrolcenter.data.backup

import com.aicontrolcenter.core.backup.BackupChunks
import com.aicontrolcenter.core.backup.BackupFormat
import com.aicontrolcenter.core.backup.BackupKeys
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupRecord
import com.aicontrolcenter.core.backup.BackupRecords
import com.aicontrolcenter.core.backup.BackupResult
import kotlinx.serialization.SerializationException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * Streaming the body of a backup file, format `aicc-backup` v1 (FROZEN; `phase6-contracts.md` section 4).
 *
 * Everything cryptographic is core's: [BackupChunks.seal] and [BackupChunks.open] seal and open one chunk, and the
 * records are written and read with [BackupRecords.encode] and [BackupRecords.decode]. What is here is only the
 * framing around them: the records joined into one body (a 4-byte length, then the record's UTF-8 JSON), cut into
 * chunks of exactly [BackupFormat.CHUNK_BYTES] plaintext bytes with the last one flagged, and read back in the same
 * order with every rule of section 4's "Reading" list that is not core's own: bytes after the last chunk, plaintext
 * after the end record, a missing end record, a wrong record count, a record outside 1..8 MiB, a malformed known
 * record. None of it writes or reads a key.
 */

/** Why a backup operation stopped: carries the owner's sentence ([BackupProblem]). Never holds file text. */
internal class BackupFailure(val problem: BackupProblem) : Exception(problem.text) {
    // No stack trace: the failure is expected (a damaged file, a wrong passphrase) and says nothing more than its problem.
    override fun fillInStackTrace(): Throwable = this
}

/** The value of a [BackupResult], or a [BackupFailure] with its problem. */
internal fun <T> BackupResult<T>.orFail(): T = when (this) {
    is BackupResult.Done -> value
    is BackupResult.Failed -> throw BackupFailure(problem)
}

/**
 * Writes the body of one file: records go in with [write], are joined and cut into chunks, each sealed by
 * [BackupChunks.seal] with a fresh nonce from [random]; [finish] seals the last chunk (flagged last). A full chunk is
 * sealed only once the next byte arrives, so the chunk flagged last always holds 1 to [BackupFormat.CHUNK_BYTES]
 * bytes. Writes nothing of its own into [out] but sealed chunks.
 */
internal class BackupBodyWriter(
    private val out: OutputStream,
    private val keys: BackupKeys,
    private val headerHash: ByteArray,
    private val random: SecureRandom,
) {
    private val buffer = ByteArray(BackupFormat.CHUNK_BYTES)
    private var filled = 0
    private var index = 0L
    private var bodyBytes = 0L
    private var finished = false

    /** Records written so far, the manifest included. */
    var records: Int = 0
        private set

    /** Bytes written to [out] so far (the chunks only; the caller adds the preamble). */
    var writtenBytes: Long = 0L
        private set

    /** Adds [record]. @throws BackupFailure [BackupProblem.TooLarge] when the record or the body would exceed the format's bounds. */
    fun write(record: BackupRecord) {
        writeEncoded(BackupRecords.encode(record).encodeToByteArray())
    }

    /** Adds a record already encoded with [BackupRecords.encode] (UTF-8 bytes). */
    fun writeEncoded(json: ByteArray) {
        check(!finished) { "The body is finished." }
        if (json.isEmpty() || json.size > BackupFormat.MAX_RECORD_BYTES) throw BackupFailure(BackupProblem.TooLarge)
        append(ByteBuffer.allocate(4).putInt(json.size).array())
        append(json)
        records++
    }

    /** Seals what is left as the chunk flagged last. Call once, after the end record. */
    fun finish() {
        check(!finished) { "The body is finished." }
        check(filled > 0) { "A body holds at least one record." }
        seal(buffer.copyOf(filled), last = true)
        filled = 0
        finished = true
    }

    private fun append(bytes: ByteArray) {
        if (bodyBytes + bytes.size > BackupFormat.MAX_BODY_BYTES) throw BackupFailure(BackupProblem.TooLarge)
        bodyBytes += bytes.size
        var from = 0
        while (from < bytes.size) {
            // A full chunk is not the last one: more bytes are coming.
            if (filled == buffer.size) {
                seal(buffer, last = false)
                filled = 0
            }
            val n = minOf(bytes.size - from, buffer.size - filled)
            bytes.copyInto(buffer, filled, from, from + n)
            filled += n
            from += n
        }
    }

    private fun seal(plaintext: ByteArray, last: Boolean) {
        val nonce = ByteArray(BackupFormat.NONCE_BYTES).also(random::nextBytes)
        val chunk = BackupChunks.seal(keys, headerHash, index, last, plaintext, nonce)
        out.write(chunk)
        writtenBytes += chunk.size
        index++
    }
}

/**
 * The plaintext of a file's chunks as one stream, read in order with [BackupChunks.open]: each chunk is opened (its tag
 * checked) before any of its bytes is handed out. After the chunk flagged last the stream ends, and only if [input] ends
 * there too (bytes after it are damage). A file without a chunk flagged last is damaged. Failures are thrown as
 * [BackupFailure]. [digest] is the SHA-256 of every plaintext byte handed out so far.
 */
internal class ChunkPlaintextStream(
    private val input: InputStream,
    private val keys: BackupKeys,
    private val headerHash: ByteArray,
) : InputStream() {
    private var current: ByteArray = ByteArray(0)
    private var position = 0
    private var index = 0L
    private var ended = false
    private val sha256: MessageDigest = MessageDigest.getInstance("SHA-256")

    /** SHA-256 of the plaintext handed out so far. */
    fun digest(): ByteArray = (sha256.clone() as MessageDigest).digest()

    override fun read(): Int {
        if (!fill()) return -1
        val b = current[position++].toInt() and 0xFF
        sha256.update(b.toByte())
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, current.size - position)
        current.copyInto(b, off, position, position + n)
        sha256.update(b, off, n)
        position += n
        return n
    }

    /** True when there is a byte to read; false at the end of the last chunk. */
    private fun fill(): Boolean {
        while (position >= current.size) {
            if (ended) return false
            val opened = BackupChunks.open(keys, headerHash, index, input).orFail()
            index++
            current = opened.plaintext
            position = 0
            if (opened.last) {
                ended = true
                // After the chunk flagged last the file must end.
                val after = try {
                    input.read()
                } catch (e: IOException) {
                    throw BackupFailure(BackupProblem.CannotOpenFile)
                }
                if (after != -1) throw BackupFailure(BackupProblem.Damaged)
            }
        }
        return true
    }
}

/**
 * Reads the records of a body from [plaintext], in order: the first must be the manifest, the last the end record
 * whose count is the number of records before it (the manifest included), and nothing may follow it. A record type
 * this version does not know is handed over as null (the caller skips it); a malformed known record, a length outside
 * 1..[BackupFormat.MAX_RECORD_BYTES], text that is not UTF-8, or a body that ends inside a record or before the end
 * record is [BackupProblem.Damaged]. Returns the number of records before the end record.
 */
internal suspend fun readRecords(plaintext: InputStream, onRecord: suspend (BackupRecord?) -> Unit): Int {
    var count = 0
    while (true) {
        val length = readLength(plaintext) ?: throw BackupFailure(BackupProblem.Damaged) // no end record
        if (length < 1 || length > BackupFormat.MAX_RECORD_BYTES) throw BackupFailure(BackupProblem.Damaged)
        val bytes = readExactly(plaintext, length)
        val record = decodeRecord(bytes)
        if (count == 0 && record !is BackupRecord.Manifest) throw BackupFailure(BackupProblem.Damaged)
        if (count > 0 && record is BackupRecord.Manifest) throw BackupFailure(BackupProblem.Damaged)
        if (record is BackupRecord.End) {
            if (record.records != count) throw BackupFailure(BackupProblem.Damaged)
            // Any plaintext byte after the end record is damage.
            if (plaintext.read() != -1) throw BackupFailure(BackupProblem.Damaged)
            return count
        }
        count++
        onRecord(record)
    }
}

/** The record's 4-byte length, or null when the body ends cleanly before it. A body cut inside the length is damaged. */
private fun readLength(input: InputStream): Int? {
    val first = input.read()
    if (first == -1) return null
    var value = first
    repeat(3) {
        val b = input.read()
        if (b == -1) throw BackupFailure(BackupProblem.Damaged)
        value = (value shl 8) or b
    }
    return value
}

private fun readExactly(input: InputStream, length: Int): ByteArray {
    val bytes = ByteArray(length)
    var filled = 0
    while (filled < length) {
        val n = input.read(bytes, filled, length - filled)
        if (n == -1) throw BackupFailure(BackupProblem.Damaged)
        filled += n
    }
    return bytes
}

/** The record in [bytes] (strict UTF-8), null for a type this version does not know; damaged when malformed. */
private fun decodeRecord(bytes: ByteArray): BackupRecord? {
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw BackupFailure(BackupProblem.Damaged)
    }
    return try {
        BackupRecords.decode(text)
    } catch (_: SerializationException) {
        throw BackupFailure(BackupProblem.Damaged)
    } catch (_: IllegalArgumentException) {
        // Also a core class's own init check refusing a value (a malformed record).
        throw BackupFailure(BackupProblem.Damaged)
    } catch (_: IllegalStateException) {
        throw BackupFailure(BackupProblem.Damaged)
    }
}

/**
 * Counts the bytes read from [input] and refuses a file larger than [BackupFormat.MAX_FILE_BYTES]
 * ([BackupProblem.TooLarge]); an [IOException] from [input] is [BackupProblem.CannotOpenFile].
 */
internal class BoundedFileStream(input: InputStream) : FilterInputStream(input) {
    var count: Long = 0L
        private set

    override fun read(): Int {
        val b = guarded { super.read() }
        if (b != -1) counted(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = guarded { super.read(b, off, len) }
        if (n > 0) counted(n)
        return n
    }

    override fun skip(n: Long): Long = throw IOException("Not supported.")

    private fun counted(n: Int) {
        count += n
        if (count > BackupFormat.MAX_FILE_BYTES) throw BackupFailure(BackupProblem.TooLarge)
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw BackupFailure(BackupProblem.CannotOpenFile)
    }
}
