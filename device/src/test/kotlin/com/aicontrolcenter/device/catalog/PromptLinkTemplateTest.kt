package com.aicontrolcenter.device.catalog

import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptLinkTemplateTest {

    @Test
    fun `fills the placeholder with a percent-encoded prompt`() {
        assertEquals(
            "https://chat.example.com/?q=Hello%20world",
            PromptLinkTemplate.fill("https://chat.example.com/?q={prompt}", "Hello world"),
        )
        assertEquals(
            "https://chat.example.com/new/a%2Fb?mode=x",
            PromptLinkTemplate.fill("https://chat.example.com/new/{prompt}?mode=x", "a/b"),
        )
    }

    @Test
    fun `a prompt cannot add parameters, a fragment or a path to the link`() {
        val hostile = "x&model=other#frag/../../admin?y=1 +*\n\"quoted\" ünïcödé 🔑"
        val url = PromptLinkTemplate.fill("https://chat.example.com/?q={prompt}&source=acc", hostile)

        val uri = URI(url)
        assertEquals("chat.example.com", uri.host)
        assertEquals("/", uri.path)
        assertEquals(null, uri.fragment)
        val parameters = uri.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(setOf("q", "source"), parameters.keys)
        assertEquals("acc", parameters["source"])
        // Decoding as a plain percent-encoded value ('+' must survive as a literal plus).
        assertEquals(hostile, URLDecoder.decode(parameters.getValue("q").replace("+", "%2B"), "UTF-8"))
        assertFalse(' ' in url || '+' in url || '*' in url || '#' in url)
    }

    @Test
    fun `accepts only https templates whose placeholder comes after the host`() {
        val usable = listOf(
            "https://claude.ai/code/new?q={prompt}",
            "https://chatgpt.com/?q={prompt}",
            "https://example.com?q={prompt}",
            "https://example.com:8443/c#{prompt}",
        )
        val unusable = listOf(
            "https://example.com/?q=prompt",
            "http://example.com/?q={prompt}",
            "intent://example.com/?q={prompt}#Intent;scheme=https;end",
            "file:///sdcard/{prompt}",
            "content://provider/{prompt}",
            "javascript:alert('{prompt}')",
            "claude://code/new?q={prompt}",
            "https://{prompt}/",
            "https://{prompt}.example.com/?q={prompt}",
            "https://trusted.example@evil.example/?q={prompt}",
            "https:///?q={prompt}",
            "https://example.com/ ?q={prompt}",
            "{prompt}",
            "",
        )
        usable.forEach { assertTrue(PromptLinkTemplate.isUsable(it), it) }
        unusable.forEach { assertFalse(PromptLinkTemplate.isUsable(it), it) }
    }

    @Test
    fun `filling an unusable template fails instead of producing a link`() {
        assertFailsWith<IllegalArgumentException> { PromptLinkTemplate.fill("http://example.com/?q={prompt}", "x") }
    }
}
