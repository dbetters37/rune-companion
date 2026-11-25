package com.aicontrolcenter.data.backup

import com.aicontrolcenter.core.backup.Passphrases
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The save screen's passphrase choice (rule 27): the generated one by default, an own one only when strong, nothing kept after the screen. */
class SavePassphraseTest {
    @Test
    fun `the generated passphrase is the default and passes as exactly what was shown`() {
        val choice = SavePassphrase(Random(7))
        val shown = assertNotNull(choice.generated)
        assertEquals(Regex("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){4}").matches(shown), true, shown)
        assertNull(choice.problem(""))
        assertEquals(shown to shown, choice.forSave("whatever the hidden field holds"))
        assertNull(Passphrases.problemForSave(shown, shown))
        // A new one on request.
        choice.regenerate()
        assertNotEquals(shown, choice.generated)
    }

    @Test
    fun `an own passphrase must pass the check, and is never handed over as the generated one`() {
        val choice = SavePassphrase(Random(7)).apply { useOwn = true }
        assertNotNull(choice.problem("password"))
        assertNull(choice.forSave("password"))
        // Typing the generated form by hand does not make it "generated".
        assertNotNull(choice.problem("aaaa-aaaa-aaaa-aaaa-aaaa"))
        val own = "violet harbour lantern quietly"
        assertNull(choice.problem(own))
        assertEquals(own to null, choice.forSave(own))
    }

    @Test
    fun `nothing is kept once the screen closes`() {
        val choice = SavePassphrase(Random(7))
        choice.forget()
        assertNull(choice.generated)
        assertNotNull(choice.problem(""))
        assertNull(choice.forSave(""))
    }

    @Test
    fun `what forSave gives is what a save accepts`() = runBlocking<Unit> {
        val app = MemoryApp()
        val choice = SavePassphrase()
        val (passphrase, shown) = assertNotNull(choice.forSave(""))
        app.backup.saveWithNotes(passphrase, shown, { ByteArrayOutputStream() }).done()
        choice.useOwn = true
        val (own, none) = assertNotNull(choice.forSave("violet harbour lantern quietly"))
        app.backup.saveWithNotes(own, none, { ByteArrayOutputStream() }).done()
    }
}
