package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ReasoningEffort
import com.aicontrolcenter.core.llm.ToolChoice
import com.aicontrolcenter.core.llm.ToolDefinition
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaFallbackParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolChoiceNone
import com.anthropic.models.beta.messages.MessageCreateParams

/**
 * Builds the Messages API request for one turn.
 *
 * Requests are built from the SDK's beta message types, a superset of the stable ones and the
 * only place the refusal-fallback parameter exists. No beta feature is switched on unless
 * that parameter is sent.
 *
 * How the neutral [ChatRequest] maps:
 *  - `maxOutputTokens` becomes the required `max_tokens`, with a default that is larger for
 *    streamed calls because only they can run long.
 *  - `temperature` is ignored: current Claude models reject sampling parameters outright.
 *  - `reasoningEffort` becomes `output_config.effort` where the model has that control.
 *  - `toolChoice` NONE becomes `tool_choice: none`; AUTO is the API's default and is not
 *    sent. Forcing a tool is not offered because current models reject it.
 *  - `jsonOutput` is ignored: Claude enforces JSON only against a schema, which the neutral
 *    request does not carry, and the older trick of prefilling the reply is rejected.
 *  - Thinking is never given a token budget. Which thinking parameter a model gets, if any,
 *    is decided by [ModelTraits].
 *
 * Prompt caching follows Anthropic's recommendation for agent loops: one explicit breakpoint
 * at the end of the stable prefix (the last system block, which also covers the tools rendered
 * before it, or the last tool when there is no system prompt) plus automatic caching of the
 * growing conversation.
 */
internal object RequestBuilder {
    /** Enough for a full answer while staying inside what the SDK allows without streaming. */
    const val DEFAULT_MAX_TOKENS = 16_000L

    const val DEFAULT_MAX_TOKENS_STREAMING = 64_000L

    /**
     * The SDK refuses a non-streaming request that could run past ten minutes, which it
     * estimates from `max_tokens` at 128,000 tokens an hour. Calls above this are streamed.
     */
    const val MAX_TOKENS_WITHOUT_STREAMING = 21_333L

    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    private const val FALLBACK_MODEL = "claude-opus-4-8"

    private val ephemeral: BetaCacheControlEphemeral = BetaCacheControlEphemeral.builder().build()

    fun maxTokens(request: ChatRequest, streaming: Boolean): Long =
        request.maxOutputTokens?.toLong()?.coerceAtLeast(1) ?: if (streaming) DEFAULT_MAX_TOKENS_STREAMING else DEFAULT_MAX_TOKENS

    /** Whether [build] with `refusalFallbacks = true` would actually send the opt-in for this model. */
    fun sendsFallbacks(request: ChatRequest): Boolean = ModelTraits.of(request.model).fallback != RefusalFallback.NONE

    /** @throws LlmException.InvalidRequest when the request cannot be expressed as a valid call. */
    fun build(request: ChatRequest, streaming: Boolean, refusalFallbacks: Boolean, replayThinking: Boolean = true): MessageCreateParams {
        val model = request.model.trim()
        if (model.isEmpty()) throw LlmException.InvalidRequest("No model name was given for this call.")
        val traits = ModelTraits.of(model)
        val leadingSystem = request.messages.takeWhile { it.role == ChatRole.SYSTEM }
        val conversation = WireMessages(traits, replayThinking).convert(request.messages.drop(leadingSystem.size))
        if (conversation.isEmpty()) throw LlmException.InvalidRequest("There is no message to send to the model.")
        if (conversation.last().isAssistant()) {
            throw LlmException.InvalidRequest(
                "The conversation ends with an assistant message. Claude models do not continue a prefilled reply; " +
                    "end with a user message or a tool result.",
            )
        }

        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens(request, streaming))
            .messages(conversation)
            .cacheControl(ephemeral)

        val system = leadingSystem.map { it.text }.filter { it.isNotBlank() }
        if (system.isNotEmpty()) {
            builder.systemOfBetaTextBlockParams(
                system.mapIndexed { index, text ->
                    val block = BetaTextBlockParam.builder().text(text)
                    if (index == system.lastIndex) block.cacheControl(ephemeral)
                    block.build()
                },
            )
        }
        request.tools.forEachIndexed { index, tool ->
            builder.addTool(tool(tool, cacheBreakpoint = system.isEmpty() && index == request.tools.lastIndex))
        }
        if (request.tools.isNotEmpty() && request.toolChoice == ToolChoice.NONE) {
            builder.toolChoice(BetaToolChoiceNone.builder().build())
        }

        when (traits.thinking) {
            ThinkingRequest.OMIT -> Unit
            ThinkingRequest.ADAPTIVE -> builder.thinking(BetaThinkingConfigAdaptive.builder().build())
            ThinkingRequest.ADAPTIVE_SUMMARIZED ->
                builder.thinking(BetaThinkingConfigAdaptive.builder().display(BetaThinkingConfigAdaptive.Display.SUMMARIZED).build())
        }
        request.reasoningEffort?.takeIf { traits.effort }?.let { effort ->
            builder.outputConfig(BetaOutputConfig.builder().effort(effort.toSdk()).build())
        }
        if (refusalFallbacks) {
            when (traits.fallback) {
                RefusalFallback.NONE -> Unit
                RefusalFallback.SERVER_DEFAULT -> builder.addBeta(FALLBACK_BETA).fallbacksDefault()
                RefusalFallback.OPUS_4_8 ->
                    builder.addBeta(FALLBACK_BETA).fallbacksOfFallbackParams(listOf(BetaFallbackParam.builder().model(FALLBACK_MODEL).build()))
            }
        }
        return builder.build()
    }

    /** The JSON Schema is passed through as written; the typed schema builder would lose keywords it does not model. */
    private fun tool(definition: ToolDefinition, cacheBreakpoint: Boolean): BetaTool {
        val tool = BetaTool.builder().name(definition.name).inputSchema(definition.parameters.toSdkJson())
        if (definition.description.isNotBlank()) tool.description(definition.description)
        if (cacheBreakpoint) tool.cacheControl(ephemeral)
        return tool.build()
    }

    private fun ReasoningEffort.toSdk(): BetaOutputConfig.Effort = when (this) {
        ReasoningEffort.LOW -> BetaOutputConfig.Effort.LOW
        ReasoningEffort.MEDIUM -> BetaOutputConfig.Effort.MEDIUM
        ReasoningEffort.HIGH -> BetaOutputConfig.Effort.HIGH
    }
}
