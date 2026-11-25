package com.aicontrolcenter.core.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.text.Normalizer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The frozen backup format v1, its keys and its key check (security review of wave 1, F5, F6).
 * Every vector here was computed independently with Python's `hashlib`, `hmac`, `unicodedata`
 * and `cryptography` (AES-GCM) from the description in `phase6-contracts.md` section 4.
 */
class BackupCryptoTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private val salt = ByteArray(16) { it.toByte() }

    /** Typed precomposed on one phone. */
    private val passphrase = Normalizer.normalize("Crème brûlée façade ﬁ１", Normalizer.Form.NFC)

    private fun header() = BackupHeader.forSave(schemaVersion = 6, appVersion = "0.6.0", appVersionCode = 9, createdAt = 1_795_000_000_000, salt = salt, iterations = 100_000)

    @Test
    fun `PBKDF2-HMAC-SHA256 gives the RFC 7914 section 11 vectors over exact bytes`() = runTest {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(BackupKeys.pbkdf2HmacSha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64)),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            hex(BackupKeys.pbkdf2HmacSha256("Password".encodeToByteArray(), "NaCl".encodeToByteArray(), 80_000, 64)),
        )
    }

    @Test
    fun `HKDF-SHA256 gives the RFC 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val hkdfSalt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val prk = BackupKeys.hmac(hkdfSalt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", hex(prk))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(BackupKeys.hkdfExpand(prk, info, 42)),
        )
    }

    @Test
    fun `a non-ASCII passphrase gives the same bytes and keys however it was typed`() = runTest {
        val decomposed = Normalizer.normalize(passphrase, Normalizer.Form.NFD)
        assertNotEquals(passphrase, decomposed, "the two are different strings")
        assertEquals("CRÈMEBRÛ1ÉEFAÇADEF11", Passphrases.normalise(passphrase))
        assertEquals(Passphrases.normalise(passphrase), Passphrases.normalise(decomposed))
        assertEquals("4352c3884d454252c39b31c389454641c387414445463131", hex(BackupKeys.passphraseBytes(decomposed)))
        // The vector: salt 00..0f, 100,000 iterations.
        assertEquals(
            "4283eba06607c47dc1d914b31444c4e4addd311d6954a7275510302cc4c360a3",
            hex(BackupKeys.pbkdf2HmacSha256(BackupKeys.passphraseBytes(passphrase), salt, 100_000, 32)),
        )
        val keys = BackupKeys.derive(decomposed, salt, 100_000)
        assertEquals("fd0ad21ad5d371eed014dc05b8be33868ae47f78b1bf919f9bb997448626ff5e", hex(keys.encryption))
        assertEquals("63b9823dadc2b1230b96f192e0a59f51c3c5f4c5e89da9abc7f51a3e5d0b6302", hex(keys.check))
    }

    @Test
    fun `the preamble is pinned byte for byte, and a one-chunk file opens`() = runTest {
        val header = header()
        assertEquals(
            """{"format":"aicc-backup","version":1,"schemaVersion":6,"appVersion":"0.6.0","appVersionCode":9,"createdAt":1795000000000,""" +
                """"kdf":{"name":"PBKDF2WithHmacSHA256","iterations":100000,"salt":"AAECAwQFBgcICQoLDA0ODw=="},"cipher":"AES-256-GCM","chunkBytes":1048576}""",
            BackupHeader.JSON.encodeToString(BackupHeader.serializer(), header),
        )
        val keys = BackupKeys.derive(passphrase, salt, 100_000)
        val preamble = BackupPreamble.write(header, keys)
        assertEquals(334, preamble.size)
        val headerEnd = preamble.size - 64
        assertEquals("5936edd39b6644fcdc3d58cf6c4a5f9237e7f8875971517035fe7bb078b2e709", hex(BackupPreamble.sha256(preamble.copyOfRange(0, headerEnd))), "H")
        assertEquals("c1bc63d379669b21290f44c1134d3bfe2021373f069ace7a48cfe8a3858c0cb1", hex(preamble.copyOfRange(headerEnd, headerEnd + 32)), "key check")
        assertEquals("0c2eabb116ccdd0110a6ca58a3cd1ba82c27363158953ac3cead8ce89ff41cfb", hex(preamble.copyOfRange(headerEnd + 32, preamble.size)), "digest")
        assertEquals("67c2d0156cb22315c813a63679305d112a29f56b4ec0edc8714a786592dcd21c", hex(BackupPreamble.sha256(preamble)))
        // One last chunk holding "hello, backup", nonce 100..111.
        val read = assertIs<BackupResult.Done<BackupPreamble>>(BackupPreamble.read(ByteArrayInputStream(preamble))).value
        val chunk = BackupChunks.seal(keys, read.headerHash, 0, last = true, "hello, backup".encodeToByteArray(), ByteArray(12) { (100 + it).toByte() })
        assertEquals("010000001d6465666768696a6b6c6d6e6ffa4fe7b6ed30aaabdbf3d5a46aa4815f1d6b8774d8b8bfee9faa23809b", hex(chunk))
        val opened = assertIs<BackupResult.Done<BackupKeys>>(read.unlock(Normalizer.normalize(passphrase, Normalizer.Form.NFD))).value
        val plain = assertIs<BackupResult.Done<BackupChunks.Opened>>(BackupChunks.open(opened, read.headerHash, 0, ByteArrayInputStream(chunk))).value
        assertTrue(plain.last)
        assertEquals("hello, backup", plain.plaintext.decodeToString())
    }

    @Test
    fun `a wrong passphrase is told apart from damage, before any chunk is read`() = runTest {
        val keys = BackupKeys.derive(passphrase, salt, 100_000)
        val preamble = BackupPreamble.write(header(), keys)
        val read = assertIs<BackupResult.Done<BackupPreamble>>(BackupPreamble.read(ByteArrayInputStream(preamble))).value
        assertEquals(BackupResult.Failed(BackupProblem.WrongPassphrase), read.unlock("Creme brulee facade fi1"))
        assertEquals(BackupResult.Failed(BackupProblem.WrongPassphrase), read.unlock("   "))
        // Any one bit flipped before the chunks: damaged, never "wrong passphrase" (the digest sees it before the KDF runs).
        for (byte in preamble.indices) {
            for (bit in listOf(0, 7)) {
                val flipped = preamble.copyOf().also { it[byte] = (it[byte].toInt() xor (1 shl bit)).toByte() }
                val result = assertIs<BackupResult.Failed>(BackupPreamble.read(ByteArrayInputStream(flipped)), "byte $byte bit $bit")
                assertTrue(
                    result.problem == BackupProblem.Damaged || result.problem == BackupProblem.NotABackup || result.problem is BackupProblem.NewerFormat,
                    "byte $byte bit $bit: ${result.problem}",
                )
            }
        }
        // A file cut inside the preamble is damaged.
        assertEquals(BackupResult.Failed(BackupProblem.Damaged), BackupPreamble.read(ByteArrayInputStream(preamble.copyOf(preamble.size - 1))))
        assertEquals(BackupResult.Failed(BackupProblem.NotABackup), BackupPreamble.read(ByteArrayInputStream("PK\u0003\u0004 not a backup".encodeToByteArray())))
    }

    @Test
    fun `only version, KDF and salt are used before the key check, and the rest must be exact after it`() = runTest {
        val keys = BackupKeys.derive(passphrase, salt, 100_000)
        suspend fun rewritten(header: BackupHeader): BackupResult<BackupKeys> {
            // A writer that put these values in and signed them: the digest and the key check pass.
            val json = BackupHeader.JSON.encodeToString(BackupHeader.serializer(), header).encodeToByteArray()
            val prefix = java.nio.ByteBuffer.allocate(8 + 2 + 4 + json.size).put("AICCBKUP".encodeToByteArray()).putShort(1).putInt(json.size).put(json).array()
            val check = keys.keyCheck(BackupPreamble.sha256(prefix))
            val bytes = prefix + check + BackupPreamble.sha256(prefix, check)
            return when (val read = BackupPreamble.read(ByteArrayInputStream(bytes))) {
                is BackupResult.Failed -> read
                is BackupResult.Done -> read.value.unlock(passphrase)
            }
        }
        assertIs<BackupResult.Done<BackupKeys>>(rewritten(header()))
        // Before the check: a KDF out of bounds is refused before any work.
        assertEquals(BackupResult.Failed(BackupProblem.Damaged), rewritten(header().copy(kdf = header().kdf.copy(iterations = 10_000_000))))
        assertEquals(BackupResult.Failed(BackupProblem.Damaged), rewritten(header().copy(kdf = header().kdf.copy(iterations = 99_999))))
        assertEquals(BackupResult.Failed(BackupProblem.Damaged), rewritten(header().copy(kdf = header().kdf.copy(name = "scrypt"))))
        assertEquals(BackupResult.Failed(BackupProblem.Damaged), rewritten(header().copy(kdf = header().kdf.copy(salt = "AAEC"))))
        assertEquals(BackupResult.Failed(BackupProblem.Damaged), rewritten(header().copy(version = 2)))
        // After the check: the chunk size must be exactly 1 MiB, the cipher AES-256-GCM.
        assertEquals(BackupResult.Failed(BackupProblem.Damaged), rewritten(header().copy(chunkBytes = 1_048_575)))
        assertEquals(BackupResult.Failed(BackupProblem.Damaged), rewritten(header().copy(chunkBytes = 64)))
        assertEquals(BackupResult.Failed(BackupProblem.Damaged), rewritten(header().copy(cipher = "AES-128-GCM")))
        assertEquals(2_000_000, BackupFormat.MAX_ITERATIONS)
        assertEquals(1_048_576, BackupFormat.CHUNK_BYTES)
    }

    @Test
    fun `chunks are bound to their place, their flag and the header`() = runTest {
        val keys = BackupKeys.derive(passphrase, salt, 100_000)
        val h = BackupPreamble.sha256("header".encodeToByteArray())
        val full = ByteArray(BackupFormat.CHUNK_BYTES) { (it % 251).toByte() }
        val nonce = ByteArray(12) { 7 }
        val first = BackupChunks.seal(keys, h, 0, last = false, full, nonce)
        val last = BackupChunks.seal(keys, h, 1, last = true, "end".encodeToByteArray(), nonce)
        assertIs<BackupResult.Done<BackupChunks.Opened>>(BackupChunks.open(keys, h, 0, ByteArrayInputStream(first)))
        assertIs<BackupResult.Done<BackupChunks.Opened>>(BackupChunks.open(keys, h, 1, ByteArrayInputStream(last)))
        val damaged = BackupResult.Failed(BackupProblem.Damaged)
        assertEquals(damaged, BackupChunks.open(keys, h, 1, ByteArrayInputStream(first)), "moved")
        assertEquals(damaged, BackupChunks.open(keys, h, 0, ByteArrayInputStream(last)), "swapped")
        assertEquals(damaged, BackupChunks.open(keys, BackupPreamble.sha256("other".encodeToByteArray()), 1, ByteArrayInputStream(last)), "another header")
        assertEquals(damaged, BackupChunks.open(keys, h, 1, ByteArrayInputStream(last.copyOf().also { it[0] = 0 })), "last flag removed")
        assertEquals(damaged, BackupChunks.open(keys, h, 1, ByteArrayInputStream(last.copyOf().also { it[0] = 3 })), "unknown flag bit")
        assertEquals(damaged, BackupChunks.open(keys, h, 1, ByteArrayInputStream(last.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })), "tag")
        assertEquals(damaged, BackupChunks.open(keys, h, 1, ByteArrayInputStream(last.copyOf(last.size - 1))), "cut")
        // A chunk that is not last must be full; the last must hold something.
        assertFailsWith<IllegalArgumentException> { BackupChunks.seal(keys, h, 0, last = false, ByteArray(10), nonce) }
        assertFailsWith<IllegalArgumentException> { BackupChunks.seal(keys, h, 0, last = true, ByteArray(0), nonce) }
    }

    @Test
    fun `the key derivation can be cancelled while it runs`() = runBlocking {
        // 20 million rounds take many seconds; a cancelled derivation stops within a few thousand rounds.
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            started.complete(Unit)
            BackupKeys.pbkdf2HmacSha256("x".encodeToByteArray(), salt, 20_000_000, 32)
        }
        started.await()
        delay(100)
        val before = System.nanoTime()
        job.cancelAndJoin()
        val stoppedAfterMs = (System.nanoTime() - before) / 1_000_000
        assertTrue(job.isCancelled)
        assertTrue(stoppedAfterMs < 2_000, "stopped $stoppedAfterMs ms after it was cancelled")
    }

    @Test
    fun `keys are checked in constant time and cleared on request`() = runTest {
        val keys = BackupKeys.derive(passphrase, salt, 100_000)
        val h = ByteArray(32) { 1 }
        val check = keys.keyCheck(h)
        assertTrue(keys.checks(h, check))
        assertTrue(!keys.checks(h, check.copyOf().also { it[31] = (it[31] + 1).toByte() }))
        assertTrue(!keys.checks(h, check.copyOf(31)))
        keys.clear()
        assertContentEquals(ByteArray(32), keys.encryption)
        assertContentEquals(ByteArray(32), keys.check)
        assertFailsWith<IllegalArgumentException> { BackupKeys.derive("  --  ", salt, 100_000) }
        assertFailsWith<IllegalArgumentException> { BackupKeys.derive(passphrase, ByteArray(8), 100_000) }
        assertFailsWith<IllegalArgumentException> { BackupKeys.derive(passphrase, salt, BackupFormat.MAX_ITERATIONS + 1) }
    }
}
