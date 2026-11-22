package com.aicontrolcenter.device.platform

import org.junit.Test
import kotlin.test.assertEquals

class ForeignTextTest {

    @Test
    fun `collapses whitespace and control characters into single spaces`() {
        assertEquals("Chat GPT", "  Chat\n\t GPT\r\n ".toSingleLine(80))
        assertEquals("a b", "a\u0000\u0007b".toSingleLine(80))
        assertEquals("", " \n\t ".toSingleLine(80))
        assertEquals("", (null as CharSequence?).toSingleLine(80))
    }

    @Test
    fun `drops bidirectional overrides but keeps ordinary non-latin text`() {
        assertEquals("gpj.exe", "\u202Egpj\u2066.exe\u2069".toSingleLine(80))
        assertEquals("\u8C46\u5305 AI", "\u8C46\u5305 AI".toSingleLine(80))
        // The zero-width non-joiner is part of correct Persian spelling and must survive.
        assertEquals("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645", "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645".toSingleLine(80))
    }

    @Test
    fun `cuts at the limit without leaving a trailing space or half a character`() {
        assertEquals("abcde", "abcdefgh".toSingleLine(5))
        assertEquals("abcd", "abcd efgh".toSingleLine(5))
        assertEquals("abc", "abc   defgh".toSingleLine(4))
        // U+1F511 is a surrogate pair; a cut in its middle removes the whole character.
        assertEquals("ab", "ab\uD83D\uDD11cd".toSingleLine(3))
        assertEquals("ab\uD83D\uDD11", "ab\uD83D\uDD11cd".toSingleLine(4))
        assertEquals("", "abc".toSingleLine(0))
    }
}
