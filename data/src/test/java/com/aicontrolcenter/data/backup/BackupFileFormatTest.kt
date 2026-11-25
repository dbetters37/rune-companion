package com.aicontrolcenter.data.backup

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.backup.BackupFormat
import com.aicontrolcenter.core.backup.BackupHeader
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupRecord
import com.aicontrolcenter.core.backup.BackupRecords
import com.aicontrolcenter.core.backup.EntryAuthor
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The file WP3 writes and reads, byte by byte (`phase6-contracts.md` section 4, "Tests WP3 owes"): a file that was
 * changed, moved, repeated or cut anywhere is damaged and nothing of it is used; a wrong passphrase is told apart
 * without reading chunk 0; a header asking for too much work is refused before any key derivation.
 */
class BackupFileFormatTest {
    private val agent = Samples.agent(id = "agt_a", name = "Ada", modelId = null).copy(templateId = null)
    private val chat = Conversation(ConversationId("cnv_1"), AgentId("agt_a"), "Plans", 1_000, 2_000)

    /** A body of [entries] chat messages of [chars] characters: several chunks when large. */
    private fun bigFile(entries: Int = 3, chars: Int = 600_000): ByteArray {
        val body = listOf(BackupRecord.Agent(agent), BackupRecord.Chat(chat)) +
            (0 until entries).map { BackupRecord.Entry(chat.id, it, EntryAuthor.OWNER, "x".repeat(chars - 1) + it, 3_000L + it) }
        return BackupFiles.craft(BackupFiles.framed(body))
    }

    private fun inspect(file: ByteArray, app: MemoryApp = MemoryApp()): BackupProblem? =
        runBlocking { app.backup.preview(BackupFiles.opener(file), BackupFiles.PASSPHRASE) }.problem()

    @Test
    fun `a file of several chunks reads back record by record`() {
        val file = bigFile()
        val (_, chunks) = BackupFiles.split(file)
        assertEquals(2, chunks.size, "1.8 MB of text is two chunks")
        val preview = runBlocking { MemoryApp().backup.preview(BackupFiles.opener(file), BackupFiles.PASSPHRASE) }.done()
        assertEquals(3, preview.summary.counts.conversationEntries)
        assertEquals(file.size.toLong(), preview.summary.sizeBytes)
        // Every chunk but the last holds exactly 1 MiB; the last is flagged.
        assertEquals(0, chunks[0][0].toInt())
        assertEquals(BackupFormat.CHUNK_BYTES + 16, ByteBuffer.wrap(chunks[0], 1, 4).int)
        assertEquals(1, chunks.last()[0].toInt())
    }

    @Test
    fun `a body of exactly one chunk ends with a full chunk flagged last`() {
        // One record whose framing fills the chunk to the byte.
        val manifest = BackupRecords.encode(BackupFiles.framed(emptyList()).first()).encodeToByteArray()
        val end = BackupRecords.encode(BackupRecord.End(1)).encodeToByteArray()
        val room = BackupFormat.CHUNK_BYTES - (4 + manifest.size) - (4 + end.size)
        // An unknown record type is skipped by a reader and counted by the end record.
        val filler = """{"type":"later","pad":"${"p".repeat(room - 4 - """{"type":"later","pad":""}""".length)}"}""".encodeToByteArray()
        val file = BackupFiles.craftRaw { w ->
            w.writeEncoded(manifest)
            w.writeEncoded(filler)
            w.writeEncoded(BackupRecords.encode(BackupRecord.End(2)).encodeToByteArray())
        }
        val (_, chunks) = BackupFiles.split(file)
        assertEquals(1, chunks.size)
        assertEquals(BackupFormat.CHUNK_BYTES + 16, ByteBuffer.wrap(chunks[0], 1, 4).int)
        assertEquals(null, inspect(file))
    }

    @Test
    fun `one flipped bit in the header, the key check, the digest or any chunk is damage`() {
        val file = bigFile()
        val (preamble, chunks) = BackupFiles.split(file)
        // The header (the salt and the iteration count among it), the key check and the digest: damage, not "wrong passphrase".
        for (at in listOf(9, 20, preamble.size - 60, preamble.size - 1)) {
            val changed = file.copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() }
            assertEquals(BackupProblem.Damaged, inspect(changed), "bit at $at")
        }
        // Each chunk: its flags, length, nonce, ciphertext and tag.
        var start = preamble.size
        for (chunk in chunks) {
            for (offset in listOf(0, 1, 6, 20, chunk.size / 2, chunk.size - 1)) {
                val changed = file.copyOf().also { it[start + offset] = (it[start + offset].toInt() xor 0x10).toByte() }
                val problem = inspect(changed)
                assertTrue(problem == BackupProblem.Damaged, "chunk at $start, offset $offset: $problem")
            }
            start += chunk.size
        }
    }

    @Test
    fun `reordered, repeated, missing and swapped chunks are damage`() {
        val file = bigFile(entries = 5)
        val (preamble, chunks) = BackupFiles.split(file)
        assertEquals(3, chunks.size)
        fun join(vararg parts: ByteArray) = parts.fold(preamble) { acc, b -> acc + b }
        assertEquals(BackupProblem.Damaged, inspect(join(chunks[1], chunks[0], chunks[2])), "reordered")
        assertEquals(BackupProblem.Damaged, inspect(join(chunks[0], chunks[0], chunks[1], chunks[2])), "repeated")
        assertEquals(BackupProblem.Damaged, inspect(join(chunks[0], chunks[2])), "missing")
        assertEquals(BackupProblem.Damaged, inspect(join(chunks[0], chunks[1])), "no chunk flagged last")
        assertEquals(BackupProblem.Damaged, inspect(join(chunks[0], chunks[1], chunks[2], chunks[2])), "the last chunk twice")
        // A chunk of another file made with the same passphrase does not fit (another salt, another header hash).
        val other = BackupFiles.split(BackupFiles.craft(BackupFiles.framed(emptyList()), header = BackupFiles.header(createdAt = 1))).second
        assertEquals(BackupProblem.Damaged, inspect(join(chunks[0], chunks[1], other[0])), "a chunk from another file")
        assertEquals(null, inspect(join(chunks[0], chunks[1], chunks[2])))
    }

    @Test
    fun `a file cut anywhere, or with bytes after its last chunk, is damage`() {
        val file = bigFile()
        val (preamble, chunks) = BackupFiles.split(file)
        val cuts = listOf(0, 7, 12, preamble.size - 1, preamble.size, preamble.size + 3, preamble.size + chunks[0].size, file.size - 1)
        for (cut in cuts) assertEquals(BackupProblem.Damaged, inspect(file.copyOf(cut)), "cut at $cut")
        assertEquals(BackupProblem.Damaged, inspect(file + byteArrayOf(0)), "one byte after the last chunk")
    }

    @Test
    fun `the body must hold the manifest first, the end record last with the right count, and nothing after it`() {
        val records = BackupFiles.framed(listOf(BackupRecord.Agent(agent)))
        assertEquals(null, inspect(BackupFiles.craft(records)))
        // A plaintext byte after the end record.
        val trailing = BackupFiles.craftRaw { w -> records.forEach(w::write); w.writeEncoded("x".encodeToByteArray()) }
        assertEquals(BackupProblem.Damaged, inspect(trailing))
        assertEquals(BackupProblem.Damaged, inspect(BackupFiles.craft(records.dropLast(1))), "no end record")
        assertEquals(BackupProblem.Damaged, inspect(BackupFiles.craft(records.dropLast(1) + BackupRecord.End(5))), "a wrong count")
        assertEquals(BackupProblem.Damaged, inspect(BackupFiles.craft(records.drop(1) + BackupRecord.End(1))), "no manifest")
        assertEquals(BackupProblem.Damaged, inspect(BackupFiles.craft(listOf(records[0], records[0]) + records.drop(1))), "two manifests")
        // The manifest's counts must be what the file holds.
        assertEquals(BackupProblem.Damaged, inspect(BackupFiles.craft(BackupFiles.framed(listOf(BackupRecord.Agent(agent)), counts = BackupFiles.countsOf(emptyList())))))
        // Records out of the order of section 4.
        val providerFirst = listOf(BackupRecord.Provider(Samples.provider()), BackupRecord.Agent(agent))
        assertEquals(BackupProblem.Damaged, inspect(BackupFiles.craft(BackupFiles.framed(providerFirst))))
        assertEquals(null, inspect(BackupFiles.craft(BackupFiles.framed(providerFirst.reversed()))))
    }

    @Test
    fun `a record of the wrong size, not UTF-8 or malformed is damage, an unknown type is skipped`() {
        val manifest = BackupRecords.encode(BackupFiles.framed(emptyList()).first()).encodeToByteArray()
        fun file(vararg middle: ByteArray, count: Int = middle.size + 1) = BackupFiles.craftRaw { w ->
            w.writeEncoded(manifest)
            middle.forEach(w::writeEncoded)
            w.writeEncoded(BackupRecords.encode(BackupRecord.End(count)).encodeToByteArray())
        }
        assertEquals(null, inspect(file("""{"type":"routine","name":"later"}""".encodeToByteArray())))
        assertEquals(BackupProblem.Damaged, inspect(file(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D))), "not UTF-8")
        assertEquals(BackupProblem.Damaged, inspect(file("""{"type":"agent","agent":{"id":"x"}}""".encodeToByteArray())), "a known record without its fields")
        assertEquals(BackupProblem.Damaged, inspect(file("""["agent"]""".encodeToByteArray())), "not an object")
        assertEquals(BackupProblem.Damaged, inspect(file("""{"kind":"agent"}""".encodeToByteArray())), "no type")
        // A length of 0, and one above 8 MiB, written by hand.
        val zero = BackupFiles.craftRaw { w ->
            w.writeEncoded(manifest)
            val raw = BackupBodyWriter::class.java.getDeclaredMethod("append", ByteArray::class.java).apply { isAccessible = true }
            raw.invoke(w, ByteBuffer.allocate(4).putInt(0).array())
            w.writeEncoded(BackupRecords.encode(BackupRecord.End(1)).encodeToByteArray())
        }
        assertEquals(BackupProblem.Damaged, inspect(zero))
        val huge = BackupFiles.craftRaw { w ->
            w.writeEncoded(manifest)
            val raw = BackupBodyWriter::class.java.getDeclaredMethod("append", ByteArray::class.java).apply { isAccessible = true }
            raw.invoke(w, ByteBuffer.allocate(4).putInt(BackupFormat.MAX_RECORD_BYTES + 1).array())
        }
        assertEquals(BackupProblem.Damaged, inspect(huge))
    }

    @Test
    fun `unknown fields of a known record are ignored`() {
        val manifest = BackupRecords.encode(BackupFiles.framed(emptyList()).first().let { it as BackupRecord.Manifest }.copy(counts = BackupFiles.countsOf(listOf(BackupRecord.Agent(agent))))).encodeToByteArray()
        // A later version's fields, at the record's level and inside the agent and the settings.
        val later = BackupRecords.encode(BackupRecord.Agent(agent)).replaceFirst("{", "{\"addedLater\":[1,2],").replace("\"agent\":{", "\"agent\":{\"mood\":\"calm\",")
        val file = BackupFiles.craftRaw { w ->
            w.writeEncoded(manifest.decodeToString().replace("\"settings\":{", "\"settings\":{\"futureSwitch\":true,").encodeToByteArray())
            w.writeEncoded(later.encodeToByteArray())
            w.writeEncoded(BackupRecords.encode(BackupRecord.End(2)).encodeToByteArray())
        }
        val app = MemoryApp()
        runBlocking { app.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE) }.done()
        assertEquals(listOf(agent.copy(memory = agent.memory.copy(readableSpaces = emptySet(), writableSpaces = emptySet()))), runBlocking { app.agents.getAll() })
    }

    @Test
    fun `a wrong passphrase is told without reading chunk 0`() {
        val file = bigFile()
        val (preamble, _) = BackupFiles.split(file)
        var read = 0L
        val counted: suspend () -> InputStream = {
            object : InputStream() {
                val inner = ByteArrayInputStream(file)
                override fun read(): Int = inner.read().also { if (it >= 0) read++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) read += it }
            }
        }
        val result = runBlocking { MemoryApp().backup.preview(counted, "WRONG-PASS-PHRA-SEXX-XXXX") }
        assertEquals(BackupProblem.WrongPassphrase, result.problem())
        assertEquals(preamble.size.toLong(), read, "nothing after the preamble was read")
    }

    @Test
    fun `a header asking for 10,000,000 iterations is damaged before any key derivation`() {
        val kdf = CountingDispatcher()
        val app = MemoryApp(kdf = kdf)
        val header = BackupHeader.JSON.encodeToString(BackupHeader.serializer(), BackupFiles.header().let { it.copy(kdf = it.kdf.copy(iterations = 10_000_000)) })
        val file = BackupFiles.preambleWith(header) + BackupFiles.split(bigFile()).second[0]
        assertEquals(BackupProblem.Damaged, runBlocking { app.backup.preview(BackupFiles.opener(file), BackupFiles.PASSPHRASE) }.problem())
        assertEquals(0, kdf.dispatches.get(), "no key was derived")
        // And a correct file does derive one, on that dispatcher.
        runBlocking { app.backup.preview(BackupFiles.opener(bigFile(entries = 1, chars = 10)), BackupFiles.PASSPHRASE) }.done()
        assertTrue(kdf.dispatches.get() > 0)
    }

    @Test
    fun `a newer format is refused politely, another file is not a backup`() {
        val header = BackupHeader.JSON.encodeToString(BackupHeader.serializer(), BackupFiles.header())
        val v2 = BackupFiles.preambleWith(header, version = 2)
        assertEquals(BackupProblem.NewerFormat(2), inspect(v2))
        assertTrue(BackupProblem.NewerFormat(2).text.contains("newer version of the app"))
        assertEquals(BackupProblem.Damaged, inspect(BackupFiles.preambleWith(header, version = 0)))
        // The header's own format and version must agree with the fixed field.
        val header2 = BackupHeader.JSON.encodeToString(BackupHeader.serializer(), BackupFiles.header().copy(version = 2))
        assertEquals(BackupProblem.Damaged, inspect(BackupFiles.preambleWith(header2)))
        assertEquals(BackupProblem.NotABackup, inspect("PK\u0003\u0004 a zip file, not a backup".encodeToByteArray()))
    }

    @Test
    fun `a file larger than the format allows is too large, whatever it holds`() {
        val file = bigFile(entries = 1, chars = 10)
        val endless: suspend () -> InputStream = {
            object : InputStream() {
                var at = 0L
                override fun read(): Int = if (at < file.size) file[(at++).toInt()].toInt() and 0xFF else { at++; 0 }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    for (i in 0 until len) b[off + i] = read().toByte()
                    return len
                }
            }
        }
        // The bytes after the last chunk are found first: damage. A file that keeps going is cut off at the bound.
        val problem = runBlocking { MemoryApp().backup.preview(endless, BackupFiles.PASSPHRASE) }.problem()
        assertEquals(BackupProblem.Damaged, problem)
        val stream = BoundedFileStream(object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = len
        })
        val buffer = ByteArray(1 shl 20)
        // 2,000 MiB offered, at most 1 GiB taken.
        val failure = runCatching { repeat(2_000) { stream.read(buffer) } }.exceptionOrNull()
        assertEquals(BackupProblem.TooLarge, (failure as BackupFailure).problem)
        assertTrue(stream.count > BackupFormat.MAX_FILE_BYTES)
    }
}
