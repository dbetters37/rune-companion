package com.aicontrolcenter.device.operator.commands

import com.aicontrolcenter.core.operator.ExactText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandTextTest {
    @Test
    fun `text that encodes exactly has no problem`() {
        for (fine in listOf("", "echo hi", "printf '%s\\n' \"a b\"", "echo é中א", "echo 😀", "a\tb\nc", "echo ?", "�")) {
            assertNull(notExactProblem(fine), "[$fine]")
        }
    }

    @Test
    fun `half of a surrogate pair is named by its code`() {
        val cases = mapOf(
            "rm keep\uD800" to "U+D800",
            "\uDC00 first" to "U+DC00",
            "a \uD83D b" to "U+D83D",
            // The two halves the wrong way round: neither is a character.
            "x\uDE00\uD83D" to "U+DE00",
            // A whole pair first, then a lone half.
            "😀\uD83D" to "U+D83D",
            "\uD83D😀" to "U+D83D",
        )
        for ((text, code) in cases) {
            val problem = assertNotNull(notExactProblem(text), "[$text]")

            assertTrue(code in problem, problem)
            assertTrue("was not run" in problem, problem)
            // One rule, one wording: the words are the ones the tools use when they refuse the same text before anyone is asked.
            assertEquals("The command contains ${ExactText.problem(text)}, so the command was not run.", problem)
        }
    }

    @Test
    fun `a NUL, which ends the text for a program, is refused in the same words`() {
        val problem = assertNotNull(notExactProblem("ls\u0000 -la"))

        assertTrue("U+0000" in problem && "was not run" in problem, problem)
        assertEquals("The command contains ${ExactText.problem("ls\u0000 -la")}, so the command was not run.", problem)
    }

    @Test
    fun `what is refused is exactly what Java would not encode as it is`() {
        // Every code unit alone and after a letter: refused exactly when the UTF-8 bytes do not give the same text back
        // (and the NUL, which the bytes carry and a program stops at).
        for (unit in 0..0xFFFF) {
            val text = "a" + unit.toChar()
            val survives = String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == text

            assertEquals(survives && unit != 0, notExactProblem(text) == null, "U+%04X".format(unit))
        }
    }
}
