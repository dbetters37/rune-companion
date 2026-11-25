package com.aicontrolcenter.core.backup

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.agents.DelegationPolicy
import com.aicontrolcenter.core.agents.GenerationSettings
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceAction
import com.aicontrolcenter.core.memory.ProvenanceEvent
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.usage.ModelPrice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The records of a backup file's body (format v1; security review of wave 1, F13 and the second
 * review's FORMAT item): every key path and enum spelling is pinned here, and what a save leaves
 * out of each record is checked on the JSON a save would write.
 *
 * A key added with a default that old files may lack keeps format v1 readable: pin it here and list
 * it in section 4. Renaming or removing a key, changing its type or an enum spelling is format
 * version 2.
 */
class BackupRecordsTest {
    private val provenance = Provenance(
        origin = ProvenanceOrigin.AGENT, agentId = AgentId("agt_a"), taskId = TaskId("tsk_1"), conversationId = ConversationId("cnv_1"),
        modelId = ModelId("mdl_a"), modelName = "model", derivedFrom = listOf(MemoryId("mem_0")), source = "src", recordedAt = 5,
    )

    private val agent = AgentProfile(
        id = AgentId("agt_a"), name = "Ada", kind = AgentKind.SPECIALIST, description = "d", systemInstructions = "s", personality = "p",
        responsibilities = listOf("r"), skills = setOf("research"), modelId = ModelId("mdl_a"), fallbackModelIds = listOf(ModelId("mdl_b")),
        generation = GenerationSettings(0.5, 100), toolIds = setOf(ToolId("files.read")),
        memory = MemoryPolicy(true, setOf(SpaceId("spc_a")), setOf(SpaceId("spc_a")), true, 6), delegation = DelegationPolicy(true, true, 1),
        enabled = true, colorIndex = 1, templateId = "t", createdAt = 1, updatedAt = 2,
    )

    private val provider = ModelProvider(
        id = ProviderId("prv_a"), name = "Cloud", kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = "https://api.example.com/v1",
        apiKeyAlias = "provider/prv_a/1", extraHeaders = mapOf("X-Title" to "AI Control Center", "HTTP-Referer" to "https://example.org", "X-Token" to "tok-9f8e7d6c5b4a"),
        locality = Locality.ON_DEVICE, enabled = true, status = ConnectionStatus.ONLINE, statusMessage = "fine", lastCheckedAt = 7, createdAt = 1, updatedAt = 2,
    )

    private val model = ModelDescriptor(
        id = ModelId("mdl_a"), providerId = ProviderId("prv_a"), modelName = "m", displayName = "M", capabilities = ModelCapabilities(),
        source = ModelSource.MANUAL, localPath = "/x", sizeBytes = 3, enabled = true, status = ModelStatus.AVAILABLE, notes = "n", createdAt = 1, updatedAt = 2,
    )

    private val app = AiApp(
        id = AiAppId("app_a"), packageName = "com.example.ai", label = "AI", vendor = "V", versionName = "1", source = DiscoverySource.CATALOG,
        capabilities = setOf(AiAppCapability.LAUNCH), status = AiAppStatus.INSTALLED, enabled = true, accessibilityAuthorized = true,
        linkedProviderId = ProviderId("prv_a"), promptDeepLink = "https://ai.example/?q={prompt}", notes = "n", firstInstallTime = 9,
        signingCertSha256 = "ab".repeat(32), verifiedByUser = true, hidden = false, firstSeenAt = 1, lastSeenAt = 2,
    )

    private val memory = MemoryRecord(
        id = MemoryId("mem_a"), scope = MemoryScope.Shared(SpaceId("spc_a")), kind = MemoryKind.FACT, tier = MemoryTier.LONG_TERM, title = "t",
        content = "c", tags = setOf("x"), importance = 0.5, confidence = 1.0, provenance = provenance, createdAt = 1, updatedAt = 2, lastAccessedAt = 3,
        accessCount = 4, expiresAt = 5, revision = 2,
        history = listOf(ProvenanceEvent(ProvenanceAction.EDITED, ProvenanceOrigin.OWNER, AgentId("agt_a"), 6, MemoryId("mem_0"), "note")),
    )

    private fun samples(): List<BackupRecord> = listOf(
        BackupRecord.Manifest(BackupCounts(1, 1, 1, 1, 1, 1, 1, 1, 1, 1), BackupSettings(directorAgentId = "agt_a")),
        BackupRecord.Agent(agent, AgentStats(lastActiveAt = 4)),
        BackupRecords.providerForSave(provider).record!!,
        BackupRecord.Model(model),
        BackupRecord.Price.of(ModelPrice(ModelId("mdl_a"), 3_000_000, 15_000_000, 300_000, "US$", 8)),
        BackupRecord.App(BackupRecords.aiAppForSave(app)),
        BackupRecord.Space(KnowledgeSpace(SpaceId("spc_a"), "Team", "d", 1, 2)),
        BackupRecords.memoryForSave(memory)!!,
        BackupRecords.memoryForSave(memory.copy(scope = MemoryScope.Agent(AgentId("agt_a"))))!!,
        BackupRecord.Link(MemoryLink(MemoryLinkId("lnk_a"), MemoryId("mem_a"), MemoryId("mem_b"), LinkRelation.SUPPORTS, 0.5, provenance, 3)),
        BackupRecord.Chat(Conversation(ConversationId("cnv_1"), AgentId("agt_a"), "Chat", 1, 2)),
        BackupRecord.Entry(ConversationId("cnv_1"), 0, EntryAuthor.OWNER, "Hello", 3, null),
        BackupRecord.End(12),
    )

    /** Every key path of [element]: "a.b", "a.list[].c". A map's keys are data, not keys of the format ([MAPS]). */
    private fun paths(element: JsonElement, prefix: String = ""): List<String> = when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) ->
            listOf("$prefix$key") + if ("$prefix$key" in MAPS) emptyList() else paths(value, "$prefix$key.")
        }
        is JsonArray -> element.flatMap { paths(it, prefix.removeSuffix(".") + "[].") }.distinct()
        else -> emptyList()
    }

    @Test
    fun `every record's keys, in the order a save writes them, are pinned`() {
        val written = samples().map { record -> BackupRecords.JSON.parseToJsonElement(BackupRecords.encode(record)).jsonObject }
        val actual = written.map { obj -> (obj["type"] as kotlinx.serialization.json.JsonPrimitive).content + ": " + paths(obj).joinToString(" ") }
        assertEquals(GOLDEN, actual)
        assertEquals(BackupRecords.TYPES, written.map { (it["type"] as kotlinx.serialization.json.JsonPrimitive).content }.distinct())
    }

    @Test
    fun `enum spellings in the file are pinned`() {
        assertEquals(listOf("DIRECTOR", "SPECIALIST", "REVIEWER", "OPERATOR"), AgentKind.entries.map { it.name })
        assertEquals(listOf("OPENAI_COMPATIBLE", "ANTHROPIC", "GEMINI", "OLLAMA", "ON_DEVICE", "SIMULATOR"), ProviderKind.entries.map { it.name })
        assertEquals(listOf("ON_DEVICE", "LOCAL_NETWORK", "CLOUD"), Locality.entries.map { it.name })
        assertEquals(listOf("UNKNOWN", "ONLINE", "OFFLINE", "AUTH_ERROR", "ERROR"), ConnectionStatus.entries.map { it.name })
        assertEquals(listOf("PROVIDER_LISTING", "MANUAL", "IMPORTED_FILE", "BUILT_IN"), ModelSource.entries.map { it.name })
        assertEquals(listOf("UNKNOWN", "AVAILABLE", "UNAVAILABLE"), ModelStatus.entries.map { it.name })
        assertEquals(listOf("CATALOG", "INTENT_QUERY", "HEURISTIC", "MANUAL"), DiscoverySource.entries.map { it.name })
        assertEquals(ENUMS_AI_APP, AiAppStatus.entries.map { it.name } + AiAppCapability.entries.map { it.name })
        assertEquals(listOf("FACT", "PREFERENCE", "EVENT", "FINDING", "DECISION", "PROCEDURE", "INSIGHT", "SUMMARY", "NOTE"), MemoryKind.entries.map { it.name })
        assertEquals(listOf("SHORT_TERM", "LONG_TERM"), MemoryTier.entries.map { it.name })
        assertEquals(listOf("OWNER", "AGENT", "TOOL", "SYSTEM", "DERIVED", "IMPORT"), ProvenanceOrigin.entries.map { it.name })
        assertEquals(ENUMS_PROVENANCE_ACTION, ProvenanceAction.entries.map { it.name })
        assertEquals(
            listOf("RELATES_TO", "SUPPORTS", "CONTRADICTS", "DERIVED_FROM", "PART_OF", "REFERENCES", "SUPERSEDES", "SHARED_COPY_OF"),
            LinkRelation.entries.map { it.name },
        )
        assertEquals("\"owner\"", BackupRecords.JSON.encodeToString(EntryAuthor.serializer(), EntryAuthor.OWNER))
        assertEquals("\"agent\"", BackupRecords.JSON.encodeToString(EntryAuthor.serializer(), EntryAuthor.AGENT))
        assertEquals(
            listOf("""{"type":"agent","agentId":"agt_a"}""", """{"type":"shared","spaceId":"spc_a"}"""),
            listOf(MemoryScope.Agent(AgentId("agt_a")), MemoryScope.Shared(SpaceId("spc_a"))).map { BackupRecords.JSON.encodeToString(MemoryScope.serializer(), it) },
        )
    }

    @Test
    fun `the manifest's counts and settings are exactly their key lists`() {
        val manifest = BackupRecords.JSON.parseToJsonElement(BackupRecords.encode(samples().first())).jsonObject
        assertEquals(listOf("type", "counts", "settings"), manifest.keys.toList())
        assertEquals(BackupCounts.KEYS, manifest["counts"]!!.jsonObject.keys.toList())
        assertEquals(BackupSettings.KEYS, manifest["settings"]!!.jsonObject.keys.toList())
    }

    @Test
    fun `a save leaves out keys, key aliases, status, unknown headers and the install on this phone`() {
        val saved = BackupRecords.providerForSave(provider)
        val text = BackupRecords.encode(saved.record!!)
        assertFalse("tok-9f8e7d6c5b4a" in text, "an X-Token header value is not in the file")
        assertFalse("provider/prv_a/1" in text, "no key alias")
        assertFalse("fine" in text || "ONLINE" in text, "no status")
        assertEquals(1, saved.headersLeftOut)
        assertEquals(mapOf("X-Title" to "AI Control Center", "HTTP-Referer" to "https://example.org"), saved.record!!.provider.extraHeaders)
        // An allowed header whose value looks like a key is left out too.
        val keyInTitle = BackupRecords.providerForSave(provider.copy(extraHeaders = mapOf("x-title" to "sk-ant-api03-abcdefghijklmnop")))
        assertEquals(emptyMap(), keyInTitle.record!!.provider.extraHeaders)
        assertEquals(1, keyInTitle.headersLeftOut)
        // A key in the address's path: the provider is left out.
        assertNull(BackupRecords.providerForSave(provider.copy(baseUrl = "https://api.example.com/sk-proj-abcdefghijklmnop12/v1")).record)
        assertNotNull(BackupRecords.providerForSave(provider.copy(baseUrl = "https://europe-west4-aiplatform.googleapis.com/v1/projects/my-project-123/locations/europe-west4")).record)
        // AI apps: nothing that describes this phone's install.
        val appText = BackupRecords.encode(BackupRecord.App(BackupRecords.aiAppForSave(app)))
        assertFalse("ab".repeat(32) in appText, "no signing certificate")
        val savedApp = BackupRecords.aiAppForSave(app)
        assertNull(savedApp.firstInstallTime)
        assertFalse(savedApp.verifiedByUser)
        assertFalse(savedApp.accessibilityAuthorized)
        // Task memories belong to objectives, which are not in the file.
        assertNull(BackupRecords.memoryForSave(memory.copy(scope = MemoryScope.Task(TaskId("tsk_1"), AgentId("agt_a")))))
        assertTrue(BackupRecords.memoryInFile(MemoryScope.Agent(AgentId("agt_a"))) && BackupRecords.memoryInFile(MemoryScope.Shared(SpaceId("spc_a"))))
    }

    @Test
    fun `conversation entries keep the owner's words and the agent's reply, never tool arguments or results`() {
        val title = { name: String -> if (name == "files.read") "Read a file" else name }
        val conversation = ConversationId("cnv_1")
        fun entry(message: ChatMessage, seq: Int = 0) = ConversationEntry(conversation, seq, message, at = 10, modelName = "m")
        assertEquals(BackupRecord.Entry(conversation, 0, EntryAuthor.OWNER, "Hello", 10, null), BackupRecords.entryForSave(entry(ChatMessage.user("Hello")), title))
        val call = ToolCallRequest("c1", "files.read", buildJsonObject { put("path", "secret-plans.txt") })
        val reply = BackupRecords.entryForSave(entry(ChatMessage.assistant("Let me look.", listOf(call))), title)!!
        assertEquals(EntryAuthor.AGENT, reply.author)
        assertEquals("Let me look.\nA tool was used here: Read a file. Its result is not in the backup.", reply.text)
        assertEquals("m", reply.modelName)
        assertFalse("secret-plans" in BackupRecords.encode(reply), "no tool arguments")
        assertNull(BackupRecords.entryForSave(entry(ChatMessage.toolResult("c1", "files.read", "the plans")), title), "no tool results")
        assertNull(BackupRecords.entryForSave(entry(ChatMessage.system("You are")), title))
        assertNull(BackupRecords.entryForSave(entry(ChatMessage.user("   ")), title))
    }

    @Test
    fun `a restore takes no key alias, the farther locality and no install facts, whatever the file says`() {
        val restored = BackupRecords.providerForRestore(provider.copy(baseUrl = "https://other.example/v1"))
        assertNull(restored.apiKeyAlias)
        assertEquals(ConnectionStatus.UNKNOWN, restored.status)
        assertNull(restored.statusMessage)
        assertEquals(Locality.CLOUD, restored.locality, "a file that calls a cloud address on-device is not believed")
        assertEquals(setOf("X-Title", "HTTP-Referer"), restored.extraHeaders.keys)
        val local = BackupRecords.providerForRestore(provider.copy(baseUrl = "http://127.0.0.1:11434", locality = Locality.CLOUD))
        assertEquals(Locality.CLOUD, local.locality, "a farther locality in the file is kept")
        val restoredApp = BackupRecords.aiAppForRestore(app)
        assertNull(restoredApp.signingCertSha256)
        assertFalse(restoredApp.accessibilityAuthorized || restoredApp.verifiedByUser)
    }

    @Test
    fun `a reader skips unknown record types and refuses malformed known ones`() {
        assertNull(BackupRecords.decode("""{"type":"routine","title":"x"}"""), "routines are not in v1: skipped")
        assertEquals(BackupRecord.End(3), BackupRecords.decode("""{"type":"end","records":3,"extra":true}"""))
        assertFailsWith<Exception> { BackupRecords.decode("""{"records":3}""") }
        assertFailsWith<Exception> { BackupRecords.decode("""{"type":"end","records":"three"}""") }
        assertFailsWith<Exception> { BackupRecords.decode("""[1,2]""") }
        for (record in samples()) assertEquals(record, BackupRecords.decode(BackupRecords.encode(record)), record.toString())
    }

    private companion object {
        /** Objects whose keys are data (a provider's extra headers: header name to value). */
        val MAPS: Set<String> = setOf("provider.extraHeaders")

        val ENUMS_AI_APP: List<String> = listOf(
            "INSTALLED", "DISABLED_BY_SYSTEM", "NOT_INSTALLED", "LAUNCH", "SHARE_TEXT", "SHARE_IMAGE", "PROCESS_TEXT", "ASSISTANT", "PROMPT_DEEP_LINK",
            "LOCAL_MODEL_HOST",
        )
        val ENUMS_PROVENANCE_ACTION: List<String> = listOf("EDITED", "SHARED_FROM", "PROMOTED_FROM")

        /** Each record's `"type"`, then its key paths in the order a save writes them. */
        val GOLDEN: List<String> = listOf(
            "manifest: type counts counts.agents counts.providers counts.models counts.modelPrices counts.aiApps counts.spaces counts.memories " +
                "counts.memoryLinks counts.conversations counts.conversationEntries settings settings.localOnlyMode settings.directorAgentId " +
                "settings.maxModelCallsPerDay settings.maxTokensPerDay settings.maxSpendPerDayMicros settings.notifyWhenWorkEnds settings.calmMotion " +
                "settings.onboardingCompleted settings.seedVersion settings.memorySeedVersion",
            "agent: type agent agent.id agent.name agent.kind agent.description agent.systemInstructions agent.personality agent.responsibilities " +
                "agent.skills agent.modelId agent.fallbackModelIds agent.generation agent.generation.temperature agent.generation.maxOutputTokens " +
                "agent.toolIds agent.memory agent.memory.privateMemoryEnabled agent.memory.readableSpaces agent.memory.writableSpaces " +
                "agent.memory.autoRecall agent.memory.recallLimit agent.delegation agent.delegation.canDelegate agent.delegation.canRequestAssistance " +
                "agent.delegation.maxDepth agent.enabled agent.colorIndex agent.templateId agent.createdAt agent.updatedAt stats stats.tasksAssigned " +
                "stats.tasksCompleted stats.tasksFailed stats.tasksTimedOut stats.tasksCancelled stats.reviewsPassed stats.revisionsRequested " +
                "stats.reviewsRejected stats.modelCalls stats.modelErrors stats.toolCalls stats.toolErrors stats.inputTokens stats.outputTokens " +
                "stats.totalTaskDurationMs stats.lastActiveAt",
            "provider: type provider provider.id provider.name provider.kind provider.baseUrl provider.apiKeyAlias provider.extraHeaders " +
                "provider.locality provider.enabled provider.status provider.statusMessage provider.lastCheckedAt provider.createdAt provider.updatedAt",
            "model: type model model.id model.providerId model.modelName model.displayName model.capabilities model.capabilities.chat " +
                "model.capabilities.toolCalling model.capabilities.vision model.capabilities.streaming model.capabilities.jsonOutput " +
                "model.capabilities.embeddings model.capabilities.reasoning model.capabilities.coding model.capabilities.contextWindow " +
                "model.capabilities.maxOutputTokens model.source model.localPath model.sizeBytes model.enabled model.status model.notes " +
                "model.createdAt model.updatedAt",
            "modelPrice: type modelId inputPerMTokMicros outputPerMTokMicros cachedInputPerMTokMicros currencyLabel updatedAt",
            "aiApp: type app app.id app.packageName app.label app.vendor app.versionName app.source app.capabilities app.status app.enabled " +
                "app.accessibilityAuthorized app.linkedProviderId app.promptDeepLink app.notes app.firstInstallTime app.signingCertSha256 " +
                "app.verifiedByUser app.hidden app.firstSeenAt app.lastSeenAt",
            "space: type space space.id space.name space.description space.createdAt space.updatedAt",
            MEMORY.replace("SCOPE", "memory.scope.spaceId"),
            MEMORY.replace("SCOPE", "memory.scope.agentId"),
            "memoryLink: type link link.id link.from link.to link.relation link.weight link.provenance link.provenance.origin link.provenance.agentId " +
                "link.provenance.taskId link.provenance.conversationId link.provenance.modelId link.provenance.modelName link.provenance.derivedFrom " +
                "link.provenance.source link.provenance.recordedAt link.createdAt",
            "conversation: type conversation conversation.id conversation.agentId conversation.title conversation.createdAt conversation.updatedAt",
            "entry: type conversationId seq author text at modelName",
            "end: type records",
        )

        private const val MEMORY: String =
            "memory: type memory memory.id memory.scope memory.scope.type SCOPE memory.kind memory.tier memory.title memory.content " +
                "memory.tags memory.importance memory.confidence memory.provenance memory.provenance.origin memory.provenance.agentId " +
                "memory.provenance.taskId memory.provenance.conversationId memory.provenance.modelId memory.provenance.modelName " +
                "memory.provenance.derivedFrom memory.provenance.source memory.provenance.recordedAt memory.createdAt memory.updatedAt " +
                "memory.lastAccessedAt memory.accessCount memory.expiresAt memory.revision memory.history memory.history[].action " +
                "memory.history[].byOrigin memory.history[].byAgentId memory.history[].at memory.history[].relatedMemory memory.history[].note"
    }
}
