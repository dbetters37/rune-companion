package com.aicontrolcenter.providers.ollama

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.ReasoningEffort
import com.aicontrolcenter.core.llm.ToolChoice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds `POST /api/chat` bodies for Ollama's native API.
 *
 * Differences from the OpenAI shape that matter here: tool-call arguments are a JSON object,
 * not a string; images are a list of base64 strings beside the text; sampling settings live
 * under `options` (`num_predict` is the output limit); and a tool result names its tool with
 * `tool_name`. Ollama has no way to forbid tool use, so [ToolChoice.NONE] is honoured by not
 * offering the tools at all; unlike other providers it does not need them to read a history
 * that contains calls.
 */
internal object OllamaRequests {
    /** The one optional setting Ollama rejects outright: models without a thinking mode refuse it. */
    const val THINK = "think"

    val THINK_ALIASES = listOf("think", "thinking")

    class Built(val body: JsonObject, val optional: Set<String>)

    fun build(request: ChatRequest, stream: Boolean, omitted: Set<String>): Built {
        val sent = LinkedHashSet<String>()
        val body = buildJsonObject {
            put("model", request.model)
            put("messages", messages(request.messages))
            put("stream", stream)
            if (request.tools.isNotEmpty() && request.toolChoice != ToolChoice.NONE) {
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
            }
            request.reasoningEffort?.takeIf { THINK !in omitted }?.let { effort ->
                put(
                    THINK,
                    when (effort) {
                        ReasoningEffort.LOW -> "low"
                        ReasoningEffort.MEDIUM -> "medium"
                        ReasoningEffort.HIGH -> "high"
                    },
                )
                sent += THINK
            }
            if (request.jsonOutput) put("format", "json")
            if (request.temperature != null || request.maxOutputTokens != null) {
                putJsonObject("options") {
                    request.temperature?.let { put("temperature", it) }
                    request.maxOutputTokens?.let { put("num_predict", it) }
                }
            }
        }
        return Built(body, sent)
    }

    private fun messages(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        val leading = messages.takeWhile { it.role == ChatRole.SYSTEM }
        val instructions = leading.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")
        if (instructions.isNotEmpty()) add(plain("system", instructions))
        for (message in messages.drop(leading.size)) {
            when (message.role) {
                ChatRole.SYSTEM -> add(plain("system", message.text))
                ChatRole.USER -> add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", message.text)
                        if (message.images.isNotEmpty()) put("images", JsonArray(message.images.map { JsonPrimitive(it.base64) }))
                    },
                )
                ChatRole.ASSISTANT -> add(assistant(message))
                ChatRole.TOOL -> message.toolResult?.let { result ->
                    add(
                        buildJsonObject {
                            put("role", "tool")
                            put("content", result.content)
                            put("tool_name", result.toolName)
                            put("tool_call_id", result.callId)
                        },
                    )
                }
            }
        }
    }

    private fun plain(role: String, text: String) = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    /**
     * A turn that called tools is sent back with its thinking, as Ollama's own clients do when
     * they append the reply to the history: models that reason between tool calls continue from it.
     */
    private fun assistant(message: ChatMessage) = buildJsonObject {
        put("role", "assistant")
        put("content", message.text)
        if (message.toolCalls.isNotEmpty()) {
            message.reasoning?.takeIf { it.isNotEmpty() }?.let { put("thinking", it) }
            putJsonArray("tool_calls") {
                for (call in message.toolCalls) {
                    add(
                        buildJsonObject {
                            put("id", call.id)
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.arguments)
                            }
                        },
                    )
                }
            }
        }
    }
}
