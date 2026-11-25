package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.FakeProviderAdapterFactory
import com.aicontrolcenter.testkit.InMemoryProviderRepository
import com.aicontrolcenter.testkit.InMemorySecretVault
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A provider record may use only a key saved for its own id (security review of wave 1, F3, second review): a record
 * that names another provider's key alias, at another address, never gets that key, so it cannot carry it there.
 */
class ProviderKeyOwnershipTest {
    private fun provider(id: String, url: String, alias: String?) = ModelProvider(
        id = ProviderId(id), name = id, kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = url, apiKeyAlias = alias,
        locality = Locality.CLOUD, createdAt = 1, updatedAt = 1,
    )

    @Test
    fun `a record naming another provider's key alias gets no key, its own alias does`() = runTest {
        val providers = InMemoryProviderRepository()
        val vault = InMemorySecretVault()
        val factory = FakeProviderAdapterFactory(ProviderKind.OPENAI_COMPATIBLE) { FakeProviderAdapter() }
        val connections = ProviderConnections(providers, vault, listOf(factory), FakeClock())
        vault.put("provider/prv_owner/1", "sk-owner-secret-0123456789")
        val owner = provider("prv_owner", "https://api.example.com/v1", "provider/prv_owner/1")
        val borrower = provider("prv_evil", "https://attacker.example/v1", "provider/prv_owner/1")
        val slashed = provider("prv_owner/1", "https://attacker2.example/v1", "provider/prv_owner/1")
        providers.upsert(owner)
        providers.upsert(borrower)
        providers.upsert(slashed)

        connections.acquire(owner)
        connections.acquire(borrower)
        connections.acquire(slashed)
        val keys = factory.connections.associate { it.provider.id.value to it.apiKey }
        assertEquals("sk-owner-secret-0123456789", keys["prv_owner"], "the key's own provider gets it")
        assertNull(keys["prv_evil"], "another provider naming its alias does not")
        assertNull(keys["prv_owner/1"], "an id with a slash cannot pass for a versioned alias")
        assertTrue(KeyAliases.belongsTo(ProviderId("prv_owner"), "provider/prv_owner") && KeyAliases.belongsTo(ProviderId("prv_owner"), "provider/prv_owner/12"))
        assertFalse(KeyAliases.belongsTo(ProviderId("prv_owner"), "provider/prv_owner2/1") || KeyAliases.belongsTo(ProviderId("prv_owner"), "provider/prv_owner/x"))
    }
}
