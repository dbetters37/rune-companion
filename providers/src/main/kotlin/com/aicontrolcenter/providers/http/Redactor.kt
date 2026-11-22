package com.aicontrolcenter.providers.http

/**
 * Removes credentials from text that came back from a provider before it is put into an
 * exception message. This module never writes a key into a message itself; the risk it covers
 * is a server, proxy or debugging endpoint that echoes the request's headers in an error body.
 *
 * Values shorter than [MIN_SECRET_LENGTH] are not treated as secrets: local servers are often
 * given placeholders such as "none", and replacing those would mangle ordinary error text.
 */
internal class Redactor(secrets: List<String?>) {
    private val secrets = secrets.mapNotNull { it?.trim() }.filter { it.length >= MIN_SECRET_LENGTH }.distinct()

    fun clean(text: String): String {
        var cleaned = text
        for (secret in secrets) cleaned = cleaned.replace(secret, MASK)
        return BEARER.replace(cleaned, "Bearer $MASK")
    }

    override fun toString(): String = "Redactor(${secrets.size} secrets)"

    private companion object {
        const val MIN_SECRET_LENGTH = 6
        const val MASK = "[redacted]"
        val BEARER = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{6,}")
    }
}
