package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.security.SecretVaultException
import com.aicontrolcenter.testkit.InMemorySecretVault
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rule 32, the owner's side: an answer that is, or holds, a stored provider key is recognised; nothing else is. */
class VaultKeyCheckTest {
    private val xai = "xai-0123456789abcdefABCDEF0123456789"
    private val openAi = "sk-proj-ZYXWVUTSRQPONMLKJIHGFEDCBA987654"

    private suspend fun vaultWith(vararg entries: Pair<String, String>) = InMemorySecretVault().apply { entries.forEach { (alias, key) -> put(alias, key) } }

    @Test
    fun `the exact key, and a key inside a longer answer, are found`() = runTest {
        val check = VaultKeyCheck(vaultWith("provider/prv_1/2" to xai))

        assertTrue(check.matchesStoredKey(xai))
        assertTrue(check.matchesStoredKey("Sure, it is $xai, use it for the login."))
        assertTrue(check.matchesStoredKey("$xai\n"))
    }

    @Test
    fun `every provider's key counts, the old unversioned alias too, and nothing else in the vault`() = runTest {
        val check = VaultKeyCheck(vaultWith("provider/prv_1" to xai, "provider/prv_2/1" to openAi, "backup/secret" to "not-a-provider-key-123456"))

        assertTrue(check.matchesStoredKey("key: $openAi"))
        assertTrue(check.matchesStoredKey(xai))
        assertFalse(check.matchesStoredKey("not-a-provider-key-123456"), "only provider keys are compared")
    }

    @Test
    fun `a key-shaped text that is not stored, or no key at all, is not a match`() = runTest {
        assertFalse(VaultKeyCheck(vaultWith("provider/prv_1" to xai)).matchesStoredKey("xai-9999999999999999999999999999999"))
        assertFalse(VaultKeyCheck(vaultWith("provider/prv_1" to xai)).matchesStoredKey(xai.dropLast(1)))
        assertFalse(VaultKeyCheck(InMemorySecretVault()).matchesStoredKey(xai))
        assertFalse(VaultKeyCheck(vaultWith("provider/prv_1" to xai)).matchesStoredKey(""))
    }

    @Test
    fun `a three-character key never matches everything`() = runTest {
        val check = VaultKeyCheck(vaultWith("provider/prv_1" to "abc", "provider/prv_2" to ""))

        assertFalse(check.matchesStoredKey("abc"))
        assertFalse(check.matchesStoredKey("the alphabet starts with abc, then d"))
    }

    @Test
    fun `a vault that cannot be read finds nothing and throws nothing`() = runTest {
        val broken = object : SecretVault by InMemorySecretVault() {
            override suspend fun aliases(): Set<String> = setOf("provider/prv_1", "provider/prv_2")
            override suspend fun get(alias: String): String? = if (alias == "provider/prv_1") throw SecretVaultException("keystore lost") else xai
        }
        val unlisted = object : SecretVault by InMemorySecretVault() {
            override suspend fun aliases(): Set<String> = throw SecretVaultException("keystore lost")
        }

        assertTrue(VaultKeyCheck(broken).matchesStoredKey(xai), "the readable key is still compared")
        assertFalse(VaultKeyCheck(unlisted).matchesStoredKey(xai))
    }
}
