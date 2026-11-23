package com.aicontrolcenter.framework.operator

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HtmlTextTest {
    @Test
    fun `blocks become lines, headings and lists are marked, inline text flows`() {
        val page = HtmlText.convert(
            """
            <!DOCTYPE html><html><head><title>  The   Title </title><meta charset="utf-8"></head>
            <body><h2>Section</h2><p>Some <b>bold</b> and <i>italic</i>
            text.</p><ul><li>one</li><li>two<ol start="3"><li>three</li><li>four</li></ol></li></ul>
            <p>a<br>b</p></body></html>
            """.trimIndent(),
        )
        assertEquals("The Title", page.title)
        assertEquals("## Section\n\nSome bold and italic text.\n\n• one\n• two\n\n  3. three\n  4. four\n\na\nb", page.text)
    }

    @Test
    fun `scripts, styles, comments and other non-content are dropped`() {
        val text = HtmlText.convert(
            "<p>keep</p><script>var a = '<p>no</p>';</script><style>.x{}</style><!-- <p>hidden</p> --><noscript>nojs</noscript>" +
                "<template><p>tpl</p></template><svg><text>svg</text></svg><p>end</p>",
        ).text
        assertEquals("keep\n\nend", text)
    }

    @Test
    fun `entities are decoded once and unknown ones stay`() {
        assertEquals("a & b < c © — é ✓ 😀 &unknown; &amp;", HtmlText.decodeEntities("a &amp; b &lt; c &copy; &mdash; &eacute; &#x2713; &#128512; &unknown; &amp;amp;"))
        assertEquals("�", HtmlText.decodeEntities("&#0;"))
    }

    @Test
    fun `tables, images and buttons keep their meaning`() {
        val text = HtmlText.convert(
            "<table><tr><th>Name</th><th>Price</th></tr><tr><td>Tea</td><td>3 €</td></tr></table>" +
                "<p><img src=x alt=\"A cat\"> <input type=submit value=\"Buy now\"></p>",
        ).text
        assertEquals("Name | Price\nTea | 3 €\n\n[Image: A cat] [Buy now]", text)
    }

    @Test
    fun `pre keeps its spacing`() {
        assertEquals("before\n\nline 1\n    indented\n\nafter", HtmlText.convert("<p>before</p><pre>line 1\n    indented</pre><p>after</p>").text)
    }

    @Test
    fun `hidden and control characters are removed so nothing hides in the text`() {
        val text = HtmlText.convert("<p>pay​‮moc.live\u0007 now please</p>").text
        assertEquals("paymoc.live now please", text)
    }

    @Test
    fun `links are collected as absolute http addresses`() {
        val page = HtmlText.convert(
            "<a href='/a'>First</a> <a href=\"https://other.org/b?x=1&amp;y=2\">Second</a> <a href=\"javascript:evil()\">JS</a> <a href=\"#top\"></a>",
            baseUrl = "https://example.com/dir/page", collectLinks = true,
        )
        assertEquals(
            listOf("First" to "https://example.com/a", "Second" to "https://other.org/b?x=1&y=2", "https://example.com/dir/page#top" to "https://example.com/dir/page#top"),
            page.links,
        )
    }

    @Test
    fun `broken markup still gives text`() {
        assertEquals("1 < 2 and 3 > 2", HtmlText.convert("1 < 2 and 3 > 2").text)
        assertEquals("unclosed", HtmlText.convert("<p>unclosed<div").text)
        assertEquals("text", HtmlText.convert("<div title=\"a > b\">text</div>").text)
        assertNull(HtmlText.convert("<p>no title</p>").title)
    }

    @Test
    fun `a huge hostile page is converted in linear time`() {
        val hostile = "<a ".repeat(50_000) + "<!--".repeat(10_000) + "&".repeat(100_000) + "<div>".repeat(50_000)
        val started = System.nanoTime()
        HtmlText.convert(hostile, collectLinks = true)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 3_000, "took $ms ms")
        val nested = "<ul>".repeat(5_000) + "<li>x" + "</ul>".repeat(5_000)
        assertFalse(HtmlText.convert(nested).text.isEmpty())
    }
}
