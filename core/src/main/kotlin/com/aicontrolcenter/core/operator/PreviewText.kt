package com.aicontrolcenter.core.operator

import java.text.Normalizer

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
     * direction-changing and unusual space characters as "⟨U+XXXX⟩". So is a surrogate without
     * its partner ("⟨U+D800⟩"): it is no character, it draws as a blank box, and what is made
     * of the text later (a command handed to a shell) gets a question mark in its place.
     * Combining marks that start
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
                isControl(cp) || isHidden(cp) || isUnusualSpace(cp) || isLoneSurrogate(cp) -> {
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
     * written as [QUOTE] and every character that looks like a quote mark as "⟨U+XXXX⟩"
     * ([looksLikeQuote]: typographic quotes and apostrophes, primes, accents and modifier
     * letters that stand alone, full-width forms and the like): the only plain `"` left is the
     * one that ends the value, so nothing in it can pretend the value ended earlier. Two or more
     * apostrophes, backticks or primes in a row draw like a double quote, so each of them is
     * written as "⟨U+XXXX⟩" too, also when combining marks stand between them (the marks are
     * written the same way); one plain apostrophe or backtick alone ("don't", "`x`") is left
     * as it is. A combining mark on punctuation, or on what is written as a marker, draws strokes
     * beside or above it that can pass for a quote mark, so it is written as "⟨U+XXXX⟩"; on a
     * letter or a digit it stays. A half-width voiced or semi-voiced sound mark (U+FF9E, U+FF9F)
     * stays after the half-width katakana letter it belongs to and is written as "⟨U+XXXX⟩"
     * anywhere else.
     *
     * These marks are help for the reader. What they guard is the look of the value; the value
     * itself is shown exactly either way.
     */
    fun quoted(text: String): String {
        val cps = exact(text).codePoints().toArray()
        val out = StringBuilder(cps.size + 8).append('"')
        // Whether a combining mark that came next would sit on punctuation or on a marker.
        var onPunctuation = false
        var i = 0
        while (i < cps.size) {
            val cp = cps[i]
            when {
                cp == '"'.code -> {
                    out.append(QUOTE)
                    onPunctuation = true
                    i++
                }
                cp in SINGLE_QUOTES -> {
                    // The run: this one and every further one that follows it directly or after combining marks only.
                    var quotes = 1
                    var end = i + 1
                    while (true) {
                        var next = end
                        while (next < cps.size && isCombiningMark(cps[next])) next++
                        if (next < cps.size && cps[next] in SINGLE_QUOTES) {
                            quotes++
                            end = next + 1
                        } else {
                            break
                        }
                    }
                    if (quotes == 1 && !looksLikeQuote(cp)) out.appendCodePoint(cp) else for (k in i until end) mark(out, cps[k])
                    onPunctuation = true
                    i = end
                }
                looksLikeQuote(cp) || isStraySoundMark(cps, i) -> {
                    mark(out, cp)
                    onPunctuation = true
                    i++
                }
                onPunctuation && isCombiningMark(cp) && !isVariationSelector(cp) && !isKeycap(cps, i) -> {
                    mark(out, cp)
                    i++
                }
                else -> {
                    out.appendCodePoint(cp)
                    // A mark, or an emoji selector, leaves what it sits on as it was.
                    if (!isCombiningMark(cp)) onPunctuation = isPunctuation(cp)
                    i++
                }
            }
        }
        return out.append('"').toString()
    }

    /**
     * Whether [codePoint], inside a quoted value, could pass for a quote mark or for half of one,
     * by itself: every opening or closing quote of any script (Unicode categories Pi and Pf: “ ”
     * ‘ ’ « »), the spacing modifier letters (U+02B0..U+02FF: ʹ ʺ ʼ ˈ ˊ ˝ and the rest), every
     * accent or modifier that stands alone (category Sk: ¨ ´ ῾ ＾, but not `^`, the backtick or
     * the emoji skin tones), every character that is a quote, an apostrophe, a backtick or an
     * acute accent in a compatibility form (full-width ＂ ＇ ｀, the Greek oxia and varia), and
     * the characters of [QUOTE_LOOKALIKES]. The plain `"`, `'` and backtick are not answered
     * here: [quoted] has its own rules for them. Letters and marks of other scripts are left
     * alone (the Japanese long-vowel mark, Greek and Hebrew letters).
     */
    fun looksLikeQuote(codePoint: Int): Boolean {
        if (codePoint < 0x80) return false
        if (codePoint in QUOTE_LOOKALIKES || codePoint in 0x02B0..0x02FF) return true
        when (Character.getType(codePoint)) {
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt() -> return true
            Character.MODIFIER_SYMBOL.toInt() -> if (codePoint !in 0x1F3FB..0x1F3FF) return true
        }
        val text = String(Character.toChars(codePoint))
        return NORMAL_FORMS.any { form -> !Normalizer.isNormalized(text, form) && Normalizer.normalize(text, form).any { it in QUOTE_CHARS } }
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
     * Characters that look like a plain double quote, or like half of one, and are named one by
     * one; [quoted] marks each of them wherever it stands: the typographic quotes (“ ” „ ‟), the
     * full-width quote and backtick, double, triple and quadruple primes, the diaeresis and the
     * double acute accent, the heavy ornamental quotes (also the comma-shaped ones, U+1F676 to
     * U+1F678), the East Asian double prime quotes and the ditto mark, the Hebrew geresh and
     * gershayim, the Greek numeral sign, oxia and varia, the modifier primes and saltillos, and
     * the Vedic sign nihshvasa (U+1CD3), which draws as two strokes.
     */
    private val QUOTE_LOOKALIKES: Set<Int> = setOf(
        0xFF02, 0x2033, 0x02BA, 0x05F4, 0x3003, 0x02EE, 0x201F, 0x2036,
        0x201C, 0x201D, 0x201E, 0x02DD, 0x275D, 0x275E, 0x301D, 0x301E, 0x301F, 0x2E42, 0x02F5, 0x02F6,
        0x00A8, 0x2034, 0x2037, 0x2057, 0x05F3, 0xA78B, 0xA78C, 0x275B, 0x275C, 0xFF40, 0x1FEF, 0x1FFD, 0x0374, 0x02B9,
        0x1F676, 0x1F677, 0x1F678, 0x1CD3,
    )

    /**
     * The apostrophe and the characters that look like it, among them the apostrophes of other
     * scripts (Armenian U+055A, the NKo tone apostrophes U+07F4 and U+07F5); two in a row look
     * like a double quote ([quoted]). Those that [looksLikeQuote] answers for are marked when
     * they stand alone too.
     */
    private val SINGLE_QUOTES: Set<Int> = setOf(
        0x0027, 0x2018, 0x2019, 0x201A, 0x201B, 0x0060, 0x00B4, 0x2032, 0x2035, 0x02B9, 0x02BB, 0x02BC, 0x02BD, 0xFF07,
        0x055A, 0x07F4, 0x07F5,
    )

    /** What a quote look-alike turns into when its compatibility form is written out: `"`, `'`, the backtick and the acute accent. */
    private const val QUOTE_CHARS = "\"'`\u00B4"

    private val NORMAL_FORMS = listOf(Normalizer.Form.NFKC, Normalizer.Form.NFKD)

    private fun isControl(codePoint: Int): Boolean = Character.isISOControl(codePoint)

    /** A surrogate that reaches here as a code point of its own has no partner beside it: a pair is read as one code point. */
    private fun isLoneSurrogate(codePoint: Int): Boolean = codePoint in 0xD800..0xDFFF

    private fun isVariationSelector(cp: Int): Boolean = cp == 0xFE0E || cp == 0xFE0F

    /** Punctuation of any script (the Unicode categories that start with P): a mark on it is no accent of ordinary writing. */
    private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        -> true
        else -> false
    }

    /** The enclosing keycap (U+20E3) at [i] on `#` or `*`, with or without the emoji selector between: a keycap emoji. */
    private fun isKeycap(cps: IntArray, i: Int): Boolean {
        if (cps[i] != 0x20E3 || i == 0) return false
        val base = if (cps[i - 1] == 0xFE0F && i >= 2) cps[i - 2] else cps[i - 1]
        return base == '#'.code || base == '*'.code
    }

    /**
     * A half-width voiced or semi-voiced sound mark (U+FF9E, U+FF9F) at [i] that does not follow a
     * half-width katakana letter (U+FF66..U+FF9D without the long-vowel mark U+FF70). After such a
     * letter it is that letter's mark (ｶﾞ, ﾊﾟ); anywhere else it stands alone, two short strokes
     * or a ring at the height of a quote mark.
     */
    private fun isStraySoundMark(cps: IntArray, i: Int): Boolean {
        if (cps[i] != 0xFF9E && cps[i] != 0xFF9F) return false
        val before = if (i == 0) -1 else cps[i - 1]
        return !(before in 0xFF66..0xFF9D && before != 0xFF70)
    }

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
