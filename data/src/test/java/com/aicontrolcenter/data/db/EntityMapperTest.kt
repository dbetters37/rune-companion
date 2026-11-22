package com.aicontrolcenter.data.db

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
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
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.data.DataJson
import com.aicontrolcenter.data.db.mapper.toDomain
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.testkit.Samples
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Proves that no field of a domain object is lost or mixed up on its way through an entity.
 *
 * A fully populated sample is not enough on its own: where the sample happens to hold a
 * field's default value, a mapper that forgot the field would still pass. So every field also
 * gets at least one variant that changes only that field, and the variant list is checked
 * against the type's real field names. Adding a field to a core type therefore fails this test
 * until the mapper and a variant are added.
 */
class EntityMapperTest {

    @Test
    fun `agent profile round-trips every field`() {
        val sample = Samples.agent()
        assertEveryFieldSurvives(
            AgentProfile.serializer(), sample, AgentProfile::toEntity, { it.toDomain() },
            mapOf(
                "id" to listOf(sample.copy(id = AgentId("agt_other"))),
                "name" to listOf(sample.copy(name = "Rédacteur 🖋")),
                "kind" to AgentKind.entries.filter { it != sample.kind }.map { sample.copy(kind = it) },
                "description" to listOf(sample.copy(description = "")),
                "systemInstructions" to listOf(sample.copy(systemInstructions = "Line one.\n\tLine \"two\" with 'quotes'.")),
                "personality" to listOf(sample.copy(personality = "Dry.")),
                "responsibilities" to listOf(
                    sample.copy(responsibilities = emptyList()),
                    sample.copy(responsibilities = sample.responsibilities.reversed()),
                ),
                "skills" to listOf(sample.copy(skills = emptySet()), sample.copy(skills = setOf("review"))),
                "modelId" to listOf(sample.copy(modelId = null), sample.copy(modelId = ModelId("mdl_other"))),
                "fallbackModelIds" to listOf(
                    sample.copy(fallbackModelIds = emptyList()),
                    sample.copy(fallbackModelIds = listOf(ModelId("mdl_b"), ModelId("mdl_a"))),
                ),
                "generation" to listOf(
                    sample.copy(generation = GenerationSettings()),
                    sample.copy(generation = GenerationSettings(temperature = 1.25, maxOutputTokens = 1)),
                ),
                "toolIds" to listOf(sample.copy(toolIds = emptySet()), sample.copy(toolIds = setOf(ToolId("browser.open")))),
                "memory" to listOf(
                    sample.copy(memory = MemoryPolicy()),
                    sample.copy(
                        memory = MemoryPolicy(
                            privateMemoryEnabled = false, readableSpaces = setOf(SpaceId("spc_a"), SpaceId("spc_b")),
                            writableSpaces = emptySet(), autoRecall = true, recallLimit = 0,
                        ),
                    ),
                ),
                "delegation" to listOf(
                    sample.copy(delegation = DelegationPolicy()),
                    sample.copy(delegation = DelegationPolicy(canDelegate = false, canRequestAssistance = true, maxDepth = 7)),
                ),
                "enabled" to listOf(sample.copy(enabled = false)),
                "colorIndex" to listOf(sample.copy(colorIndex = 11)),
                "templateId" to listOf(sample.copy(templateId = null), sample.copy(templateId = "reviewer")),
                "createdAt" to listOf(sample.copy(createdAt = 1_700_000_000_123)),
                "updatedAt" to listOf(sample.copy(updatedAt = 1_700_000_000_456)),
            ),
        )
    }

    @Test
    fun `agent stats round-trip every counter`() {
        val agentId = AgentId("agt_sample")
        val sample = AgentStats(
            tasksAssigned = 1, tasksCompleted = 2, tasksFailed = 3, tasksTimedOut = 4, tasksCancelled = 5,
            reviewsPassed = 6, revisionsRequested = 7, reviewsRejected = 8, modelCalls = 9, modelErrors = 10,
            toolCalls = 11, toolErrors = 12, inputTokens = 13, outputTokens = 14, totalTaskDurationMs = 15,
            lastActiveAt = 16,
        )
        assertEveryFieldSurvives(
            AgentStats.serializer(), sample, { it.toEntity(agentId) }, { it.toDomain() },
            mapOf(
                "tasksAssigned" to listOf(sample.copy(tasksAssigned = 101)),
                "tasksCompleted" to listOf(sample.copy(tasksCompleted = 102)),
                "tasksFailed" to listOf(sample.copy(tasksFailed = 103)),
                "tasksTimedOut" to listOf(sample.copy(tasksTimedOut = 104)),
                "tasksCancelled" to listOf(sample.copy(tasksCancelled = 105)),
                "reviewsPassed" to listOf(sample.copy(reviewsPassed = 106)),
                "revisionsRequested" to listOf(sample.copy(revisionsRequested = 107)),
                "reviewsRejected" to listOf(sample.copy(reviewsRejected = 108)),
                "modelCalls" to listOf(sample.copy(modelCalls = 109)),
                "modelErrors" to listOf(sample.copy(modelErrors = 110)),
                "toolCalls" to listOf(sample.copy(toolCalls = 111)),
                "toolErrors" to listOf(sample.copy(toolErrors = 112)),
                "inputTokens" to listOf(sample.copy(inputTokens = 5_000_000_000)),
                "outputTokens" to listOf(sample.copy(outputTokens = 6_000_000_000)),
                "totalTaskDurationMs" to listOf(sample.copy(totalTaskDurationMs = 7_000_000_000)),
                "lastActiveAt" to listOf(sample.copy(lastActiveAt = null), sample.copy(lastActiveAt = 1_700_000_000_000)),
            ),
        )
        assertEquals("agt_sample", sample.toEntity(agentId).agentId)
        assertEquals(AgentStats(), AgentStats().toEntity(agentId).toDomain())
    }

    @Test
    fun `provider round-trips every field`() {
        val sample = Samples.provider()
        assertEveryFieldSurvives(
            ModelProvider.serializer(), sample, ModelProvider::toEntity, { it.toDomain() },
            mapOf(
                "id" to listOf(sample.copy(id = ProviderId("prv_other"))),
                "name" to listOf(sample.copy(name = "Cloud")),
                "kind" to ProviderKind.entries.filter { it != sample.kind }.map { sample.copy(kind = it) },
                "baseUrl" to listOf(sample.copy(baseUrl = null), sample.copy(baseUrl = "https://api.example.com/v1")),
                "apiKeyAlias" to listOf(sample.copy(apiKeyAlias = null), sample.copy(apiKeyAlias = "provider/other")),
                "extraHeaders" to listOf(
                    sample.copy(extraHeaders = emptyMap()),
                    sample.copy(extraHeaders = mapOf("A" to "1", "B" to "two words")),
                ),
                "locality" to Locality.entries.filter { it != sample.locality }.map { sample.copy(locality = it) },
                "enabled" to listOf(sample.copy(enabled = false)),
                "status" to ConnectionStatus.entries.filter { it != sample.status }.map { sample.copy(status = it) },
                "statusMessage" to listOf(sample.copy(statusMessage = null), sample.copy(statusMessage = "401 Unauthorized")),
                "lastCheckedAt" to listOf(sample.copy(lastCheckedAt = null), sample.copy(lastCheckedAt = 42)),
                "createdAt" to listOf(sample.copy(createdAt = 77)),
                "updatedAt" to listOf(sample.copy(updatedAt = 78)),
            ),
        )
    }

    @Test
    fun `model round-trips every field`() {
        val sample = Samples.model()
        assertEveryFieldSurvives(
            ModelDescriptor.serializer(), sample, ModelDescriptor::toEntity, { it.toDomain() },
            mapOf(
                "id" to listOf(sample.copy(id = ModelId("mdl_other"))),
                "providerId" to listOf(sample.copy(providerId = ProviderId("prv_other"))),
                "modelName" to listOf(sample.copy(modelName = "qwen3:8b")),
                "displayName" to listOf(sample.copy(displayName = "Qwen 3")),
                "capabilities" to listOf(
                    sample.copy(capabilities = com.aicontrolcenter.core.registry.ModelCapabilities()),
                    sample.copy(
                        capabilities = sample.capabilities.copy(
                            chat = false, toolCalling = false, vision = false, streaming = false, jsonOutput = false,
                            embeddings = true, reasoning = CapabilityTier.STRONG, coding = CapabilityTier.UNKNOWN,
                            contextWindow = null, maxOutputTokens = null,
                        ),
                    ),
                ),
                "source" to ModelSource.entries.filter { it != sample.source }.map { sample.copy(source = it) },
                "localPath" to listOf(sample.copy(localPath = null), sample.copy(localPath = "/other/path.gguf")),
                "sizeBytes" to listOf(sample.copy(sizeBytes = null), sample.copy(sizeBytes = 9_000_000_000)),
                "enabled" to listOf(sample.copy(enabled = false)),
                "status" to ModelStatus.entries.filter { it != sample.status }.map { sample.copy(status = it) },
                "notes" to listOf(sample.copy(notes = "")),
                "createdAt" to listOf(sample.copy(createdAt = 77)),
                "updatedAt" to listOf(sample.copy(updatedAt = 78)),
            ),
        )
    }

    @Test
    fun `ai app round-trips every field`() {
        val sample = Samples.aiApp()
        assertEveryFieldSurvives(
            AiApp.serializer(), sample, AiApp::toEntity, { it.toDomain() },
            mapOf(
                "id" to listOf(sample.copy(id = AiAppId("app_other"))),
                "packageName" to listOf(sample.copy(packageName = "org.other.app")),
                "label" to listOf(sample.copy(label = "Other")),
                "vendor" to listOf(sample.copy(vendor = null), sample.copy(vendor = "Other Ltd")),
                "versionName" to listOf(sample.copy(versionName = null), sample.copy(versionName = "5.0")),
                "source" to DiscoverySource.entries.filter { it != sample.source }.map { sample.copy(source = it) },
                "capabilities" to listOf(
                    sample.copy(capabilities = emptySet()),
                    sample.copy(capabilities = AiAppCapability.entries.toSet()),
                ),
                "status" to AiAppStatus.entries.filter { it != sample.status }.map { sample.copy(status = it) },
                "enabled" to listOf(sample.copy(enabled = false)),
                "accessibilityAuthorized" to listOf(sample.copy(accessibilityAuthorized = false)),
                "linkedProviderId" to listOf(
                    sample.copy(linkedProviderId = null),
                    sample.copy(linkedProviderId = ProviderId("prv_other")),
                ),
                "promptDeepLink" to listOf(sample.copy(promptDeepLink = null), sample.copy(promptDeepLink = "https://x.test/?p={prompt}")),
                "notes" to listOf(sample.copy(notes = "")),
                "firstSeenAt" to listOf(sample.copy(firstSeenAt = 77)),
                "lastSeenAt" to listOf(sample.copy(lastSeenAt = 78)),
            ),
        )
    }

    @Test
    fun `activity event round-trips every field`() {
        val sample = Samples.event()
        assertEveryFieldSurvives(
            ActivityEvent.serializer(), sample, ActivityEvent::toEntity, { it.toDomain() },
            mapOf(
                "id" to listOf(sample.copy(id = EventId("evt_other"))),
                "at" to listOf(sample.copy(at = 1_700_000_000_000)),
                "type" to ActivityType.entries.filter { it != sample.type }.map { sample.copy(type = it) },
                "severity" to Severity.entries.filter { it != sample.severity }.map { sample.copy(severity = it) },
                "agentId" to listOf(sample.copy(agentId = null), sample.copy(agentId = AgentId("agt_other"))),
                "taskId" to listOf(sample.copy(taskId = null), sample.copy(taskId = TaskId("tsk_other"))),
                "rootTaskId" to listOf(sample.copy(rootTaskId = null), sample.copy(rootTaskId = TaskId("tsk_other_root"))),
                "title" to listOf(sample.copy(title = "Tool call")),
                "detail" to listOf(sample.copy(detail = "")),
                "data" to listOf(
                    sample.copy(data = null),
                    sample.copy(
                        data = buildJsonObject {
                            put("nested", buildJsonObject { put("ok", true) })
                            put("text", "ünïcode")
                            put("ratio", 0.5)
                        },
                    ),
                ),
                "egress" to listOf(
                    sample.copy(egress = null),
                    sample.copy(
                        egress = sample.egress!!.copy(
                            destinationHost = "api.example.com", locality = Locality.CLOUD, providerId = null,
                            purpose = "search query", approxBytes = null,
                        ),
                    ),
                ),
            ),
        )
    }

    /**
     * @param variants for each field name, copies of [sample] that differ from it in that field only.
     */
    private fun <D, E> assertEveryFieldSurvives(
        serializer: KSerializer<D>,
        sample: D,
        toEntity: (D) -> E,
        toDomain: (E) -> D,
        variants: Map<String, List<D>>,
    ) {
        fun fields(value: D) = DataJson.encodeToJsonElement(serializer, value).jsonObject

        assertEquals(fields(sample).keys, variants.keys, "every field of the type needs a variant")
        assertEquals(sample, toDomain(toEntity(sample)), "the sample itself does not round-trip")
        for ((field, copies) in variants) {
            assertEquals(true, copies.isNotEmpty(), "no variant for $field")
            for (variant in copies) {
                val changed = fields(sample).filter { (name, value) -> fields(variant)[name] != value }.keys
                assertEquals(setOf(field), changed, "the variant for $field must change exactly that field")
                assertEquals(variant, toDomain(toEntity(variant)), "$field is lost or altered in the round trip")
                assertNotEquals(toEntity(sample), toEntity(variant), "$field never reaches the entity")
            }
        }
    }
}
