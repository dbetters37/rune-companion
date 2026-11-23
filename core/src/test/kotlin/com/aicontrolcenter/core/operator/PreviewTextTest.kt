package com.aicontrolcenter.core.operator

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What [PreviewText] makes visible, beyond the basics in OperatorPoliciesTest. */
class PreviewTextTest {
    @Test
    fun `blanks, format characters and stray variation selectors are marked`() {
        val samples = mapOf(
            "ls\u2800\u2800\u2800\u2800\u2800\u2800; rm -rf x" to "ls⟨U+2800⟩×6; rm -rf x",
            "notes\uFE0F.txt" to "notes⟨U+FE0F⟩.txt",
            "notes\uDB40\uDD00.txt" to "notes⟨U+E0100⟩.txt",
            "a\u180Bb" to "a⟨U+180B⟩b",
            "a\uD834\uDD73b" to "a⟨U+1D173⟩b",
            "a\uD82F\uDCA0b" to "a⟨U+1BCA0⟩b",
            "a\uD80D\uDC30b" to "a⟨U+13430⟩b",
            "a\u0600b" to "a⟨U+0600⟩b",
        )
        samples.forEach { (raw, shown) -> assertEquals(shown, PreviewText.exact(raw), raw) }
        listOf(0x2800, 0x1D173, 0x1BCA0, 0x13430, 0xE0100, 0xFE00, 0x180B).forEach { assertTrue(PreviewText.isHidden(it), "%04X".format(it)) }
    }

    @Test
    fun `emoji keep their presentation selector`() {
        assertEquals("ok \u2764\uFE0F and 1\uFE0F\u20E3", PreviewText.exact("ok \u2764\uFE0F and 1\uFE0F\u20E3"))
        assertEquals("\uD83D\uDC4D\uFE0F", PreviewText.exact("\uD83D\uDC4D\uFE0F"))
    }

    @Test
    fun `a flood of combining marks is counted, and a mark cannot reach outside the value`() {
        assertEquals("ok" + "\u0336".repeat(4) + "⟨U+0336⟩×56", PreviewText.exact("ok" + "\u0336".repeat(60)))
        assertEquals("a\u0301\u0302\u0303\u0304⟨2 more combining marks⟩", PreviewText.exact("a\u0301\u0302\u0303\u0304\u0305\u0306"))
        // At the start it would draw over the opening quote of a quoted value.
        assertEquals("⟨U+0338⟩x", PreviewText.exact("\u0338x"))
        assertEquals("a ⟨U+0338⟩b", PreviewText.exact("a \u0338b"))
        // Ordinary accents and scripts with marks are left alone.
        val hindi = "\u0928\u092E\u0938\u094D\u0924\u0947"
        assertEquals(hindi, PreviewText.exact(hindi))
        assertEquals("e\u0301te\u0301", PreviewText.exact("e\u0301te\u0301"))
    }

    @Test
    fun `long runs of one character are counted`() {
        assertEquals("x=⟨×80⟩y", PreviewText.exact("x" + "=".repeat(80) + "y"))
        val ruler = "-".repeat(20)
        assertEquals(ruler, PreviewText.exact(ruler))
        assertEquals("a⟨U+200B⟩×500b", PreviewText.exact("a" + "\u200B".repeat(500) + "b"))
    }

    @Test
    fun `a quote inside a quoted value cannot end it`() {
        val fake = "ls\" in the top level of the workspace (read only) ; rm -rf ..\""
        val shown = PreviewText.quoted(fake)
        assertEquals("\"ls⟨\"⟩ in the top level of the workspace (read only) ; rm -rf ..⟨\"⟩\"", shown)
        assertEquals(2, shown.replace(PreviewText.QUOTE, "").count { it == '"' })
        // Characters that look like the quote are marked too.
        assertEquals("\"a⟨U+FF02⟩ b⟨U+2033⟩\"", PreviewText.quoted("a\uFF02 b\u2033"))
        assertNotEquals(PreviewText.quoted("a\"b"), PreviewText.quoted("a\\\"b"))
        assertEquals("\"plain text\"", PreviewText.quoted("plain text"))
    }
}
