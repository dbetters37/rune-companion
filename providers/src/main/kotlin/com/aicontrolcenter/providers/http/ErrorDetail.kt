package com.aicontrolcenter.providers.http

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a provider said about a failure, reduced to the fields the error mapping and the
 * parameter tolerance need. Understands the body shapes in use: OpenAI's
 * `{"error":{"message","type","param","code"}}`, Google's
 * `{"error":{"code","message","status","details":[...]}}`, Ollama's `{"error":"..."}`, and the
 * validation lists (`detail: [{"loc","msg","type"}]`) of FastAPI-based servers such as vLLM
 * and Mistral. The message has been through the [Redactor] and is cut to a readable length.
 */
internal data class ErrorDetail(
    val message: String = "",
    val code: String? = null,
    val type: String? = null,
    /** The request field the provider blames, e.g. "max_tokens" or "stream_options.include_usage". */
    val param: String? = null,
    /** google.rpc status name, e.g. "RESOURCE_EXHAUSTED". */
    val status: String? = null,
    /** google.rpc.ErrorInfo reason, e.g. "API_KEY_INVALID". */
    val reason: String? = null,
    /** A wait the body itself asks for (google.rpc.RetryInfo). */
    val retryDelayMs: Long? = null,
) {
    /** Everything the provider said, lower-cased, for phrase matching. */
    val searchable: String by lazy {
        listOfNotNull(message, code, type, status, reason).joinToString(" ").lowercase()
    }

    companion object {
        private const val MAX_MESSAGE_LENGTH = 400

        fun parse(body: String, redactor: Redactor): ErrorDetail {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) return ErrorDetail()
            val root = try {
                Json.parseToJsonElement(trimmed)
            } catch (e: SerializationException) {
                // Not JSON. A short plain-text body is the message; an HTML error page says nothing useful.
                return ErrorDetail(message = if (trimmed.startsWith("<")) "" else tidy(trimmed, redactor))
            }
            return fromJson(root, redactor)
        }

        fun fromJson(root: JsonElement, redactor: Redactor): ErrorDetail {
            // Google wraps stream errors in a one-element array.
            val obj = (root as? JsonObject) ?: (root as? JsonArray)?.firstOrNull() as? JsonObject
                ?: return ErrorDetail(message = tidy((root as? JsonPrimitive)?.content.orEmpty(), redactor))
            val error = obj["error"]
            val source = error as? JsonObject ?: obj
            val validation = validationEntry(source["detail"]) ?: validationEntry(source["message"])
            val message = when {
                validation != null -> validation.first
                error is JsonPrimitive && error.isString -> error.content
                else -> readable(source["message"]) ?: readable(source["detail"]) ?: readable(source["msg"]) ?: ""
            }
            val details = source.array("details")?.objects().orEmpty()
            return ErrorDetail(
                message = tidy(message, redactor),
                code = source.scalar("code"),
                type = source.string("type"),
                param = source.string("param") ?: validation?.second,
                status = source.string("status"),
                reason = details.firstNotNullOfOrNull { it.string("reason") },
                retryDelayMs = details.firstNotNullOfOrNull { it.string("retryDelay") }?.let(::parseProtoDuration),
            )
        }

        /** A pydantic validation list as (message, blamed field), or null when [element] is not one. */
        private fun validationEntry(element: JsonElement?): Pair<String, String?>? {
            val list = element as? JsonArray ?: (element as? JsonObject)?.array("detail") ?: return null
            val first = list.objects().firstOrNull() ?: return null
            val text = first.string("msg") ?: return null
            val path = first.array("loc").orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .filter { it != "body" }
            val field = path.joinToString(".").ifEmpty { null }
            return (if (field == null) text else "$text ($field)") to field
        }

        private fun readable(element: JsonElement?): String? = when (element) {
            null -> null
            is JsonPrimitive -> element.content.takeIf { element.isString && it.isNotBlank() }
            else -> element.toString()
        }

        private fun tidy(text: String, redactor: Redactor): String {
            val singleLine = redactor.clean(text).replace(Regex("\\s+"), " ").trim()
            return if (singleLine.length <= MAX_MESSAGE_LENGTH) singleLine else singleLine.take(MAX_MESSAGE_LENGTH) + "…"
        }

        /** "34s" or "1.5s", the JSON form of google.protobuf.Duration. */
        private fun parseProtoDuration(text: String): Long? =
            text.removeSuffix("s").toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).toLong() }
    }
}

/** A reply with a non-2xx status. */
internal class HttpFailure(
    val status: Int,
    val detail: ErrorDetail,
    /** From the Retry-After (or retry-after-ms) header. */
    val retryAfterMs: Long? = null,
)
