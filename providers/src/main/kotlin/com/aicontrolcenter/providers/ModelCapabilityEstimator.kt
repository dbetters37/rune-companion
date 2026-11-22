package com.aicontrolcenter.providers

import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ModelCapabilities

/**
 * Guesses what a model can do from its name, for providers whose listing reports nothing.
 *
 * These are estimates, not facts: the owner can correct every field of a registered model, and
 * anything a provider does report takes precedence. The guesses lean towards "cannot":
 *
 *  - Native tool calling is claimed only for families known to have it. A model wrongly marked
 *    as lacking it still works, through the prompted tool protocol; one wrongly marked as
 *    having it fails.
 *  - The context window of a hosted family is the smallest among its current members. For
 *    open-weight models, which usually run on a local server whose window is a setting rather
 *    than a property of the model, it is [LOCAL_CONTEXT_FLOOR]. For unknown names it is null.
 *  - Reasoning and coding tiers come from the family's standing or, for open-weight models,
 *    from the parameter count in the name ("…:7b"). Unknown names stay
 *    [CapabilityTier.UNKNOWN].
 */
object ModelCapabilityEstimator {
    /** A window most local servers are configured to at least; the owner raises it to match theirs. */
    const val LOCAL_CONTEXT_FLOOR = 8_192

    /**
     * @param modelName the provider's wire name, with or without a vendor prefix or a tag
     * @param parameterSize the parameter count when the provider reports it (Ollama's "7.6B"),
     *   used instead of the one parsed from the name
     * @param thinks whether the provider says the model reasons before answering; null leaves
     *   that to the name
     */
    fun estimate(modelName: String, parameterSize: String? = null, thinks: Boolean? = null): ModelCapabilities {
        val name = modelName.trim().lowercase().substringAfterLast('/')
        if (EMBEDDING.containsMatchIn(name)) return ModelCapabilities(chat = false, streaming = false, embeddings = true)
        if (NOT_A_CHAT_MODEL.containsMatchIn(name)) return ModelCapabilities(chat = false, streaming = false)

        val family = FAMILIES.firstOrNull { it.pattern.containsMatchIn(name) }
        val billions = parameterSize?.let(::billionsOf) ?: billionsOf(name)
        // A parameter count in the name marks a build someone runs themselves, whatever the family.
        val hosted = family != null && !family.openWeights && billions == null
        val base = when {
            hosted -> if (SMALL_VARIANT.containsMatchIn(name)) family!!.tier.stepDown() else family!!.tier
            billions != null -> tierForSize(billions)
            else -> family?.tier ?: CapabilityTier.UNKNOWN
        }
        val reasons = thinks ?: REASONS.containsMatchIn(name)
        val tools = family?.tools == true && family.exceptTools?.containsMatchIn(name) != true
        return ModelCapabilities(
            chat = true,
            toolCalling = tools,
            vision = (family?.vision == true && family.exceptVision?.containsMatchIn(name) != true) || VISION.containsMatchIn(name),
            streaming = true,
            // Providers that implement tool calling almost always implement JSON mode as well.
            jsonOutput = tools,
            reasoning = if (reasons) base.stepUp() else base,
            coding = if (CODER.containsMatchIn(name)) base.stepUp() else base,
            contextWindow = when {
                hosted -> family!!.context
                family != null || billions != null -> LOCAL_CONTEXT_FLOOR
                else -> null
            },
        )
    }

    private class Family(
        pattern: String,
        val tools: Boolean = false,
        val vision: Boolean = false,
        val tier: CapabilityTier = CapabilityTier.UNKNOWN,
        val context: Int? = null,
        /** Weights are public, so the model is probably served locally and its size decides its tier. */
        val openWeights: Boolean = false,
        exceptTools: String? = null,
        exceptVision: String? = null,
    ) {
        val pattern = Regex(pattern)
        val exceptTools = exceptTools?.let(::Regex)
        val exceptVision = exceptVision?.let(::Regex)
    }

    /** First match wins, so specific families come before the general ones they overlap. */
    private val FAMILIES = listOf(
        // Hosted families.
        Family("^o[1-9](-|$)", tools = true, vision = true, tier = CapabilityTier.STRONG, context = 128_000),
        Family("^(gpt-[5-9]|chatgpt-[5-9])", tools = true, vision = true, tier = CapabilityTier.STRONG, context = 128_000),
        Family("^gpt-4\\.1", tools = true, vision = true, tier = CapabilityTier.GOOD, context = 1_000_000),
        Family("^(gpt-4o|chatgpt-4o)", tools = true, vision = true, tier = CapabilityTier.GOOD, context = 128_000),
        Family("^gpt-4-(turbo|\\d{4}-preview)", tools = true, vision = true, tier = CapabilityTier.GOOD, context = 128_000),
        Family("^gpt-4", tools = true, tier = CapabilityTier.GOOD, context = 8_192),
        Family("^gpt-3\\.5", tools = true, tier = CapabilityTier.BASIC, context = 16_385),
        Family("^claude.*(opus|sonnet)", tools = true, vision = true, tier = CapabilityTier.STRONG, context = 200_000),
        Family("^claude", tools = true, vision = true, tier = CapabilityTier.GOOD, context = 200_000),
        Family(
            "^gemini.*pro", tools = true, vision = true, tier = CapabilityTier.STRONG, context = 1_000_000,
            exceptTools = "image|tts",
        ),
        Family(
            "^gemini", tools = true, vision = true, tier = CapabilityTier.GOOD, context = 1_000_000,
            exceptTools = "image|tts",
        ),
        Family("^grok-[4-9]", tools = true, vision = true, tier = CapabilityTier.STRONG, context = 128_000),
        Family("^grok", tools = true, tier = CapabilityTier.GOOD, context = 128_000),
        Family("^deepseek-(chat|reasoner|v[3-9])", tools = true, tier = CapabilityTier.GOOD, context = 64_000),
        Family("^(mistral-(large|medium)|magistral)", tools = true, tier = CapabilityTier.GOOD, context = 128_000),
        Family("^pixtral", tools = true, vision = true, tier = CapabilityTier.GOOD, context = 128_000),
        Family("^(codestral|devstral)", tools = true, tier = CapabilityTier.GOOD, context = 32_000),
        Family("^(mistral-small|mistral-tiny|ministral|open-mistral-nemo)", tools = true, tier = CapabilityTier.BASIC, context = 32_000),
        Family("^command-(r|a)", tools = true, tier = CapabilityTier.GOOD, context = 128_000),
        Family("^(kimi|moonshot)", tools = true, tier = CapabilityTier.GOOD, context = 128_000),
        Family("^glm-[4-9]", tools = true, tier = CapabilityTier.GOOD, context = 128_000),
        // Open-weight families: tier from the parameter count, context from the local floor.
        Family("gpt-oss", tools = true, openWeights = true),
        Family("llama-?(3[.-][1-9]|[4-9])|llama3-groq-tool-use", tools = true, openWeights = true, exceptTools = "vision"),
        Family("qwen-?(2\\.5|[3-9])|qwq", tools = true, openWeights = true),
        Family("mistral-nemo|mistral-small|hermes|firefunction|functionary|functiongemma|granite-?[3-9]|nemotron", tools = true, openWeights = true),
        Family("phi-?4-mini", tools = true, openWeights = true),
        Family("gemma-?3n?", vision = true, openWeights = true, exceptVision = "(^|[^0-9.])(1b|270m)"),
        Family("llama|mistral|mixtral|gemma|phi|qwen|deepseek|yi-|vicuna|orca|zephyr|solar|falcon|starcoder|codellama|smollm|tinyllama", openWeights = true),
    )

    private val EMBEDDING = Regex("embed|(^|[-_])(bge|e5|gte)[-_]|minilm|nomic-bert")
    private val NOT_A_CHAT_MODEL = Regex(
        "whisper|(^|[-_])tts|tts($|[-_])|dall-e|gpt-image|imagen|(^|[-_])veo|(^|[-_])sora|moderation|rerank|" +
            "stable-diffusion|sdxl|(^|[-_])flux|transcribe|speech|lyria|image-generation",
    )
    private val VISION = Regex("vision|llava|(^|[-_.0-9])vl($|[-_:])|vlm|multimodal|pixtral|moondream|minicpm-v")
    private val REASONS = Regex("reason|think|(^|[-_])r1($|[-_:])|qwq|magistral|^o[1-9](-|$)")
    private val CODER = Regex("coder|codestral|devstral|starcoder|codellama|codegemma|(^|[-_])code($|[-_:])")
    private val SMALL_VARIANT = Regex("(^|[-_.])(mini|nano|lite|micro|tiny|small)($|[-_.:])")
    private val SIZE_BILLIONS = Regex("(?<![a-df-z0-9.])(\\d{1,3}(?:\\.\\d+)?)b(?![a-z0-9])")
    private val SIZE_MILLIONS = Regex("(?<![a-z0-9.])(\\d{2,4})m(?![a-z0-9])")

    private fun billionsOf(text: String): Double? {
        val lower = text.lowercase()
        SIZE_BILLIONS.find(lower)?.let { return it.groupValues[1].toDoubleOrNull() }
        return SIZE_MILLIONS.find(lower)?.groupValues?.get(1)?.toDoubleOrNull()?.div(1000)
    }

    private fun tierForSize(billions: Double): CapabilityTier = if (billions < 13) CapabilityTier.BASIC else CapabilityTier.GOOD

    private fun CapabilityTier.stepUp(): CapabilityTier = when (this) {
        CapabilityTier.UNKNOWN -> CapabilityTier.UNKNOWN
        CapabilityTier.BASIC -> CapabilityTier.GOOD
        CapabilityTier.GOOD, CapabilityTier.STRONG -> CapabilityTier.STRONG
    }

    private fun CapabilityTier.stepDown(): CapabilityTier = when (this) {
        CapabilityTier.STRONG -> CapabilityTier.GOOD
        CapabilityTier.GOOD -> CapabilityTier.BASIC
        CapabilityTier.BASIC, CapabilityTier.UNKNOWN -> this
    }
}
