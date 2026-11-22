package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.DelegationPolicy
import com.aicontrolcenter.core.agents.GenerationSettings
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.ProviderKind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fully populated sample objects, so persistence tests exercise every field. */
object Samples {
    fun agent(
        id: String = "agt_sample",
        name: String = "Researcher",
        kind: AgentKind = AgentKind.SPECIALIST,
        modelId: String? = "mdl_sample",
        createdAt: Long = 1_000,
        enabled: Boolean = true,
    ) = AgentProfile(
        id = AgentId(id), name = name, kind = kind, description = "Finds and summarises sources.",
        systemInstructions = "Research carefully.\nCite what you use.", personality = "Curious, precise.",
        responsibilities = listOf("Gather sources", "Summarise findings"), skills = setOf("research", "analysis"),
        modelId = modelId?.let(::ModelId), fallbackModelIds = listOf(ModelId("mdl_fallback")),
        generation = GenerationSettings(temperature = 0.4, maxOutputTokens = 2048),
        toolIds = setOf(ToolId("memory.search"), ToolId("workspace.write_file")),
        memory = MemoryPolicy(
            privateMemoryEnabled = true, readableSpaces = setOf(SpaceId("spc_commons")),
            writableSpaces = setOf(SpaceId("spc_commons")), autoRecall = false, recallLimit = 4,
        ),
        delegation = DelegationPolicy(canDelegate = true, canRequestAssistance = false, maxDepth = 2),
        enabled = enabled, colorIndex = 3, templateId = "researcher", createdAt = createdAt, updatedAt = createdAt + 5,
    )

    fun provider(
        id: String = "prv_sample",
        name: String = "Home server",
        kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl: String? = "http://192.168.1.20:8080/v1",
        locality: Locality = Locality.LOCAL_NETWORK,
        status: ConnectionStatus = ConnectionStatus.ONLINE,
        enabled: Boolean = true,
        createdAt: Long = 1_000,
    ) = ModelProvider(
        id = ProviderId(id), name = name, kind = kind, baseUrl = baseUrl, apiKeyAlias = "provider/$id",
        extraHeaders = mapOf("X-Title" to "AI Control Center"), locality = locality, enabled = enabled,
        status = status, statusMessage = "ok", lastCheckedAt = createdAt + 1, createdAt = createdAt, updatedAt = createdAt + 2,
    )

    fun model(
        id: String = "mdl_sample",
        providerId: String = "prv_sample",
        modelName: String = "gemma3:4b",
        status: ModelStatus = ModelStatus.AVAILABLE,
        enabled: Boolean = true,
        createdAt: Long = 1_000,
    ) = ModelDescriptor(
        id = ModelId(id), providerId = ProviderId(providerId), modelName = modelName, displayName = "Gemma 3 4B",
        capabilities = ModelCapabilities(
            chat = true, toolCalling = true, vision = true, streaming = true, jsonOutput = true, embeddings = false,
            reasoning = CapabilityTier.GOOD, coding = CapabilityTier.BASIC, contextWindow = 8192, maxOutputTokens = 2048,
        ),
        source = ModelSource.PROVIDER_LISTING, localPath = "/data/models/gemma.litertlm", sizeBytes = 3_100_000_000,
        enabled = enabled, status = status, notes = "quantised", createdAt = createdAt, updatedAt = createdAt + 3,
    )

    fun aiApp(
        id: String = "app_sample",
        packageName: String = "com.example.chatbot",
        status: AiAppStatus = AiAppStatus.INSTALLED,
        firstSeenAt: Long = 1_000,
    ) = AiApp(
        id = AiAppId(id), packageName = packageName, label = "Example Chatbot", vendor = "Example Inc.",
        versionName = "4.2.1", source = DiscoverySource.CATALOG,
        capabilities = setOf(AiAppCapability.LAUNCH, AiAppCapability.SHARE_TEXT, AiAppCapability.PROCESS_TEXT),
        status = status, enabled = true, accessibilityAuthorized = true, linkedProviderId = ProviderId("prv_sample"),
        promptDeepLink = "https://chat.example.com/?q={prompt}", notes = "Work account",
        firstSeenAt = firstSeenAt, lastSeenAt = firstSeenAt + 10,
    )

    fun event(
        id: String = "evt_sample",
        at: Long = 1_000,
        type: ActivityType = ActivityType.MODEL_CALL,
        severity: Severity = Severity.INFO,
        agentId: String? = "agt_sample",
        taskId: String? = "tsk_child",
        rootTaskId: String? = "tsk_root",
        egress: Boolean = true,
    ) = ActivityEvent(
        id = EventId(id), at = at, type = type, severity = severity, agentId = agentId?.let(::AgentId),
        taskId = taskId?.let { com.aicontrolcenter.core.TaskId(it) },
        rootTaskId = rootTaskId?.let { com.aicontrolcenter.core.TaskId(it) },
        title = "Model call", detail = "Sent 3 messages",
        data = buildJsonObject { put("tokens", 120); put("model", "gemma3:4b") },
        egress = if (egress) {
            EgressInfo(
                destinationHost = "192.168.1.20", locality = Locality.LOCAL_NETWORK,
                providerId = ProviderId("prv_sample"), purpose = "prompt and conversation", approxBytes = 2048,
            )
        } else {
            null
        },
    )
}
