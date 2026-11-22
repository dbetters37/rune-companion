package com.aicontrolcenter.framework.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A shallow check of a call's arguments against the tool's JSON Schema: required arguments,
 * top-level types and unknown names where the schema forbids them. It exists so that every
 * tool answers the most common model mistakes in the same words; anything deeper is the
 * tool's own business.
 */
internal object ToolArguments {
    fun problems(schema: JsonObject, arguments: JsonObject): List<String> {
        val problems = ArrayList<String>()
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        for (name in required) {
            if (arguments[name] == null || arguments[name] is JsonNull) problems += "The required argument \"$name\" is missing."
        }
        val closed = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
        for ((name, value) in arguments) {
            val property = properties[name] as? JsonObject
            if (property == null) {
                if (closed) problems += "There is no argument called \"$name\"."
                continue
            }
            if (value is JsonNull) continue
            val allowed = allowedTypes(property["type"])
            if (allowed.isNotEmpty() && allowed.none { matches(it, value) }) {
                problems += "The argument \"$name\" must be ${allowed.joinToString(" or ") { article(it) }}."
            }
        }
        return problems
    }

    private fun allowedTypes(type: JsonElement?): List<String> = when (type) {
        is JsonPrimitive -> listOf(type.content)
        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content }
        else -> emptyList()
    }

    private fun matches(type: String, value: JsonElement): Boolean = when (type) {
        "string" -> value is JsonPrimitive && value.isString
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "array" -> value is JsonArray
        "object" -> value is JsonObject
        "null" -> value is JsonNull
        // A type this check does not know is not a reason to refuse the call.
        else -> true
    }

    private fun article(type: String): String = when (type) {
        "integer", "array", "object" -> "an $type"
        else -> "a $type"
    }
}
