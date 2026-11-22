package com.aicontrolcenter.providers.openai

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.ReasoningEffort
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.llm.ToolChoice
import com.aicontrolcenter.core.llm.ToolResultContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds Chat Completions request bodies.
 *
 * Choices made for the widest compatibility across the services and local servers that speak
 * this protocol:
 *  - The instruction role is "system". OpenAI's newer "developer" role is not understood
 *    elsewhere, while OpenAI still accepts "system".
 *  - The output limit is sent as `max_tokens`, which every implementation knows. Models that
 *    have dropped it are switched to `max_completion_tokens` by the parameter tolerance.
 *  - `tool_choice` is sent only to forbid tool use; "auto" is the protocol's default.
 *
 * Verification: platform.openai.com and openrouter.ai could not be reached from the build
 * environment, so the request and reply fields here, in [OpenAiReplyAssembler] and in
 * [OpenAiModels] follow the published protocol as known when this was written rather than a
 * fresh reading of the reference. The fields individual services add (`reasoning_details`,
 * `extra_content`, `prompt_cache_hit_tokens`, the model-list metadata) are all read leniently:
 * if one is renamed, that piece of information is lost, not the call.
 */
internal object OpenAiRequests {
    const val MAX_TOKENS = "max_tokens"
    const val MAX_COMPLETION_TOKENS = "max_completion_tokens"
    const val TEMPERATURE = "temperature"
    const val REASONING_EFFORT = "reasoning_effort"
    const val RESPONSE_FORMAT = "response_format"
    const val STREAM_OPTIONS = "stream_options"

    /** Fields returned to the provider with an assistant turn; see [OpenAiEcho]. */
    const val REASONING_CONTENT = "reasoning_content"
    const val REASONING_DETAILS = "reasoning_details"

    /** The names a provider may use when it objects to an optional setting. */
    fun aliases(setting: String): List<String> = when (setting) {
        STREAM_OPTIONS -> listOf(STREAM_OPTIONS, "include_usage")
        RESPONSE_FORMAT -> listOf(RESPONSE_FORMAT, "json_object")
        else -> listOf(setting)
    }

    /** A request body and the optional settings that went into it. */
    class Built(val body: JsonObject, val optional: Set<String>)

    /**
     * @param omitted optional settings to leave out. Omitting [MAX_TOKENS] sends the limit as
     *   [MAX_COMPLETION_TOKENS] instead; omitting both sends no limit.
     * @param origin the host being called; echo data captured from another host is not replayed
     */
    fun build(request: ChatRequest, stream: Boolean, omitted: Set<String>, origin: String): Built {
        val sent = LinkedHashSet<String>()
        fun JsonObjectBuilder.optional(setting: String, write: JsonObjectBuilder.() -> Unit) {
            if (setting in omitted) return
            write()
            sent += setting
        }
        val body = buildJsonObject {
            put("model", request.model)
            put("messages", messages(request.messages, omitted, origin, sent))
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (tool in request.tools) {
                        add(
                            buildJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parameters)
                                }
                            },
                        )
                    }
                }
                if (request.toolChoice == ToolChoice.NONE) put("tool_choice", "none")
            }
            request.temperature?.let { optional(TEMPERATURE) { put(TEMPERATURE, it) } }
            request.maxOutputTokens?.let { limit ->
                if (MAX_TOKENS !in omitted) {
                    optional(MAX_TOKENS) { put(MAX_TOKENS, limit) }
                } else {
                    optional(MAX_COMPLETION_TOKENS) { put(MAX_COMPLETION_TOKENS, limit) }
                }
            }
            request.reasoningEffort?.let { effort ->
                optional(REASONING_EFFORT) {
                    put(
                        REASONING_EFFORT,
                        when (effort) {
                            ReasoningEffort.LOW -> "low"
                            ReasoningEffort.MEDIUM -> "medium"
                            ReasoningEffort.HIGH -> "high"
                        },
                    )
                }
            }
            if (request.jsonOutput) optional(RESPONSE_FORMAT) { putJsonObject(RESPONSE_FORMAT) { put("type", "json_object") } }
            if (stream) {
                put("stream", true)
                // Without this a streamed reply reports no token usage at all.
                optional(STREAM_OPTIONS) { putJsonObject(STREAM_OPTIONS) { put("include_usage", true) } }
            }
        }
        return Built(body, sent)
    }

    private fun messages(messages: List<ChatMessage>, omitted: Set<String>, origin: String, sent: MutableSet<String>): JsonArray =
        buildJsonArray {
            val leading = messages.takeWhile { it.role == ChatRole.SYSTEM }
            val instructions = leading.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")
            if (instructions.isNotEmpty()) add(plain("system", instructions))
            for (message in messages.drop(leading.size)) {
                when (message.role) {
                    ChatRole.SYSTEM -> add(plain("system", message.text))
                    ChatRole.USER -> add(user(message))
                    ChatRole.ASSISTANT -> add(assistant(message, omitted, origin, sent))
                    ChatRole.TOOL -> message.toolResult?.let { add(toolResult(it)) }
                }
            }
        }

    private fun plain(role: String, text: String) = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    /** Text alone is sent as a string; with images the content becomes typed parts, images as data URLs. */
    private fun user(message: ChatMessage): JsonObject {
        if (message.images.isEmpty()) return plain("user", message.text)
        return buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                if (message.text.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        },
                    )
                }
                for (image in message.images) {
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:${image.mimeType};base64,${image.base64}") }
                        },
                    )
                }
            }
        }
    }

    private fun assistant(message: ChatMessage, omitted: Set<String>, origin: String, sent: MutableSet<String>) = buildJsonObject {
        val echo = OpenAiEcho.of(message, origin)
        put("role", "assistant")
        // A turn that only calls tools has no content; null is what the protocol's own clients send back.
        if (message.text.isEmpty() && message.toolCalls.isNotEmpty()) put("content", JsonNull) else put("content", message.text)
        if (message.toolCalls.isNotEmpty()) {
            put("tool_calls", echo?.toolCalls ?: JsonArray(message.toolCalls.map(::toolCall)))
        }
        echo?.reasoningContent?.takeIf { REASONING_CONTENT !in omitted }?.let {
            put(REASONING_CONTENT, it)
            sent += REASONING_CONTENT
        }
        echo?.reasoningDetails?.takeIf { REASONING_DETAILS !in omitted }?.let {
            put(REASONING_DETAILS, it)
            sent += REASONING_DETAILS
        }
    }

    /** The wire form of a tool call: arguments travel as a JSON-encoded string. */
    fun toolCall(call: ToolCallRequest): JsonObject = buildJsonObject {
        put("id", call.id)
        put("type", "function")
        putJsonObject("function") {
            put("name", call.name)
            put("arguments", call.arguments.toString())
        }
    }

    private fun toolResult(result: ToolResultContent) = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", result.callId)
        put("content", result.content)
    }
}
