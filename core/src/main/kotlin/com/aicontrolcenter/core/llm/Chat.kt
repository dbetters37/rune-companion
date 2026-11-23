package com.aicontrolcenter.core.llm

import com.aicontrolcenter.core.registry.ProviderKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Provider-neutral chat types. Every model, whatever serves it, is driven through these.
 * Adapters translate to and from each provider's wire format.
 */

@Serializable
enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * One message of a conversation.
 *
 *  - SYSTEM: instructions. Adapters merge the leading system messages into the provider's
 *    system field; a system message later in the list is sent in the provider's supported form.
 *  - USER: [text] and optional [images].
 *  - ASSISTANT: [text] and/or [toolCalls]; [reasoning] is a readable summary when the provider
 *    returned one.
 *  - TOOL: the result of exactly one tool call, in [toolResult].
 *
 * [providerData] is the provider's own representation of an assistant message. Some providers
 * require parts of it (reasoning blocks, signatures) to be sent back unchanged, so an adapter
 * that finds data of its own [ProviderKind] replays it instead of rebuilding from the neutral
 * fields. Data from another kind is ignored.
 */
@Serializable
data class ChatMessage(
    val role: ChatRole,
    val text: String = "",
    val images: List<ImageContent> = emptyList(),
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolResult: ToolResultContent? = null,
    val reasoning: String? = null,
    val providerData: ProviderData? = null,
) {
    companion object {
        fun system(text: String) = ChatMessage(ChatRole.SYSTEM, text)

        fun user(text: String, images: List<ImageContent> = emptyList()) = ChatMessage(ChatRole.USER, text, images = images)

        fun assistant(text: String, toolCalls: List<ToolCallRequest> = emptyList()) =
            ChatMessage(ChatRole.ASSISTANT, text, toolCalls = toolCalls)

        fun toolResult(callId: String, toolName: String, content: String, isError: Boolean = false) =
            ChatMessage(ChatRole.TOOL, toolResult = ToolResultContent(callId, toolName, content, isError))
    }
}

@Serializable
data class ImageContent(
    /** e.g. "image/jpeg". */
    val mimeType: String,
    /** Base64 without line breaks. */
    val base64: String,
)

/** The model asking for a tool to be run. */
@Serializable
data class ToolCallRequest(
    /** Identifies this call within the conversation; echoed in the matching [ToolResultContent]. */
    val id: String,
    /** The tool's function name as offered in [ChatRequest.tools]. */
    val name: String,
    val arguments: JsonObject,
    /**
     * Set when the model named a tool but its arguments could not be read (not valid JSON, not
     * an object). [arguments] is then empty. The call is not run: it is answered with this
     * message as an error result, so the model can correct itself instead of the turn failing.
     */
    val argumentsError: String? = null,
)

@Serializable
data class ToolResultContent(
    val callId: String,
    val toolName: String,
    /** What the model reads. */
    val content: String,
    val isError: Boolean = false,
)

@Serializable
data class ProviderData(val kind: ProviderKind, val payload: JsonElement)

/** A tool offered to the model: a function name, what it does, and a JSON Schema for its arguments. */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

enum class ToolChoice {
    /** The model decides whether to call a tool. */
    AUTO,

    /** The model must not call tools. */
    NONE,
}

enum class ReasoningEffort { LOW, MEDIUM, HIGH }

/**
 * One request for the model's next message. Optional settings are hints: an adapter omits any
 * that its provider or model does not accept rather than failing the call.
 */
data class ChatRequest(
    /** The provider's wire name of the model. Filled in by the gateway. */
    val model: String = "",
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.AUTO,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val reasoningEffort: ReasoningEffort? = null,
    /** Ask for a single JSON object as the reply, where the provider can enforce it. */
    val jsonOutput: Boolean = false,
)

@Serializable
data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    /** Input tokens served from the provider's prompt cache, if reported. Included in [inputTokens]. */
    val cachedInputTokens: Long = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens

    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cachedInputTokens + other.cachedInputTokens,
    )
}

enum class FinishReason {
    /** The model finished its message. */
    STOP,

    /** The model wants tools run; see [ChatMessage.toolCalls]. */
    TOOL_CALLS,

    /** Output was cut off at the token limit. */
    LENGTH,

    /** The provider declined or filtered the reply. */
    REFUSED,
    UNKNOWN,
}

data class ChatResponse(
    val message: ChatMessage,
    val finishReason: FinishReason,
    /** Null when the provider did not report usage. */
    val usage: TokenUsage? = null,
    /** The model that actually answered, as reported by the provider. May differ from the one requested. */
    val modelName: String,
)

/** Incremental output of a streamed reply. A stream ends with exactly one [Completed]. */
sealed interface ChatChunk {
    data class Text(val delta: String) : ChatChunk

    data class Reasoning(val delta: String) : ChatChunk

    /** A tool call has been fully received. */
    data class ToolCall(val call: ToolCallRequest) : ChatChunk

    data class Completed(val response: ChatResponse) : ChatChunk
}
