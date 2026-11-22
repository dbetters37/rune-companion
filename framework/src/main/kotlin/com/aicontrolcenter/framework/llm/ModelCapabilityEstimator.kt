package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ProviderKind

/**
 * A first guess at what a model can do when its provider's listing says nothing about it.
 *
 * The guess errs towards "unknown": a model wrongly believed to call tools natively fails its
 * request, while one wrongly believed not to simply uses the prompted tool protocol, which
 * always works. So only hosted model families whose abilities are well established are
 * recognised, and models on local servers are left for the owner (or the server) to describe.
 * Nothing here is ever applied over capabilities a provider reported or the owner entered.
 */
internal object ModelCapabilityEstimator {
    private val EMBEDDING = Regex("embed|bge-|e5-|rerank", RegexOption.IGNORE_CASE)
    private val HOSTED_TOOL_CALLERS = Regex(
        "^(openai/|anthropic/|google/|mistralai/|x-ai/)?" +
            "(gpt-[345]|o[134](-|$)|chatgpt-|claude-|gemini-|mistral-(large|medium|small)|codestral|pixtral|grok-|deepseek-chat|command-r)",
        RegexOption.IGNORE_CASE,
    )
    private val VISION = Regex("gpt-4o|gpt-4\\.1|gpt-5|claude-|gemini-|pixtral|vision|llava", RegexOption.IGNORE_CASE)

    fun estimate(kind: ProviderKind, modelName: String): ModelCapabilities {
        if (EMBEDDING.containsMatchIn(modelName)) {
            return ModelCapabilities(chat = false, streaming = false, embeddings = true)
        }
        val hostedFamily = HOSTED_TOOL_CALLERS.containsMatchIn(modelName)
        return when (kind) {
            ProviderKind.SIMULATOR -> Simulator.capabilities
            ProviderKind.ANTHROPIC, ProviderKind.GEMINI ->
                ModelCapabilities(toolCalling = true, vision = true, jsonOutput = kind == ProviderKind.GEMINI)
            ProviderKind.OPENAI_COMPATIBLE ->
                ModelCapabilities(toolCalling = hostedFamily, vision = hostedFamily && VISION.containsMatchIn(modelName), jsonOutput = hostedFamily)
            ProviderKind.OLLAMA, ProviderKind.ON_DEVICE -> ModelCapabilities()
        }
    }
}
