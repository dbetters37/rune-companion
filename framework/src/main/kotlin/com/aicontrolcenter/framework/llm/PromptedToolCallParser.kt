package com.aicontrolcenter.framework.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Finds tool calls in the text of a model that was told to write them in the format of
 * [PromptedToolsFormat], and that may have followed the instruction only roughly.
 *
 * Two levels of trust keep prose from being mistaken for a call:
 *  - An explicit marker (`<tool_call>`, `<function=name>`, `[TOOL_CALLS]`) is a call whatever
 *    tool it names; an unknown name is reported back to the model by the tool executor.
 *  - Unmarked JSON (a code fence, a bare object on its own line, "Action:" lines) is a call
 *    only when it names a tool that was actually offered.
 *
 * Whatever cannot be read as a call is left in the text exactly as written, with one
 * exception: a marked call that names a tool but whose arguments cannot be read becomes a call
 * with [Call.argumentsError] set, so the model is told what was wrong instead of its markup
 * becoming the answer.
 *
 * Nothing inside a `<tool_result>` block of the reply counts: that is the model repeating what
 * a tool returned, and tool output is data, never a call. A call repeated without those tags is
 * found as a call; [Parsed.written] gives the text it was written as, for [EchoGuard] to compare
 * with the request's tool output.
 */
internal object PromptedToolCallParser {
    data class Call(val name: String, val arguments: JsonObject, val argumentsError: String? = null)

    /** [text] is the reply without the call blocks that were understood. */
    data class Parsed(val text: String, val calls: List<Call>) {
        /**
         * For each of [calls], the texts of the reply it was written as: its whole block; for a
         * `<function=name>` or "Action:" call, its marker and arguments without anything that
         * closes them; and every JSON object in the block that reads as this very call. Not part
         * of equality.
         */
        var written: List<List<String>> = calls.map { emptyList() }
            private set

        constructor(text: String, calls: List<Call>, written: List<List<String>>) : this(text, calls) {
            require(written.size == calls.size)
            this.written = written
        }
    }

    /**
     * @param canonical for a call written as a marker followed by its arguments (a function tag,
     * an "Action:" line), the marker and the arguments as written, without a closing tag: an
     * echo that added one, or that ends elsewhere, still contains it.
     */
    private class Block(val start: Int, val end: Int, val calls: List<Call>, val canonical: String? = null)

    private val TAG_OPEN = Regex("<(tool_calls?|toolcall|function_calls?|tool_use)>", RegexOption.IGNORE_CASE)
    private val FUNCTION_TAG = Regex("<function=([A-Za-z0-9_.\\-]+)>", RegexOption.IGNORE_CASE)
    private val FUNCTION_CLOSE = Regex("\\s*</function>", RegexOption.IGNORE_CASE)
    private val FUNCTION_CLOSE_ANYWHERE = Regex("</function>", RegexOption.IGNORE_CASE)
    private val BRACKET_MARKER = Regex("\\[TOOL_CALLS]", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("```[A-Za-z_]*[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
    private val FENCE_LINE = Regex("```[A-Za-z_]*")
    private val LINE_START_JSON = Regex("(?m)^[ \\t]*([{\\[])")
    private val REACT = Regex("(?im)^[ \\t]*Action:[ \\t]*([A-Za-z0-9_.\\-]+)[ \\t]*\\r?\\n[ \\t]*Action Input:[ \\t]*")
    private val TOOL_NAME = Regex("[A-Za-z0-9_.\\-]{1,64}")
    private val BLANK_LINES = Regex("\\n{3,}")

    private val NAME_KEYS = listOf("name", "tool", "tool_name", "function", "function_name")

    /** Keys under which a call's name may sit one level down, in JSON too broken to read. */
    private val NESTING_KEYS = setOf("function", "tool_calls", "tool_call")
    private const val QUOTES = "\"'\u201C\u201D\u2018\u2019"
    private val RESULT_OPEN = Regex("<tool_results?\\b", RegexOption.IGNORE_CASE)
    private val RESULT_CLOSE = Regex("</tool_results?\\s*>", RegexOption.IGNORE_CASE)
    private const val NOT_JSON = "the arguments are not valid JSON"

    /** At most this many places in a block are tried as the start of a JSON object reading as a call. */
    private const val MAX_OBJECT_READS = 50_000
    private const val NOT_AN_OBJECT = "the arguments are not a JSON object"
    private val ARGUMENT_KEYS = listOf("arguments", "args", "parameters", "params", "input", "tool_input", "action_input")

    /**
     * @param truncated the reply was cut off at the output limit. A call that was still being
     * written is then not completed by guesswork: half an argument is worse than no call.
     */
    fun parse(reply: String, knownTools: Set<String>, truncated: Boolean = false): Parsed {
        // Same length and line structure as the reply, so blocks found in it are where they are in the reply.
        val source = withoutEchoedResults(reply)
        val blocks = marked(source, truncated)
            .ifEmpty { fenced(source, knownTools) }
            .ifEmpty { bare(source, knownTools) }
            .ifEmpty { reactStyle(source, knownTools) }
        if (blocks.isEmpty()) return Parsed(reply, emptyList())
        val text = StringBuilder()
        var from = 0
        for (block in blocks) {
            text.append(reply, from, block.start)
            from = block.end
        }
        text.append(reply, from, reply.length)
        val written = blocks.flatMap { block ->
            val blockText = source.substring(block.start, block.end)
            val objects = if (block.calls.any { it.argumentsError == null }) callObjects(blockText) else emptyMap()
            block.calls.map { call ->
                val own = if (call.argumentsError == null) objects[call].orEmpty() else emptyList()
                (listOf(blockText) + listOfNotNull(block.canonical) + own).distinct()
            }
        }
        return Parsed(BLANK_LINES.replace(text, "\n\n").trim(), blocks.flatMap { it.calls }, written)
    }

    /**
     * The JSON objects in [text] that read as a call, by the call they read as: each call's own
     * object inside an array, a wrapper or a block of several, as the model wrote it. Blocks of
     * any length are searched; only the number of places tried is bounded ([MAX_OBJECT_READS]),
     * and the block itself is always compared in full.
     */
    private fun callObjects(text: String): Map<Call, List<String>> {
        val found = HashMap<Call, MutableList<String>>()
        var at = text.indexOf('{')
        var reads = 0
        while (at >= 0 && reads++ < MAX_OBJECT_READS) {
            val read = LenientJson.readAt(text, at, allowUnclosed = false)
            val call = (read?.element as? JsonObject)?.let(::toCall)
            if (call != null) found.getOrPut(call) { ArrayList() } += text.substring(at, read.end)
            at = text.indexOf('{', at + 1)
        }
        return found
    }

    /**
     * [reply] with every `<tool_result>` block blanked out: from its opening tag to its closing
     * tag or, when it has none, to the end. Line breaks are kept.
     */
    private fun withoutEchoedResults(reply: String): String {
        var open = RESULT_OPEN.find(reply) ?: return reply
        val out = StringBuilder(reply)
        while (true) {
            val end = RESULT_CLOSE.find(reply, open.range.last + 1)?.range?.last?.plus(1) ?: reply.length
            for (i in open.range.first until end) if (out[i] != '\n') out[i] = ' '
            open = RESULT_OPEN.find(reply, end) ?: break
        }
        return out.toString()
    }

    private fun marked(reply: String, truncated: Boolean): List<Block> {
        val blocks = ArrayList<Block>()
        var from = 0
        while (true) {
            val next = listOfNotNull(TAG_OPEN.find(reply, from), FUNCTION_TAG.find(reply, from), BRACKET_MARKER.find(reply, from))
                .minByOrNull { it.range.first } ?: break
            val block = when {
                next.value.startsWith("[") -> afterBracketMarker(reply, next, truncated)
                next.value.contains('=') -> functionTag(reply, next, truncated)
                else -> tagged(reply, next, truncated)
            }
            if (block != null) blocks += block
            from = block?.end ?: (next.range.last + 1)
        }
        return blocks
    }

    private fun tagged(reply: String, open: MatchResult, truncated: Boolean): Block? {
        val bodyStart = open.range.last + 1
        val close = reply.indexOf("</" + open.groupValues[1] + ">", bodyStart, ignoreCase = true)
        if (close >= 0) {
            val calls = callsIn(reply.substring(bodyStart, close), allowUnclosed = true, marked = true) ?: return null
            return Block(open.range.first, close + open.groupValues[1].length + 3, calls)
        }
        // No closing tag: the call runs to the next opening tag or the end of the reply.
        if (truncated) return null
        val bodyEnd = TAG_OPEN.find(reply, bodyStart)?.range?.first ?: reply.length
        val calls = callsIn(reply.substring(bodyStart, bodyEnd), allowUnclosed = true, marked = true) ?: return null
        return Block(open.range.first, bodyEnd, calls)
    }

    private fun functionTag(reply: String, open: MatchResult, truncated: Boolean): Block? {
        val name = open.groupValues[1]
        var end = open.range.last + 1
        var arguments = JsonObject(emptyMap())
        var error: String? = null
        val jsonStart = indexOfNonSpace(reply, end)
        if (jsonStart >= 0 && reply[jsonStart] == '{') {
            val read = LenientJson.readAt(reply, jsonStart, allowUnclosed = !truncated)
            when {
                // Half an argument is worse than no call.
                read == null && truncated -> return null
                // The arguments run to the closing tag, or to the next marker when there is none.
                read == null -> {
                    error = NOT_JSON
                    end = FUNCTION_CLOSE_ANYWHERE.find(reply, jsonStart)?.range?.first ?: nextMarker(reply, jsonStart)
                }
                read.element is JsonObject -> {
                    arguments = read.element
                    end = read.end
                }
                else -> {
                    error = NOT_AN_OBJECT
                    end = read.end
                }
            }
        }
        val canonical = reply.substring(open.range.first, end)
        FUNCTION_CLOSE.matchAt(reply, end)?.let { end = it.range.last + 1 }
        return Block(open.range.first, end, listOf(Call(name, arguments, error)), canonical)
    }

    private fun afterBracketMarker(reply: String, marker: MatchResult, truncated: Boolean): Block? {
        val jsonStart = indexOfNonSpace(reply, marker.range.last + 1)
        if (jsonStart < 0) return null
        val read = LenientJson.readAt(reply, jsonStart, allowUnclosed = false)
        if (read == null) {
            if (truncated) return null
            val end = nextMarker(reply, jsonStart)
            val broken = brokenCall(reply.substring(jsonStart, end)) ?: return null
            return Block(marker.range.first, end, listOf(broken))
        }
        val calls = toCalls(read.element)?.takeIf { it.isNotEmpty() } ?: return null
        return Block(marker.range.first, read.end, calls)
    }

    /** Where the next marked call starts after [from], or the end of [reply]. */
    private fun nextMarker(reply: String, from: Int): Int =
        listOfNotNull(TAG_OPEN.find(reply, from), FUNCTION_TAG.find(reply, from), BRACKET_MARKER.find(reply, from))
            .minOfOrNull { it.range.first } ?: reply.length

    /** A call for the tool [text] names at its top level, whose JSON could not be read, or null when it names none there. */
    private fun brokenCall(text: String): Call? = topLevelName(text, 0, 0)?.let { Call(it, JsonObject(emptyMap()), NOT_JSON) }

    /**
     * The tool name among the top-level members of the object (or the first object of the
     * array) at the start of [text], whose JSON is broken somewhere. A name inside "arguments" or
     * any other member's value is never taken: it is not the call's. Only the OpenAI shape and
     * wrappers ("function", "tool_calls") are looked into, one level down. Members are walked one
     * by one; once the walk loses its place in the broken JSON, null rather than a guess.
     */
    private fun topLevelName(text: String, from: Int, depth: Int): String? {
        var i = indexOfNonSpace(text, from)
        if (i >= 0 && text[i] == '[') i = indexOfNonSpace(text, i + 1)
        if (i < 0 || text[i] != '{') return null
        i++
        while (true) {
            while (i < text.length && (text[i].isWhitespace() || text[i] == ',')) i++
            if (i >= text.length || text[i] == '}') return null
            val key: String
            if (text[i] in QUOTES) {
                val end = stringEnd(text, i) ?: return null
                key = text.substring(i + 1, end - 1)
                i = end
            } else {
                val start = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-')) i++
                if (i == start) return null
                key = text.substring(start, i)
            }
            i = indexOfNonSpace(text, i)
            if (i < 0 || (text[i] != ':' && text[i] != '=')) return null
            i = indexOfNonSpace(text, i + 1)
            if (i < 0) return null
            if (key in NAME_KEYS && text[i] in QUOTES) {
                val end = stringEnd(text, i) ?: return null
                val name = text.substring(i + 1, end - 1).trim()
                if (TOOL_NAME.matches(name)) return name
                i = end
            } else {
                if (key in NESTING_KEYS && depth == 0 && (text[i] == '{' || text[i] == '[')) {
                    topLevelName(text, i, depth + 1)?.let { return it }
                }
                i = valueEnd(text, i) ?: return null
            }
            // After a member comes the next one or the end of the object; anything else means the walk is lost.
            i = indexOfNonSpace(text, i)
            if (i < 0 || (text[i] != ',' && text[i] != '}')) return null
        }
    }

    /** Index just past the value that starts at [at] in broken JSON, or null when its end cannot be told. */
    private fun valueEnd(text: String, at: Int): Int? = when (val c = text[at]) {
        in QUOTES -> stringEnd(text, at)
        '{', '[' -> LenientJson.readAt(text, at, allowUnclosed = false)?.end ?: closingIndex(text, at, c, strings = true) ?: closingIndex(text, at, c, strings = false)
        else -> {
            var i = at
            while (i < text.length && text[i] != ',' && text[i] != '}' && text[i] != ']' && !text[i].isWhitespace()) i++
            i
        }
    }

    /**
     * Index just past the bracket that closes the one at [at], counting only brackets of its own
     * kind; with [strings], brackets inside quoted strings are skipped. Null when it is never closed.
     */
    private fun closingIndex(text: String, at: Int, open: Char, strings: Boolean): Int? {
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var i = at
        while (i < text.length) {
            val c = text[i]
            when {
                strings && c in QUOTES -> {
                    i = stringEnd(text, i) ?: return null
                    continue
                }
                c == open -> depth++
                c == close -> if (--depth == 0) return i + 1
            }
            i++
        }
        return null
    }

    /** Index just past the string whose opening quote is at [at], as [LenientJson] reads it, or null when it is never closed. */
    private fun stringEnd(text: String, at: Int): Int? {
        val closers = LenientJson.closersFor(text[at])
        var i = at + 1
        while (i < text.length) {
            val c = text[i++]
            if (c == '\\') i++ else if (c in closers) return i
        }
        return null
    }

    private fun fenced(reply: String, knownTools: Set<String>): List<Block> =
        FENCE.findAll(reply).mapNotNull { fence ->
            val calls = callsIn(fence.groupValues[1], allowUnclosed = false, marked = false)?.takeIf { all -> all.all { it.understood(knownTools) } }
            calls?.let { Block(fence.range.first, fence.range.last + 1, it) }
        }.toList()

    private fun bare(reply: String, knownTools: Set<String>): List<Block> {
        val blocks = ArrayList<Block>()
        var from = 0
        while (true) {
            val candidate = LINE_START_JSON.find(reply, from) ?: break
            val start = candidate.groups[1]!!.range.first
            val read = LenientJson.readAt(reply, start, allowUnclosed = false)
            val calls = read?.let { toCalls(it.element) }?.takeIf { all -> all.isNotEmpty() && all.all { it.understood(knownTools) } }
            if (read != null && calls != null) {
                blocks += Block(candidate.range.first, read.end, calls)
                from = read.end
            } else {
                from = start + 1
            }
        }
        return blocks
    }

    private fun reactStyle(reply: String, knownTools: Set<String>): List<Block> =
        REACT.findAll(reply).mapNotNull { action ->
            val name = action.groupValues[1]
            if (name !in knownTools) return@mapNotNull null
            val inputStart = action.range.last + 1
            val read = LenientJson.readAt(reply, inputStart, allowUnclosed = false)
            val arguments = if (read == null) JsonObject(emptyMap()) else read.element as? JsonObject ?: return@mapNotNull null
            val end = read?.end ?: inputStart
            // Nothing closes this form: its block is its marker and its arguments, which is also its canonical form.
            Block(action.range.first, end, listOf(Call(name, arguments)), canonical = reply.substring(action.range.first, end))
        }.toList()

    /** Unmarked JSON is a call only when it names an offered tool and could be read completely. */
    private fun Call.understood(knownTools: Set<String>): Boolean = name in knownTools && argumentsError == null

    /**
     * The calls written in the body of a block: one object, an array of them, or several
     * objects one after another, optionally inside a code fence. Null when any part of the
     * body is not a call, so a block is understood completely or not at all; except that in a
     * [marked] block, JSON that cannot be read but names a tool becomes a call with an error.
     */
    private fun callsIn(body: String, allowUnclosed: Boolean, marked: Boolean): List<Call>? {
        val text = FENCE_LINE.replace(body, "")
        val calls = ArrayList<Call>()
        var at = indexOfNonSpace(text, 0)
        while (at >= 0) {
            val read = LenientJson.readAt(text, at, allowUnclosed)
            if (read == null) {
                val broken = if (marked) brokenCall(text.substring(at)) else null
                return broken?.let { calls + it }
            }
            calls += toCalls(read.element) ?: return null
            at = indexOfNonSpace(text, read.end)
            while (at >= 0 && text[at] == ',') at = indexOfNonSpace(text, at + 1)
        }
        return calls.takeIf { it.isNotEmpty() }
    }

    /** Interprets parsed JSON as tool calls in any of the shapes models commonly produce, or null. */
    private fun toCalls(element: JsonElement): List<Call>? {
        return when (element) {
            is JsonArray -> element.map { toCalls(it) ?: return null }.flatten()
            is JsonObject -> {
                val wrapped = element["tool_calls"] ?: element["tool_call"]
                if (wrapped is JsonArray || wrapped is JsonObject) toCalls(wrapped) else toCall(element)?.let { listOf(it) }
            }
            else -> null
        }
    }

    private fun toCall(obj: JsonObject): Call? {
        // OpenAI's wire shape: {"type": "function", "function": {"name": ..., "arguments": "..."}}.
        (obj["function"] as? JsonObject)?.let { return toCall(it) }
        val name = NAME_KEYS.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content }
            ?.trim()?.takeIf { TOOL_NAME.matches(it) } ?: return null
        val arguments = when (val raw = ARGUMENT_KEYS.firstNotNullOfOrNull { obj[it] }) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            // Arguments encoded as a JSON string inside the JSON.
            is JsonPrimitive -> decodeArguments(raw) ?: return Call(name, JsonObject(emptyMap()), if (raw.isString) NOT_JSON else NOT_AN_OBJECT)
            else -> return Call(name, JsonObject(emptyMap()), NOT_AN_OBJECT)
        }
        return Call(name, arguments)
    }

    private fun decodeArguments(raw: JsonPrimitive): JsonObject? {
        if (!raw.isString) return null
        val text = raw.content.trim()
        if (text.isEmpty()) return JsonObject(emptyMap())
        val read = LenientJson.readAt(text, 0, allowUnclosed = false) ?: return null
        return (read.element as? JsonObject)?.takeIf { indexOfNonSpace(text, read.end) < 0 }
    }

    private fun indexOfNonSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return if (i < text.length) i else -1
    }
}
