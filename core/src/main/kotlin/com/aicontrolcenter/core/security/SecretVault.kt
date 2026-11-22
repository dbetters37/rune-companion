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

/** A masked preview safe to display, e.g. "sk-a…9f2c". */
fun maskSecret(secret: String): String = when {
    secret.length <= 8 -> "•".repeat(secret.length.coerceAtLeast(4))
    else -> secret.take(4) + "…" + secret.takeLast(4)
}
