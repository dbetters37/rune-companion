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
 * Whatever cannot be read as a call is left in the text exactly as written.
 */
internal object PromptedToolCallParser {
    data class Call(val name: String, val arguments: JsonObject)

    /** [text] is the reply without the call blocks that were understood. */
    data class Parsed(val text: String, val calls: List<Call>)

    private class Block(val start: Int, val end: Int, val calls: List<Call>)

    private val TAG_OPEN = Regex("<(tool_calls?|toolcall|function_calls?|tool_use)>", RegexOption.IGNORE_CASE)
    private val FUNCTION_TAG = Regex("<function=([A-Za-z0-9_.\\-]+)>", RegexOption.IGNORE_CASE)
    private val FUNCTION_CLOSE = Regex("\\s*</function>", RegexOption.IGNORE_CASE)
    private val BRACKET_MARKER = Regex("\\[TOOL_CALLS]", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("```[A-Za-z_]*[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
    private val FENCE_LINE = Regex("```[A-Za-z_]*")
    private val LINE_START_JSON = Regex("(?m)^[ \\t]*([{\\[])")
    private val REACT = Regex("(?im)^[ \\t]*Action:[ \\t]*([A-Za-z0-9_.\\-]+)[ \\t]*\\r?\\n[ \\t]*Action Input:[ \\t]*")
    private val TOOL_NAME = Regex("[A-Za-z0-9_.\\-]{1,64}")
    private val BLANK_LINES = Regex("\\n{3,}")

    private val NAME_KEYS = listOf("name", "tool", "tool_name", "function", "function_name")
    private val ARGUMENT_KEYS = listOf("arguments", "args", "parameters", "params", "input", "tool_input", "action_input")

    /**
     * @param truncated the reply was cut off at the output limit. A call that was still being
     * written is then not completed by guesswork: half an argument is worse than no call.
     */
    fun parse(reply: String, knownTools: Set<String>, truncated: Boolean = false): Parsed {
        val blocks = marked(reply, truncated)
            .ifEmpty { fenced(reply, knownTools) }
            .ifEmpty { bare(reply, knownTools) }
            .ifEmpty { reactStyle(reply, knownTools) }
        if (blocks.isEmpty()) return Parsed(reply, emptyList())
        val text = StringBuilder()
        var from = 0
        for (block in blocks) {
            text.append(reply, from, block.start)
            from = block.end
        }
        text.append(reply, from, reply.length)
        return Parsed(BLANK_LINES.replace(text, "\n\n").trim(), blocks.flatMap { it.calls })
    }

    private fun marked(reply: String, truncated: Boolean): List<Block> {
        val blocks = ArrayList<Block>()
        var from = 0
        while (true) {
            val next = listOfNotNull(TAG_OPEN.find(reply, from), FUNCTION_TAG.find(reply, from), BRACKET_MARKER.find(reply, from))
                .minByOrNull { it.range.first } ?: break
            val block = when {
                next.value.startsWith("[") -> afterBracketMarker(reply, next)
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
            val calls = callsIn(reply.substring(bodyStart, close), allowUnclosed = true) ?: return null
            return Block(open.range.first, close + open.groupValues[1].length + 3, calls)
        }
        // No closing tag: the call runs to the next opening tag or the end of the reply.
        if (truncated) return null
        val bodyEnd = TAG_OPEN.find(reply, bodyStart)?.range?.first ?: reply.length
        val calls = callsIn(reply.substring(bodyStart, bodyEnd), allowUnclosed = true) ?: return null
        return Block(open.range.first, bodyEnd, calls)
    }

    private fun functionTag(reply: String, open: MatchResult, truncated: Boolean): Block? {
        val name = open.groupValues[1]
        var end = open.range.last + 1
        var arguments = JsonObject(emptyMap())
        val jsonStart = indexOfNonSpace(reply, end)
        if (jsonStart >= 0 && reply[jsonStart] == '{') {
            val read = LenientJson.readAt(reply, jsonStart, allowUnclosed = !truncated) ?: return null
            arguments = read.element as? JsonObject ?: return null
            end = read.end
        }
        FUNCTION_CLOSE.matchAt(reply, end)?.let { end = it.range.last + 1 }
        return Block(open.range.first, end, listOf(Call(name, arguments)))
    }

    private fun afterBracketMarker(reply: String, marker: MatchResult): Block? {
        val jsonStart = indexOfNonSpace(reply, marker.range.last + 1)
        if (jsonStart < 0) return null
        val read = LenientJson.readAt(reply, jsonStart, allowUnclosed = false) ?: return null
        val calls = toCalls(read.element)?.takeIf { it.isNotEmpty() } ?: return null
        return Block(marker.range.first, read.end, calls)
    }

    private fun fenced(reply: String, knownTools: Set<String>): List<Block> =
        FENCE.findAll(reply).mapNotNull { fence ->
            val calls = callsIn(fence.groupValues[1], allowUnclosed = false)?.takeIf { all -> all.all { it.name in knownTools } }
            calls?.let { Block(fence.range.first, fence.range.last + 1, it) }
        }.toList()

    private fun bare(reply: String, knownTools: Set<String>): List<Block> {
        val blocks = ArrayList<Block>()
        var from = 0
        while (true) {
            val candidate = LINE_START_JSON.find(reply, from) ?: break
            val start = candidate.groups[1]!!.range.first
            val read = LenientJson.readAt(reply, start, allowUnclosed = false)
            val calls = read?.let { toCalls(it.element) }?.takeIf { all -> all.isNotEmpty() && all.all { it.name in knownTools } }
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
            Block(action.range.first, read?.end ?: inputStart, listOf(Call(name, arguments)))
        }.toList()

    /**
     * The calls written in the body of a block: one object, an array of them, or several
     * objects one after another, optionally inside a code fence. Null when any part of the
     * body is not a call, so a block is understood completely or not at all.
     */
    private fun callsIn(body: String, allowUnclosed: Boolean): List<Call>? {
        val text = FENCE_LINE.replace(body, "")
        val calls = ArrayList<Call>()
        var at = indexOfNonSpace(text, 0)
        while (at >= 0) {
            val read = LenientJson.readAt(text, at, allowUnclosed) ?: return null
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
            is JsonPrimitive -> decodeArguments(raw) ?: return null
            else -> return null
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
