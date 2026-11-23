package com.aicontrolcenter.core.operator

/**
 * Makes model-supplied text exact on an approval screen: nothing in it can hide, reorder or
 * push away the rest. Tools pass every model-chosen value (a command, URL, file name, message
 * body, typed text) through [exact] before putting it in an assessment's summary or details.
 */
object PreviewText {
    /** Shown for a line break, followed by the real break. */
    const val NEWLINE = "⏎"
    const val TAB = "⇥"
    const val SPACE = "␣"

    /** Runs of spaces or line breaks longer than this are shown as one marker with a count. */
    const val MAX_RUN = 3

    /**
     * [text] with every character that could mislead made visible: line breaks as [NEWLINE]
     * (runs over [MAX_RUN] as "⏎×N"), tabs as [TAB], runs of more than [MAX_RUN] spaces as
     * "␣×N", spaces at the very start or end as [SPACE], and control, invisible, direction-changing
     * and unusual space characters as "⟨U+XXXX⟩". Everything else is unchanged.
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
                    out.append("⟨U+").append(String.format("%04X", cp)).append('⟩')
                    i++
                }
                else -> {
                    out.appendCodePoint(cp)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Invisible or direction-changing: format characters, zero-width characters, fillers and tag characters. */
    fun isHidden(codePoint: Int): Boolean =
        codePoint == 0x00AD || codePoint == 0x034F || codePoint == 0x061C || codePoint == 0x115F || codePoint == 0x1160 ||
            codePoint == 0x17B4 || codePoint == 0x17B5 || codePoint == 0x180E || codePoint in 0x200B..0x200F ||
            codePoint in 0x202A..0x202E || codePoint in 0x2060..0x206F || codePoint == 0x3164 || codePoint == 0xFEFF ||
            codePoint == 0xFFA0 || codePoint in 0xFFF9..0xFFFB || codePoint in 0xE0000..0xE007F

    /** [isHidden] for one UTF-16 unit. U+DB40, the high surrogate of the tag characters, counts too. */
    fun isHidden(c: Char): Boolean = isHidden(c.code) || c.code == 0xDB40

    /** A space or line separator other than the plain space: no-break, ideographic, em and similar spaces. */
    fun isUnusualSpace(codePoint: Int): Boolean =
        codePoint != ' '.code && (Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt() ||
            codePoint == 0x2028 || codePoint == 0x2029)

    private fun isControl(codePoint: Int): Boolean = Character.isISOControl(codePoint)
}
