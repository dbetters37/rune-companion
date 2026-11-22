package com.aicontrolcenter.core.security

/**
 * Encrypted storage for secrets such as API keys. Everything else in the app refers to a
 * secret by alias only; the plaintext is read at the moment a request is sent and never
 * logged, exported or shown again in full.
 */
interface SecretVault {
    suspend fun put(alias: String, secret: String)

    /** Returns the secret, or null if nothing is stored under [alias]. */
    suspend fun get(alias: String): String?

    suspend fun contains(alias: String): Boolean

    suspend fun delete(alias: String)

    /** Aliases currently stored. Never returns secret material. */
    suspend fun aliases(): Set<String>
}

/** Thrown when the vault cannot encrypt or decrypt, e.g. the device keystore entry was lost. */
class SecretVaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A masked preview safe to display. Only long secrets reveal anything: at most the first and
 * last four characters of a secret of 20 characters or more. Shorter ones show a fixed mask,
 * so neither their content nor their length leaks.
 */
fun maskSecret(secret: String): String =
    if (secret.length >= 20) secret.take(4) + "…" + secret.takeLast(4) else "••••••••"
