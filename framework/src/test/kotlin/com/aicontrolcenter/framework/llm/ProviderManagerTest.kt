package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.DetectedServer
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderDraft
import com.aicontrolcenter.core.llm.ProviderField
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.ProviderValidationException
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderManagerTest {
    private val world = TestWorld()
    private val manager = world.providerManager
    private val secret = "sk-test-0123456789abcdef"

    private fun registry() = world.events(ActivityType.REGISTRY)

    private fun server(
        name: String = "Home server",
        baseUrl: String? = "http://192.168.1.20:8080/v1",
        apiKey: String? = null,
        headers: Map<String, String> = emptyMap(),
        kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    ) = ProviderDraft(name, kind, baseUrl, apiKey, headers)

    private suspend fun fields(draft: ProviderDraft, editing: ProviderId? = null) = manager.validate(draft, editing).map { it.field }

    // ---- presets ----

    @Test
    fun `presets cover the well-known services and local servers, and each is a valid starting point`() = runTest {
        val byId = manager.presets.associateBy { it.id }
        assertEquals(
            setOf(
                "openai", "anthropic", "gemini", "openrouter", "groq", "mistral", "deepseek", "xai",
                "ollama-phone", "ollama-lan", "lmstudio", "llamacpp", "custom-openai", "simulator",
            ),
            byId.keys,
        )
        assertEquals(manager.presets.size, byId.size, "preset ids are unique")
        assertEquals(ProviderKind.ANTHROPIC, byId.getValue("anthropic").kind)
        assertEquals(ProviderKind.GEMINI, byId.getValue("gemini").kind)
        assertEquals(ProviderKind.OLLAMA, byId.getValue("ollama-lan").kind)
        assertEquals(ProviderKind.SIMULATOR, byId.getValue("simulator").kind)

        for (preset in manager.presets) {
            assertTrue(preset.title.isNotBlank() && preset.description.isNotBlank() && preset.setupHint.isNotBlank(), preset.id)
            val draft = ProviderDraft(preset.title, preset.kind, preset.baseUrl, apiKey = if (preset.needsApiKey) secret else null)
            // The custom server is the one preset the owner has to complete: it has no address yet.
            val expected = if (preset.id == "custom-openai") listOf(ProviderField.BASE_URL) else emptyList()
            assertEquals(expected, fields(draft), preset.id)
        }

        val cloud = listOf("openai", "openrouter", "groq", "mistral", "deepseek", "xai").map(byId::getValue)
        assertTrue(cloud.all { it.needsApiKey && Locality.infer(it.kind, it.baseUrl) == Locality.CLOUD && it.baseUrl!!.startsWith("https://") })
        assertEquals(Locality.ON_DEVICE, byId.getValue("ollama-phone").let { Locality.infer(it.kind, it.baseUrl) })
        assertEquals(Locality.LOCAL_NETWORK, byId.getValue("ollama-lan").let { Locality.infer(it.kind, it.baseUrl) })
        assertEquals(Locality.LOCAL_NETWORK, byId.getValue("lmstudio").let { Locality.infer(it.kind, it.baseUrl) })
    }

    // ---- validation ----

    @Test
    fun `a name must be present and unique whatever its case`() = runTest {
        val existing = world.provider(name = "Home Server")

        assertEquals(listOf(ProviderField.NAME), fields(server(name = "   ")))
        assertEquals(listOf(ProviderField.NAME), fields(server(name = " home server ")))
        assertEquals(listOf(ProviderField.NAME), fields(server(name = "x".repeat(200))))
        assertEquals(emptyList(), fields(server(name = "Second server")))
        assertEquals(emptyList(), fields(server(name = "Home Server"), editing = existing.id), "a provider may keep its own name")
    }

    @Test
    fun `an address is required where the kind needs one, parsed strictly, and http only for local hosts`() = runTest {
        val accepted = listOf(
            "http://127.0.0.1:8080/v1", "http://localhost:1234/v1", "http://[::1]:8080/v1", "http://192.168.1.20:11434",
            "http://nas.local:8080/v1/", "https://api.example.com/v1", " https://openrouter.ai/api/v1 ",
        )
        for (url in accepted) assertEquals(emptyList(), fields(server(baseUrl = url)), url)

        val rejected = listOf(
            null, "", "   ",
            "192.168.1.20:8080", "localhost:8080", "ftp://192.168.1.20/v1", "https:///v1",
            "http://api.example.com/v1",
            "https://user:password@api.example.com/v1", "https://api.example.com/v1?key=abc", "https://api.example.com/v1#top",
            "http://192.168.1.20\\@evil.example.com/v1", "http://exa mple.com/v1",
        )
        for (url in rejected) assertEquals(listOf(ProviderField.BASE_URL), fields(server(baseUrl = url)), url.toString())

        assertEquals(listOf(ProviderField.BASE_URL), fields(server(kind = ProviderKind.OLLAMA, baseUrl = null)))
        assertEquals(emptyList(), fields(server(kind = ProviderKind.ANTHROPIC, baseUrl = null, apiKey = secret)), "the vendor's endpoint is the default")
        assertEquals(
            listOf(ProviderField.BASE_URL),
            fields(server(kind = ProviderKind.SIMULATOR, baseUrl = "http://127.0.0.1:1/v1")),
            "an in-process provider has no address",
        )
    }

    @Test
    fun `extra headers must be well formed and must not carry credentials`() = runTest {
        assertEquals(emptyList(), fields(server(headers = mapOf("X-Title" to "AI Control Center", "HTTP-Referer" to "https://example.com"))))

        val credentialNames = listOf("Authorization", "authorization", "X-Api-Key", "api-key", "Cookie", "Proxy-Authorization", "X-Auth-Token")
        val malformedNames = listOf("Bad Name", "", "X-Title:", "Content-Type")
        for (name in credentialNames + malformedNames) {
            assertEquals(listOf(ProviderField.HEADERS), fields(server(headers = mapOf(name to "value"))), "header \"$name\"")
        }
        for (value in listOf("line\nbreak", "carriage\rreturn", "nul\u0000byte", "ünïcode")) {
            assertEquals(listOf(ProviderField.HEADERS), fields(server(headers = mapOf("X-Title" to value))), "value \"$value\"")
        }
    }

    @Test
    fun `a vendor's cloud API needs a key, and no message ever repeats a secret`() = runTest {
        assertEquals(listOf(ProviderField.API_KEY), fields(server(name = "Claude", kind = ProviderKind.ANTHROPIC, baseUrl = null)))
        assertEquals(listOf(ProviderField.API_KEY), fields(server(name = "Gemini", kind = ProviderKind.GEMINI, baseUrl = null, apiKey = " ")))
        assertEquals(listOf(ProviderField.API_KEY), fields(server(kind = ProviderKind.SIMULATOR, baseUrl = null, apiKey = secret)))

        val claude = manager.addProvider(server(name = "Claude", kind = ProviderKind.ANTHROPIC, baseUrl = null, apiKey = secret))
        val edit = server(name = "Claude", kind = ProviderKind.ANTHROPIC, baseUrl = null)
        assertEquals(emptyList(), fields(edit, editing = claude.id), "leaving the key field alone keeps the stored key")
        assertEquals(listOf(ProviderField.API_KEY), fields(edit.copy(apiKey = ""), editing = claude.id), "removing it would leave none")

        val leaky = server(name = "Leaky", apiKey = "$secret with spaces", headers = mapOf("Authorization" to "Bearer $secret"))
        val issues = manager.validate(leaky)
        assertEquals(listOf(ProviderField.API_KEY, ProviderField.HEADERS), issues.map { it.field })
        assertTrue(issues.none { secret in it.message })
        val refused = assertFailsWith<ProviderValidationException> { manager.addProvider(leaky) }
        assertEquals(issues, refused.issues)
        assertFalse(secret in refused.message.orEmpty())
    }

    @Test
    fun `an invalid draft changes nothing`() = runTest {
        assertFailsWith<ProviderValidationException> { manager.addProvider(server(name = "", apiKey = secret)) }
        assertTrue(world.providers.getAll().isEmpty())
        assertTrue(world.vault.aliases().isEmpty(), "a key without a provider would never be deleted")
        assertTrue(registry().isEmpty())

        val provider = manager.addProvider(server())
        assertFailsWith<ProviderValidationException> { manager.updateProvider(provider.id, server(baseUrl = "http://api.example.com/v1")) }
        assertEquals(provider, world.providers.get(provider.id))
        assertFailsWith<IllegalArgumentException> { manager.updateProvider(ProviderId("prv_missing"), server(name = "Other")) }
    }

    // ---- providers and their keys ----

    @Test
    fun `adding a provider stores the key in the vault only, and its locality follows the address`() = runTest {
        val provider = manager.addProvider(
            server(name = " OpenRouter ", baseUrl = "https://openrouter.ai/api/v1/", apiKey = "  $secret\n", headers = mapOf(" X-Title " to " AI Control Center ")),
        )

        assertEquals("OpenRouter", provider.name)
        assertEquals("https://openrouter.ai/api/v1", provider.baseUrl)
        assertEquals(mapOf("X-Title" to "AI Control Center"), provider.extraHeaders)
        assertEquals(Locality.CLOUD, provider.locality)
        assertEquals(ConnectionStatus.UNKNOWN, provider.status)
        assertTrue(provider.enabled)
        assertEquals(provider, world.providers.get(provider.id))

        val alias = assertNotNull(provider.apiKeyAlias)
        assertEquals(secret, world.vault.get(alias), "stored as entered, without the white space pasted around it")
        assertEquals(setOf(alias), world.vault.aliases())
        assertTrue(manager.hasApiKey(provider.id))
        assertFalse(secret in Json.encodeToString(ModelProvider.serializer(), provider))
        assertFalse(secret in world.logText())

        val event = registry().single()
        assertEquals("Provider added", event.title)
        assertTrue("OpenRouter" in event.detail && "API key saved" in event.detail)

        val lan = manager.addProvider(server(name = "LAN", baseUrl = "http://192.168.1.20:8080/v1", apiKey = "sk-test-another-key-for-lan"))
        val phone = manager.addProvider(server(name = "Phone", baseUrl = "http://127.0.0.1:8080/v1"))
        assertEquals(Locality.LOCAL_NETWORK, lan.locality)
        assertEquals(Locality.ON_DEVICE, phone.locality)
        assertNotEquals(alias, lan.apiKeyAlias, "every provider has its own alias")
        assertEquals("sk-test-another-key-for-lan", world.vault.get(lan.apiKeyAlias!!))
        assertNull(phone.apiKeyAlias)
        assertFalse(manager.hasApiKey(phone.id))
        assertFalse(manager.hasApiKey(ProviderId("prv_missing")))
    }

    @Test
    fun `editing keeps the key when none is entered, replaces it when one is, and removes it when emptied`() = runTest {
        val replacement = "sk-test-replacement-key-000"
        val provider = manager.addProvider(server(apiKey = secret))
        val alias = provider.apiKeyAlias!!

        val renamed = manager.updateProvider(provider.id, server(name = "Renamed"))
        assertEquals("Renamed", renamed.name)
        assertEquals(alias, renamed.apiKeyAlias)
        assertEquals(secret, world.vault.get(alias))

        manager.updateProvider(provider.id, server(name = "Renamed", apiKey = replacement))
        assertEquals(replacement, world.vault.get(alias))
        assertEquals(setOf(alias), world.vault.aliases())

        val keyless = manager.updateProvider(provider.id, server(name = "Renamed", apiKey = ""))
        assertNull(keyless.apiKeyAlias)
        assertTrue(world.vault.aliases().isEmpty())
        assertFalse(manager.hasApiKey(provider.id))

        val changes = registry().filter { it.title == "Provider updated" }.map { it.detail }
        assertEquals(listOf("Renamed: name", "Renamed: API key replaced", "Renamed: API key removed"), changes)
        assertFalse(secret in world.logText() || replacement in world.logText())

        val before = registry().size
        assertEquals(keyless, manager.updateProvider(provider.id, server(name = "Renamed")))
        assertEquals(before, registry().size, "saving without a change is not a change")
    }

    @Test
    fun `changing how a provider is reached re-derives its locality and forgets the old connection's status`() = runTest {
        val provider = manager.addProvider(server(baseUrl = "http://127.0.0.1:8080/v1"))
        manager.checkProvider(provider.id)
        assertEquals(ConnectionStatus.ONLINE, world.providers.get(provider.id)!!.status)

        val renamed = manager.updateProvider(provider.id, server(name = "Llama box", baseUrl = "http://127.0.0.1:8080/v1"))
        assertEquals(ConnectionStatus.ONLINE, renamed.status, "a new name says nothing about the connection")

        val moved = manager.updateProvider(provider.id, server(name = "Llama box", baseUrl = "http://192.168.1.20:8080/v1"))
        assertEquals(Locality.LOCAL_NETWORK, moved.locality)
        assertEquals(ConnectionStatus.UNKNOWN, moved.status)
        assertNull(moved.lastCheckedAt)
        assertEquals(moved, world.providers.get(provider.id))
        assertEquals(provider.createdAt, moved.createdAt)
    }

    @Test
    fun `enabling and disabling is recorded once per change`() = runTest {
        val provider = manager.addProvider(server())

        manager.setProviderEnabled(provider.id, false)
        manager.setProviderEnabled(provider.id, false)
        assertFalse(world.providers.get(provider.id)!!.enabled)
        manager.setProviderEnabled(provider.id, true)
        assertTrue(world.providers.get(provider.id)!!.enabled)

        assertEquals(listOf("Provider added", "Provider disabled", "Provider enabled"), registry().map { it.title })
        assertFailsWith<IllegalArgumentException> { manager.setProviderEnabled(ProviderId("prv_missing"), true) }
    }

    @Test
    fun `removing a provider removes its models, its key and its open connection, and leaves agents alone`() = runTest {
        val adapter = FakeProviderAdapter()
        world.adaptersByUrl["http://192.168.1.20:8080/v1"] = adapter
        val provider = manager.addProvider(server(apiKey = secret))
        val model = manager.addModel(provider.id, "llama3")
        manager.addModel(provider.id, "gemma3")
        val unrelated = world.model(world.provider(name = "Other"))
        val agent = world.agent(model)
        manager.checkProvider(provider.id)

        manager.removeProvider(provider.id)

        assertNull(world.providers.get(provider.id))
        assertTrue(world.models.getByProvider(provider.id).isEmpty())
        assertNotNull(world.models.get(unrelated.id), "other providers' models stay")
        assertTrue(world.vault.aliases().isEmpty())
        assertTrue(adapter.closed)
        assertEquals(agent, world.agents.get(agent.id), "the agent keeps its configuration")
        val availability = world.directory.snapshot().agentsById.getValue(agent.id).availability
        assertEquals(UnavailableReason.MODEL_NOT_REGISTERED, (availability as AgentAvailability.Unavailable).reason)

        val event = registry().last()
        assertEquals("Provider removed", event.title)
        assertTrue("2 models" in event.detail)
        assertFalse(secret in world.logText())

        val before = registry().size
        manager.removeProvider(provider.id)
        assertEquals(before, registry().size, "removing what is gone does nothing")
    }

    // ---- connectivity ----

    @Test
    fun `a check stores status, message and time, and logs only changes of status`() = runTest {
        val adapter = FakeProviderAdapter(health = ProviderHealth(ConnectionStatus.OFFLINE, "Connection refused"))
        val provider = world.provider(adapter)

        val health = manager.checkProvider(provider.id)

        assertEquals(ProviderHealth(ConnectionStatus.OFFLINE, "Connection refused"), health)
        val offline = world.providers.get(provider.id)!!
        assertEquals(ConnectionStatus.OFFLINE, offline.status)
        assertEquals("Connection refused", offline.statusMessage)
        assertNotNull(offline.lastCheckedAt)
        assertEquals(provider.updatedAt, offline.updatedAt, "an observation is not an edit")

        adapter.health = ProviderHealth(ConnectionStatus.ONLINE, latencyMs = 12)
        manager.checkProvider(provider.id)
        manager.checkProvider(provider.id)
        val online = world.providers.get(provider.id)!!
        assertEquals(ConnectionStatus.ONLINE, online.status)
        assertNull(online.statusMessage)
        assertTrue(online.lastCheckedAt!! > offline.lastCheckedAt!!)

        val changes = registry()
        assertEquals(listOf("Provider status changed", "Provider status changed"), changes.map { it.title })
        assertEquals(listOf(Severity.WARNING, Severity.INFO), changes.map { it.severity })
        assertTrue("offline" in changes[0].detail && "Connection refused" in changes[0].detail)
        assertFailsWith<IllegalArgumentException> { manager.checkProvider(ProviderId("prv_missing")) }
    }

    @Test
    fun `a status message that echoes the key is stored without it`() = runTest {
        val adapter = FakeProviderAdapter(health = ProviderHealth(ConnectionStatus.AUTH_ERROR, "The key $secret was rejected."))
        val provider = world.provider(adapter, baseUrl = "https://api.example.com/v1", apiKey = secret)

        val health = manager.checkProvider(provider.id)

        assertEquals("The key [key removed] was rejected.", health.message)
        val stored = world.providers.get(provider.id)!!
        assertEquals(ConnectionStatus.AUTH_ERROR, stored.status)
        assertFalse(secret in stored.statusMessage.orEmpty())
        assertFalse(secret in world.logText())
    }

    @Test
    fun `checking all providers skips the disabled ones`() = runTest {
        val up = world.provider(FakeProviderAdapter(), name = "Up")
        val down = world.provider(FakeProviderAdapter(health = ProviderHealth(ConnectionStatus.OFFLINE, "No route to host")), name = "Down")
        val off = world.provider(FakeProviderAdapter(), name = "Off", enabled = false)

        manager.checkAllProviders()

        assertEquals(ConnectionStatus.ONLINE, world.providers.get(up.id)!!.status)
        assertEquals(ConnectionStatus.OFFLINE, world.providers.get(down.id)!!.status)
        val untouched = world.providers.get(off.id)!!
        assertEquals(ConnectionStatus.UNKNOWN, untouched.status)
        assertNull(untouched.lastCheckedAt)
    }

    @Test
    fun `local-only mode keeps checks and listings away from providers off the device`() = runTest {
        val cloud = world.provider(baseUrl = "https://api.example.com/v1", apiKey = secret)
        val phone = world.provider(FakeProviderAdapter(models = listOf(RemoteModel("llama3"))), name = "Phone")
        world.settings.update { it.copy(localOnlyMode = true) }

        val health = manager.checkProvider(cloud.id)
        assertTrue("Local-only mode" in health.message.orEmpty())
        assertFailsWith<LlmException.BlockedByPolicy> { manager.refreshModels(cloud.id) }
        manager.checkAllProviders()

        val contacted = world.factories.getValue(ProviderKind.OPENAI_COMPATIBLE).connections.map { it.provider.id }.toSet()
        assertEquals(setOf(phone.id), contacted, "the key of the cloud provider was never handed to an adapter")
        assertEquals(ConnectionStatus.UNKNOWN, world.providers.get(cloud.id)!!.status)
        assertEquals(1, manager.refreshModels(phone.id).added.size)
    }

    // ---- models ----

    @Test
    fun `a first listing adds every model with reported, estimated or unknown capabilities`() = runTest {
        val reported = ModelCapabilities(toolCalling = true, vision = true, contextWindow = 8192)
        val adapter = FakeProviderAdapter(
            models = listOf(
                RemoteModel("llama3:8b", "Llama 3 8B", reported, sizeBytes = 4_700_000_000),
                RemoteModel("mystery-model"),
                RemoteModel("gpt-4o-mini"),
                RemoteModel("llama3:8b", "A duplicate entry"),
                RemoteModel("   "),
            ),
        )
        val provider = world.provider(adapter)

        val report = manager.refreshModels(provider.id)

        assertEquals(listOf("llama3:8b", "mystery-model", "gpt-4o-mini"), report.added.map { it.modelName })
        assertTrue(report.updated.isEmpty() && report.missing.isEmpty())
        val (llama, mystery, hosted) = report.added
        assertEquals("Llama 3 8B", llama.displayName)
        assertEquals(reported, llama.capabilities)
        assertEquals(4_700_000_000, llama.sizeBytes)
        assertEquals("mystery-model", mystery.displayName)
        assertEquals(ModelCapabilities(), mystery.capabilities, "nothing is claimed for a model nobody described")
        assertTrue(hosted.capabilities.toolCalling, "a well-known hosted family is recognised by name")
        assertTrue(report.added.all { it.source == ModelSource.PROVIDER_LISTING && it.status == ModelStatus.AVAILABLE && it.enabled && it.providerId == provider.id })
        assertEquals(report.added, world.models.getByProvider(provider.id), "listing order is kept")
        assertEquals(ConnectionStatus.ONLINE, world.providers.get(provider.id)!!.status, "a listing that came back proves the connection")
        assertEquals("Test server: 3 added, 0 updated, 0 no longer listed", registry().single { it.title == "Models refreshed" }.detail)
    }

    @Test
    fun `a later listing updates what changed, never what the owner edited, and keeps what disappeared`() = runTest {
        val adapter = FakeProviderAdapter(
            models = listOf(
                RemoteModel("llama3:8b", "Llama 3 8B", ModelCapabilities(toolCalling = true, contextWindow = 8192)),
                RemoteModel("mystery-model"),
                RemoteModel("gpt-4o-mini"),
            ),
        )
        val provider = world.provider(adapter)
        val (llama, mystery, hosted) = manager.refreshModels(provider.id).added
        val ownerCapabilities = ModelCapabilities(toolCalling = true, contextWindow = 4096)
        manager.updateModel(mystery.id, "My fine-tune", ownerCapabilities, "tuned at home")
        val typedIn = manager.addModel(provider.id, "private-model")

        adapter.models = listOf(
            RemoteModel("llama3:8b", "Llama 3 8B", ModelCapabilities(toolCalling = true, contextWindow = 16384)),
            RemoteModel("mystery-model", "Mystery", ModelCapabilities(vision = true)),
            RemoteModel("phi4"),
        )
        val second = manager.refreshModels(provider.id)

        assertEquals(listOf("phi4"), second.added.map { it.modelName })
        assertEquals(listOf(llama.id), second.updated.map { it.id })
        assertEquals(16384, world.models.get(llama.id)!!.capabilities.contextWindow)

        val edited = world.models.get(mystery.id)!!
        assertEquals("My fine-tune", edited.displayName)
        assertEquals(ownerCapabilities, edited.capabilities)
        assertEquals("tuned at home", edited.notes)

        assertEquals(listOf(hosted.id), second.missing.map { it.id })
        val gone = world.models.get(hosted.id)!!
        assertEquals(ModelStatus.UNAVAILABLE, gone.status, "kept, so agents using it show why they cannot run")
        assertEquals(hosted.copy(status = ModelStatus.UNAVAILABLE, updatedAt = gone.updatedAt), gone)
        assertEquals(typedIn, world.models.get(typedIn.id), "a model the owner typed in is not judged by the listing")

        adapter.models = adapter.models + RemoteModel("gpt-4o-mini")
        val third = manager.refreshModels(provider.id)
        assertEquals(listOf(hosted.id), third.updated.map { it.id })
        assertEquals(ModelStatus.AVAILABLE, world.models.get(hosted.id)!!.status)
        assertTrue(third.added.isEmpty() && third.missing.isEmpty())
        assertEquals(5, world.models.getByProvider(provider.id).size, "nothing was duplicated or dropped")

        val unchanged = registry().size
        manager.refreshModels(provider.id)
        assertEquals(unchanged, registry().size, "a listing that changes nothing is not logged")
    }

    @Test
    fun `a listing that fails surfaces as an LlmException and marks a rejected key`() = runTest {
        val adapter = FakeProviderAdapter(listError = LlmException.Network("Connection refused"))
        val provider = world.provider(adapter, baseUrl = "https://api.example.com/v1", apiKey = secret)

        assertFailsWith<LlmException.Network> { manager.refreshModels(provider.id) }
        assertEquals(ConnectionStatus.UNKNOWN, world.providers.get(provider.id)!!.status)

        adapter.listError = LlmException.Authentication("Key $secret is not valid.")
        val rejected = assertFailsWith<LlmException.Authentication> { manager.refreshModels(provider.id) }
        assertFalse(secret in rejected.message.orEmpty())
        val stored = world.providers.get(provider.id)!!
        assertEquals(ConnectionStatus.AUTH_ERROR, stored.status)
        assertFalse(secret in stored.statusMessage.orEmpty())
        assertTrue(world.models.getAll().isEmpty())
        assertFailsWith<IllegalArgumentException> { manager.refreshModels(ProviderId("prv_missing")) }
    }

    @Test
    fun `models can be added, described, switched off and removed by hand`() = runTest {
        val provider = world.provider()

        val model = manager.addModel(provider.id, "  qwen2.5:7b ")
        assertEquals("qwen2.5:7b", model.modelName)
        assertEquals("qwen2.5:7b", model.displayName)
        assertEquals(ModelSource.MANUAL, model.source)
        assertEquals(ModelCapabilities(), model.capabilities)
        assertEquals(model, world.models.get(model.id))
        val described = manager.addModel(provider.id, "phi4", " Phi 4 ", ModelCapabilities(toolCalling = true))
        assertEquals("Phi 4", described.displayName)
        assertTrue(described.capabilities.toolCalling)

        assertFailsWith<IllegalArgumentException> { manager.addModel(provider.id, "qwen2.5:7b") }
        assertFailsWith<IllegalArgumentException> { manager.addModel(provider.id, "  ") }
        assertFailsWith<IllegalArgumentException> { manager.addModel(ProviderId("prv_missing"), "anything") }

        manager.updateModel(model.id, " Qwen 2.5 ", ModelCapabilities(toolCalling = true, contextWindow = 32_768), " fast ")
        val updated = world.models.get(model.id)!!
        assertEquals("Qwen 2.5", updated.displayName)
        assertEquals(32_768, updated.capabilities.contextWindow)
        assertEquals("fast", updated.notes)
        assertFailsWith<IllegalArgumentException> { manager.updateModel(model.id, " ", ModelCapabilities(), "") }

        manager.setModelEnabled(model.id, false)
        manager.setModelEnabled(model.id, false)
        assertFalse(world.models.get(model.id)!!.enabled)

        manager.removeModel(model.id)
        manager.removeModel(model.id)
        assertNull(world.models.get(model.id))
        assertNotNull(world.models.get(described.id))

        assertEquals(listOf("Model added", "Model added", "Model updated", "Model disabled", "Model removed"), registry().map { it.title })
    }

    // ---- local servers and built-ins ----

    @Test
    fun `detection reports servers answering on this phone, quickly, without registering anything`() = runTest {
        val ollama = FakeProviderAdapter(models = listOf(RemoteModel("llama3"), RemoteModel("gemma3")))
        val silent = object : ProviderAdapter {
            override val client: LlmClient = ScriptedLlmClient.replies("never")
            override suspend fun checkHealth() = ProviderHealth(ConnectionStatus.OFFLINE)
            override suspend fun listModels(): List<RemoteModel> = awaitCancellation()
        }
        world.adaptersByUrl["http://127.0.0.1:11434"] = ollama
        world.adaptersByUrl["http://127.0.0.1:8080/v1"] = FakeProviderAdapter(listError = LlmException.Network("Connection refused"))
        world.adaptersByUrl["http://127.0.0.1:1234/v1"] = silent

        val found = manager.detectLocalServers()

        assertEquals(listOf(DetectedServer("ollama-phone", "Ollama on this phone", "http://127.0.0.1:11434", ProviderKind.OLLAMA, modelCount = 2)), found)
        assertTrue(currentTime in 1..5_000, "a server that does not answer is given up on quickly, took $currentTime ms")
        assertTrue(ollama.closed, "probe connections are not kept")
        assertTrue(world.factories.values.flatMap { it.connections }.all { it.apiKey == null && Locality.infer(it.provider.kind, it.provider.baseUrl) == Locality.ON_DEVICE })
        assertTrue(world.providers.getAll().isEmpty())
        assertTrue(registry().isEmpty())

        manager.addProvider(ProviderDraft("Ollama", ProviderKind.OLLAMA, "http://localhost:11434"))
        assertTrue(manager.detectLocalServers().isEmpty(), "a server that is already registered is not offered again")
    }

    @Test
    fun `the simulator is registered once and works without a key or a network`() = runTest {
        manager.ensureBuiltIns()
        manager.ensureBuiltIns()

        val provider = world.providers.getAll().single()
        assertEquals(Simulator.PROVIDER_ID, provider.id)
        assertEquals(ProviderKind.SIMULATOR, provider.kind)
        assertEquals(Locality.ON_DEVICE, provider.locality)
        assertNull(provider.apiKeyAlias)
        assertNull(provider.baseUrl)
        val model = world.models.getAll().single()
        assertEquals(Simulator.MODEL_ID, model.id)
        assertEquals(provider.id, model.providerId)
        assertEquals(ModelSource.BUILT_IN, model.source)
        assertTrue("not a real model" in model.displayName.lowercase())
        assertEquals(listOf("Simulator registered"), registry().map { it.title })

        world.settings.update { it.copy(localOnlyMode = true) }
        val response = world.gateway.chat(model.id, ChatRequest(messages = listOf(ChatMessage.user("Hello"))))
        assertTrue(response.message.text.startsWith(Simulator.DISCLOSURE))
        assertNull(world.events(ActivityType.MODEL_CALL).single().egress)
        assertEquals(ConnectionStatus.ONLINE, manager.checkProvider(provider.id).status)
        assertTrue(manager.refreshModels(provider.id).added.isEmpty(), "its one model is already there")
    }

    @Test
    fun `a removed simulator model comes back on the next start, next to a provider the owner named Simulator`() = runTest {
        manager.addProvider(server(name = "simulator"))
        manager.ensureBuiltIns()
        manager.removeModel(Simulator.MODEL_ID)

        manager.ensureBuiltIns()

        assertNotNull(world.models.get(Simulator.MODEL_ID))
        val names = world.providers.getAll().map { it.name }
        assertEquals(2, names.map { it.lowercase() }.toSet().size, "names stay unique: $names")
    }
}
