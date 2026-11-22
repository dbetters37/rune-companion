package com.aicontrolcenter.device.scan

/**
 * Guesses from an app's label and package id whether it is an AI application.
 *
 * Deliberately conservative: a match only proposes the app (source HEURISTIC, unverified until
 * the user confirms it), so a missed app costs one manual registration while a false match puts
 * a stranger in the registry. For that reason the rules use technical terms only:
 *
 *  - no brand names: "Gemini" is also a crypto exchange and "Copilot" a sat-nav, and the real
 *    products are found through the catalog anyway;
 *  - never the words "chat", "assistant" or "bot" on their own, which would sweep in every
 *    messaging app;
 *  - "AI" only as a separate, upper-case word ("Pocket AI", "OpenAI"), so that "Mail", "AIMP",
 *    "Samurai" and the Italian word "ai" do not match.
 */
object AiNameHeuristic {

    /** True when either the visible [label] or the [packageName] carries one of the technical terms. */
    fun looksLikeAiApp(label: String, packageName: String): Boolean =
        labelSuggestsAi(label) || packageSuggestsAi(packageName)

    internal fun labelSuggestsAi(label: String): Boolean {
        if (DOT_AI_SUFFIX.containsMatchIn(label)) return true
        return WORD.findAll(label).any { match ->
            val word = match.value
            hasTechnicalTerm(word.lowercase()) || CAMEL_BOUNDARY.split(word).any { it == "AI" }
        }
    }

    internal fun packageSuggestsAi(packageName: String): Boolean =
        packageName.lowercase().split('.', '_').any { segment ->
            segment == "ai" || hasTechnicalTerm(segment)
        }

    /** [word] is one lower-case word or package segment. */
    private fun hasTechnicalTerm(word: String): Boolean =
        CONTAINED_TERMS.any { it in word } ||
            // Prefix or suffix only: "hallmark" contains "llm" too.
            word.startsWith("llm") || word.endsWith("llm")

    private val CONTAINED_TERMS = listOf("gpt", "chatbot", "openai", "ollama", "llamacpp")

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** Splits "ChatGPT" into Chat|GPT, "OpenAI" into Open|AI and "AIChat" into AI|Chat. */
    private val CAMEL_BOUNDARY = Regex("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})")

    /** Domain-style names such as "Otter.ai" or "Character.AI". */
    private val DOT_AI_SUFFIX = Regex("[\\p{L}\\p{N}]\\.[aA][iI](?![\\p{L}\\p{N}])")
}
