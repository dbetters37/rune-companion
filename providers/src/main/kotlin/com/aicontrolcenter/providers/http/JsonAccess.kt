package com.aicontrolcenter.providers.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Lenient readers for provider JSON. Providers differ in which fields they send and in what
 * type, so every accessor answers null instead of throwing when a field is absent or has an
 * unexpected shape; the adapters decide which absences are fatal.
 */

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** The value of a JSON string field. Numbers and booleans are not strings. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The text of any scalar field: providers send codes as strings or as numbers. */
internal fun JsonObject.scalar(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.long(key: String): Long? {
    val primitive = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString } ?: return null
    return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
}

internal fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()

internal fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

/** The strings of an array field, ignoring anything else in it. */
internal fun JsonObject.strings(key: String): List<String> =
    array(key)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

internal fun JsonArray.objects(): List<JsonObject> = filterIsInstance<JsonObject>()
