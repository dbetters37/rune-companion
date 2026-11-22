package com.aicontrolcenter.device.catalog

import java.net.URLEncoder

/**
 * Validates and fills prompt deep-link templates such as `https://chat.example.com/?q={prompt}`.
 *
 * Only https templates are accepted, and the placeholder must come after the host. A template
 * reaches the launcher from the registry, so anything else (`intent:`, `file:`, `content:`,
 * cleartext `http:`, a prompt-controlled host) is refused instead of being turned into an intent.
 */
object PromptLinkTemplate {
    const val PLACEHOLDER = "{prompt}"

    /** What must precede the first placeholder: scheme, a plain host, then path, query or fragment. */
    private val FIXED_PREFIX = Regex("^https://[A-Za-z0-9.-]+(:\\d+)?[/?#].*$")

    /** True when [template] is an https URL whose path, query or fragment contains [PLACEHOLDER]. */
    fun isUsable(template: String): Boolean =
        PLACEHOLDER in template &&
            template.none { it.isWhitespace() || it.isISOControl() } &&
            FIXED_PREFIX.matches(template.substringBefore(PLACEHOLDER))

    /**
     * Replaces every [PLACEHOLDER] with [prompt], percent-encoded so that no character of the
     * prompt can add query parameters, a fragment or a different path to the link.
     *
     * @throws IllegalArgumentException if the template is not [isUsable].
     */
    fun fill(template: String, prompt: String): String {
        require(isUsable(template)) { "Not a usable https prompt link template." }
        return template.replace(PLACEHOLDER, encode(prompt))
    }

    /** RFC 3986 percent-encoding: URLEncoder's form encoding with its two deviations undone. */
    private fun encode(text: String): String =
        URLEncoder.encode(text, Charsets.UTF_8.name()).replace("+", "%20").replace("*", "%2A")
}
