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
 *    "Samurai" and the Italian word "ai" do not match;
 *  - never an app that names itself as a keyboard, a browser or a messaging or mail app, even
 *    when it also advertises AI ("AI Keyboard", "Opera browser with AI"): those are tools with an
 *    AI feature, not AI applications. The AI browsers this product knows are catalog entries.
 */
object AiNameHeuristic {

    /**
     * True when the visible [label] or the [packageName] carries one of the technical terms and
     * neither says the app is a keyboard, browser or messaging app.
     */
    fun looksLikeAiApp(label: String, packageName: String): Boolean =
        !isAnotherKindOfApp(label, packageName) && (labelSuggestsAi(label) || packageSuggestsAi(packageName))

    /** True when the label or the package id names the app as a keyboard, browser, messaging or mail app. */
    internal fun isAnotherKindOfApp(label: String, packageName: String): Boolean =
        (WORD.findAll(label).map { it.value.lowercase() } + packageName.segments()).any { part ->
            part in OTHER_KIND_WORDS || OTHER_KIND_TERMS.any { it in part }
        }

    internal fun labelSuggestsAi(label: String): Boolean {
        if (DOT_AI_SUFFIX.containsMatchIn(label)) return true
        return WORD.findAll(label).any { match ->
            val word = match.value
            hasTechnicalTerm(word.lowercase()) || CAMEL_BOUNDARY.split(word).any { it == "AI" }
        }
    }

    internal fun packageSuggestsAi(packageName: String): Boolean =
        packageName.segments().any { segment -> segment == "ai" || hasTechnicalTerm(segment) }

    private fun String.segments(): List<String> = lowercase().split('.', '_')

    /** [word] is one lower-case word or package segment. */
    private fun hasTechnicalTerm(word: String): Boolean =
        CONTAINED_TERMS.any { it in word } ||
            // Prefix or suffix only: "hallmark" contains "llm" too.
            word.startsWith("llm") || word.endsWith("llm")

    private val CONTAINED_TERMS = listOf("gpt", "chatbot", "openai", "ollama", "llamacpp")

    /**
     * Contained in a label word or package segment, these name another kind of app ("messag"
     * covers messages and messaging).
     */
    private val OTHER_KIND_TERMS = listOf("keyboard", "inputmethod", "browser", "messenger", "messag", "mail")

    /** The same, but too short to look for inside longer words: "ime" is in "time", "sms" in "prisms". */
    private val OTHER_KIND_WORDS = setOf("ime", "sms", "mms")

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** Splits "ChatGPT" into Chat|GPT, "OpenAI" into Open|AI and "AIChat" into AI|Chat. */
    private val CAMEL_BOUNDARY = Regex("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})")

    /** Domain-style names such as "Otter.ai" or "Character.AI". */
    private val DOT_AI_SUFFIX = Regex("[\\p{L}\\p{N}]\\.[aA][iI](?![\\p{L}\\p{N}])")
}
