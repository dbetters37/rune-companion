package com.aicontrolcenter.providers.openai

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ProviderData
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.providers.http.array
import com.aicontrolcenter.providers.http.obj
import com.aicontrolcenter.providers.http.objects
import com.aicontrolcenter.providers.http.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The parts of an assistant turn that some OpenAI-compatible providers want back unchanged on
 * the next request, kept in [ChatMessage.providerData]:
 *
 *  - [toolCalls] when the provider attached fields of its own to a call. Google's
 *    OpenAI-compatible endpoint carries Gemini's thought signature that way
 *    (`extra_content.google.thought_signature`) and rejects the follow-up without it.
 *  - [reasoningDetails], OpenRouter's opaque reasoning blocks, which it asks to have returned
 *    so that reasoning models keep their state across tool calls.
 *  - [reasoningContent] for a turn that also called tools. DeepSeek and Moonshot require the
 *    reasoning text of such a turn to accompany the tool results.
 *
 * Nothing is stored for an ordinary reply, which is rebuilt from the neutral fields. The data
 * records the host it came from and is replayed only to that host, since another
 * OpenAI-compatible service would not know what to do with it. Sending these fields is still
 * optimistic, so they take part in the parameter tolerance like any optional setting.
 */
internal class OpenAiEcho(
    val toolCalls: JsonArray?,
    val reasoningContent: String?,
    val reasoningDetails: JsonArray?,
) {
    fun toProviderData(origin: String): ProviderData? {
        if (toolCalls == null && reasoningContent == null && reasoningDetails == null) return null
        return ProviderData(
            ProviderKind.OPENAI_COMPATIBLE,
            buildJsonObject {
                put(ORIGIN, origin)
                toolCalls?.let { put(TOOL_CALLS, it) }
                reasoningContent?.let { put(REASONING_CONTENT, it) }
                reasoningDetails?.let { put(REASONING_DETAILS, it) }
            },
        )
    }

    companion object {
        private const val ORIGIN = "origin"
        private const val TOOL_CALLS = "tool_calls"
        private const val REASONING_CONTENT = "reasoning_content"
        private const val REASONING_DETAILS = "reasoning_details"
        private val STANDARD_CALL_FIELDS = setOf("id", "type", "function", "index")

        /** What to replay with [message], or null when it carries nothing for this host. */
        fun of(message: ChatMessage, origin: String): OpenAiEcho? {
            val data = message.providerData?.takeIf { it.kind == ProviderKind.OPENAI_COMPATIBLE } ?: return null
            val payload = data.payload as? JsonObject ?: return null
            if (payload.string(ORIGIN) != origin) return null
            // Stored calls are used only while they still describe the calls of the message.
            val calls = payload.array(TOOL_CALLS)?.takeIf { stored ->
                stored.objects().map { it.string("id") } == message.toolCalls.map { it.id }
            }
            return OpenAiEcho(calls, payload.string(REASONING_CONTENT), payload.array(REASONING_DETAILS))
        }

        /**
         * @param calls the parsed calls, with the provider's extra fields per call
         * @param reasoningContent the reasoning text, when the provider sent it as `reasoning_content`
         */
        fun capture(
            calls: List<Pair<ToolCallRequest, Map<String, JsonElement>>>,
            reasoningContent: String?,
            reasoningDetails: JsonArray?,
        ): OpenAiEcho {
            val extended = calls.any { (_, extras) -> extras.isNotEmpty() }
            val wireCalls = if (!extended) null else JsonArray(
                calls.map { (call, extras) -> JsonObject(OpenAiRequests.toolCall(call) + extras) },
            )
            return OpenAiEcho(
                toolCalls = wireCalls,
                reasoningContent = reasoningContent?.takeIf { it.isNotEmpty() && calls.isNotEmpty() },
                reasoningDetails = reasoningDetails?.takeIf { it.isNotEmpty() },
            )
        }

        /** The provider's own additions to a tool call. */
        fun extrasOf(rawCall: JsonObject): Map<String, JsonElement> = rawCall.filterKeys { it !in STANDARD_CALL_FIELDS }
    }
}
