package com.aicontrolcenter.core.operator

/**
 * Makes model-supplied text exact on an approval screen: nothing in it can hide, reorder or
 * push away the rest. Tools pass every model-chosen value (a command, URL, file name, message
 * body, typed text) through [exact] before putting it in an assessment's summary or details,
 * and through [quoted] where the value is shown between double quotes.
 */
object PreviewText {
    /** Shown for a line break, followed by the real break. */
    const val NEWLINE = "⏎"
    const val TAB = "⇥"
    const val SPACE = "␣"

    /** Shown for a double quote inside a [quoted] value, so it cannot pass for the quote that ends the value. */
    const val QUOTE = "⟨\"⟩"

    /** Runs of spaces or line breaks longer than this are shown as one marker with a count. */
    const val MAX_RUN = 3

    /** Runs of any other one character longer than this are shown as the character and "⟨×N⟩". */
    const val MAX_SAME_RUN = 20

    /** Combining marks kept on one character; more are counted, not drawn, so they cannot pile up over other lines. */
    const val MAX_MARKS = 4

    /**
     * [text] with every character that could mislead made visible: line breaks as [NEWLINE]
     * (runs over [MAX_RUN] as "⏎×N"), tabs as [TAB], runs of more than [MAX_RUN] spaces as
     * "␣×N", spaces at the very start or end as [SPACE], and control, invisible, format,
     * direction-changing and unusual space characters as "⟨U+XXXX⟩". Combining marks that start
     * the text or follow a space (they would draw over what is before the value) are written the
     * same way, and more than [MAX_MARKS] on one character are counted ("⟨U+0336⟩×56" or
     * "⟨56 more combining marks⟩"). A variation selector is kept only right after a symbol
     * it can belong to (an emoji). Runs of more than [MAX_RUN] of one marked character are
     * counted ("⟨U+200B⟩×500"), and a run of more than [MAX_SAME_RUN] of any other one
     * character is shown once with its count ("=⟨×80⟩"). Everything else is unchanged.
     */
    fun exact(text: String): String {
        val out = StringBuilder(text.length + 16)
        val cps = text.codePoints().toArray()
        val firstNonSpace = cps.indexOfFirst { it != ' '.code }.let { if (it < 0) cps.size else it }
        val lastNonSpace = cps.indexOfLast { it != ' '.code }
        var i = 0
        while (i < cps.size) {
            val cp = cps[i]
            var run = 1
            while (i + run < cps.size && cps[i + run] == cp) run++
            when {
                cp == '\n'.code -> {
                    if (run > MAX_RUN) out.append(NEWLINE).append('×').append(run).append('\n') else repeat(run) { out.append(NEWLINE).append('\n') }
                    i += run
                }
                cp == ' '.code -> {
                    val edge = i < firstNonSpace || i > lastNonSpace
                    when {
                        run > MAX_RUN -> out.append(SPACE).append('×').append(run)
                        edge -> repeat(run) { out.append(SPACE) }
                        else -> repeat(run) { out.append(' ') }
                    }
                    i += run
                }
                cp == '\t'.code -> {
                    out.append(TAB)
                    i++
                }
                isControl(cp) || isHidden(cp) || isUnusualSpace(cp) -> {
                    // A long run is counted, not repeated: "⟨U+200B⟩×500".
                    mark(out, cp)
                    if (run > MAX_RUN) out.append('×').append(run) else repeat(run - 1) { mark(out, cp) }
                    i += run
                }
                isVariationSelector(cp) && !belongsTo(cps, i) -> {
                    mark(out, cp)
                    i++
                }
                isCombiningMark(cp) -> {
                    // A mark with no letter of the value before it would draw over the quote or text before the value.
                    if (i == 0 || !carriesMarks(cps[i - 1])) {
                        mark(out, cp)
                        i++
                    } else {
                        var marks = 0
                        while (i + marks < cps.size && isCombiningMark(cps[i + marks])) marks++
                        for (k in 0 until minOf(marks, MAX_MARKS)) out.appendCodePoint(cps[i + k])
                        if (marks > MAX_MARKS) {
                            val extra = cps.copyOfRange(i + MAX_MARKS, i + marks)
                            if (extra.all { it == extra[0] }) mark(out, extra[0]).also { out.append('×').append(extra.size) }
                            else out.append('⟨').append(extra.size).append(" more combining marks⟩")
                        }
                        i += marks
                    }
                }
                run > MAX_SAME_RUN -> {
                    out.appendCodePoint(cp).append("⟨×").append(run).append('⟩')
                    i += run
                }
                else -> {
                    out.appendCodePoint(cp)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * [text] as a value between double quotes, made [exact], with every double quote inside it
     * written as [QUOTE] and characters that look like one as "⟨U+XXXX⟩" (typographic and
     * full-width quotes, double primes and the like, [QUOTE_LOOKALIKES]): the only plain `"`
     * left is the one that ends the value, so nothing in it can pretend the value ended earlier.
     * Two or more apostrophes or single quotes in a row draw like a double quote, so each of them
     * is written as "⟨U+XXXX⟩" too; one alone ("don't", "it’s") is left as it is.
     */
    fun quoted(text: String): String {
        val cps = exact(text).codePoints().toArray()
        val out = StringBuilder(cps.size + 8).append('"')
        var i = 0
        while (i < cps.size) {
            val cp = cps[i]
            when {
                cp == '"'.code -> {
                    out.append(QUOTE)
                    i++
                }
                cp in QUOTE_LOOKALIKES -> {
                    mark(out, cp)
                    i++
                }
                cp in SINGLE_QUOTES -> {
                    var run = 1
                    while (i + run < cps.size && cps[i + run] in SINGLE_QUOTES) run++
                    if (run == 1) out.appendCodePoint(cp) else for (k in 0 until run) mark(out, cps[i + k])
                    i += run
                }
                else -> {
                    out.appendCodePoint(cp)
                    i++
                }
            }
        }
        return out.append('"').toString()
    }

    /**
     * Invisible or direction-changing: every format character (Unicode category Cf: zero-width
     * and direction controls, tag characters, soft hyphen and the like), fillers and blanks that
     * draw nothing (Hangul fillers, the braille blank), the combining grapheme joiner and
     * variation selectors other than the emoji ones (U+FE0E and U+FE0F, which [exact] judges by
     * what is before them).
     */
    fun isHidden(codePoint: Int): Boolean =
        Character.getType(codePoint) == Character.FORMAT.toInt() ||
            codePoint == 0x00AD || codePoint == 0x034F || codePoint == 0x061C || codePoint == 0x115F || codePoint == 0x1160 ||
            codePoint == 0x17B4 || codePoint == 0x17B5 || codePoint == 0x180E || codePoint in 0x200B..0x200F ||
            codePoint in 0x202A..0x202E || codePoint in 0x2060..0x206F || codePoint == 0x2800 || codePoint == 0x3164 || codePoint == 0xFEFF ||
            codePoint == 0xFFA0 || codePoint in 0xFFF9..0xFFFB || codePoint in 0xE0000..0xE007F ||
            codePoint in 0x180B..0x180D || codePoint == 0x180F || codePoint in 0xFE00..0xFE0D || codePoint in 0xE0100..0xE01EF

    /** [isHidden] for one UTF-16 unit. U+DB40, the high surrogate of the tag characters and supplementary variation selectors, counts too. */
    fun isHidden(c: Char): Boolean = isHidden(c.code) || c.code == 0xDB40

    /** A space or line separator other than the plain space: no-break, ideographic, em and similar spaces. */
    fun isUnusualSpace(codePoint: Int): Boolean =
        codePoint != ' '.code && (Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt() ||
            codePoint == 0x2028 || codePoint == 0x2029)

    /**
     * Characters that look like a plain double quote; [quoted] marks them: the typographic quotes
     * (“ ” „ ‟), the full-width quote, double primes, the double acute accent, the heavy
     * ornamental quotes and the East Asian double prime quotes.
     */
    private val QUOTE_LOOKALIKES: Set<Int> = setOf(
        0xFF02, 0x2033, 0x02BA, 0x05F4, 0x3003, 0x02EE, 0x201F, 0x2036,
        0x201C, 0x201D, 0x201E, 0x02DD, 0x275D, 0x275E, 0x301D, 0x301E, 0x301F, 0x2E42, 0x02F5, 0x02F6,
    )

    /** The apostrophe and the characters that look like it; two in a row look like a double quote ([quoted]). */
    private val SINGLE_QUOTES: Set<Int> = setOf(
        0x0027, 0x2018, 0x2019, 0x201A, 0x201B, 0x0060, 0x00B4, 0x2032, 0x2035, 0x02B9, 0x02BB, 0x02BC, 0x02BD, 0xFF07,
    )

    private fun isControl(codePoint: Int): Boolean = Character.isISOControl(codePoint)

    private fun isVariationSelector(cp: Int): Boolean = cp == 0xFE0E || cp == 0xFE0F

    private fun isCombiningMark(cp: Int): Boolean = Character.getType(cp).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() }

    /** Whether marks after [cp] belong to a character of the value: not after a space, a line break, a control or a mark marker. */
    private fun carriesMarks(cp: Int): Boolean = !Character.isWhitespace(cp) && !isControl(cp) && !isHidden(cp) && !isUnusualSpace(cp)

    /** An emoji variation selector belongs to a symbol right before it (or to a keycap digit followed by U+20E3). */
    private fun belongsTo(cps: IntArray, i: Int): Boolean {
        if (i == 0) return false
        val base = cps[i - 1]
        val symbol = Character.getType(base) == Character.OTHER_SYMBOL.toInt() || Character.getType(base) == Character.MATH_SYMBOL.toInt() ||
            base in 0x2000..0x2BFF || base >= 0x1F000
        val keycap = (base in '0'.code..'9'.code || base == '#'.code || base == '*'.code) && cps.getOrNull(i + 1) == 0x20E3
        return symbol || keycap
    }

    private fun mark(out: StringBuilder, cp: Int) {
        out.append("⟨U+").append(hex(cp)).append('⟩')
    }

    private fun hex(cp: Int): String = String.format("%04X", cp)
}
