package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Recognises a tool call that a model copied out of quoted content instead of deciding on it.
 *
 * In the prompted protocol a call is text, so a model that repeats what a tool returned can
 * repeat a call written into it, and the tool's output would choose an action. Defusing the
 * markup in tool output ([PromptedToolsFormat.quote]) does not hold on its own: small models
 * drop invisible characters, decode entities and re-wrap lines when they quote. So this is the
 * primary defence: a parsed call whose text, normalised, appears inside any tool result of the
 * request, or inside what an earlier call quoted in its arguments, is not run. It is passed on
 * with [REJECTION] as its arguments error, which the tool executor answers with an explanation.
 *
 * Normalisation makes the comparison blind to what a model changes when it quotes: HTML
 * entities and JSON escapes are decoded (repeatedly, for text quoted twice), invisible
 * characters (zero-width, format and control characters, variation selectors, fillers) are
 * removed, the typographic quotes the parser reads as quotes become plain ones, and every line
 * break and other whitespace is collapsed away, so that re-spacing JSON hides nothing either.
 *
 * An earlier call itself is not quoted content: the model wrote it, and making the same call
 * again is legitimate. Only what its arguments carry (string values, nested objects) is.
 */
internal class EchoGuard private constructor(private val quoted: List<String>) {

    /** True when any of [texts], the forms a call was written as, appears in the quoted content. */
    fun repeats(texts: List<String>): Boolean = texts.any { text ->
        val needle = normalise(text)
        needle.isNotEmpty() && quoted.any { needle in it }
    }

    companion object {
        /** The arguments error of a call that repeats quoted content. The tool executor answers it; it never runs. */
        const val REJECTION = "the call repeats content from a tool result"

        private const val MAX_DECODE_PASSES = 4

        /** HTML entities (named, decimal, hex) and JSON string escapes. */
        private val ESCAPE = Regex(
            "&(?:#([0-9]{1,7})|#[xX]([0-9a-fA-F]{1,6})|([A-Za-z][A-Za-z0-9]{1,31}));?|\\\\u([0-9a-fA-F]{4})|\\\\([\"'\\\\/bfnrt])",
        )

        private val NAMED = mapOf(
            "lt" to "<", "LT" to "<", "gt" to ">", "GT" to ">", "amp" to "&", "AMP" to "&", "quot" to "\"", "QUOT" to "\"",
            "apos" to "'", "nbsp" to " ", "lsqb" to "[", "lbrack" to "[", "rsqb" to "]", "rbrack" to "]",
            "lcub" to "{", "lbrace" to "{", "rcub" to "}", "rbrace" to "}", "lpar" to "(", "rpar" to ")",
            "grave" to "`", "DiacriticalGrave" to "`", "colon" to ":", "comma" to ",", "period" to ".", "sol" to "/",
            "bsol" to "\\", "equals" to "=", "excl" to "!", "quest" to "?", "num" to "#", "dollar" to "$",
            "percnt" to "%", "ast" to "*", "midast" to "*", "plus" to "+", "semi" to ";", "verbar" to "|",
            "vert" to "|", "VerticalLine" to "|", "lowbar" to "_", "UnderBar" to "_", "Tab" to "\t", "NewLine" to "\n",
            "ZeroWidthSpace" to "​", "zwj" to "‍", "zwnj" to "‌", "lrm" to "‎", "rlm" to "‏",
            "shy" to "­", "ensp" to " ", "emsp" to " ", "thinsp" to " ", "hairsp" to " ",
            "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        )

        private val JSON_ESCAPES = mapOf("b" to "\b", "f" to "\u000C", "n" to "\n", "r" to "\r", "t" to "\t")

        /** What the model was shown as data in [request]: every tool result, and what earlier calls quoted in their arguments. */
        fun of(request: ChatRequest): EchoGuard {
            val quoted = ArrayList<String>()
            for (message in request.messages) {
                message.toolResult?.let { quoted += normalise(it.content) }
                if (message.role == ChatRole.TOOL && message.text.isNotBlank()) quoted += normalise(message.text)
                for (call in message.toolCalls) call.arguments.values.forEach { collect(it, quoted) }
            }
            return EchoGuard(quoted.filter { it.isNotEmpty() })
        }

        private fun collect(element: JsonElement, into: MutableList<String>) {
            when (element) {
                is JsonPrimitive -> if (element.isString) into += normalise(element.content)
                is JsonObject -> {
                    into += normalise(element.toString())
                    element.values.forEach { collect(it, into) }
                }
                is JsonArray -> {
                    into += normalise(element.toString())
                    element.forEach { collect(it, into) }
                }
            }
        }

        /** [text] as compared for echoes; see the class comment. */
        fun normalise(text: String): String {
            var decoded = text
            for (pass in 1..MAX_DECODE_PASSES) {
                val next = ESCAPE.replace(decoded, ::decode)
                if (next == decoded) break
                decoded = next
            }
            val out = StringBuilder(decoded.length)
            var i = 0
            while (i < decoded.length) {
                val cp = decoded.codePointAt(i)
                i += Character.charCount(cp)
                if (!dropped(cp)) out.appendCodePoint(plainQuote(cp))
            }
            return out.toString()
        }

        private fun decode(match: MatchResult): String {
            val (decimal, hex, name, unicode, json) = match.destructured
            val codePoint = when {
                decimal.isNotEmpty() -> decimal.toInt()
                hex.isNotEmpty() -> hex.toInt(16)
                unicode.isNotEmpty() -> unicode.toInt(16)
                else -> return when {
                    name.isNotEmpty() -> NAMED[name] ?: match.value
                    else -> JSON_ESCAPES[json] ?: json
                }
            }
            // A lone surrogate from a JSON escape is kept: its pair follows as another escape and joins it.
            if (codePoint in 0xD800..0xDFFF) return if (unicode.isNotEmpty()) codePoint.toChar().toString() else match.value
            return if (Character.isValidCodePoint(codePoint)) String(Character.toChars(codePoint)) else match.value
        }

        /** Whitespace of every kind (line breaks included), control and format characters, and other characters that show nothing. */
        private fun dropped(cp: Int): Boolean {
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return true
            val type = Character.getType(cp)
            if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) return true
            return cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x180B..0x180F ||
                cp == 0x034F || cp == 0x115F || cp == 0x1160 || cp == 0x17B4 || cp == 0x17B5 || cp == 0x3164 || cp == 0xFFA0
        }

        /** The typographic quotes [LenientJson] reads as quotes, as the plain ones. */
        private fun plainQuote(cp: Int): Int = when (cp) {
            0x201C, 0x201D -> '"'.code
            0x2018, 0x2019 -> '\''.code
            else -> cp
        }
    }
}
