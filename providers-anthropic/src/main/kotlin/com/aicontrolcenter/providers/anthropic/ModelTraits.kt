package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.registry.CapabilityTier

/** What to put in the `thinking` request parameter for a model. Budgets are never sent. */
internal enum class ThinkingRequest {
    /** Send nothing: the model has no adaptive thinking, or it is not a model this table knows. */
    OMIT,

    /** `{"type":"adaptive"}`: thinking must be asked for, and summaries are already the default. */
    ADAPTIVE,

    /**
     * `{"type":"adaptive","display":"summarized"}`. Sent to models that leave thinking text out
     * unless asked. Where thinking is on anyway the explicit adaptive type is documented as
     * equivalent to omitting the parameter; it is there only because `display` lives inside it.
     */
    ADAPTIVE_SUMMARIZED,
}

/** How a model is opted in to server-side refusal fallbacks. */
internal enum class RefusalFallback {
    /** No classifiers, or no documented fallback: sending the parameter could be a 400. */
    NONE,

    /** `fallbacks: "default"`: the API picks the substitute by refusal category. */
    SERVER_DEFAULT,

    /** The explicit list form naming Claude Opus 4.8, for the model documented only with it. */
    OPUS_4_8,
}

/**
 * The request-shaping differences between Claude models, taken from Anthropic's model and
 * migration guides. Everything a model does not accept is left out of the request rather than
 * sent and rejected, so an unknown id gets the most conservative request: no thinking, effort
 * or fallback parameter.
 */
internal data class ModelTraits(
    val thinking: ThinkingRequest,
    /** Accepts `output_config.effort`. */
    val effort: Boolean,
    val fallback: RefusalFallback,
    /** Accepts `role: "system"` entries inside `messages`. */
    val midConversationSystem: Boolean,
) {
    companion object {
        private val UNKNOWN = ModelTraits(ThinkingRequest.OMIT, effort = false, RefusalFallback.NONE, midConversationSystem = false)

        private fun current(fallback: RefusalFallback, midConversationSystem: Boolean = true) =
            ModelTraits(ThinkingRequest.ADAPTIVE_SUMMARIZED, effort = true, fallback, midConversationSystem)

        /** A dated snapshot id ("claude-haiku-4-5-20251001") behaves like its alias. */
        private val snapshotDate = Regex("-\\d{8}$")

        private val known: Map<String, ModelTraits> = mapOf(
            "claude-fable-5-2" to current(RefusalFallback.SERVER_DEFAULT),
            "claude-mythos-5-2" to current(RefusalFallback.SERVER_DEFAULT),
            "claude-fable-5-1" to current(RefusalFallback.OPUS_4_8),
            // These two run classifiers, but the guides name no fallback targets for them.
            "claude-mythos-5-1" to current(RefusalFallback.NONE),
            "claude-fable-5" to current(RefusalFallback.NONE),
            // Runs no classifiers, so it never refuses this way.
            "claude-mythos-5" to current(RefusalFallback.NONE),
            "claude-opus-5-5" to current(RefusalFallback.SERVER_DEFAULT),
            "claude-opus-5" to current(RefusalFallback.SERVER_DEFAULT),
            "claude-sonnet-5-5" to current(RefusalFallback.SERVER_DEFAULT),
            "claude-sonnet-5" to current(RefusalFallback.NONE, midConversationSystem = false),
            // Declines stay declined with the default form, and the list form is a 400.
            "claude-haiku-5-5" to current(RefusalFallback.NONE),
            "claude-opus-4-8" to current(RefusalFallback.NONE),
            "claude-opus-4-7" to current(RefusalFallback.NONE, midConversationSystem = false),
            // `display` arrived with Opus 4.7; these two summarise by default.
            "claude-opus-4-6" to ModelTraits(ThinkingRequest.ADAPTIVE, effort = true, RefusalFallback.NONE, midConversationSystem = false),
            "claude-sonnet-4-6" to ModelTraits(ThinkingRequest.ADAPTIVE, effort = true, RefusalFallback.NONE, midConversationSystem = false),
            // Thinking on the 4.5 generation needs a token budget, which this adapter never sends.
            "claude-opus-4-5" to ModelTraits(ThinkingRequest.OMIT, effort = true, RefusalFallback.NONE, midConversationSystem = false),
        )

        fun of(modelId: String): ModelTraits = known[modelId.trim().lowercase().replace(snapshotDate, "")] ?: UNKNOWN

        /**
         * A coarse strength estimate by model family, as the guides describe the families: the
         * Models API reports limits and features but no quality ranking.
         */
        fun tierOf(modelId: String): CapabilityTier {
            val id = modelId.lowercase()
            return when {
                listOf("fable", "mythos", "opus", "sonnet").any { it in id } -> CapabilityTier.STRONG
                "haiku" in id -> CapabilityTier.GOOD
                else -> CapabilityTier.UNKNOWN
            }
        }
    }
}
