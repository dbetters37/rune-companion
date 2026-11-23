package com.aicontrolcenter.data

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.DelegationPolicy
import com.aicontrolcenter.core.agents.GenerationSettings
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.ImageContent
import com.aicontrolcenter.core.llm.ProviderData
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.llm.ToolResultContent
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceAction
import com.aicontrolcenter.core.memory.ProvenanceEvent
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.ReviewVerdict
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskUsage
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.TaskDao
import com.aicontrolcenter.data.db.entity.AgentMessageEntity
import com.aicontrolcenter.data.db.entity.KnowledgeSpaceEntity
import com.aicontrolcenter.data.db.entity.MemoryEmbeddingEntity
import com.aicontrolcenter.data.db.entity.MemoryEntity
import com.aicontrolcenter.data.db.entity.MemoryLinkEntity
import com.aicontrolcenter.data.db.entity.TaskEntity
import com.aicontrolcenter.data.db.mapper.ColumnSerializers
import com.aicontrolcenter.data.db.mapper.EmbeddingCodec
import com.aicontrolcenter.data.db.mapper.MemoryScopeType
import com.aicontrolcenter.data.db.mapper.ParticipantColumns
import com.aicontrolcenter.data.db.mapper.ParticipantKind
import com.aicontrolcenter.data.db.mapper.ScopeColumns
import com.aicontrolcenter.data.db.mapper.encodeColumn
import com.aicontrolcenter.data.db.mapper.level
import com.aicontrolcenter.data.db.mapper.memoryHistorySerializer
import com.aicontrolcenter.data.db.mapper.memorySearchText
import com.aicontrolcenter.data.db.mapper.memorySearchWords
import com.aicontrolcenter.data.db.mapper.toColumns
import com.aicontrolcenter.data.db.mapper.toDomain
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.data.security.AndroidKeystoreVaultCipher
import com.aicontrolcenter.data.security.VaultFileStore
import com.aicontrolcenter.data.settings.DataStoreSettingsRepository
import com.aicontrolcenter.data.settings.SettingsCodec
import com.aicontrolcenter.testkit.contract.TaskSamples
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins every name that ends up in stored data: enum constants written to text columns and
 * into JSON, the keys of the JSON objects, and the names of the files, preference keys and
 * keystore entries themselves.
 *
 * These names live in `:core` and in this module's constants, where renaming one looks like a
 * harmless refactoring. On a phone that already holds data it is not: rows stop decoding,
 * settings fall back to defaults, secrets and the database become unreachable. So a change to
 * any of them fails here first.
 *
 * When this test fails:
 * - a name was **added**: add it to the list below. Old data stays readable, no migration.
 * - a name was **renamed or removed**: stored data still uses the old name. Write a database
 *   migration (or a settings/vault conversion) first, then update the list.
 */
class StoredNamesGoldenTest {

    @Test
    fun `enum names stored in text columns and JSON`() {
        assertStoredNames<AgentKind>("DIRECTOR", "SPECIALIST", "REVIEWER", "OPERATOR")
        assertStoredNames<ProviderKind>("OPENAI_COMPATIBLE", "ANTHROPIC", "GEMINI", "OLLAMA", "ON_DEVICE", "SIMULATOR")
        assertStoredNames<Locality>("ON_DEVICE", "LOCAL_NETWORK", "CLOUD")
        assertStoredNames<ConnectionStatus>("UNKNOWN", "ONLINE", "OFFLINE", "AUTH_ERROR", "ERROR")
        assertStoredNames<ModelSource>("PROVIDER_LISTING", "MANUAL", "IMPORTED_FILE", "BUILT_IN")
        assertStoredNames<ModelStatus>("UNKNOWN", "AVAILABLE", "UNAVAILABLE")
        assertStoredNames<CapabilityTier>("UNKNOWN", "BASIC", "GOOD", "STRONG")
        assertStoredNames<DiscoverySource>("CATALOG", "INTENT_QUERY", "HEURISTIC", "MANUAL")
        assertStoredNames<AiAppStatus>("INSTALLED", "DISABLED_BY_SYSTEM", "NOT_INSTALLED")
        assertStoredNames<AiAppCapability>(
            "LAUNCH", "SHARE_TEXT", "SHARE_IMAGE", "PROCESS_TEXT", "ASSISTANT", "PROMPT_DEEP_LINK", "LOCAL_MODEL_HOST",
        )
        assertStoredNames<ActivityType>(
            "SYSTEM", "USER_ACTION", "REGISTRY", "MESSAGE", "MODEL_CALL", "TOOL_CALL", "PERMISSION", "APPROVAL",
            "TASK", "MEMORY", "DATA_EGRESS", "ERROR",
        )
        assertStoredNames<ChatRole>("SYSTEM", "USER", "ASSISTANT", "TOOL")
        assertStoredNames<MemoryKind>("FACT", "PREFERENCE", "EVENT", "FINDING", "DECISION", "PROCEDURE", "INSIGHT", "SUMMARY", "NOTE")
        assertStoredNames<MemoryTier>("SHORT_TERM", "LONG_TERM")
        assertStoredNames<ProvenanceOrigin>("OWNER", "AGENT", "TOOL", "SYSTEM", "DERIVED", "IMPORT")
        assertStoredNames<LinkRelation>(
            "RELATES_TO", "SUPPORTS", "CONTRADICTS", "DERIVED_FROM", "PART_OF", "REFERENCES", "SUPERSEDES", "SHARED_COPY_OF",
        )
        assertStoredNames<TaskKind>("OBJECTIVE", "WORK", "REVIEW", "SYNTHESIS")
        assertStoredNames<TaskStatus>(
            "QUEUED", "RUNNING", "WAITING_FOR_AGENT", "IN_REVIEW", "PAUSED", "COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED",
            "INTERRUPTED", "WAITING_FOR_OWNER",
        )
        assertStoredNames<ReviewDecision>("APPROVED", "REVISE", "REJECTED")
        assertStoredNames<MessageType>(
            "ASSIGNMENT", "ASSISTANCE_REQUEST", "ASSISTANCE_RESPONSE", "FINDINGS", "DELEGATION", "MEMORY_SHARE", "REVIEW_REQUEST",
            "REVIEW_RESULT", "COMPLETION", "FAILURE", "QUESTION", "ANSWER", "CANCEL", "STATUS",
        )
    }

    @Test
    fun `names of the task tables and of the participant kinds in them`() {
        assertEquals(listOf("tasks", "agent_messages"), listOf(TaskEntity.TABLE, AgentMessageEntity.TABLE))
        // The objectives listing filters on the stored name of the kind.
        assertTrue("kind = '${TaskKind.OBJECTIVE.name}'" in TaskDao.OBJECTIVES, TaskDao.OBJECTIVES)
        assertEquals(listOf("OWNER", "AGENT", "SYSTEM"), ParticipantKind.entries.map { it.name })
        assertEquals(ParticipantColumns(ParticipantKind.OWNER, null), Participant.Owner.toColumns())
        assertEquals(ParticipantColumns(ParticipantKind.SYSTEM, null), Participant.System.toColumns())
        assertEquals(ParticipantColumns(ParticipantKind.AGENT, "agt_1"), Participant.Agent(AgentId("agt_1")).toColumns())
        val message = AgentMessage(
            MessageId("msg_1"), TaskId("tsk_root"), TaskId("tsk_1"), Participant.Agent(AgentId("agt_1")), Participant.Owner,
            MessageType.REVIEW_RESULT, "Looks right", MessageId("msg_0"), hop = 2, at = 9,
        ).toEntity()
        assertEquals(
            AgentMessageEntity("msg_1", "tsk_root", "tsk_1", "AGENT", "agt_1", "OWNER", null, "REVIEW_RESULT", "Looks right", "msg_0", 2, 9),
            message,
        )
    }

    @Test
    fun `keys and exact text of the task columns`() {
        assertStoredKeys(ReviewVerdict.serializer(), "reviewer", "decision", "summary", "issues", "at", "reviewTaskId")
        assertStoredKeys(TaskUsage.serializer(), "modelCalls", "toolCalls", "inputTokens", "outputTokens", "messagesSent")
        assertStoredKeys(ObjectiveOptions.serializer(), "reviewResults", "saveKnowledge", "maxTasks")
        assertEquals("""{"type":"owner"}""", encodeColumn(ColumnSerializers.taskRequester, TaskRequester.Owner))
        assertEquals(
            """{"type":"agent","agentId":"agt_director"}""",
            encodeColumn(ColumnSerializers.taskRequester, TaskRequester.Agent(AgentId("agt_director"))),
        )
        assertEquals(
            """[{"reviewer":"agt_reviewer","decision":"REVISE","summary":"Missing prices","issues":["Add prices","Cite sources"],""" +
                """"at":5,"reviewTaskId":"tsk_rev"},{"reviewer":"agt_b","decision":"APPROVED","summary":"Fine","issues":[],""" +
                """"at":6,"reviewTaskId":null}]""",
            encodeColumn(
                ColumnSerializers.reviewList,
                listOf(
                    ReviewVerdict(AgentId("agt_reviewer"), ReviewDecision.REVISE, "Missing prices", listOf("Add prices", "Cite sources"), 5, TaskId("tsk_rev")),
                    ReviewVerdict(AgentId("agt_b"), ReviewDecision.APPROVED, "Fine", at = 6),
                ),
            ),
        )
        assertEquals(
            """{"modelCalls":3,"toolCalls":2,"inputTokens":1200,"outputTokens":300,"messagesSent":4}""",
            encodeColumn(TaskUsage.serializer(), TaskUsage(3, 2, 1_200, 300, 4)),
        )
        assertEquals(
            """{"reviewResults":true,"saveKnowledge":false,"maxTasks":null}""",
            encodeColumn(ObjectiveOptions.serializer(), ObjectiveOptions()),
        )
        assertEquals("""["tsk_z","tsk_a"]""", encodeColumn(ColumnSerializers.taskIdList, listOf(TaskId("tsk_z"), TaskId("tsk_a"))))
        assertEquals("""["agt_director","agt_lead"]""", encodeColumn(ColumnSerializers.agentIdList, listOf(AgentId("agt_director"), AgentId("agt_lead"))))
    }

    @Test
    fun `a task stored with fewer JSON keys than today still reads back`() {
        // Objects in the task columns may gain fields with defaults without a migration; rows keep what they were written with.
        val stored = TaskSamples.objective().toEntity().copy(usageJson = """{"modelCalls":2}""", optionsJson = """{"saveKnowledge":true}""")
        val task = stored.toDomain()
        assertEquals(TaskUsage(modelCalls = 2), task.usage)
        assertEquals(ObjectiveOptions(reviewResults = true, saveKnowledge = true, maxTasks = null), task.options)
    }

    @Test
    fun `names of the memory tables and of the scope types in them`() {
        assertEquals(
            listOf("knowledge_spaces", "memories", "memory_links", "memory_embeddings"),
            listOf(KnowledgeSpaceEntity.TABLE, MemoryEntity.TABLE, MemoryLinkEntity.TABLE, MemoryEmbeddingEntity.TABLE),
        )
        assertEquals(listOf("AGENT", "SHARED", "TASK"), MemoryScopeType.entries.map { it.name })
        assertEquals(ScopeColumns(MemoryScopeType.AGENT, "agt_1", null, null), MemoryScope.Agent(AgentId("agt_1")).toColumns())
        assertEquals(ScopeColumns(MemoryScopeType.SHARED, null, "spc_1", null), MemoryScope.Shared(SpaceId("spc_1")).toColumns())
        assertEquals(
            ScopeColumns(MemoryScopeType.TASK, "agt_1", null, "tsk_1"),
            MemoryScope.Task(TaskId("tsk_1"), AgentId("agt_1")).toColumns(),
        )
    }

    @Test
    fun `keys and exact text of the memory columns`() {
        assertStoredKeys(
            Provenance.serializer(),
            "origin", "agentId", "taskId", "conversationId", "modelId", "modelName", "derivedFrom", "source", "recordedAt",
        )
        assertEquals(
            """{"origin":"AGENT","agentId":"agt_1","taskId":"tsk_1","conversationId":"cnv_1","modelId":"mdl_1",""" +
                """"modelName":"model-x","derivedFrom":["mem_z","mem_a"],"source":"Tool: Clock","recordedAt":42}""",
            encodeColumn(
                Provenance.serializer(),
                Provenance(
                    ProvenanceOrigin.AGENT, AgentId("agt_1"), TaskId("tsk_1"), ConversationId("cnv_1"), ModelId("mdl_1"), "model-x",
                    listOf(MemoryId("mem_z"), MemoryId("mem_a")), "Tool: Clock", recordedAt = 42,
                ),
            ),
        )
        assertEquals(
            """{"origin":"OWNER","agentId":null,"taskId":null,"conversationId":null,"modelId":null,"modelName":null,""" +
                """"derivedFrom":[],"source":null,"recordedAt":1}""",
            encodeColumn(Provenance.serializer(), Provenance(ProvenanceOrigin.OWNER, recordedAt = 1)),
        )
        assertEquals("""["research","shared tag"]""", encodeColumn(ColumnSerializers.stringSet, linkedSetOf("research", "shared tag")))
        // Rows keep the search text they were written with: a change here needs a migration that rebuilds it.
        // Accents go and compatibility characters decompose, as in the memory engine's keyword matching.
        assertEquals(
            "uber apfel\nline one\nline two\ntea time ß cafe fi 2",
            memorySearchText("Über ÄPFEL", "Line one\nLINE two", linkedSetOf("tea time", "ß cafe\u0301 ﬁ ²")),
        )
        assertEquals(listOf("cafe", "munchen", "ωμεγα"), memorySearchWords("Café  MÜNCHEN Ωμέγα café"))
        assertStoredKeys(ProvenanceEvent.serializer(), "action", "byOrigin", "byAgentId", "at", "relatedMemory", "note")
        assertStoredNames<ProvenanceAction>("EDITED", "SHARED_FROM", "PROMOTED_FROM")
        assertEquals(
            """[{"action":"SHARED_FROM","byOrigin":"AGENT","byAgentId":"agt_1","at":7,"relatedMemory":"mem_a","note":"Copied revision 2"},""" +
                """{"action":"EDITED","byOrigin":"OWNER","byAgentId":null,"at":9,"relatedMemory":null,"note":null}]""",
            encodeColumn(
                memoryHistorySerializer,
                listOf(
                    ProvenanceEvent(ProvenanceAction.SHARED_FROM, ProvenanceOrigin.AGENT, AgentId("agt_1"), 7, MemoryId("mem_a"), "Copied revision 2"),
                    ProvenanceEvent(ProvenanceAction.EDITED, ProvenanceOrigin.OWNER, at = 9),
                ),
            ),
        )
        // Little-endian float32: 1.0 and -0.0.
        assertContentEquals(
            byteArrayOf(0, 0, 0x80.toByte(), 0x3F, 0, 0, 0, 0x80.toByte()),
            EmbeddingCodec.encode(floatArrayOf(1f, -0f)),
        )
    }

    @Test
    fun `severity is stored as a number per level`() {
        assertEquals(
            mapOf("INFO" to 10, "WARNING" to 20, "ERROR" to 30),
            Severity.entries.associate { it.name to it.level },
        )
    }

    @Test
    fun `keys of the JSON objects stored in columns`() {
        assertStoredKeys(GenerationSettings.serializer(), "temperature", "maxOutputTokens")
        assertStoredKeys(
            MemoryPolicy.serializer(),
            "privateMemoryEnabled", "readableSpaces", "writableSpaces", "autoRecall", "recallLimit",
        )
        assertStoredKeys(DelegationPolicy.serializer(), "canDelegate", "canRequestAssistance", "maxDepth")
        assertStoredKeys(
            ModelCapabilities.serializer(),
            "chat", "toolCalling", "vision", "streaming", "jsonOutput", "embeddings", "reasoning", "coding",
            "contextWindow", "maxOutputTokens",
        )
        assertStoredKeys(EgressInfo.serializer(), "destinationHost", "locality", "providerId", "purpose", "approxBytes")
    }

    @Test
    fun `keys of a stored conversation message and its token usage`() {
        assertStoredKeys(
            ChatMessage.serializer(),
            "role", "text", "images", "toolCalls", "toolResult", "reasoning", "providerData",
        )
        assertStoredKeys(ImageContent.serializer(), "mimeType", "base64")
        assertStoredKeys(ToolCallRequest.serializer(), "id", "name", "arguments", "argumentsError")
        assertStoredKeys(ToolResultContent.serializer(), "callId", "toolName", "content", "isError")
        assertStoredKeys(ProviderData.serializer(), "kind", "payload")
        assertStoredKeys(TokenUsage.serializer(), "inputTokens", "outputTokens", "cachedInputTokens")
    }

    @Test
    fun `a tool call stored before argumentsError existed still reads back`() {
        // Conversations saved by the first build with conversation storage have no such key.
        val stored = """{"role":"ASSISTANT","text":"Checking","images":[],""" +
            """"toolCalls":[{"id":"call_1","name":"clock_now","arguments":{"zone":"UTC"}}],""" +
            """"toolResult":null,"reasoning":null,"providerData":null}"""
        val message = DataJson.decodeFromString(ChatMessage.serializer(), stored)
        assertEquals(ToolCallRequest("call_1", "clock_now", buildJsonObject { put("zone", "UTC") }), message.toolCalls.single())
    }

    @Test
    fun `exact text of the conversation entry columns`() {
        assertEquals(
            """{"role":"USER","text":"Look","images":[{"mimeType":"image/png","base64":"aGVsbG8="}],"toolCalls":[],""" +
                """"toolResult":null,"reasoning":null,"providerData":null}""",
            encodeColumn(ChatMessage.serializer(), ChatMessage.user("Look", listOf(ImageContent("image/png", "aGVsbG8=")))),
        )
        assertEquals(
            """{"role":"ASSISTANT","text":"Checking","images":[],""" +
                """"toolCalls":[{"id":"call_1","name":"clock_now","arguments":{"zone":"UTC"},"argumentsError":null}],"toolResult":null,""" +
                """"reasoning":"The owner wants the time.",""" +
                """"providerData":{"kind":"ANTHROPIC","payload":[{"type":"thinking","signature":"c2ln"}]}}""",
            encodeColumn(
                ChatMessage.serializer(),
                ChatMessage(
                    role = ChatRole.ASSISTANT, text = "Checking",
                    toolCalls = listOf(ToolCallRequest("call_1", "clock_now", buildJsonObject { put("zone", "UTC") })),
                    reasoning = "The owner wants the time.",
                    providerData = ProviderData(
                        ProviderKind.ANTHROPIC,
                        buildJsonArray { add(buildJsonObject { put("type", "thinking"); put("signature", "c2ln") }) },
                    ),
                ),
            ),
        )
        assertEquals(
            """{"role":"TOOL","text":"","images":[],"toolCalls":[],""" +
                """"toolResult":{"callId":"call_1","toolName":"clock_now","content":"10:00","isError":false},""" +
                """"reasoning":null,"providerData":null}""",
            encodeColumn(ChatMessage.serializer(), ChatMessage.toolResult("call_1", "clock_now", "10:00")),
        )
        assertEquals(
            """{"inputTokens":120,"outputTokens":30,"cachedInputTokens":100}""",
            encodeColumn(TokenUsage.serializer(), TokenUsage(120, 30, 100)),
        )
    }

    @Test
    fun `exact text of every kind of JSON column`() {
        assertEquals(
            """{"temperature":0.4,"maxOutputTokens":2048}""",
            encodeColumn(GenerationSettings.serializer(), GenerationSettings(temperature = 0.4, maxOutputTokens = 2048)),
        )
        assertEquals(
            """{"privateMemoryEnabled":false,"readableSpaces":["spc_a","spc_b"],"writableSpaces":[],"autoRecall":true,"recallLimit":4}""",
            encodeColumn(
                MemoryPolicy.serializer(),
                MemoryPolicy(
                    privateMemoryEnabled = false, readableSpaces = setOf(SpaceId("spc_a"), SpaceId("spc_b")),
                    writableSpaces = emptySet(), autoRecall = true, recallLimit = 4,
                ),
            ),
        )
        assertEquals(
            """{"canDelegate":true,"canRequestAssistance":false,"maxDepth":2}""",
            encodeColumn(DelegationPolicy.serializer(), DelegationPolicy(canDelegate = true, canRequestAssistance = false, maxDepth = 2)),
        )
        assertEquals(
            """{"chat":true,"toolCalling":true,"vision":false,"streaming":true,"jsonOutput":false,"embeddings":false,""" +
                """"reasoning":"GOOD","coding":"UNKNOWN","contextWindow":8192,"maxOutputTokens":null}""",
            encodeColumn(
                ModelCapabilities.serializer(),
                ModelCapabilities(toolCalling = true, reasoning = CapabilityTier.GOOD, contextWindow = 8192),
            ),
        )
        assertEquals(
            """{"destinationHost":"api.example.com","locality":"CLOUD","providerId":"prv_1","purpose":"prompt","approxBytes":2048}""",
            encodeColumn(
                EgressInfo.serializer(),
                EgressInfo("api.example.com", Locality.CLOUD, ProviderId("prv_1"), purpose = "prompt", approxBytes = 2048),
            ),
        )
        assertEquals("""["b, \"quoted\"","a",""]""", encodeColumn(ColumnSerializers.stringList, listOf("b, \"quoted\"", "a", "")))
        assertEquals("""["research","analysis"]""", encodeColumn(ColumnSerializers.stringSet, setOf("research", "analysis")))
        assertEquals("""{"X-Title":"AI Control Center"}""", encodeColumn(ColumnSerializers.stringMap, mapOf("X-Title" to "AI Control Center")))
        assertEquals("""["mdl_z","mdl_a"]""", encodeColumn(ColumnSerializers.modelIdList, listOf(ModelId("mdl_z"), ModelId("mdl_a"))))
        assertEquals("""["memory.search"]""", encodeColumn(ColumnSerializers.toolIdSet, setOf(ToolId("memory.search"))))
        assertEquals(
            """["LAUNCH","SHARE_TEXT"]""",
            encodeColumn(ColumnSerializers.appCapabilitySet, setOf(AiAppCapability.LAUNCH, AiAppCapability.SHARE_TEXT)),
        )
    }

    @Test
    fun `keys and text of the stored settings`() {
        assertStoredKeys(
            FrameworkSettings.serializer(),
            "localOnlyMode", "activityRetentionDays", "directorAgentId", "maxConcurrentTasks", "maxDelegationDepth",
            "maxTasksPerObjective", "maxMessagesPerObjective", "maxModelCallsPerTask", "maxToolCallsPerTask",
            "maxRevisionRounds", "maxTaskAttempts", "taskTimeoutMinutes", "objectiveTimeoutMinutes",
            "approvalTimeoutMinutes", "autoResumeInterruptedTasks", "operatorEnabled", "accessibilityOperatorEnabled",
            "onboardingCompleted", "seedVersion", "memorySeedVersion",
        )
        // The defaults are deliberately not pinned as text: changing a default is not a format change.
        val stored = SettingsCodec.encode(FrameworkSettings(localOnlyMode = true, seedVersion = 3, memorySeedVersion = 1))
        assertEquals(true, stored.startsWith("""{"localOnlyMode":true,"activityRetentionDays":"""), stored)
        assertEquals(true, stored.endsWith(""","onboardingCompleted":false,"seedVersion":3,"memorySeedVersion":1}"""), stored)
    }

    @Test
    fun `keys of the vault index file`() {
        assertStoredKeys(VaultFileStore.VaultIndex.serializer(), "version", "entries")
        assertEquals(
            """{"version":1,"entries":{"provider/a":"AAAA"}}""",
            DataJson.encodeToString(VaultFileStore.VaultIndex.serializer(), VaultFileStore.VaultIndex(1, mapOf("provider/a" to "AAAA"))),
        )
        assertEquals(1, VaultFileStore.FORMAT_VERSION)
    }

    @Test
    fun `names of files, preference keys and keystore entries`() {
        assertEquals("aicc.db", AiccDatabase.DEFAULT_NAME)
        assertEquals("aicc_settings", DataStoreSettingsRepository.DEFAULT_FILE_NAME)
        assertEquals("framework_settings", DataStoreSettingsRepository.SETTINGS_KEY.name)
        assertEquals("framework_settings_unreadable", DataStoreSettingsRepository.UNREADABLE_COPY_KEY.name)
        assertEquals("vault/secrets.json", DataLayer.VAULT_FILE)
        assertEquals("database-keys/aicc.db.key", DataLayer.databaseKeyFile(AiccDatabase.DEFAULT_NAME))
        assertEquals("aicc.secret-vault.v1", AndroidKeystoreVaultCipher.DEFAULT_KEY_ALIAS)
        assertEquals("aicc.database-key.v1", AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS)
    }

    /**
     * Checks both spellings of an enum: the constant name, which the mappers write to text
     * columns, and the serial name, which is what appears inside JSON columns.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified E : Enum<E>> assertStoredNames(vararg expected: String) {
        val type = E::class.simpleName
        assertEquals(expected.toSet(), enumValues<E>().map { it.name }.toSet(), "$type constants (text columns)")
        assertEquals(expected.toSet(), serializer<E>().descriptor.elementNames.toSet(), "$type serial names (JSON columns)")
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun assertStoredKeys(serializer: KSerializer<*>, vararg expected: String) {
        assertEquals(expected.toList(), serializer.descriptor.elementNames.toList(), "JSON keys of ${serializer.descriptor.serialName}")
    }
}
