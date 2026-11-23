package com.aicontrolcenter.app.ui.tasks.markdown

import androidx.compose.runtime.Immutable

/*
 * A small Markdown reader for what agents write: the Director's final document and task
 * results. It covers what models actually produce (headings, paragraphs, bulleted and numbered
 * lists with nesting, fenced code, quotes, rules, simple tables, and bold, italic, code and
 * links inside text) and nothing else. It never fails: anything it does not recognise is shown
 * as the text it is. Pure Kotlin, so every case is unit-tested on the JVM.
 */

/** A run of text with one style. Styles nest, so a run can be bold and italic at once. */
@Immutable
data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    /** Set for the text of a link. */
    val url: String? = null,
)

@Immutable
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: List<InlineRun>) : MarkdownBlock

    data class Paragraph(val text: List<InlineRun>) : MarkdownBlock

    /** One list item; consecutive items form a list. [marker] is "•" or the item's number ("2."). */
    data class ListItem(val level: Int, val marker: String, val text: List<InlineRun>) : MarkdownBlock

    data class Code(val text: String, val language: String?) : MarkdownBlock

    data class Quote(val text: List<InlineRun>) : MarkdownBlock

    data class Table(val header: List<List<InlineRun>>, val rows: List<List<List<InlineRun>>>) : MarkdownBlock

    data object Rule : MarkdownBlock
}

object Markdown {
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val FENCE = Regex("^\\s{0,3}(```+|~~~+)\\s*([\\w+#.-]*)\\s*$")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    /** Splits [source] into blocks. Line endings of any platform are accepted. */
    fun parse(source: String): List<MarkdownBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MarkdownBlock>()
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks += MarkdownBlock.Paragraph(inline(paragraph.joinToString(" ") { it.trim() }))
                paragraph.clear()
            }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            when {
                fence != null -> {
                    flush()
                    val close = fence.groupValues[1]
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(close)) code += lines[i++]
                    blocks += MarkdownBlock.Code(code.joinToString("\n"), fence.groupValues[2].ifEmpty { null })
                }
                line.isBlank() -> flush()
                RULE.matches(line) -> {
                    flush()
                    blocks += MarkdownBlock.Rule
                }
                HEADING.matches(line.trimStart()) -> {
                    flush()
                    val match = HEADING.matchEntire(line.trimStart())!!
                    blocks += MarkdownBlock.Heading(match.groupValues[1].length, inline(match.groupValues[2]))
                }
                line.trimStart().startsWith(">") -> {
                    flush()
                    val quoted = ArrayList<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) quoted += lines[i++].trimStart().removePrefix(">").trim()
                    i--
                    blocks += MarkdownBlock.Quote(inline(quoted.filter { it.isNotEmpty() }.joinToString(" ")))
                }
                line.contains('|') && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(line)
                    i += 2
                    val rows = ArrayList<List<List<InlineRun>>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) rows += cells(lines[i++]).let { row -> List(header.size) { row.getOrElse(it) { emptyList() } } }
                    i--
                    blocks += MarkdownBlock.Table(header, rows)
                }
                BULLET.matches(line) && !RULE.matches(line) -> {
                    flush()
                    val match = BULLET.matchEntire(line)!!
                    blocks += MarkdownBlock.ListItem(level(match.groupValues[1]), "•", inline(match.groupValues[2]))
                }
                NUMBERED.matches(line) -> {
                    flush()
                    val match = NUMBERED.matchEntire(line)!!
                    blocks += MarkdownBlock.ListItem(level(match.groupValues[1]), match.groupValues[2] + ".", inline(match.groupValues[3]))
                }
                // A line that continues a list item (indented, no marker) belongs to that item.
                paragraph.isEmpty() && line.startsWith("  ") && blocks.lastOrNull() is MarkdownBlock.ListItem -> {
                    val item = blocks.removeAt(blocks.lastIndex) as MarkdownBlock.ListItem
                    blocks += item.copy(text = item.text + InlineRun(" ") + inline(line.trim()))
                }
                else -> paragraph += line
            }
            i++
        }
        flush()
        return blocks
    }

    /** Two spaces (or a tab) of indentation per nesting level, at most three levels deep. */
    private fun level(indent: String): Int = (indent.replace("\t", "  ").length / 2).coerceAtMost(3)

    private fun cells(row: String): List<List<InlineRun>> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map { inline(it.trim()) }

    /** Parses the styles inside one block of text. Unclosed markers are kept as plain text. */
    fun inline(text: String): List<InlineRun> = merge(parseInline(text, InlineRun("")))

    private fun parseInline(text: String, style: InlineRun): List<InlineRun> {
        val runs = ArrayList<InlineRun>()
        val plain = StringBuilder()
        fun emitPlain() {
            if (plain.isNotEmpty()) {
                runs += style.copy(text = plain.toString())
                plain.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // A backslash keeps the next character literal.
            if (c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
                plain.append(text[i + 1])
                i += 2
                continue
            }
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    emitPlain()
                    runs += style.copy(text = text.substring(i + 1, end), code = true)
                    i = end + 1
                    continue
                }
            }
            if (c == '[') {
                val close = text.indexOf("](", i + 1)
                val end = if (close > i) text.indexOf(')', close + 2) else -1
                if (close > i + 1 && end > close + 2) {
                    emitPlain()
                    runs += parseInline(text.substring(i + 1, close), style.copy(url = text.substring(close + 2, end).trim()))
                    i = end + 1
                    continue
                }
            }
            val pair = when {
                text.startsWith("**", i) -> "**"
                text.startsWith("__", i) && opensWord(text, i) -> "__"
                text.startsWith("~~", i) -> "~~"
                c == '*' -> "*"
                c == '_' && opensWord(text, i) -> "_"
                else -> null
            }
            if (pair != null) {
                val end = closing(text, i + pair.length, pair)
                if (end > i + pair.length) {
                    emitPlain()
                    val inner = text.substring(i + pair.length, end)
                    val nested = when (pair) {
                        "**", "__" -> style.copy(bold = true)
                        "~~" -> style.copy(strike = true)
                        else -> style.copy(italic = true)
                    }
                    runs += parseInline(inner, nested)
                    i = end + pair.length
                    continue
                }
            }
            plain.append(c)
            i++
        }
        emitPlain()
        return runs
    }

    /** Underscores only emphasise at a word boundary, so snake_case names stay as written. */
    private fun opensWord(text: String, at: Int): Boolean = at == 0 || !text[at - 1].isLetterOrDigit()

    /** Where [marker] closes, starting at [from]: not right after whitespace, and not part of a longer run. */
    private fun closing(text: String, from: Int, marker: String): Int {
        if (from >= text.length || text[from].isWhitespace()) return -1
        var at = text.indexOf(marker, from)
        while (at >= 0) {
            val longer = marker.length == 1 && at + 1 < text.length && text[at + 1] == marker[0]
            val afterSpace = text[at - 1].isWhitespace()
            val wordContinues = marker[0] == '_' && at + marker.length < text.length && text[at + marker.length].isLetterOrDigit()
            if (!longer && !afterSpace && !wordContinues) return at
            at = text.indexOf(marker, at + if (longer) 2 else 1)
        }
        return -1
    }

    /** Joins neighbouring runs of the same style, so the text is drawn in as few spans as possible. */
    private fun merge(runs: List<InlineRun>): List<InlineRun> {
        val merged = ArrayList<InlineRun>()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = "") == run.copy(text = "")) {
                merged[merged.lastIndex] = last.copy(text = last.text + run.text)
            } else {
                merged += run
            }
        }
        return merged
    }

    /** The text without any formatting, for copying a block or reading it aloud. */
    fun plainText(runs: List<InlineRun>): String = runs.joinToString("") { it.text }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"
}
