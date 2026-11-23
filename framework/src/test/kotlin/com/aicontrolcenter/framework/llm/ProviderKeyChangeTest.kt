package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ProviderDraft
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.security.SecretVaultException
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.FakeProviderAdapterFactory
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryModelRepository
import com.aicontrolcenter.testkit.InMemoryProviderRepository
import com.aicontrolcenter.testkit.InMemorySecretVault
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A provider's address and its key are two stores, the record and the vault. These tests break
 * the write of one or the other half-way, as a full disk or a process that dies would, and check
 * that a key is never paired with an address it was not saved for.
 */
class ProviderKeyChangeTest {

    /** A record store whose next write can be made to fail. */
    private class FlakyProviders(private val inner: InMemoryProviderRepository = InMemoryProviderRepository()) : ProviderRepository by inner {
        var failNextUpsert = false

        override suspend fun upsert(provider: ModelProvider) {
            if (failNextUpsert) {
                failNextUpsert = false
                throw IOException("disk full (simulated)")
            }
            inner.upsert(provider)
        }
    }

    /** A vault whose deletes, or listing, can be made to fail. */
    private class FlakyVault(private val inner: InMemorySecretVault = InMemorySecretVault()) : SecretVault by inner {
        var failDeletes = false
        var failListing = false

        override suspend fun delete(alias: String) {
            if (failDeletes) throw SecretVaultException("the process stopped here (simulated)")
            inner.delete(alias)
        }

        override suspend fun aliases(): Set<String> {
            if (failListing) throw SecretVaultException("keystore unavailable (simulated)")
            return inner.aliases()
        }
    }

    /** What survives a restart: the stores. [start] wires a fresh app over them. */
    private class Stores {
        val providers = FlakyProviders()
        val vault = FlakyVault()
        val models = InMemoryModelRepository()
        val settings = InMemorySettingsRepository()
        val log = InMemoryActivityLog()
        val clock = FakeClock(autoAdvanceMs = 1)
        val ids = SequentialIdGenerator()

        /** Every request that reached a provider, as "address key". */
        val sent = CopyOnWriteArrayList<String>()

        fun start() = App(this)

        fun logText() = log.all.joinToString("\n") { "${it.title} ${it.detail} ${it.data} ${it.egress}" }
    }

    private class App(stores: Stores) {
        private val factory = FakeProviderAdapterFactory(ProviderKind.OPENAI_COMPATIBLE) { connection ->
            FakeProviderAdapter(
                client = ScriptedLlmClient("m") { _, _ ->
                    stores.sent += "${connection.provider.baseUrl} ${connection.apiKey}"
                    ScriptStep.Reply("ok")
                },
            )
        }
        private val activity = ActivityRecorder(stores.log, stores.ids, stores.clock)
        val connections = ProviderConnections(stores.providers, stores.vault, listOf(factory), stores.clock)
        val manager = DefaultProviderManager(stores.providers, stores.models, stores.settings, stores.vault, connections, activity, stores.ids, stores.clock)
        val gateway = DefaultModelGateway(stores.providers, stores.models, stores.settings, connections, activity, stores.clock, RetryPolicy(initialDelayMs = 1, maxDelayMs = 1))
    }

    private val keyA = "sk-test-key-for-host-a-111111"
    private val keyB = "sk-test-key-for-host-b-222222"
    private fun at(host: String, key: String?) = ProviderDraft("Cloud", ProviderKind.OPENAI_COMPATIBLE, "https://$host/v1", apiKey = key)
    private val ask = ChatRequest(messages = listOf(ChatMessage.user("hi")))

    @Test
    fun `a record write that fails leaves the old address with its old key, and the new key is not kept`() = runTest {
        val stores = Stores()
        val app = stores.start()
        val provider = app.manager.addProvider(at("a.example.com", keyA))
        val model = app.manager.addModel(provider.id, "m")

        stores.providers.failNextUpsert = true
        assertFailsWith<IOException> { app.manager.updateProvider(provider.id, at("b.example.com", keyB)) }

        assertEquals(provider, stores.providers.get(provider.id), "the record is as it was")
        assertEquals(setOf(provider.apiKeyAlias), stores.vault.aliases(), "the new key went with the failed edit")
        assertEquals(keyA, stores.vault.get(provider.apiKeyAlias!!))
        app.gateway.chat(model.id, ask)
        assertEquals(listOf("https://a.example.com/v1 $keyA"), stores.sent, "the next call goes to A with A's key")

        // If the new key cannot be deleted either, the failure reported is still the record's.
        stores.providers.failNextUpsert = true
        stores.vault.failDeletes = true
        val failure = assertFailsWith<IOException> { app.manager.updateProvider(provider.id, at("b.example.com", keyB)) }
        // (Coroutines may hand on a copy of the exception, with the original as its cause.)
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { e -> e.suppressed.any { it is SecretVaultException } })
        stores.vault.failDeletes = false
        app.gateway.chat(model.id, ask)
        assertEquals("https://a.example.com/v1 $keyA", stores.sent.last(), "the leftover key is not the record's")
        assertFalse(keyB in stores.logText())
    }

    @Test
    fun `adding a provider whose record cannot be written keeps no key`() = runTest {
        val stores = Stores()
        val app = stores.start()
        stores.providers.failNextUpsert = true
        assertFailsWith<IOException> { app.manager.addProvider(at("a.example.com", keyA)) }
        assertTrue(stores.providers.getAll().isEmpty())
        assertEquals(emptySet(), stores.vault.aliases(), "a key with no record would never be deleted")
        assertFalse(keyA in stores.logText())
    }

    @Test
    fun `removing a provider deletes every version of its key, including ones an earlier failure left behind`() = runTest {
        val stores = Stores()
        val app = stores.start()
        val provider = app.manager.addProvider(at("a.example.com", keyA))
        // An edit whose deletion of the previous key fails leaves that version behind.
        stores.vault.failDeletes = true
        assertFailsWith<SecretVaultException> { app.manager.updateProvider(provider.id, at("b.example.com", keyB)) }
        stores.vault.failDeletes = false
        stores.vault.put("provider/${provider.id.value}", "sk-original-form-key-000000")
        stores.vault.put("backup/passphrase", "not a provider key")
        val other = app.manager.addProvider(at("c.example.com", "sk-test-key-for-host-c-333333").copy(name = "Other"))
        assertEquals(5, stores.vault.aliases().size)

        app.manager.removeProvider(provider.id)

        assertEquals(setOf(other.apiKeyAlias, "backup/passphrase"), stores.vault.aliases())
    }

    @Test
    fun `an edit interrupted after the record write leaves a key nothing uses, which the next start deletes`() = runTest {
        val stores = Stores()
        val app = stores.start()
        val provider = app.manager.addProvider(at("a.example.com", keyA))
        val model = app.manager.addModel(provider.id, "m")
        val oldAlias = provider.apiKeyAlias!!

        // The process dies right after the record write, before the previous key is deleted.
        stores.vault.failDeletes = true
        assertFailsWith<SecretVaultException> { app.manager.updateProvider(provider.id, at("b.example.com", keyB)) }
        stores.vault.failDeletes = false
        val edited = stores.providers.get(provider.id)!!
        assertEquals("https://b.example.com/v1", edited.baseUrl)
        val newAlias = assertNotNull(edited.apiKeyAlias)
        assertNotEquals(oldAlias, newAlias)
        assertEquals(setOf(oldAlias, newAlias), stores.vault.aliases(), "the previous key is left behind")
        // Even so, each key only ever goes with its own address.
        app.gateway.chat(model.id, ask)
        assertEquals(listOf("https://b.example.com/v1 $keyB"), stores.sent)

        // Keys the clean-up must leave alone: another provider's, one of a record this version cannot read, and a non-provider secret.
        val other = app.manager.addProvider(at("c.example.com", "sk-test-key-for-host-c-333333").copy(name = "Other"))
        stores.vault.put("provider/prv_unreadable/1", "sk-test-key-of-an-unreadable-record")
        stores.vault.put("backup/passphrase", "not a provider key")

        val restarted = stores.start()
        restarted.manager.ensureBuiltIns()

        assertEquals(
            setOf(newAlias, other.apiKeyAlias, "provider/prv_unreadable/1", "backup/passphrase"),
            stores.vault.aliases(),
            "only the key nothing points at is deleted",
        )
        assertEquals(keyB, stores.vault.get(newAlias))
        val event = stores.log.all.single { it.title == "Unused API keys deleted" }
        assertTrue("1 saved API key" in event.detail, event.detail)
        assertFalse(oldAlias in stores.logText() || keyA in stores.logText() || keyB in stores.logText(), "the log names no key")
        restarted.gateway.chat(model.id, ask)
        assertEquals("https://b.example.com/v1 $keyB", stores.sent.last())

        // Nothing left to tidy: a later start logs nothing.
        val events = stores.log.all.size
        assertEquals(0, stores.start().manager.deleteUnusedKeys())
        stores.start().manager.ensureBuiltIns()
        assertEquals(events, stores.log.all.size)
    }

    @Test
    fun `a key saved under the original alias form keeps working, and moves to a new alias when replaced`() = runTest {
        val stores = Stores()
        val app = stores.start()
        // As saved before aliases were versioned.
        val id = ProviderId("prv_saved_earlier")
        val oldForm = "provider/${id.value}"
        stores.vault.put(oldForm, keyA)
        stores.providers.upsert(
            ModelProvider(
                id = id, name = "Cloud", kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = "https://a.example.com/v1",
                apiKeyAlias = oldForm, locality = Locality.CLOUD, createdAt = 1, updatedAt = 1,
            ),
        )
        val model = app.manager.addModel(id, "m")

        app.manager.ensureBuiltIns()
        assertEquals(keyA, stores.vault.get(oldForm), "a key a record points at is never tidied away")
        assertTrue(app.manager.hasApiKey(id))
        app.gateway.chat(model.id, ask)
        assertEquals(listOf("https://a.example.com/v1 $keyA"), stores.sent)

        val replaced = app.manager.updateProvider(id, at("a.example.com", keyB))
        assertEquals("$oldForm/1", replaced.apiKeyAlias)
        assertEquals(setOf("$oldForm/1"), stores.vault.aliases())
        app.gateway.chat(model.id, ask)
        assertEquals("https://a.example.com/v1 $keyB", stores.sent.last())
        assertEquals("$oldForm/2", app.manager.updateProvider(id, at("a.example.com", keyA)).apiKeyAlias, "every new key gets a new alias")

        // Removing the key, or the provider, deletes the alias the record points at.
        val keyless = app.manager.updateProvider(id, at("a.example.com", ""))
        assertNull(keyless.apiKeyAlias)
        assertTrue(stores.vault.aliases().isEmpty())
        val again = app.manager.updateProvider(id, at("a.example.com", keyB))
        assertEquals("$oldForm/1", again.apiKeyAlias)
        app.manager.removeProvider(id)
        assertTrue(stores.vault.aliases().isEmpty())
    }

    @Test
    fun `a vault that cannot list its keys does not keep the simulator from being registered`() = runTest {
        val stores = Stores()
        stores.vault.failListing = true

        stores.start().manager.ensureBuiltIns()

        assertEquals(Simulator.PROVIDER_ID, stores.providers.getAll().single().id)
        assertNotNull(stores.models.get(Simulator.MODEL_ID))
    }
}
