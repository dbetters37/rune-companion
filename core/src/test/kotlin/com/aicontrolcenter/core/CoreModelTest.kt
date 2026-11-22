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
import com.aicontrolcenter.core.registry.controlChannels
import com.aicontrolcenter.core.security.maskSecret
import com.aicontrolcenter.core.settings.FrameworkSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreModelTest {
    @Test
    fun `locality is inferred from the host and never under-reports`() {
        assertEquals(Locality.ON_DEVICE, Locality.infer(null))
        assertEquals(Locality.ON_DEVICE, Locality.infer("http://127.0.0.1:11434"))
        assertEquals(Locality.ON_DEVICE, Locality.infer("http://localhost:8080/v1"))
        assertEquals(Locality.ON_DEVICE, Locality.infer("http://[::1]:8080/v1"))
        assertEquals(Locality.LOCAL_NETWORK, Locality.infer("http://192.168.1.20:1234/v1"))
        assertEquals(Locality.LOCAL_NETWORK, Locality.infer("http://10.0.0.5"))
        assertEquals(Locality.LOCAL_NETWORK, Locality.infer("http://172.16.4.4/v1"))
        assertEquals(Locality.LOCAL_NETWORK, Locality.infer("http://100.101.102.103:11434"))
        assertEquals(Locality.LOCAL_NETWORK, Locality.infer("http://desktop.local:11434"))
        assertEquals(Locality.CLOUD, Locality.infer("http://172.32.0.1/v1"))
        assertEquals(Locality.CLOUD, Locality.infer("https://api.example.com/v1"))
        assertEquals(Locality.CLOUD, Locality.infer("https://user:pw@api.example.com:443/v1"))
        assertEquals(Locality.CLOUD, Locality.infer("https://8.8.8.8"))
        assertEquals(Locality.CLOUD, Locality.infer("://"))
        assertEquals(Locality.CLOUD, Locality.infer("https://localhost.evil.example"))
        assertEquals(Locality.CLOUD, Locality.infer("https://127.evil.example"))
    }

    private val provider = ModelProvider(
        id = ProviderId("p"), name = "Cloud", kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = "https://api.example.com",
        locality = Locality.CLOUD, status = ConnectionStatus.ONLINE, createdAt = 0, updatedAt = 0,
    )
    private val localProvider = provider.copy(id = ProviderId("local"), name = "Phone", locality = Locality.ON_DEVICE)
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
    fun `control channels only include what the app supports and the user authorised`() {
        val app = AiApp(
            id = AiAppId("x"), packageName = "p", label = "P", source = DiscoverySource.CATALOG,
            capabilities = setOf(AiAppCapability.LAUNCH, AiAppCapability.SHARE_TEXT), firstSeenAt = 0, lastSeenAt = 0,
        )
        assertEquals(setOf(ControlChannel.LAUNCH, ControlChannel.INTENT_HANDOFF), app.controlChannels())
        assertEquals(
            setOf(ControlChannel.LAUNCH, ControlChannel.INTENT_HANDOFF, ControlChannel.ROUND_TRIP_TEXT, ControlChannel.ACCESSIBILITY),
            app.copy(capabilities = app.capabilities + AiAppCapability.PROCESS_TEXT, accessibilityAuthorized = true).controlChannels(),
        )
        assertEquals(emptySet(), app.copy(enabled = false, accessibilityAuthorized = true).controlChannels())
        assertEquals(emptySet(), app.copy(status = AiAppStatus.NOT_INSTALLED, accessibilityAuthorized = true).controlChannels())
        assertEquals(
            setOf(ControlChannel.LINKED_API),
            app.copy(status = AiAppStatus.NOT_INSTALLED, linkedProviderId = ProviderId("p")).controlChannels(),
        )
        assertEquals(emptySet(), app.copy(capabilities = emptySet()).controlChannels())
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
    fun `secrets are masked for display`() {
        assertEquals("sk-a…9f2c", maskSecret("sk-abcdefgh9f2c"))
        assertEquals("••••", maskSecret("abc"))
        assertEquals("••••••••", maskSecret("12345678"))
    }
}
