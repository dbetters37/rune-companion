package com.aicontrolcenter.core.backup

import com.aicontrolcenter.core.operator.OwnerResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 6, K13: passphrases, the maintenance gate and the backup format's constants. */
class BackupContractsTest {
    @Test
    fun `a generated passphrase is 20 Crockford characters in five groups`() {
        val passphrase = Passphrases.generate(Random(42))
        assertTrue(Regex("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){4}").matches(passphrase), passphrase)
        assertTrue(Passphrases.isGeneratedForm(passphrase))
        assertEquals(20, Passphrases.normalise(passphrase).length)
        assertNotEquals(passphrase, Passphrases.generate(Random(43)))
        // 32 symbols, 20 of them: 100 bits. Every symbol turns up.
        val seen = (1..200).flatMap { Passphrases.normalise(Passphrases.generate(Random(it.toLong()))).toList() }.toSet()
        assertEquals(Passphrases.ALPHABET.toSet(), seen)
    }

    @Test
    fun `entry ignores case, spaces, dashes and the letters people confuse`() {
        val generated = "7KQ2-M9XD-1ABC-0DEF-GHJK"
        assertEquals(Passphrases.normalise(generated), Passphrases.normalise("7kq2 m9xd lab c-odef ghjk"))
        assertEquals(Passphrases.normalise(generated), Passphrases.normalise(" 7KQ2–M9XD–IABC–ODEF–GHJK "))
        assertEquals("C0RRECTH0RSE", Passphrases.normalise("correct horse"))
    }

    @Test
    fun `the owner's own passphrase needs 16 characters or 4 words and is not common`() {
        assertNull(Passphrases.check("my dog likes long walks"))
        assertNull(Passphrases.check("Seventeen chars!!"))
        assertNull(Passphrases.check("blue train under hill"))
        assertNotNull(Passphrases.check(""))
        assertNotNull(Passphrases.check("short one"))
        assertNotNull(Passphrases.check("a b c d e f"), "words of one letter do not count")
        assertNotNull(Passphrases.check("aaaaaaaaaaaaaaaaaaaa"))
        assertNotNull(Passphrases.check("abcdefghijklmnopq"))
        assertNotNull(Passphrases.check("1234567890123456"))
        assertNotNull(Passphrases.check("passwordpassword"))
        assertNotNull(Passphrases.check("correct horse battery staple"))
        assertNotNull(Passphrases.check("Password12345678"))
    }

    @Test
    fun `maintenance runs one at a time and is not busy afterwards, however it ends`() = runTest {
        val gate = LocalMaintenanceGate()
        assertFalse(gate.busy.value)
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { gate.runExclusive { inside.complete(Unit); release.await(); "restored" } }
        inside.await()
        assertTrue(gate.busy.value)
        assertEquals(OwnerResult.Refused(MaintenanceGate.RESTORING), gate.runExclusive { "second" })
        release.complete(Unit)
        assertEquals(OwnerResult.Done("restored"), first.await())
        assertFalse(gate.busy.value)
        assertFailsWith<IllegalStateException> { gate.runExclusive { error("failed") } }
        assertFalse(gate.busy.value, "a failure frees the gate")
        assertIs<OwnerResult.Done<Int>>(gate.runExclusive { 1 })
    }

    @Test
    fun `the backup format's frozen constants`() {
        assertEquals(8, BackupFormat.MAGIC.length)
        assertEquals(1, BackupFormat.VERSION)
        assertEquals("PBKDF2WithHmacSHA256", BackupFormat.KDF)
        assertEquals(600_000, BackupFormat.DEFAULT_ITERATIONS)
        assertTrue(BackupFormat.DEFAULT_ITERATIONS in BackupFormat.MIN_ITERATIONS..BackupFormat.MAX_ITERATIONS)
        assertEquals(12, BackupFormat.NONCE_BYTES)
        assertEquals(1 shl 20, BackupFormat.CHUNK_BYTES)
        assertTrue(BackupProblem.WrongPassphrase.text.isNotBlank())
        assertTrue(BackupProblem.Busy(MaintenanceGate.RESTORING).text.startsWith("The app is restoring a backup."))
    }
}
