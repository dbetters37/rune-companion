package com.aicontrolcenter.providers.http

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The arguments of one tool call, read from what the model sent. When they cannot be read,
 * [arguments] is empty and [error] says why in a sentence meant for the model, which receives
 * it as the call's error result and can try again (see `ToolCallRequest.argumentsError`).
 */
internal class ToolArguments private constructor(val arguments: JsonObject, val error: String?) {
    companion object {
        /** Arguments longer than this are described, not repeated, in [error]. */
        const val MAX_QUOTED_CHARS = 200
        private const val MAX_DETAIL_CHARS = 120
        private const val MAX_LITERAL_CHARS = 40
        private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

        /**
         * The first value that is neither quoted nor a JSON literal (number, true, false, null).
         * kotlinx's tree parser accepts `{"city": Oslo}` and keeps `Oslo` as an unquoted value,
         * which is not JSON and which a tool would receive as something it never asked for.
         * Walked without recursion, so deeply nested input cannot exhaust the stack.
         */
        private fun unquotedValue(root: JsonElement): String? {
            val pending = ArrayDeque<JsonElement>().apply { add(root) }
            while (pending.isNotEmpty()) {
                when (val element = pending.removeLast()) {
                    is JsonObject -> pending.addAll(element.values)
                    is JsonArray -> pending.addAll(element)
                    is JsonPrimitive -> if (!element.isString && element !is JsonNull) {
                        val text = element.content
                        if (text != "true" && text != "false" && !JSON_NUMBER.matches(text)) return text
                    }
                }
            }
            return null
        }
        private val NONE = JsonObject(emptyMap())

        private fun of(arguments: JsonObject): ToolArguments = ToolArguments(arguments, null)

        /** The object, unless it holds a value the parser let through unquoted (see [unquotedValue]). */
        private fun checked(arguments: JsonObject, raw: String?): ToolArguments {
            val unquoted = unquotedValue(arguments) ?: return of(arguments)
            return invalid("The arguments were not valid JSON: the value ${unquoted.take(MAX_LITERAL_CHARS)} is not in quotes.", raw ?: arguments.toString())
        }

        /**
         * Arguments that arrived as text. Blank or `null` means none. A JSON string that holds
         * the object (arguments encoded twice, which some servers do) is unwrapped once.
         */
        fun fromText(raw: String): ToolArguments {
            if (raw.isBlank()) return of(NONE)
            val parsed = try {
                Json.parseToJsonElement(raw)
            } catch (e: SerializationException) {
                return invalid("The arguments were not valid JSON${detailOf(e)}.", raw)
            }
            return when {
                parsed is JsonPrimitive && parsed.isString -> {
                    val inner = try {
                        Json.parseToJsonElement(parsed.content)
                    } catch (e: SerializationException) {
                        null
                    }
                    if (inner is JsonObject) checked(inner, raw) else notAnObject(parsed, raw)
                }
                else -> fromElement(parsed, raw)
            }
        }

        /** Arguments that arrived as JSON already. Absent or `null` means none; a string is read as text. */
        fun fromElement(element: JsonElement?, raw: String? = null): ToolArguments = when (element) {
            null, is JsonNull -> of(NONE)
            is JsonObject -> checked(element, raw)
            is JsonPrimitive -> if (element.isString && raw == null) fromText(element.content) else notAnObject(element, raw ?: element.toString())
            is JsonArray -> notAnObject(element, raw ?: element.toString())
        }

        private fun notAnObject(element: JsonElement, raw: String): ToolArguments {
            val kind = when (element) {
                is JsonArray -> "a JSON array"
                is JsonPrimitive -> if (element.isString) "a JSON string" else "a single JSON value"
                else -> "something else"
            }
            return invalid("The arguments must be one JSON object of named values, but were $kind.", raw)
        }

        private fun invalid(reason: String, raw: String): ToolArguments {
            val quoted = if (raw.length <= MAX_QUOTED_CHARS) " Received: $raw" else " Received ${raw.length} characters."
            return ToolArguments(NONE, reason + quoted)
        }

        /**
         * The parser's own explanation without the input it quotes after "JSON input:", which
         * can be long. Kept short, because it is only a hint for the model.
         */
        private fun detailOf(e: SerializationException): String {
            val text = e.message?.substringBefore("JSON input:")?.lineSequence()?.firstOrNull()?.trim()?.trimEnd('.', ',')
            return if (text.isNullOrEmpty()) "" else ": " + text.take(MAX_DETAIL_CHARS)
        }
    }
}
