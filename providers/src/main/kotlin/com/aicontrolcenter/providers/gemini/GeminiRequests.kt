package com.aicontrolcenter.providers.gemini

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ReasoningEffort
import com.aicontrolcenter.core.llm.ToolChoice
import com.aicontrolcenter.core.llm.ToolResultContent
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.http.array
import com.aicontrolcenter.providers.http.obj
import com.aicontrolcenter.providers.http.objects
import com.aicontrolcenter.providers.http.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds `generateContent` request bodies for the Gemini API (v1beta).
 *
 * How the neutral conversation maps onto Gemini's `contents`:
 *  - Leading system messages become `systemInstruction`. Gemini has no system role inside the
 *    conversation, so a later system message is sent as a user turn.
 *  - An assistant turn that came from Gemini is replayed from [ChatMessage.providerData]: its
 *    original parts, verbatim. That is how thought signatures get back to the model, which
 *    Gemini 3 and later require for every turn that called a function and reject with a 400
 *    otherwise. Any other assistant turn is rebuilt from text and tool calls.
 *  - Consecutive tool results are grouped into one user turn of `functionResponse` parts,
 *    because Gemini wants all the responses to a turn's calls together, after all the calls.
 *  - Tool schemas go in `parametersJsonSchema`, the field that takes standard JSON Schema;
 *    `parameters` accepts only an OpenAPI subset.
 *
 * Verification: ai.google.dev could not be reached from the build environment. Confirmed
 * through search excerpts of Google's documentation only: that Gemini 3 and later answer 400
 * when the signature of the first function call of a step is missing, the [SKIP_SIGNATURE]
 * value, `parametersJsonSchema`, and the thinking levels (MINIMAL, LOW, MEDIUM, HIGH, not all
 * of them on every model). Not confirmed afresh: `id` on `functionCall` and
 * `functionResponse`, the exact set of finish and block reasons, and that
 * `thoughtsTokenCount` is counted apart from `candidatesTokenCount`
 * ([GeminiReplyAssembler]), and the `thinking` flag of the model list ([GeminiModels]).
 */
internal object GeminiRequests {
    const val TEMPERATURE = "temperature"
    const val MAX_OUTPUT_TOKENS = "maxOutputTokens"
    const val RESPONSE_MIME_TYPE = "responseMimeType"
    const val THINKING_LEVEL = "thinkingLevel"
    const val THINKING_BUDGET = "thinkingBudget"

    /**
     * Stands in for the thought signature of a function call that Gemini did not produce (a
     * turn written by another provider's model). Google documents this value as the way to
     * pass signature validation for such calls; it is a last resort, so it is used only where
     * a real signature cannot exist.
     */
    const val SKIP_SIGNATURE = "skip_thought_signature_validator"

    private val MODEL_NAME = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)?")
    private val BUDGET_GENERATION = Regex("gemini-[12][.-]")
    private val SIGNED_GENERATION = Regex("gemini-(\\d+)")

    /** The names Gemini's error messages use for an optional setting. */
    fun aliases(setting: String): List<String> = when (setting) {
        MAX_OUTPUT_TOKENS -> listOf("max_output_tokens", MAX_OUTPUT_TOKENS, "max output tokens")
        RESPONSE_MIME_TYPE -> listOf("response_mime_type", RESPONSE_MIME_TYPE, "response mime type", "json mode")
        THINKING_LEVEL -> listOf("thinking_level", THINKING_LEVEL, "thinking level")
        THINKING_BUDGET -> listOf("thinking_budget", THINKING_BUDGET, "thinking budget")
        else -> listOf(setting)
    }

    /** What an error says when it rejects thinking as such rather than one way of asking for it. */
    val THINKING_ALIASES = listOf("thinking_config", "thinkingConfig", "thinking", "thoughts", "include_thoughts", "includeThoughts")

    class Built(val body: JsonObject, val optional: Set<String>)

    /**
     * The path of a model method under the API root.
     *
     * @param model "gemini-2.5-flash", or a full resource name such as "tunedModels/my-model"
     * @throws LlmException.ModelNotFound when the name could not be a Gemini resource name
     */
    fun path(model: String, method: String): String {
        val name = model.trim()
        if (!MODEL_NAME.matches(name) || ".." in name) {
            throw LlmException.ModelNotFound("\"${name.take(80)}\" is not a valid Gemini model name.")
        }
        return (if ('/' in name) name else "models/$name") + ":" + method
    }

    fun build(request: ChatRequest, omitted: Set<String>): Built {
        val sent = LinkedHashSet<String>()
        val leading = request.messages.takeWhile { it.role == ChatRole.SYSTEM }
        val instructions = leading.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")
        val body = buildJsonObject {
            if (instructions.isNotEmpty()) {
                putJsonObject("systemInstruction") { putJsonArray("parts") { add(textPart(instructions)) } }
            }
            put("contents", contents(request.messages.drop(leading.size), request.model))
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    add(
                        buildJsonObject {
                            putJsonArray("functionDeclarations") {
                                for (tool in request.tools) {
                                    add(
                                        buildJsonObject {
                                            put("name", tool.name)
                                            put("description", tool.description)
                                            put("parametersJsonSchema", tool.parameters)
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
                // The declarations stay so that calls in the history remain valid; the mode forbids new ones.
                if (request.toolChoice == ToolChoice.NONE) {
                    putJsonObject("toolConfig") { putJsonObject("functionCallingConfig") { put("mode", "NONE") } }
                }
            }
            val generation = buildJsonObject {
                request.temperature?.takeIf { TEMPERATURE !in omitted }?.let {
                    put(TEMPERATURE, it)
                    sent += TEMPERATURE
                }
                request.maxOutputTokens?.takeIf { MAX_OUTPUT_TOKENS !in omitted }?.let {
                    put(MAX_OUTPUT_TOKENS, it)
                    sent += MAX_OUTPUT_TOKENS
                }
                if (request.jsonOutput && RESPONSE_MIME_TYPE !in omitted) {
                    put(RESPONSE_MIME_TYPE, "application/json")
                    sent += RESPONSE_MIME_TYPE
                }
                request.reasoningEffort?.let { effort -> thinking(request.model, effort, omitted) }?.let { (setting, config) ->
                    put("thinkingConfig", config)
                    sent += setting
                }
            }
            if (generation.isNotEmpty()) put("generationConfig", generation)
        }
        return Built(body, sent)
    }

    /**
     * Reasoning effort has two spellings: `thinkingLevel` for Gemini 3 and later, and a token
     * `thinkingBudget` for the 2.x models, each rejected by the other generation. The one that
     * fits the model name is tried first and the other is the fallback. Thought summaries are
     * requested alongside, so that the reply's reasoning can be shown.
     */
    private fun thinking(model: String, effort: ReasoningEffort, omitted: Set<String>): Pair<String, JsonObject>? {
        val order = if (BUDGET_GENERATION.containsMatchIn(model)) listOf(THINKING_BUDGET, THINKING_LEVEL) else listOf(THINKING_LEVEL, THINKING_BUDGET)
        val setting = order.firstOrNull { it !in omitted } ?: return null
        val config = buildJsonObject {
            put("includeThoughts", true)
            if (setting == THINKING_LEVEL) {
                put(THINKING_LEVEL, effort.name)
            } else {
                put(
                    THINKING_BUDGET,
                    when (effort) {
                        ReasoningEffort.LOW -> 1_024
                        ReasoningEffort.MEDIUM -> 8_192
                        ReasoningEffort.HIGH -> 24_576
                    },
                )
            }
        }
        return setting to config
    }

    private fun contents(messages: List<ChatMessage>, model: String): JsonArray {
        // Ids that Gemini itself issued; only those may be sent back in a functionResponse.
        val issuedIds = messages.mapNotNull(::replayedParts).flatMap { parts ->
            parts.objects().mapNotNull { it.obj("functionCall")?.string("id") }
        }.toSet()
        val needsSignature = (SIGNED_GENERATION.find(model)?.groupValues?.get(1)?.toIntOrNull() ?: 0) >= 3
        return buildJsonArray {
            val responses = ArrayList<JsonObject>()
            fun flushResponses() {
                if (responses.isEmpty()) return
                add(content("user", responses.toList()))
                responses.clear()
            }
            for (message in messages) {
                if (message.role != ChatRole.TOOL) flushResponses()
                when (message.role) {
                    ChatRole.SYSTEM, ChatRole.USER -> userParts(message).takeIf { it.isNotEmpty() }?.let { add(content("user", it)) }
                    ChatRole.ASSISTANT -> {
                        val parts = replayedParts(message) ?: JsonArray(rebuiltParts(message, needsSignature))
                        if (parts.isNotEmpty()) add(content("model", parts))
                    }
                    ChatRole.TOOL -> message.toolResult?.let { responses += functionResponse(it, issuedIds) }
                }
            }
            flushResponses()
        }
    }

    private fun content(role: String, parts: List<JsonElement>) = buildJsonObject {
        put("role", role)
        put("parts", JsonArray(parts))
    }

    private fun textPart(text: String) = buildJsonObject { put("text", text) }

    private fun userParts(message: ChatMessage): List<JsonObject> = buildList {
        if (message.text.isNotEmpty()) add(textPart(message.text))
        for (image in message.images) {
            add(
                buildJsonObject {
                    putJsonObject("inlineData") {
                        put("mimeType", image.mimeType)
                        put("data", image.base64)
                    }
                },
            )
        }
    }

    /** The parts Gemini originally returned for [message], or null when it did not come from Gemini. */
    private fun replayedParts(message: ChatMessage): JsonArray? =
        message.providerData?.takeIf { it.kind == ProviderKind.GEMINI }
            ?.let { (it.payload as? JsonObject)?.array(GeminiReplyAssembler.PARTS) }
            ?.takeIf { it.isNotEmpty() }

    private fun rebuiltParts(message: ChatMessage, needsSignature: Boolean): List<JsonObject> = buildList {
        if (message.text.isNotEmpty()) add(textPart(message.text))
        message.toolCalls.forEachIndexed { index, call ->
            add(
                buildJsonObject {
                    putJsonObject("functionCall") {
                        put("name", call.name)
                        put("args", call.arguments)
                    }
                    // As with real signatures, only the first call of a turn carries one.
                    if (needsSignature && index == 0) put("thoughtSignature", SKIP_SIGNATURE)
                },
            )
        }
    }

    /** `response` must be an object; the tool's text goes under "result", or "error" when it failed. */
    private fun functionResponse(result: ToolResultContent, issuedIds: Set<String>) = buildJsonObject {
        putJsonObject("functionResponse") {
            if (result.callId in issuedIds) put("id", result.callId)
            put("name", result.toolName)
            putJsonObject("response") { put(if (result.isError) "error" else "result", result.content) }
        }
    }
}
