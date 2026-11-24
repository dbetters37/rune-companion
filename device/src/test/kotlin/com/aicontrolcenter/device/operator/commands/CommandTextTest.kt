package com.aicontrolcenter.device.operator.commands

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
        }
    }

    @Test
    fun `what is refused is exactly what Java would not encode as it is`() {
        // Every code unit alone and after a letter: refused exactly when the UTF-8 bytes do not give the same text back.
        for (unit in 0..0xFFFF) {
            val text = "a" + unit.toChar()
            val survives = String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == text

            assertEquals(survives, notExactProblem(text) == null, "U+%04X".format(unit))
        }
    }
}
