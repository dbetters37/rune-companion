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
    fun `trivially guessable own passphrases are refused`() {
        // Security review of wave 1, F7: four short or repeated words, one-letter blocks and keyboard walks.
        for (weak in listOf(
            "abc abd abe abf", "aaaa bbbb cccc dddd eeee", "the cat sat mat", "dog dog dog cat", "qwerty asdf zxcv 1234",
            "0000-0000-0000-0000-0000", "abab abab abab abab", "qwertyuiopasdfghjkl", "1q2w3e4r5t6y7u8i9o0p", "zxcvbnm zxcvbnm!!",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Password Password Password", "x".repeat(Passphrases.MAX_CHARS + 1),
            // Second review: near-copies of one word, one word with different accents, keyboard columns, patterns.
            "abca abcb abcc abce", "abcx abcy abcz abcw", "Abce Bcdf Cdeg Defh", "ghjk ghjl ghjm ghjn", "cafe caf\u00e9 caf\u00e8 caf\u00ea",
            "1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik,9ol.", "Aa1!Bb2@Cc3#Dd4\$", "Summer2024!Summer2024!",
        )) {
            assertNotNull(Passphrases.check(weak), weak)
        }
        assertEquals(1, Passphrases.realWords("abca abcb abcc abce").size, "words one letter apart are one word")
        assertEquals(1, Passphrases.realWords("cafe caf\u00e9 caf\u00e8 caf\u00ea").size, "accents do not make another word")
        assertTrue(Passphrases.realWords("Abce Bcdf Cdeg Defh").size >= Passphrases.MIN_OWN_WORDS, "four words, but too weak together")
        assertTrue(Passphrases.strengthBits("Abce Bcdf Cdeg Defh") < Passphrases.MIN_WORDS_BITS)
        // Four real words, or enough strength without them.
        for (strong in listOf("blue train under hill", "river candle orbit maple", "Vögel fliegen über Brücken", "k7#Qm2!vR9@xL4\$w", "Seventeen chars!!")) {
            assertNull(Passphrases.check(strong), strong)
        }
        assertEquals(setOf("B1UE", "TRA1N", "UNDER", "H111"), Passphrases.realWords("blue train under hill"))
        assertEquals(emptySet(), Passphrases.realWords("aaaa bbbb abab qwerty asdf abcd"), "repeats, walks and runs are no words")
        assertTrue(Passphrases.strengthBits("qwerty asdf zxcv 1234") < Passphrases.MIN_OWN_BITS)
        assertTrue(Passphrases.strengthBits("k7#Qm2!vR9@xL4\$w") >= Passphrases.MIN_OWN_BITS)
    }

    @Test
    fun `a save takes the passphrase it generated and showed, or an own one that passes the check, never a look-alike`() {
        val shown = Passphrases.generate(Random(7))
        assertNull(Passphrases.problemForSave(shown, shown))
        assertNull(Passphrases.problemForSave(shown.lowercase().replace("-", " "), shown), "typed back in another form")
        // Typed by the owner, in the generated form: checked like any own passphrase.
        assertTrue(Passphrases.isGeneratedForm("0000-0000-0000-0000-0000"))
        assertNotNull(Passphrases.problemForSave("0000-0000-0000-0000-0000", generatedShown = null))
        assertNotNull(Passphrases.problemForSave("0000-0000-0000-0000-0000", generatedShown = shown))
        assertNull(Passphrases.problemForSave("blue train under hill", generatedShown = shown))
        assertNotNull(Passphrases.problemForSave("", generatedShown = ""))
        // Second review, NEW-1: a screen slip that passes the typed text as the generated one does not switch the check off.
        assertNotNull(Passphrases.problemForSave("aaaa", generatedShown = "aaaa"))
        assertNotNull(Passphrases.problemForSave("password", generatedShown = "password"))
        assertNotNull(Passphrases.problemForSave("0000-0000-0000-0000-0000", generatedShown = "0000 0000 0000 0000 0000"), "not the form generate() gives")
        assertNotNull(Passphrases.problemForSave("aaaa", generatedShown = "AAAA-AAAA-AAAA-AAAA-AAAA"), "the generated one, but not what was typed")
    }

    @Test
    fun `entry is Unicode-stable - precomposed, decomposed and compatibility forms normalise alike`() {
        // Security review of wave 1, F6.
        val precomposed = "Caf\u00e9 cr\u00e8me"
        val decomposed = "Cafe\u0301 cre\u0300me"
        assertEquals(Passphrases.normalise(precomposed), Passphrases.normalise(decomposed))
        assertEquals("CAFÉCRÈME", Passphrases.normalise(decomposed))
        assertEquals(Passphrases.normalise("7KQ2"), Passphrases.normalise("\uFF17\uFF2B\uFF31\uFF12"), "full-width forms")
        assertEquals(Passphrases.normalise("a-b"), Passphrases.normalise("a\u2212b\u2011"), "minus sign and non-breaking hyphen are dashes")
        assertEquals("STRASSE", Passphrases.normalise("stra\u00dfe"))
        // Second review: every case variant of a letter, invisible characters and other dashes.
        assertEquals("STRASSE", Passphrases.normalise("STRA\u1E9EE"), "capital sharp s")
        assertEquals(Passphrases.normalise("\u0390"), Passphrases.normalise("\u03AA\u0301"), "Greek iota with dialytika and tonos, either case")
        assertEquals(Passphrases.normalise("\u0130stanbul"), Passphrases.normalise("i\u0307stanbul"), "Turkish dotted capital I, either case")
        for (hidden in listOf("A\u00ADB", "A\u200BB", "A\u200DB", "A\uFEFFB", "A\u2043B", "A\uFE63B", "A\uFF0DB", "A\u2E3AB")) {
            assertEquals("AB", Passphrases.normalise(hidden), hidden.map { "%04x".format(it.code) }.toString())
        }
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
        assertEquals(100_000..2_000_000, BackupFormat.MIN_ITERATIONS..BackupFormat.MAX_ITERATIONS)
        assertTrue(BackupFormat.DEFAULT_ITERATIONS in BackupFormat.MIN_ITERATIONS..BackupFormat.MAX_ITERATIONS)
        assertEquals(12, BackupFormat.NONCE_BYTES)
        assertEquals(1 shl 20, BackupFormat.CHUNK_BYTES)
        assertTrue(BackupProblem.WrongPassphrase.text.isNotBlank())
        assertTrue(BackupProblem.Busy(MaintenanceGate.RESTORING).text.startsWith("The app is restoring a backup."))
    }
}
