package com.aicontrolcenter.providers.anthropic

import com.anthropic.core.JsonValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Conversion between the framework's JSON (kotlinx.serialization) and the SDK's JSON. Both
 * directions keep object key order, string contents and numeric values, which is what lets
 * stored content blocks (thinking signatures in particular) go back to the API unchanged and
 * serialise to the same bytes on every later request.
 */

/** This element as an SDK value that can stand in for any typed request field. */
internal fun JsonElement.toSdkJson(): JsonValue = JsonValue.from(toPlainValue())

private fun JsonElement.toPlainValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> LinkedHashMap<String, Any?>(size).also { map -> forEach { (key, value) -> map[key] = value.toPlainValue() } }
    is JsonArray -> map { it.toPlainValue() }
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: number()
}

/**
 * Integers of any size stay integers. A decimal becomes a double when that represents it
 * exactly, which serialises the way it was written, and a BigDecimal when a double would
 * round it.
 */
private fun JsonPrimitive.number(): Any {
    longOrNull?.let { return it }
    content.toBigIntegerOrNull()?.let { return it }
    val decimal = content.toBigDecimalOrNull() ?: return content
    val double = content.toDoubleOrNull()
    return if (double != null && double.isFinite() && double.toBigDecimal().compareTo(decimal) == 0) double else decimal
}

/**
 * The JSON form of an SDK object (a content block, an event payload) or SDK JSON value.
 * Fields the SDK version does not model are preserved, because its types carry them along.
 */
internal fun sdkToJson(value: Any?): JsonElement = JsonValue.from(value).accept(ToKotlinxJson)

private object ToKotlinxJson : JsonValue.Visitor<JsonElement> {
    override fun visitNull(): JsonElement = JsonNull

    override fun visitMissing(): JsonElement = JsonNull

    override fun visitBoolean(value: Boolean): JsonElement = JsonPrimitive(value)

    override fun visitNumber(value: Number): JsonElement = JsonPrimitive(value)

    override fun visitString(value: String): JsonElement = JsonPrimitive(value)

    override fun visitArray(values: List<JsonValue>): JsonElement = JsonArray(values.map { it.accept(this) })

    override fun visitObject(values: Map<String, JsonValue>): JsonElement {
        val fields = LinkedHashMap<String, JsonElement>(values.size)
        values.forEach { (key, value) -> fields[key] = value.accept(this) }
        return JsonObject(fields)
    }
}

internal fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal val JsonObject.blockType: String? get() = stringOrNull("type")
