package com.aicontrolcenter.providers.http

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ProviderKind
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URISyntaxException

/**
 * A provider's base address, checked before anything is sent to it.
 *
 * Unencrypted `http://` is accepted only when [Locality.infer] places the address on this
 * device or the local network. Anything it classifies as cloud, which includes every address it
 * cannot parse strictly, must be `https://`, so a prompt or a key never crosses the internet in
 * the clear. As a second guard the strict parser used for that classification and OkHttp's
 * lenient one must agree on the host: an address written to look local to one and remote to
 * the other is refused.
 */
internal class Endpoint private constructor(private val base: HttpUrl) {
    val host: String get() = base.host

    /** [path] is appended to the base path; a query on the base address (an api-version, say) is kept. */
    fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = base.newBuilder().addPathSegments(path.trimStart('/'))
        for ((name, value) in query) builder.addQueryParameter(name, value)
        return builder.build()
    }

    override fun toString(): String = "Endpoint(${base.scheme}://${base.host}:${base.port})"

    companion object {
        /**
         * @param baseUrl what the owner entered; blank means [defaultBaseUrl]
         * @throws LlmException.InvalidRequest when the address is not a usable http(s) URL
         * @throws LlmException.BlockedByPolicy when it would send data unencrypted beyond the local network
         */
        fun resolve(kind: ProviderKind, baseUrl: String?, defaultBaseUrl: String): Endpoint {
            val raw = baseUrl?.trim().orEmpty().ifEmpty { defaultBaseUrl }
            val url = raw.toHttpUrlOrNull()
                ?: throw LlmException.InvalidRequest("The provider address is not a valid http:// or https:// URL.")
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
                throw LlmException.InvalidRequest(
                    "The provider address must not contain a user name or password. Store the secret as the provider's API key.",
                )
            }
            if (!url.isHttps) requireLocal(kind, raw, url)
            return Endpoint(url)
        }

        private fun requireLocal(kind: ProviderKind, raw: String, url: HttpUrl) {
            if (Locality.infer(kind, raw) == Locality.CLOUD) {
                throw LlmException.BlockedByPolicy(
                    "Refusing to use unencrypted http:// for ${url.host}, which is not on this device or the local network. " +
                        "Use an https:// address.",
                )
            }
            val strictHost = try {
                URI(raw).host
            } catch (e: URISyntaxException) {
                null
            }?.removePrefix("[")?.removeSuffix("]")?.lowercase()
            // OkHttp prints IPv6 literals in canonical form, so those are compared only by kind.
            val agree = strictHost != null &&
                if (':' in strictHost) ':' in url.host else strictHost == url.host
            if (!agree) {
                throw LlmException.BlockedByPolicy(
                    "Refusing to use unencrypted http:// for an address whose host is ambiguous. Use an https:// address.",
                )
            }
        }
    }
}
