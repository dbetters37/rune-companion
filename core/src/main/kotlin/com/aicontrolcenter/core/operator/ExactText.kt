package com.aicontrolcenter.core.operator

/**
 * The one rule for a text this app hands to another program (a shell command, the folder it
 * runs in, a command for Termux): the program must get it as the text the owner is shown.
 *
 * A program gets its text as UTF-8 bytes, and the text ends for it at the first NUL. Text in
 * this app is UTF-16, where a character outside the basic plane is a pair of two units
 * (surrogates). Half of such a pair alone, which a model can send as `\ud800` in its JSON, is
 * no character: Java encodes a question mark in its place without a word, and a shell reads
 * that question mark as a pattern for any one character, so `rm keep<half>` would remove
 * `keep1` and `keepX`.
 *
 * The rule is asked in three places, and it is the same rule in each. The tools ask [problem]
 * while a call is assessed, so such a text is refused before anyone is asked. The runners ask it
 * again, as the last check before a process gets the text. And [PreviewText.exact] shows the
 * same characters by their code ("⟨U+D800⟩") wherever a text is quoted for the owner. A refusal
 * names the character by its code too, never as it is.
 */
object ExactText {
    /**
     * Why a program would not get [text] as it is, in the words every refusal uses after
     * "contains", or null when it would get exactly this text.
     */
    fun problem(text: String): String? {
        val at = firstNotExact(text)
        if (at < 0 && survivesUtf8(text)) return null
        val which = when {
            at < 0 -> ""
            text[at] == NUL -> " (U+0000, which ends a text for a program)"
            else -> " (U+%04X, half of a two-part character)".format(text[at].code)
        }
        return "a character that cannot be passed on to a program as it is$which: the program would get a different text than the one the owner is shown"
    }

    /** Where the first NUL or surrogate without its partner stands in [text], or -1. */
    fun firstNotExact(text: String): Int {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                i += 2
                continue
            }
            if (c == NUL || c.isSurrogate()) return i
            i++
        }
        return -1
    }

    /** The same question asked of the encoder itself, whatever else it may not carry over unchanged. */
    private fun survivesUtf8(text: String): Boolean = String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == text

    private const val NUL = '\u0000'
}
