package com.aicontrolcenter.framework.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads JSON the way small language models write it: single or typographic quotes, unquoted
 * keys, trailing or missing commas, Python literals, raw line breaks inside strings and, when
 * allowed, brackets that were never closed. Strict JSON reads the same as with a strict
 * parser. It never guesses beyond that: anything else is "not JSON" and yields null.
 */
internal class LenientJson private constructor(
    private val src: String,
    private var pos: Int,
    private val allowUnclosed: Boolean,
) {
    /** A value and the index just past it. */
    class Read(val element: JsonElement, val end: Int)

    private class Malformed : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun fail(): Nothing = throw Malformed()

    private fun skipSpace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun value(depth: Int): JsonElement {
        if (depth > MAX_DEPTH) fail()
        skipSpace()
        if (pos >= src.length) fail()
        val c = src[pos]
        return when {
            c == '{' -> obj(depth)
            c == '[' -> array(depth)
            c in OPENING_QUOTES -> JsonPrimitive(string())
            c == '-' || c.isDigit() -> number()
            else -> word()
        }
    }

    private fun obj(depth: Int): JsonObject {
        pos++
        val members = LinkedHashMap<String, JsonElement>()
        while (true) {
            skipSeparators()
            if (pos >= src.length) {
                if (allowUnclosed) return JsonObject(members) else fail()
            }
            if (src[pos] == '}') {
                pos++
                return JsonObject(members)
            }
            val key = if (src[pos] in OPENING_QUOTES) string() else bareKey()
            skipSpace()
            if (pos >= src.length || (src[pos] != ':' && src[pos] != '=')) fail()
            pos++
            members[key] = value(depth + 1)
        }
    }

    private fun array(depth: Int): JsonArray {
        pos++
        val items = ArrayList<JsonElement>()
        while (true) {
            skipSeparators()
            if (pos >= src.length) {
                if (allowUnclosed) return JsonArray(items) else fail()
            }
            if (src[pos] == ']') {
                pos++
                return JsonArray(items)
            }
            items += value(depth + 1)
        }
    }

    /** Whitespace and commas between members: tolerates trailing, doubled and missing commas alike. */
    private fun skipSeparators() {
        while (pos < src.length && (src[pos].isWhitespace() || src[pos] == ',')) pos++
    }

    private fun bareKey(): String {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos] == '-' || src[pos] == '.')) pos++
        if (pos == start) fail()
        return src.substring(start, pos)
    }

    private fun string(): String {
        val closers = closersFor(src[pos])
        pos++
        val out = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            when {
                c in closers -> return out.toString()
                c == '\\' -> escape(out)
                else -> out.append(c)
            }
        }
        if (allowUnclosed) return out.toString()
        fail()
    }

    private fun escape(out: StringBuilder) {
        if (pos >= src.length) {
            if (allowUnclosed) return else fail()
        }
        when (val e = src[pos++]) {
            'n' -> out.append('\n')
            't' -> out.append('\t')
            'r' -> out.append('\r')
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'u' -> {
                val code = if (pos + 4 <= src.length) src.substring(pos, pos + 4).toIntOrNull(16) else null
                if (code == null) fail()
                out.append(code.toChar())
                pos += 4
            }
            // Covers \" \' \\ \/ and any stray backslash before an ordinary character.
            else -> out.append(e)
        }
    }

    private fun number(): JsonPrimitive {
        val start = pos
        if (src[pos] == '-') pos++
        while (pos < src.length && (src[pos].isDigit() || src[pos] in ".eE+-")) pos++
        val text = src.substring(start, pos)
        text.toLongOrNull()?.let { return JsonPrimitive(it) }
        val real = text.toDoubleOrNull()
        if (real == null || real.isNaN() || real.isInfinite()) fail()
        return JsonPrimitive(real)
    }

    /** true/false/null in JSON or Python spelling; any other bare word is read as a string. */
    private fun word(): JsonElement {
        val text = bareKey()
        return when (text) {
            "true", "True" -> JsonPrimitive(true)
            "false", "False" -> JsonPrimitive(false)
            "null", "None" -> JsonNull
            else -> JsonPrimitive(text)
        }
    }

    companion object {
        private const val MAX_DEPTH = 48
        private const val OPENING_QUOTES = "\"'\u201C\u201D\u2018\u2019"

        private fun closersFor(open: Char): String = when (open) {
            '"' -> "\""
            '\'' -> "'"
            '\u201C', '\u201D' -> "\u201D\u201C\""
            else -> "\u2019\u2018'"
        }

        /**
         * Reads the object or array that starts at [start]. With [allowUnclosed], input that
         * ends inside a string, object or array is closed where it ends; use that only for text
         * known to be complete, never for output that was cut off.
         */
        fun readAt(text: String, start: Int, allowUnclosed: Boolean): Read? {
            if (start !in text.indices || (text[start] != '{' && text[start] != '[')) return null
            val reader = LenientJson(text, start, allowUnclosed)
            return try {
                Read(reader.value(0), reader.pos)
            } catch (e: Malformed) {
                null
            }
        }
    }
}
