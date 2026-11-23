package com.aicontrolcenter.providers.web

import okhttp3.MediaType.Companion.toMediaType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which responses count as text, and how their bytes are decoded. */
class TextBodyTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `text, and the application types that are text, are read, and everything else is not`() {
        val text = listOf(
            "text/html", "text/plain", "text/csv", "text/markdown", "application/json", "application/ld+json", "application/xml",
            "application/xhtml+xml", "application/rss+xml", "application/atom+xml", "application/javascript", "application/vnd.api+json",
        )
        for (type in text) assertTrue(TextBody.isTextual(type.toMediaType()), type)
        val other = listOf("image/png", "image/svg", "application/pdf", "application/octet-stream", "application/zip", "video/mp4", "audio/mpeg", "font/woff2")
        for (type in other) assertFalse(TextBody.isTextual(type.toMediaType()), type)
    }

    @Test
    fun `without a content type, text is recognised by the absence of NUL bytes or by a byte-order mark`() {
        assertTrue(TextBody.looksLikeText("plain words".toByteArray()))
        assertTrue(TextBody.looksLikeText(ByteArray(0)))
        assertFalse(TextBody.looksLikeText(bytes(0x89, 0x50, 0x4E, 0x47, 0x00)))
        // UTF-16 text has NUL bytes, but announces itself.
        assertTrue(TextBody.looksLikeText(bytes(0xFF, 0xFE, 'h'.code, 0, 'i'.code, 0)))
    }

    @Test
    fun `the charset comes from a byte-order mark, then the header, then a meta tag, else it is UTF-8`() {
        val html = "text/html".toMediaType()
        val utf8Bom = bytes(0xEF, 0xBB, 0xBF) + "café".toByteArray()
        assertEquals("café", TextBody.decode(utf8Bom, TextBody.charsetOf("text/plain; charset=iso-8859-1".toMediaType(), utf8Bom), false))
        val utf16le = bytes(0xFF, 0xFE) + "hé".toByteArray(Charsets.UTF_16LE)
        assertEquals("hé", TextBody.decode(utf16le, TextBody.charsetOf(null, utf16le), false))
        val utf16be = bytes(0xFE, 0xFF) + "hé".toByteArray(Charsets.UTF_16BE)
        assertEquals("hé", TextBody.decode(utf16be, TextBody.charsetOf(html, utf16be), false))

        val meta = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1252\"></head>\u0080</html>"
            .toByteArray(Charsets.ISO_8859_1)
        assertEquals("windows-1252", TextBody.charsetOf(html, meta).name())
        assertTrue(TextBody.decode(meta, TextBody.charsetOf(html, meta), false).endsWith("€</html>"))
        // The header wins over the document.
        assertEquals(Charsets.UTF_8, TextBody.charsetOf("text/html; charset=utf-8".toMediaType(), meta))
        // A document readable as ASCII markup is not UTF-16, whatever its meta tag says.
        assertEquals(Charsets.UTF_8, TextBody.charsetOf(html, "<meta charset=utf-16>".toByteArray()))
        // Unknown names fall back to UTF-8; Latin-1 means windows-1252, as in browsers.
        assertEquals(Charsets.UTF_8, TextBody.charsetOf("text/plain; charset=x-no-such-charset".toMediaType(), "a".toByteArray()))
        assertEquals("windows-1252", TextBody.charsetOf("text/plain; charset=\"ISO-8859-1\"".toMediaType(), "a".toByteArray()).name())
        assertEquals(Charsets.UTF_8, TextBody.charsetOf("application/json".toMediaType(), "{}".toByteArray()))
    }

    @Test
    fun `malformed bytes become replacement characters, except an incomplete last character of a cut body`() {
        assertEquals("a�b", TextBody.decode(bytes('a'.code, 0xFF, 'b'.code), Charsets.UTF_8, false))
        val cut = "ab€".toByteArray().copyOf(4)
        assertEquals("ab", TextBody.decode(cut, Charsets.UTF_8, truncated = true))
        assertEquals("ab�", TextBody.decode(cut, Charsets.UTF_8, truncated = false))
    }
}
