package com.aicontrolcenter.providers.http

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which optional request settings a provider refused for a model, so that later
 * requests leave them out from the start instead of paying for a rejected round trip each time.
 *
 * Optional settings ([com.aicontrolcenter.core.llm.ChatRequest] documents them as hints) are
 * sent optimistically. When a provider answers 400 or 422 and names one of them, the adapter
 * repeats the request once without that setting. Whether the refusal is remembered depends on
 * how it was worded: "unsupported parameter" is a property of the model and is kept; "value
 * too large" concerns this one request and is not.
 *
 * The memory lives in the adapter factory, so it outlasts the short-lived adapters created per
 * call, and is lost when the app restarts. Thread-safe.
 */
internal class ParameterMemory {
    private val refused = ConcurrentHashMap<String, Set<String>>()

    /** @param scope identifies one model at one provider address */
    fun refused(scope: String): Set<String> = refused[scope].orEmpty()

    fun remember(scope: String, setting: String) {
        refused.merge(scope, setOf(setting)) { known, added -> known + added }
    }
}

/** A setting the provider would not accept. */
internal data class Refusal(
    val setting: String,
    /** The provider said the setting itself is unsupported, rather than objecting to its value. */
    val permanent: Boolean,
)

internal object RefusedSetting {
    private val UNSUPPORTED_CODES = setOf("unsupported_parameter", "unsupported_value", "unknown_parameter", "extra_forbidden")
    private val UNSUPPORTED_PHRASES = listOf(
        "unsupported", "not supported", "does not support", "doesn't support", "not permitted", "not allowed",
        "unrecognized", "unknown", "unexpected", "additional properties", "only the default", "is not enabled",
    )

    /**
     * Which of the settings that were sent the provider is objecting to, or null when the
     * failure is about something else.
     *
     * @param sent setting key to the names a provider may call it by, in the order to test
     */
    fun find(failure: HttpFailure, sent: Map<String, List<String>>): Refusal? {
        if (failure.status != 400 && failure.status != 422) return null
        val detail = failure.detail
        val param = detail.param?.lowercase()
        // A field the provider blames explicitly is trusted over a search of its prose, which
        // may quote parts of the request.
        val setting = sent.entries.firstOrNull { (_, names) -> names.any { param != null && blames(param, it.lowercase()) } }?.key
            ?: sent.entries.firstOrNull { (_, names) -> names.any { mentions(detail.searchable, it.lowercase()) } }?.key
            ?: return null
        val permanent = detail.code in UNSUPPORTED_CODES || detail.type == "extra_forbidden" ||
            UNSUPPORTED_PHRASES.any { it in detail.searchable }
        return Refusal(setting, permanent)
    }

    private fun blames(param: String, name: String): Boolean =
        param == name || param.startsWith("$name.") || param.endsWith(".$name") || ".$name." in param

    /** [name] as a whole word, so that "reasoning" is not found inside "reasoning_effort". */
    private fun mentions(text: String, name: String): Boolean {
        var index = text.indexOf(name)
        while (index != -1) {
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + name.length)
            if (!isWordChar(before) && !isWordChar(after)) return true
            index = text.indexOf(name, index + 1)
        }
        return false
    }

    private fun isWordChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')
}
