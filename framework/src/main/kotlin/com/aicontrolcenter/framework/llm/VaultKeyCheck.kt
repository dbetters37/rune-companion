package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.OwnerKeyCheck
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.security.SecretVaultException
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

/**
 * The owner's side of rule 32 (Phase 6; security review of wave 1, F3): whether an answer the owner is about to send
 * an agent is, or holds, one of the provider keys stored in the vault.
 *
 * Owner-only: it answers "is this text a stored key?", an oracle no agent may reach; only the owner's answer path
 * (`OwnerServices.answer`, WP5, through `OperatorAssembly`) gets it, and both architecture tests forbid it anywhere
 * an agent reaches.
 *
 * Every key saved for a provider (`provider/<id>` and `provider/<id>/<n>`, current or left over) is compared with
 * every stretch of the text of the key's length, each comparison in constant time ([MessageDigest.isEqual]) and all
 * of them made whatever matched, so the time taken says nothing about where or whether a key matched. Keys shorter
 * than [MIN_KEY_LENGTH] are not compared: a short "key" would match ordinary words. Nothing is kept between calls,
 * and no key is ever returned, logged or put in an error.
 */
class VaultKeyCheck(private val vault: SecretVault) : OwnerKeyCheck {

    override suspend fun matchesStoredKey(text: String): Boolean {
        if (text.length < MIN_KEY_LENGTH) return false
        val aliases = try {
            vault.aliases().filter { it.startsWith(PROVIDER_ALIAS_PREFIX) }
        } catch (e: SecretVaultException) {
            return false
        }
        val candidate = text.toByteArray(Charsets.UTF_16BE)
        var found = false
        for (alias in aliases.sorted()) {
            val key = try {
                vault.get(alias)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A key that cannot be read cannot be compared; the others still are.
                null
            } ?: continue
            if (key.length < MIN_KEY_LENGTH || key.length > text.length) continue
            if (containsConstantTime(candidate, key.toByteArray(Charsets.UTF_16BE))) found = true
        }
        return found
    }

    /** True when [needle] appears in [haystack] at a character boundary (both UTF-16BE), every window compared. */
    private fun containsConstantTime(haystack: ByteArray, needle: ByteArray): Boolean {
        var found = false
        var start = 0
        while (start + needle.size <= haystack.size) {
            if (MessageDigest.isEqual(haystack.copyOfRange(start, start + needle.size), needle)) found = true
            start += BYTES_PER_CHAR
        }
        return found
    }

    /** Bounds. */
    companion object {
        /** The shortest key that is compared: shorter strings are not keys worth guarding and would match ordinary words. */
        const val MIN_KEY_LENGTH: Int = 8

        private const val PROVIDER_ALIAS_PREFIX = "provider/"
        private const val BYTES_PER_CHAR = 2
    }
}
