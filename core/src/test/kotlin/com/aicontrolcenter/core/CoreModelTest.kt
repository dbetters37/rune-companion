package com.aicontrolcenter.core

import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.agents.resolveAvailability
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ControlChannel
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.registry.authorisedChannels
import com.aicontrolcenter.core.registry.controlChannels
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.security.maskSecret
import com.aicontrolcenter.core.settings.FrameworkSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreModelTest {
    private fun infer(url: String?, kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE) = Locality.infer(kind, url)

    @Test
    fun `locality is inferred from the host`() {
        assertEquals(Locality.ON_DEVICE, infer("http://127.0.0.1:11434"))
        assertEquals(Locality.ON_DEVICE, infer("http://127.8.9.10/v1"))
        assertEquals(Locality.ON_DEVICE, infer("http://localhost:8080/v1"))
        assertEquals(Locality.ON_DEVICE, infer("HTTP://LOCALHOST:8080/v1"))
        assertEquals(Locality.ON_DEVICE, infer("http://[::1]:8080/v1"))
        assertEquals(Locality.ON_DEVICE, infer("http://0.0.0.0:8080"))
        assertEquals(Locality.LOCAL_NETWORK, infer("http://192.168.1.20:1234/v1"))
        assertEquals(Locality.LOCAL_NETWORK, infer("http://10.0.0.5"))
        assertEquals(Locality.LOCAL_NETWORK, infer("http://172.16.4.4/v1"))
        assertEquals(Locality.LOCAL_NETWORK, infer("http://100.101.102.103:11434"))
        assertEquals(Locality.LOCAL_NETWORK, infer("http://desktop.local:11434"))
        assertEquals(Locality.LOCAL_NETWORK, infer("http://[fd12:3456::1]:11434"))
        assertEquals(Locality.CLOUD, infer("http://172.32.0.1/v1"))
        assertEquals(Locality.CLOUD, infer("https://api.example.com/v1"))
        assertEquals(Locality.CLOUD, infer("https://user:pw@api.example.com:443/v1"))
        assertEquals(Locality.CLOUD, infer("https://8.8.8.8"))
    }

    @Test
    fun `locality never under-reports for odd or crafted addresses`() {
        val cloud = listOf(
            "://", "not a url", "localhost:8080", "127.0.0.1", "ftp://127.0.0.1/", "file:///etc/hosts",
            "https://localhost.evil.example", "https://127.evil.example", "http://127.0.0.1.evil.example/",
            // Parsed differently by lenient HTTP clients: the real host is evil.example.
            "http://evil.example\\@127.0.0.1/", "https://evil.example\\@localhost/v1",
            "http://127.0.0.1:80@evil.example/", "http://localhost@evil.example/",
            // No scheme, with a local-looking URL further in.
            "evil.example/?next=http://127.0.0.1", "evil.example/proxy/http://localhost/v1", "api.evil.example/v1#x://192.168.1.2",
            "http://evil.example/#@127.0.0.1", "http://evil.example/?@localhost",
            "http://127.1", "http://0x7f.0.0.1", "http://2130706433", "http://127.0.0.1 .evil.example",
            "http://ex ample.com", "http://exa_mple.local.evil.example", "http://١٢٧.0.0.1",
        )
        for (url in cloud) assertEquals(Locality.CLOUD, infer(url), "must be treated as cloud: $url")
    }

    @Test
    fun `a missing address is local only for kinds that run in this process`() {
        for (blank in listOf(null, "", "   ")) {
            assertEquals(Locality.ON_DEVICE, infer(blank, ProviderKind.ON_DEVICE))
            assertEquals(Locality.ON_DEVICE, infer(blank, ProviderKind.SIMULATOR))
            assertEquals(Locality.CLOUD, infer(blank, ProviderKind.OPENAI_COMPATIBLE))
            assertEquals(Locality.CLOUD, infer(blank, ProviderKind.ANTHROPIC))
            assertEquals(Locality.CLOUD, infer(blank, ProviderKind.GEMINI))
            assertEquals(Locality.CLOUD, infer(blank, ProviderKind.OLLAMA))
        }
    }

    @Test
    fun `a provider can never be labelled more private than its address`() {
        val mislabelled = provider.copy(locality = Locality.ON_DEVICE)
        assertEquals(Locality.CLOUD, mislabelled.effectiveLocality)
        assertEquals(
            UnavailableReason.BLOCKED_BY_LOCAL_ONLY_MODE,
            reason(availability(providers = listOf(mislabelled, localProvider), settings = FrameworkSettings(localOnlyMode = true))),
        )
        val overCautious = localProvider.copy(baseUrl = "http://127.0.0.1:11434", locality = Locality.CLOUD)
        assertEquals(Locality.CLOUD, overCautious.effectiveLocality, "a stricter stored label is respected")
    }

    private val provider = ModelProvider(
        id = ProviderId("p"), name = "Cloud", kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = "https://api.example.com",
        locality = Locality.CLOUD, status = ConnectionStatus.ONLINE, createdAt = 0, updatedAt = 0,
    )
    private val localProvider = provider.copy(
        id = ProviderId("local"), name = "Phone", kind = ProviderKind.OLLAMA, baseUrl = "http://127.0.0.1:11434", locality = Locality.ON_DEVICE,
    )
    private val model = ModelDescriptor(
        id = ModelId("m"), providerId = provider.id, modelName = "x", displayName = "X", source = ModelSource.MANUAL,
        status = ModelStatus.AVAILABLE, createdAt = 0, updatedAt = 0,
    )
    private val localModel = model.copy(id = ModelId("lm"), providerId = localProvider.id, displayName = "Local")
    private val agent = AgentProfile(
        id = AgentId("a"), name = "A", kind = AgentKind.SPECIALIST, modelId = model.id, createdAt = 0, updatedAt = 0,
    )

    private fun availability(
        agent: AgentProfile = this.agent,
        models: List<ModelDescriptor> = listOf(model, localModel),
        providers: List<ModelProvider> = listOf(provider, localProvider),
        settings: FrameworkSettings = FrameworkSettings(),
    ) = resolveAvailability(agent, models.associateBy { it.id }, providers.associateBy { it.id }, settings)

    private fun reason(a: AgentAvailability) = assertIs<AgentAvailability.Unavailable>(a).reason

    @Test
    fun `availability explains exactly why an agent cannot run`() {
        assertEquals(AgentAvailability.Ready(model.id, usingFallback = false), availability())
        assertEquals(AgentAvailability.Disabled, availability(agent.copy(enabled = false)))
        assertEquals(UnavailableReason.NO_MODEL_ASSIGNED, reason(availability(agent.copy(modelId = null))))
        assertEquals(UnavailableReason.MODEL_NOT_REGISTERED, reason(availability(models = emptyList())))
        assertEquals(UnavailableReason.MODEL_DISABLED, reason(availability(models = listOf(model.copy(enabled = false)))))
        assertEquals(UnavailableReason.MODEL_UNAVAILABLE, reason(availability(models = listOf(model.copy(status = ModelStatus.UNAVAILABLE)))))
        assertEquals(UnavailableReason.PROVIDER_NOT_REGISTERED, reason(availability(providers = emptyList())))
        assertEquals(UnavailableReason.PROVIDER_DISABLED, reason(availability(providers = listOf(provider.copy(enabled = false)))))
        assertEquals(UnavailableReason.PROVIDER_OFFLINE, reason(availability(providers = listOf(provider.copy(status = ConnectionStatus.OFFLINE)))))
        assertEquals(UnavailableReason.PROVIDER_AUTH_ERROR, reason(availability(providers = listOf(provider.copy(status = ConnectionStatus.AUTH_ERROR)))))
        assertTrue(availability(providers = listOf(provider.copy(status = ConnectionStatus.UNKNOWN))).isReady)
    }

    @Test
    fun `local-only mode blocks cloud models and falls back to an on-device one`() {
        val localOnly = FrameworkSettings(localOnlyMode = true)
        assertEquals(UnavailableReason.BLOCKED_BY_LOCAL_ONLY_MODE, reason(availability(settings = localOnly)))
        val withFallback = agent.copy(fallbackModelIds = listOf(localModel.id))
        assertEquals(AgentAvailability.Ready(localModel.id, usingFallback = true), availability(withFallback, settings = localOnly))
    }

    @Test
    fun `reliability is smoothed and penalises failures and revisions`() {
        assertNull(AgentStats().reliability)
        assertEquals(2.0 / 3.0, AgentStats(tasksCompleted = 1).reliability!!, 1e-9)
        assertEquals(1.0 / 3.0, AgentStats(tasksFailed = 1).reliability!!, 1e-9)
        val mixed = AgentStats(tasksCompleted = 8, tasksFailed = 1, tasksTimedOut = 1, reviewsPassed = 4, revisionsRequested = 2, reviewsRejected = 1)
        assertEquals((12 + 1.0) / (12 + 4.0 + 2.0), mixed.reliability!!, 1e-9)
        assertEquals(1_000L, AgentStats(tasksCompleted = 2, tasksFailed = 1, totalTaskDurationMs = 3_000).averageTaskDurationMs)
        assertNull(AgentStats(tasksAssigned = 3).averageTaskDurationMs)
    }

    @Test
    fun `control channels only include what the app supports and the owner authorised`() {
        val operatorOn = FrameworkSettings(operatorEnabled = true, accessibilityOperatorEnabled = true)
        val app = AiApp(
            id = AiAppId("x"), packageName = "p", label = "P", source = DiscoverySource.CATALOG,
            capabilities = setOf(AiAppCapability.LAUNCH, AiAppCapability.SHARE_TEXT), firstSeenAt = 0, lastSeenAt = 0,
        )
        assertEquals(setOf(ControlChannel.LAUNCH, ControlChannel.INTENT_HANDOFF), app.controlChannels(operatorOn))
        val full = app.copy(capabilities = app.capabilities + AiAppCapability.PROCESS_TEXT, accessibilityAuthorized = true)
        assertEquals(
            setOf(ControlChannel.LAUNCH, ControlChannel.INTENT_HANDOFF, ControlChannel.ROUND_TRIP_TEXT, ControlChannel.ACCESSIBILITY),
            full.controlChannels(operatorOn),
        )
        assertEquals(emptySet(), app.copy(enabled = false, accessibilityAuthorized = true).controlChannels(operatorOn))
        assertEquals(emptySet(), app.copy(hidden = true, accessibilityAuthorized = true).controlChannels(operatorOn))
        assertEquals(emptySet(), app.copy(status = AiAppStatus.NOT_INSTALLED, accessibilityAuthorized = true).controlChannels(operatorOn))
        assertEquals(
            setOf(ControlChannel.LINKED_API),
            app.copy(status = AiAppStatus.NOT_INSTALLED, linkedProviderId = ProviderId("p")).controlChannels(operatorOn),
        )
        assertEquals(emptySet(), app.copy(capabilities = emptySet()).controlChannels(operatorOn))
        assertEquals(
            setOf(ControlChannel.LAUNCH),
            app.copy(capabilities = setOf(AiAppCapability.LAUNCH, AiAppCapability.SHARE_IMAGE)).controlChannels(operatorOn),
            "an app that only accepts images offers no text hand-off",
        )
    }

    @Test
    fun `accessibility is never a usable channel unless both operator switches are on`() {
        val app = AiApp(
            id = AiAppId("x"), packageName = "p", label = "P", source = DiscoverySource.CATALOG,
            capabilities = setOf(AiAppCapability.LAUNCH), accessibilityAuthorized = true, firstSeenAt = 0, lastSeenAt = 0,
        )
        assertTrue(ControlChannel.ACCESSIBILITY in app.authorisedChannels(), "the owner's own choice stays visible")
        for (settings in listOf(
            FrameworkSettings(),
            FrameworkSettings(operatorEnabled = true),
            FrameworkSettings(accessibilityOperatorEnabled = true),
        )) {
            assertEquals(setOf(ControlChannel.LAUNCH), app.controlChannels(settings))
        }
        assertTrue(app.copy(source = DiscoverySource.HEURISTIC).isUnverified)
        assertTrue(!app.copy(source = DiscoverySource.HEURISTIC, verifiedByUser = true).isUnverified)
    }

    @Test
    fun `domain models survive json`() {
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString(AgentProfile.serializer(), json.encodeToString(AgentProfile.serializer(), agent))
        assertEquals(agent, restored)
        assertEquals("\"agt_1\"", json.encodeToString(AgentId.serializer(), AgentId("agt_1")))
        assertEquals(FrameworkSettings(), json.decodeFromString(FrameworkSettings.serializer(), "{}"))
    }

    @Test
    fun `masking never reveals a short secret or its length`() {
        assertEquals("sk-a…9f2c", maskSecret("sk-abcdefghijklmnop9f2c"))
        for (short in listOf("", "abc", "12345678", "123456789", "hunter2pass", "x".repeat(19))) {
            assertEquals("••••••••", maskSecret(short))
        }
        assertEquals("aaaa…bbbb", maskSecret("aaaa" + "x".repeat(12) + "bbbb"))
    }
}
