package com.aicontrolcenter.core.agents

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.settings.FrameworkSettings
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Phase 6, K15, rule 25: no silent move farther from the phone while a provider is only not answering. */
class NoSilentMoveTest {
    private fun provider(id: String, baseUrl: String, locality: Locality, status: ConnectionStatus = ConnectionStatus.ONLINE) = ModelProvider(
        id = ProviderId(id), name = id, kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = baseUrl, locality = locality, status = status,
        createdAt = 0, updatedAt = 0,
    )

    private fun model(id: String, provider: ModelProvider) = ModelDescriptor(
        id = ModelId(id), providerId = provider.id, modelName = id, displayName = id, source = ModelSource.MANUAL,
        status = ModelStatus.AVAILABLE, createdAt = 0, updatedAt = 0,
    )

    private val phone = provider("phone", "http://127.0.0.1:8080", Locality.ON_DEVICE)
    private val lan = provider("lan", "http://192.168.1.20:11434", Locality.LOCAL_NETWORK)
    private val otherLan = provider("lan2", "http://192.168.1.30:11434", Locality.LOCAL_NETWORK)
    private val cloud = provider("cloud", "https://api.example.com", Locality.CLOUD)

    private val onPhone = model("m_phone", phone)
    private val onLan = model("m_lan", lan)
    private val onOtherLan = model("m_lan2", otherLan)
    private val inCloud = model("m_cloud", cloud)

    private fun resolve(primary: ModelDescriptor, fallbacks: List<ModelDescriptor>, providers: List<ModelProvider>, settings: FrameworkSettings = FrameworkSettings()) =
        resolveAvailability(
            AgentProfile(id = AgentId("a"), name = "A", kind = AgentKind.SPECIALIST, modelId = primary.id, fallbackModelIds = fallbacks.map { it.id }, createdAt = 0, updatedAt = 0),
            listOf(onPhone, onLan, onOtherLan, inCloud).associateBy { it.id },
            providers.associateBy { it.id },
            settings,
        )

    @Test
    fun `only a provider that is not answering is a passing reason`() {
        assertEquals(listOf(UnavailableReason.PROVIDER_OFFLINE), UnavailableReason.entries.filter { it.isTransient })
    }

    @Test
    fun `a network model that is not answering never falls back to the cloud`() {
        val providers = listOf(phone, lan.copy(status = ConnectionStatus.OFFLINE), otherLan, cloud)
        val waiting = assertIs<AgentAvailability.Unavailable>(resolve(onLan, listOf(inCloud), providers))
        assertEquals(UnavailableReason.PROVIDER_OFFLINE, waiting.reason, "the work waits for the primary")
        // Fallbacks as close or closer to the phone are still used, in order.
        assertEquals(AgentAvailability.Ready(onOtherLan.id, usingFallback = true), resolve(onLan, listOf(inCloud, onOtherLan), providers))
        assertEquals(AgentAvailability.Ready(onPhone.id, usingFallback = true), resolve(onLan, listOf(inCloud, onPhone), providers))
    }

    @Test
    fun `a cloud primary that is not answering may fall back anywhere, and owner-made reasons keep the order`() {
        val cloudDown = listOf(phone, lan, cloud.copy(status = ConnectionStatus.OFFLINE))
        assertEquals(AgentAvailability.Ready(onLan.id, usingFallback = true), resolve(inCloud, listOf(onLan), cloudDown))
        // Disabled, key rejected, local-only: the owner's doing; fallbacks farther away are used as before.
        assertEquals(AgentAvailability.Ready(inCloud.id, usingFallback = true), resolve(onLan, listOf(inCloud), listOf(lan.copy(enabled = false), cloud)))
        assertEquals(AgentAvailability.Ready(inCloud.id, usingFallback = true), resolve(onLan, listOf(inCloud), listOf(lan.copy(status = ConnectionStatus.AUTH_ERROR), cloud)))
        assertEquals(AgentAvailability.Ready(inCloud.id, usingFallback = true), resolve(onPhone, listOf(inCloud), listOf(cloud)), "provider removed")
    }

    @Test
    fun `a provider labelled closer than its address is judged by its address`() {
        // Stored as on the network, but its address is on the internet: it counts as cloud.
        val disguised = provider("disguised", "https://api.example.net", Locality.LOCAL_NETWORK)
        val disguisedModel = model("m_disguised", disguised)
        val availability = resolveAvailability(
            AgentProfile(id = AgentId("a"), name = "A", kind = AgentKind.SPECIALIST, modelId = onLan.id, fallbackModelIds = listOf(disguisedModel.id), createdAt = 0, updatedAt = 0),
            listOf(onLan, disguisedModel).associateBy { it.id },
            listOf(lan.copy(status = ConnectionStatus.OFFLINE), disguised).associateBy { it.id },
            FrameworkSettings(),
        )
        assertFalse(availability.isReady)
        assertTrue(resolve(onLan, emptyList(), listOf(lan)).isReady)
    }
}
