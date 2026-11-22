package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ProviderDraft
import com.aicontrolcenter.core.llm.ProviderField
import com.aicontrolcenter.core.llm.ProviderValidationIssue
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind
import java.net.URI
import java.net.URISyntaxException

/**
 * The rules a provider must meet before it is saved. They exist for two reasons: so a typo is
 * caught while the owner is still looking at the form, and so nothing that carries a
 * credential ends up in the registry, which is stored in plain form. No message ever repeats
 * the key or a header value.
 */
internal object ProviderDraftValidator {
    const val MAX_NAME_LENGTH = 60
    private const val MAX_KEY_LENGTH = 4_096
    private const val MAX_HEADERS = 20
    private const val MAX_HEADER_VALUE_LENGTH = 1_000

    /** RFC 9110 "token": the characters allowed in a header name. */
    private val HEADER_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")

    /** Names that carry credentials by convention, and fragments that mark others that do. */
    private val CREDENTIAL_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "api-key")
    private val CREDENTIAL_FRAGMENTS = listOf("api-key", "apikey", "api_key", "authorization", "auth-token", "access-token", "secret", "password", "credential", "cookie")

    /** Headers the HTTP client sets itself; overriding them breaks requests in ways that are hard to trace. */
    private val MANAGED_HEADERS = setOf("host", "content-length", "content-type", "transfer-encoding", "connection")

    /**
     * @param others every other registered provider (the one being edited excluded)
     * @param storedKey whether the provider being edited already has a key in the vault
     */
    fun validate(draft: ProviderDraft, others: List<ModelProvider>, storedKey: Boolean): List<ProviderValidationIssue> {
        val issues = ArrayList<ProviderValidationIssue>()
        fun issue(field: ProviderField, message: String) {
            issues += ProviderValidationIssue(field, message)
        }

        val name = draft.name.trim()
        when {
            name.isEmpty() -> issue(ProviderField.NAME, "Give the provider a name.")
            name.length > MAX_NAME_LENGTH -> issue(ProviderField.NAME, "Keep the name to $MAX_NAME_LENGTH characters or fewer.")
            others.any { nameKey(it.name) == nameKey(name) } -> issue(ProviderField.NAME, "Another provider is already called \"$name\".")
        }
        baseUrlProblem(draft.kind, draft.baseUrl)?.let { issue(ProviderField.BASE_URL, it) }
        apiKeyProblem(draft, storedKey)?.let { issue(ProviderField.API_KEY, it) }
        headerProblems(draft.extraHeaders).forEach { issue(ProviderField.HEADERS, it) }
        return issues
    }

    /** One key for "the same name": trimmed and case-folded. */
    fun nameKey(name: String): String = name.trim().uppercase().lowercase()

    /** The address as stored: trimmed, without trailing slashes, null when blank. */
    fun normaliseBaseUrl(raw: String?): String? = raw?.trim()?.trimEnd('/')?.ifEmpty { null }

    /** The key as stored: surrounding whitespace from pasting removed; null or blank means "no key entered". */
    fun normaliseKey(raw: String?): String? = raw?.trim()?.ifEmpty { null }

    private fun baseUrlProblem(kind: ProviderKind, raw: String?): String? {
        val url = normaliseBaseUrl(raw)
        if (url == null) {
            val required = kind == ProviderKind.OPENAI_COMPATIBLE || kind == ProviderKind.OLLAMA
            return if (required) "Enter the server's address, for example http://192.168.1.20:11434." else null
        }
        if (kind.runsInProcess) return "This kind of provider runs inside the app and has no address."
        // java.net.URI rejects what lenient parsers reinterpret (spaces, backslashes, stray characters), so
        // an address means the same thing here, in Locality.infer and in the HTTP client.
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return "That is not a valid address. It should look like https://host/path."
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "The address must start with http:// or https://."
        if (uri.host.isNullOrEmpty()) return "The address needs a valid host name or IP address."
        if (uri.rawUserInfo != null) return "Do not put a user name or password in the address. Use the API key field."
        if (uri.rawQuery != null || uri.rawFragment != null) return "The address must not contain a \"?\" or \"#\" part."
        if (uri.port == 0 || uri.port > 65_535) return "The port number in the address is not valid."
        if (scheme == "http" && Locality.infer(kind, url) == Locality.CLOUD) {
            return "Plain http is only allowed for this phone and your local network (an IP address or a .local name). Use https for a server on the internet."
        }
        return null
    }

    private fun apiKeyProblem(draft: ProviderDraft, storedKey: Boolean): String? {
        val entered = normaliseKey(draft.apiKey)
        if (entered != null) {
            if (draft.kind.runsInProcess) return "This kind of provider does not use an API key."
            if (entered.any { it.isWhitespace() || it.isISOControl() }) return "The key contains spaces or line breaks. Paste it again."
            if (entered.length > MAX_KEY_LENGTH) return "That is too long to be an API key."
        }
        // A null key keeps the stored one when editing; an empty one removes it.
        val willHaveKey = entered != null || (draft.apiKey == null && storedKey)
        val vendorCloud = (draft.kind == ProviderKind.ANTHROPIC || draft.kind == ProviderKind.GEMINI) &&
            Locality.infer(draft.kind, normaliseBaseUrl(draft.baseUrl)) == Locality.CLOUD
        return if (vendorCloud && !willHaveKey) "This provider needs an API key." else null
    }

    private fun headerProblems(headers: Map<String, String>): List<String> {
        val problems = ArrayList<String>()
        if (headers.size > MAX_HEADERS) problems += "Use at most $MAX_HEADERS extra headers."
        val seen = HashSet<String>()
        for ((rawName, value) in headers) {
            val name = rawName.trim()
            val key = name.lowercase()
            when {
                !HEADER_NAME.matches(name) -> problems += "\"${rawName.take(40)}\" is not a valid header name."
                key in CREDENTIAL_HEADERS || CREDENTIAL_FRAGMENTS.any { it in key } ->
                    problems += "The header \"$name\" carries a credential. Put the key in the API key field, where it is stored encrypted."
                key in MANAGED_HEADERS -> problems += "The header \"$name\" is set by the app and cannot be overridden."
                !seen.add(key) -> problems += "The header \"$name\" is listed twice."
                value.length > MAX_HEADER_VALUE_LENGTH -> problems += "The value of \"$name\" is too long."
                value.any { it != '\t' && (it < ' ' || it > '~') } ->
                    problems += "The value of \"$name\" contains line breaks or characters that are not allowed in a header."
            }
        }
        return problems
    }
}
