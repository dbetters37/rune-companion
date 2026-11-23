package com.aicontrolcenter.framework.operator

import java.util.Locale

/**
 * Turns an HTML page into readable plain text for an agent, without a parser library: one
 * linear pass, so a hostile page cannot make it slow. Scripts, styles, templates, SVG and other
 * non-content elements are dropped with their content; block elements become line breaks; list
 * items get "• "; headings get "#" marks; table cells are separated by " | "; `<pre>` keeps its
 * spacing; images show their alt text; entities are decoded. Control and invisible characters
 * are removed, so the text cannot hide anything from the model or the owner. Links can be
 * collected separately.
 */
object HtmlText {
    /** The page as text. [links] holds up to [MAX_LINKS] absolute http(s) links in page order when asked for. */
    data class Page(val title: String?, val text: String, val links: List<Pair<String, String>>)

    const val MAX_LINKS = 100

    private val SKIPPED = setOf("script", "style", "noscript", "template", "svg", "math", "head", "iframe", "object", "canvas", "select", "datalist")
    private val BLOCK = setOf(
        "address", "article", "aside", "blockquote", "body", "dd", "details", "dialog", "div", "dl", "dt", "fieldset", "figcaption",
        "figure", "footer", "form", "header", "hgroup", "hr", "html", "legend", "main", "nav", "ol", "p", "section", "summary", "table",
        "tbody", "thead", "tfoot", "ul", "caption", "center", "menu",
    )
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

    fun convert(html: String, baseUrl: String? = null, collectLinks: Boolean = false): Page {
        val out = Output()
        var title: String? = null
        val links = ArrayList<Pair<String, String>>()
        var linkHref: String? = null
        var linkTextStart = -1
        var skipUntil: String? = null
        var preDepth = 0
        val listStack = ArrayList<Int>() // -1 for an unordered list, else the next number
        var i = 0
        val n = html.length
        while (i < n) {
            val c = html[i]
            if (c != '<') {
                val next = html.indexOf('<', i).let { if (it < 0) n else it }
                if (skipUntil == null) out.text(decodeEntities(html.substring(i, next)), preDepth > 0)
                i = next
                continue
            }
            // Comments, doctype, CDATA and processing instructions.
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            if (i + 1 < n && (html[i + 1] == '!' || html[i + 1] == '?')) {
                val end = html.indexOf('>', i)
                i = if (end < 0) n else end + 1
                continue
            }
            val tag = readTag(html, i)
            if (tag == null) {
                // A lone "<" is text.
                if (skipUntil == null) out.text("<", preDepth > 0)
                i++
                continue
            }
            i = tag.end
            val name = tag.name
            if (name == "title" && !tag.closing && (skipUntil == null || skipUntil == "head")) {
                // The title is read even inside <head>, whose other content is skipped.
                val end = html.indexOf("</", i).let { at -> if (at < 0) n else at }
                if (title == null) title = cleanLine(decodeEntities(html.substring(i, end))).ifBlank { null }
                i = end
                continue
            }
            if (skipUntil != null) {
                if (tag.closing && name == skipUntil) skipUntil = null
                continue
            }
            if (name == "title") continue
            if (!tag.closing && name in SKIPPED && !tag.selfClosing) {
                skipUntil = name
                continue
            }
            when {
                name == "br" -> out.lineBreak()
                name == "hr" -> out.block()
                name == "pre" -> {
                    out.block()
                    preDepth = if (tag.closing) (preDepth - 1).coerceAtLeast(0) else preDepth + 1
                }
                name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> {
                    out.block()
                    if (!tag.closing) out.text("#".repeat(name[1] - '0') + " ", pre = true)
                }
                name == "ul" || name == "ol" -> {
                    out.block()
                    if (tag.closing) {
                        if (listStack.isNotEmpty()) listStack.removeAt(listStack.size - 1)
                    } else if (!tag.selfClosing) {
                        listStack += if (name == "ol") (tag.attribute("start")?.toIntOrNull() ?: 1) else -1
                    }
                }
                name == "li" -> if (!tag.closing) {
                    out.newLine()
                    val indent = "  ".repeat((listStack.size - 1).coerceAtLeast(0))
                    val top = listStack.lastOrNull() ?: -1
                    if (top >= 0) {
                        out.text("$indent$top. ", pre = true)
                        listStack[listStack.size - 1] = top + 1
                    } else {
                        out.text("$indent• ", pre = true)
                    }
                } else {
                    out.newLine()
                }
                name == "tr" -> out.newLine()
                name == "td" || name == "th" -> if (!tag.closing) out.cellSeparator()
                name == "img" -> tag.attribute("alt")?.let(::cleanLine)?.takeIf { it.isNotBlank() }?.let { out.text(" [Image: $it] ", pre = false) }
                name == "input" && tag.attribute("type")?.lowercase(Locale.ROOT) in setOf("submit", "button") ->
                    tag.attribute("value")?.let(::cleanLine)?.takeIf { it.isNotBlank() }?.let { out.text(" [$it] ", pre = false) }
                name == "a" -> if (collectLinks) {
                    if (!tag.closing) {
                        linkHref = tag.attribute("href")?.let { absolute(it, baseUrl) }
                        linkTextStart = out.length()
                    } else {
                        val href = linkHref
                        if (href != null && links.size < MAX_LINKS) {
                            val text = cleanLine(out.since(linkTextStart)).ifBlank { href }
                            links += text.take(200) to href
                        }
                        linkHref = null
                    }
                }
                name in BLOCK -> out.block()
                name in VOID -> Unit
            }
        }
        return Page(title, out.finish(), links)
    }

    private class Tag(val name: String, val closing: Boolean, val selfClosing: Boolean, val attributes: String, val end: Int) {
        fun attribute(wanted: String): String? {
            var i = 0
            val s = attributes
            while (i < s.length) {
                while (i < s.length && (s[i].isWhitespace() || s[i] == '/')) i++
                val nameStart = i
                while (i < s.length && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && s[i] != '/') i++
                val attrName = s.substring(nameStart, i).lowercase(Locale.ROOT)
                while (i < s.length && s[i].isWhitespace()) i++
                var value: String? = null
                if (i < s.length && s[i] == '=') {
                    i++
                    while (i < s.length && s[i].isWhitespace()) i++
                    if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                        val quote = s[i]
                        val end = s.indexOf(quote, i + 1).let { if (it < 0) s.length else it }
                        value = s.substring(i + 1, end)
                        i = end + 1
                    } else {
                        val start = i
                        while (i < s.length && !s[i].isWhitespace()) i++
                        value = s.substring(start, i)
                    }
                }
                if (attrName == wanted) return value?.let(::decodeEntities) ?: ""
                if (attrName.isEmpty()) i++
            }
            return null
        }
    }

    /** The tag starting at [start] ("<"), or null if it is not a tag. Quoted attribute values may contain ">". */
    private fun readTag(html: String, start: Int): Tag? {
        var i = start + 1
        val closing = i < html.length && html[i] == '/'
        if (closing) i++
        if (i >= html.length || !html[i].isLetter()) return null
        val nameStart = i
        while (i < html.length && (html[i].isLetterOrDigit() || html[i] == '-' || html[i] == ':')) i++
        val name = html.substring(nameStart, i).lowercase(Locale.ROOT)
        val attrStart = i
        var quote: Char? = null
        while (i < html.length) {
            val c = html[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                break
            }
            i++
        }
        val attrs = html.substring(attrStart, i.coerceAtMost(html.length))
        val end = if (i < html.length) i + 1 else html.length
        return Tag(name, closing, attrs.trimEnd().endsWith("/"), attrs, end)
    }

    private fun absolute(href: String, base: String?): String? {
        val trimmed = href.trim()
        val resolved = try {
            if (base != null) java.net.URI(base).resolve(trimmed).toString() else java.net.URI(trimmed).toString()
        } catch (e: Exception) {
            return null
        }
        val scheme = resolved.substringBefore(':', "").lowercase(Locale.ROOT)
        return resolved.takeIf { scheme == "http" || scheme == "https" }
    }

    /** Collapses whitespace and removes control and invisible characters. */
    private fun cleanLine(s: String): String = clean(s).replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")

    /** Removes control characters other than line breaks and tabs, and invisible or direction-changing characters. */
    internal fun clean(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val keep = cp == '\n'.code || cp == '\t'.code || (!Character.isISOControl(cp) && !com.aicontrolcenter.core.operator.PreviewText.isHidden(cp))
            if (keep) {
                if (com.aicontrolcenter.core.operator.PreviewText.isUnusualSpace(cp) && cp != 0x2028 && cp != 0x2029) out.append(' ') else out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "copy" to "©", "reg" to "®",
        "trade" to "™", "mdash" to "—", "ndash" to "–", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
        "rdquo" to "”", "sbquo" to "‚", "bdquo" to "„", "laquo" to "«", "raquo" to "»", "bull" to "•", "middot" to "·",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "deg" to "°", "times" to "×", "divide" to "÷",
        "sect" to "§", "para" to "¶", "plusmn" to "±", "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "micro" to "µ",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "hearts" to "♥", "check" to "✓", "shy" to "",
        "zwj" to "", "zwnj" to "", "lrm" to "", "rlm" to "", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "aacute" to "á", "agrave" to "à", "acirc" to "â", "ccedil" to "ç",
        "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "ntilde" to "ñ", "Eacute" to "É", "aring" to "å", "oslash" to "ø",
    )

    /** Decodes named (common set), decimal and hexadecimal character references; unknown ones stay as written. */
    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                out.append(c)
                i++
                continue
            }
            val body = s.substring(i + 1, semi)
            val decoded: String? = when {
                body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)?.let(::codePoint)
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED[body]
            }
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun codePoint(cp: Int): String? = when {
        cp == 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF -> "�"
        else -> String(Character.toChars(cp))
    }

    /** Builds the text: collapses spaces outside <pre>, keeps at most one blank line between blocks. */
    private class Output {
        private val sb = StringBuilder()
        private var pendingSpace = false

        fun length() = sb.length

        fun since(start: Int): String = if (start in 0..sb.length) sb.substring(start) else ""

        fun text(raw: String, pre: Boolean) {
            val s = clean(raw)
            if (pre) {
                if (pendingSpace && sb.isNotEmpty() && !sb.endsWith("\n") && !sb.endsWith(" ")) sb.append(' ')
                pendingSpace = false
                sb.append(s)
                return
            }
            for (ch in s) {
                if (ch.isWhitespace()) {
                    pendingSpace = true
                } else {
                    if (pendingSpace && sb.isNotEmpty() && !sb.endsWith("\n") && !sb.endsWith(" ")) sb.append(' ')
                    pendingSpace = false
                    sb.append(ch)
                }
            }
        }

        /** A `<br>`: always a break, so two of them leave a blank line. */
        fun lineBreak() {
            trimTrailingSpaces()
            sb.append('\n')
            pendingSpace = false
        }

        /** Starts a new line unless one was just started (list items, table rows). */
        fun newLine() {
            trimTrailingSpaces()
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
            pendingSpace = false
        }

        fun block() {
            trimTrailingSpaces()
            if (sb.isEmpty()) return
            if (!sb.endsWith("\n")) sb.append('\n')
            if (!sb.endsWith("\n\n")) sb.append('\n')
            pendingSpace = false
        }

        fun cellSeparator() {
            trimTrailingSpaces()
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append(" | ")
            pendingSpace = false
        }

        private fun trimTrailingSpaces() {
            while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
        }

        fun finish(): String {
            val lines = sb.toString().split('\n').map { it.trimEnd() }
            val out = StringBuilder()
            var blank = 0
            for (line in lines) {
                if (line.isBlank()) {
                    blank++
                    if (blank > 1) continue
                } else {
                    blank = 0
                }
                out.append(line).append('\n')
            }
            return out.toString().trim('\n')
        }
    }
}
