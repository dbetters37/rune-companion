package com.aicontrolcenter.core.operator

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 4, where the three sets meet (R4, D3, J3): one rule for a text that is handed to
 * another program, used by the tools before anyone is asked and by the runners as their last
 * check, and the same characters shown by their code in every preview.
 */
class ExactTextTest {
    @Test
    fun `text that a program gets exactly has no problem`() {
        for (fine in listOf("", "echo hi", "printf '%s\\n' \"a b\"", "echo é中א", "echo 😀", "a\tb\nc", "echo ?", "�", "echo 👍🏽 größe 日本語")) {
            assertNull(ExactText.problem(fine), "[$fine]")
            assertEquals(-1, ExactText.firstNotExact(fine), "[$fine]")
        }
    }

    @Test
    fun `half of a two-part character and a NUL are named by their code, in one wording`() {
        val cases = mapOf(
            // The verifier's command: the shell would get "echo keep?", a pattern.
            "echo keep\uD800" to "U+D800",
            "\uDC00 first" to "U+DC00",
            "a \uD83D b" to "U+D83D",
            // The two halves the wrong way round: neither is a character.
            "x\uDE00\uD83D" to "U+DE00",
            // A whole pair first, then a lone half, and the other way round.
            "😀\uD83D" to "U+D83D",
            "\uD83D😀" to "U+D83D",
            "ls\u0000 -la" to "U+0000",
            // Whichever comes first is the one that is named.
            "a\u0000b\uD800" to "U+0000",
            "a\uD800b\u0000" to "U+D800",
        )
        for ((text, code) in cases) {
            val problem = assertNotNull(ExactText.problem(text), "[$text]")

            assertTrue(problem.startsWith("a character that cannot be passed on to a program as it is ($code, "), problem)
            assertTrue(problem.endsWith(": the program would get a different text than the one the owner is shown"), problem)
            // Named by its code, never repeated as it is: the refusal goes to a model and into the activity log.
            assertTrue(problem.none { it.isSurrogate() || it == '\u0000' }, "the refusal holds the character itself: $problem")
        }
        assertTrue("half of a two-part character" in ExactText.problem("\uD800")!!)
        assertTrue("which ends a text for a program" in ExactText.problem("\u0000")!!)
    }

    @Test
    fun `what is refused is exactly a NUL and what Java would not encode as it is`() {
        // Every code unit alone, after a letter and before one.
        for (unit in 0..0xFFFF) {
            for (text in listOf(unit.toChar().toString(), "a" + unit.toChar(), unit.toChar() + "b")) {
                val survives = String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == text

                assertEquals(survives && unit != 0, ExactText.problem(text) == null, "U+%04X in [%s]".format(unit, text.length))
            }
        }
        // Every pair of a high and a low half is one character and passes.
        for (high in listOf(0xD800, 0xD83D, 0xDBFF)) for (low in listOf(0xDC00, 0xDE00, 0xDFFF)) {
            assertNull(ExactText.problem("x" + high.toChar() + low.toChar() + "y"))
        }
    }

    @Test
    fun `a preview shows by its code exactly the character the refusal names`() {
        // J3 and R4/D3 are one rule: what cannot be passed on is never drawn as it is, and both name the same code first.
        val texts = listOf(
            "echo keep\uD800", "rm keep\uDC00 *", "\uD800", "a\uDC00\uD800b", "😀\uD83D", "\uDE00😀", "x\uD800́y", "'\uD800'", "\"\uDC00\"",
            "tidy.sh\uDBFF", "sub\uD800/x",
        )
        for (text in texts) {
            val problem = assertNotNull(ExactText.problem(text), "[$text]")
            val code = Regex("U\\+[0-9A-F]{4}").find(problem)!!.value

            for (shown in listOf(PreviewText.exact(text), PreviewText.quoted(text))) {
                assertEquals("⟨$code⟩", Regex("⟨U\\+D[89A-F][0-9A-F]{2}⟩").find(shown)?.value, "the first half shown by its code in [$shown]")
                assertFalse(hasLoneSurrogate(shown), "[$shown] still holds half of a character as it is")
            }
        }
        // And the other way round: a text the rule passes is shown without any such mark.
        for (text in listOf("echo 😀", "echo größe 日本語 👍🏽", "echo keep?")) {
            assertNull(ExactText.problem(text))
            assertNull(Regex("⟨U\\+D[89A-F][0-9A-F]{2}⟩").find(PreviewText.exact(text)), text)
        }
        // A NUL is a control character: a preview has always shown it by its code.
        assertEquals("ls⟨U+0000⟩ -la", PreviewText.exact("ls\u0000 -la"))
    }

    private fun hasLoneSurrogate(text: String): Boolean = text.indices.any { i ->
        val c = text[i]
        (c.isHighSurrogate() && text.getOrNull(i + 1)?.isLowSurrogate() != true) || (c.isLowSurrogate() && text.getOrNull(i - 1)?.isHighSurrogate() != true)
    }
}
