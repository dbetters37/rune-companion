package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.framework.llm.LenientJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Finds the JSON object in a model's reply. Models asked for "one JSON object" still wrap it in
 * a code fence, put a sentence before or after it, or write it loosely (single quotes, trailing
 * commas); all of that is accepted. Fenced blocks are tried first, then every `{` in order, and
 * the first one that reads as an object wins. Nothing else is guessed: a reply without a
 * readable object yields null.
 */
internal object JsonReply {
    private val FENCE = Regex("```[a-zA-Z0-9_-]*\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
    private const val MAX_STARTS = 64

    fun firstObject(text: String): JsonObject? {
        for (fence in FENCE.findAll(text)) scan(fence.groupValues[1])?.let { return it }
        return scan(text)
    }

    private fun scan(text: String): JsonObject? {
        var start = text.indexOf('{')
        var tries = 0
        while (start >= 0 && tries < MAX_STARTS) {
            val read = LenientJson.readAt(text, start, allowUnclosed = false)
            if (read != null && read.element is JsonObject) return read.element
            start = text.indexOf('{', start + 1)
            tries++
        }
        return null
    }
}

/** Typed, forgiving reads of model-written JSON: alternative spellings of a field, strings where lists were asked for. */
internal fun JsonObject.field(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeUnless { it is JsonNull } }

internal fun JsonElement?.text(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content.trim().takeIf { it.isNotEmpty() }
    else -> null
}

/** A list of strings: a JSON array of strings or numbers, or one string split on line breaks and semicolons. */
internal fun JsonElement?.texts(): List<String> = when (this) {
    is JsonArray -> mapNotNull { it.text() }
    is JsonPrimitive -> if (isString) content.split('\n', ';').map { it.trim().trimStart('-', '*', '•').trim() }.filter { it.isNotEmpty() } else listOfNotNull(text())
    else -> emptyList()
}

internal fun JsonElement?.flag(): Boolean? = when (this) {
    is JsonPrimitive -> booleanOrNull ?: when (content.trim().lowercase()) {
        "yes", "y", "1" -> true
        "no", "n", "0" -> false
        else -> null
    }
    else -> null
}
