package com.aicontrolcenter.data.backup

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.backup.BackupChunks
import com.aicontrolcenter.core.backup.BackupHeader
import com.aicontrolcenter.core.backup.BackupKeys
import com.aicontrolcenter.core.backup.BackupPreamble
import com.aicontrolcenter.core.backup.BackupResult
import com.aicontrolcenter.core.backup.Passphrases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The frozen format's test vectors (`phase6-contracts.md` section 4) through the phone's own crypto provider (security
 * review of wave 1, F6): a file the JVM writes must open on the phone and the other way round, so PBKDF2, HKDF, the
 * passphrase's normalisation (the phone's Unicode tables), the key check and AES-GCM must give the same bytes here.
 * Also: the key of a real save (600,000 iterations) is derived on `Dispatchers.Default` within 15 s on a phone (within
 * 10 minutes on the emulator, see [keyBoundMs]), and the time is logged (tag `BackupVectors`). Method names are
 * camelCase: names with spaces cannot be dexed below API 30.
 */
@RunWith(AndroidJUnit4::class)
class BackupVectorsDeviceTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private val salt = ByteArray(16) { it.toByte() }

    /** "Crème brûlée façade ﬁ１", typed precomposed. */
    private val passphrase = Normalizer.normalize("Crème brûlée façade ﬁ１", Normalizer.Form.NFC)

    @Test
    fun pbkdf2GivesTheRfc7914Vectors(): Unit = runBlocking {
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
    fun hkdfGivesTheRfc5869Vector() {
        val prk = BackupKeys.hmac(ByteArray(13) { it.toByte() }, ByteArray(22) { 0x0b })
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", hex(prk))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(BackupKeys.hkdfExpand(prk, ByteArray(10) { (0xf0 + it).toByte() }, 42)),
        )
    }

    @Test
    fun aNonAsciiPassphraseGivesTheSameBytesAndKeysOnThePhone(): Unit = runBlocking {
        val decomposed = Normalizer.normalize(passphrase, Normalizer.Form.NFD)
        assertNotEquals(passphrase, decomposed)
        assertEquals("CRÈMEBRÛ1ÉEFAÇADEF11", Passphrases.normalise(passphrase))
        assertEquals("4352c3884d454252c39b31c389454641c387414445463131", hex(BackupKeys.passphraseBytes(decomposed)))
        assertEquals(
            "4283eba06607c47dc1d914b31444c4e4addd311d6954a7275510302cc4c360a3",
            hex(BackupKeys.pbkdf2HmacSha256(BackupKeys.passphraseBytes(passphrase), salt, 100_000, 32)),
        )
    }

    @Test
    fun thePreambleKeyCheckAndChunkAreTheJvmsByteForByte(): Unit = runBlocking {
        val header = BackupHeader.forSave(schemaVersion = 6, appVersion = "0.6.0", appVersionCode = 9, createdAt = 1_795_000_000_000, salt = salt, iterations = 100_000)
        val keys = BackupKeys.derive(Normalizer.normalize(passphrase, Normalizer.Form.NFD), salt, 100_000)
        val preamble = BackupPreamble.write(header, keys)
        assertEquals(334, preamble.size)
        val headerEnd = preamble.size - 64
        assertEquals("5936edd39b6644fcdc3d58cf6c4a5f9237e7f8875971517035fe7bb078b2e709", hex(sha256(preamble.copyOfRange(0, headerEnd))), "H")
        // The key check is HMAC under K_check: the vector pins K_check through it.
        assertEquals("c1bc63d379669b21290f44c1134d3bfe2021373f069ace7a48cfe8a3858c0cb1", hex(preamble.copyOfRange(headerEnd, headerEnd + 32)), "key check")
        assertEquals("0c2eabb116ccdd0110a6ca58a3cd1ba82c27363158953ac3cead8ce89ff41cfb", hex(preamble.copyOfRange(headerEnd + 32, preamble.size)), "digest")
        assertEquals("67c2d0156cb22315c813a63679305d112a29f56b4ec0edc8714a786592dcd21c", hex(sha256(preamble)))
        // The chunk is AES-256-GCM under K_enc: the vector pins K_enc and the phone's GCM through it.
        val read = (BackupPreamble.read(ByteArrayInputStream(preamble)) as BackupResult.Done).value
        val chunk = BackupChunks.seal(keys, read.headerHash, 0, last = true, plaintext = "hello, backup".encodeToByteArray(), nonce = ByteArray(12) { (100 + it).toByte() })
        assertEquals("010000001d6465666768696a6b6c6d6e6ffa4fe7b6ed30aaabdbf3d5a46aa4815f1d6b8774d8b8bfee9faa23809b", hex(chunk))
        val unlocked = (read.unlock(passphrase) as BackupResult.Done).value
        val opened = (BackupChunks.open(unlocked, read.headerHash, 0, ByteArrayInputStream(chunk)) as BackupResult.Done).value
        assertEquals("hello, backup", opened.plaintext.decodeToString())
    }

    /**
     * 15 s on a phone. The emulator's CPU may be emulated in software (the device gate's emulator runs without hardware
     * acceleration): there these 600,000 rounds took 196 s and the platform's own `PBKDF2WithHmacSHA256` 41 s, against
     * 0.4 s on the JVM, so no implementation could meet 15 s; there the bound only catches a derivation gone far slower
     * or stuck. The time is logged either way.
     */
    private val keyBoundMs = if (Build.HARDWARE in setOf("ranchu", "goldfish")) 600_000L else 15_000L

    @Test
    fun theKeyOfARealSaveIsDerivedWithinFifteenSecondsOnAPhone(): Unit = runBlocking {
        val started = SystemClock.elapsedRealtime()
        val keys = withContext(Dispatchers.Default) { BackupKeys.derive("7KQ2-M9XD-4RTB-8HJN-3VCP", salt, 600_000) }
        val tookMs = SystemClock.elapsedRealtime() - started
        keys.clear()
        Log.i("BackupVectors", "PBKDF2-HMAC-SHA256, 600,000 iterations, on Dispatchers.Default: $tookMs ms (bound $keyBoundMs ms, hardware ${Build.HARDWARE})")
        assertTrue(tookMs <= keyBoundMs, "the key took $tookMs ms, more than $keyBoundMs ms")
    }
}
