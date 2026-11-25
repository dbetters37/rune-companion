package com.aicontrolcenter.data.backup

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppRepository
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.OwnerModelPrices
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.contract.MemorySamples
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The content of an owner's phone for the backup tests, written into any set of stores (in memory or Room): two
 * agents of the owner's, providers with keys, headers and a key in an address, models, a price, an AI app with this
 * phone's install details, a space, private, shared and task memories with links, and a chat whose agent used tools.
 * The strings named here must never be found in a backup file's decrypted body (rule 8, F13).
 */
internal object BackupPhone {
    const val KEY_ALIAS = "provider/prv_xai/1"
    const val HEADER_SECRET = "header-secret-value-0123"
    const val URL_KEY = "sk-abcdefghijklmnopqrstuvwxyz0123456789"
    const val SCREEN_SECRET = "SCREEN-RESULT-SECRET-42"
    const val TASK_MEMORY_TEXT = "Task-scope memory that belongs to an objective"
    const val CERT = "c0ffee"
    const val REASONING_SECRET = "REASONING-SECRET-77"
    const val SYSTEM_SECRET = "SYSTEM-PROMPT-SECRET-11"

    val ada = AgentId("agt_ada")
    val bo = AgentId("agt_bo")
    val team = SpaceId("spc_team")
    val chat = ConversationId("cnv_ada")

    fun agents(): List<AgentProfile> = listOf(
        Samples.agent(id = ada.value, name = "Ada", kind = AgentKind.DIRECTOR, modelId = "mdl_grok").copy(
            templateId = "director", memory = MemoryPolicy(readableSpaces = setOf(team), writableSpaces = setOf(team)),
        ),
        Samples.agent(id = bo.value, name = "Bo", modelId = "mdl_home", createdAt = 2_000).copy(
            templateId = null, memory = MemoryPolicy(readableSpaces = setOf(team)),
        ),
    )

    val adaStats = AgentStats(tasksAssigned = 7, tasksCompleted = 5, modelCalls = 40, inputTokens = 12_345, lastActiveAt = 9_000)

    fun providers(): List<ModelProvider> = listOf(
        ModelProvider(
            id = ProviderId("prv_xai"), name = "xAI", kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = "https://api.x.ai/v1",
            apiKeyAlias = KEY_ALIAS, extraHeaders = mapOf("X-Title" to "AI Control Center", "X-Token" to HEADER_SECRET, "HTTP-Referer" to "https://example.org"),
            locality = Locality.CLOUD, createdAt = 1_000, updatedAt = 1_100, lastCheckedAt = 1_200, statusMessage = "Online",
        ),
        Samples.provider(id = "prv_home", name = "Home server"),
        ModelProvider(
            id = ProviderId("prv_leaky"), name = "Leaky", kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = "https://api.example.com/v1/$URL_KEY",
            locality = Locality.CLOUD, createdAt = 1_000, updatedAt = 1_000,
        ),
        ModelProvider(
            id = ProviderId("prv_simulator"), name = "Simulator", kind = ProviderKind.SIMULATOR, locality = Locality.ON_DEVICE,
            createdAt = 500, updatedAt = 500,
        ),
    )

    fun models() = listOf(
        Samples.model(id = "mdl_grok", providerId = "prv_xai", modelName = "grok-4-fast").copy(source = ModelSource.PROVIDER_LISTING),
        Samples.model(id = "mdl_home", providerId = "prv_home"),
        Samples.model(id = "mdl_leaky", providerId = "prv_leaky", modelName = "leaky-model"),
        Samples.model(id = "mdl_simulator", providerId = "prv_simulator", modelName = "simulator-1").copy(source = ModelSource.BUILT_IN),
    )

    val price = ModelPrice(ModelId("mdl_grok"), inputPerMTokMicros = 200_000, outputPerMTokMicros = 500_000, cachedInputPerMTokMicros = 50_000)

    fun app(): AiApp = Samples.aiApp(id = "app_chat", packageName = "com.example.chatbot").copy(signingCertSha256 = CERT.repeat(16))

    val space = KnowledgeSpace(team, "Team", "What the team knows", 1_000, 1_000)

    fun memories() = listOf(
        MemorySamples.memory("mem_private", scope = MemoryScope.Agent(ada), title = "Ada's own note", content = "Only Ada reads this."),
        MemorySamples.memory("mem_team", scope = MemoryScope.Shared(team), title = "Team fact", content = "Everyone on the team reads this."),
        MemorySamples.memory("mem_task", scope = MemoryScope.Task(TaskId("tsk_1"), ada), title = "Task note", content = TASK_MEMORY_TEXT),
    )

    val conversation = Conversation(chat, ada, "Plans for Monday", 3_000, 4_000)

    fun entries(): List<ConversationEntry> {
        val screenCall = ToolCallRequest("call_1", "screen_read", buildJsonObject { put("app", JsonPrimitive("com.bank")) })
        val menuCall = ToolCallRequest("call_2", "tools_open", buildJsonObject { put("family", JsonPrimitive("screen")) })
        return listOf(
            ConversationEntry(chat, 0, ChatMessage.system(SYSTEM_SECRET), 3_000),
            ConversationEntry(chat, 1, ChatMessage.user("What is on my screen?"), 3_001),
            ConversationEntry(chat, 2, ChatMessage(ChatRole.ASSISTANT, "Let me look.", toolCalls = listOf(menuCall, screenCall), reasoning = REASONING_SECRET), 3_002, modelName = "grok-4-fast"),
            ConversationEntry(chat, 3, ChatMessage.toolResult("call_1", "screen_read", SCREEN_SECRET), 3_003),
            ConversationEntry(chat, 4, ChatMessage.assistant("Your bank app is open."), 3_004, modelName = "grok-4-fast"),
        )
    }

    /** What a restore stores for [entries]: the owner's text and the agent's reply text with a line per tool. */
    val restoredTexts = listOf(
        ChatRole.USER to "What is on my screen?",
        ChatRole.ASSISTANT to "Let me look.\nA tool was used here: Open more tools. Its result is not in the backup.\n" +
            "A tool was used here: Read the screen. Its result is not in the backup.",
        ChatRole.ASSISTANT to "Your bank app is open.",
    )

    suspend fun fill(
        agents: AgentRepository,
        providers: ProviderRepository,
        models: ModelRepository,
        prices: OwnerModelPrices,
        aiApps: AiAppRepository,
        memories: MemoryRepository,
        conversations: ConversationRepository,
    ) {
        agents().forEach { agents.upsert(it) }
        agents.updateStats(ada) { adaStats }
        providers().forEach { providers.upsert(it) }
        models().forEach { models.upsert(it) }
        prices.set(price)
        aiApps.upsert(app())
        memories.upsertSpace(space)
        memories.upsertAll(memories(), listOf(MemorySamples.link("lnk_1", "mem_private", "mem_team"), MemorySamples.link("lnk_task", "mem_task", "mem_team")))
        conversations.upsertConversation(conversation)
        entries().forEach { conversations.appendEntry(it) }
    }

    /** A fresh app as a first start leaves it: the starter team, the simulator, the seeded space, a scanned AI app. */
    suspend fun fillFresh(agents: AgentRepository, providers: ProviderRepository, models: ModelRepository, aiApps: AiAppRepository, memories: MemoryRepository) {
        for ((i, template) in listOf("director", "researcher", "writer").withIndex()) {
            agents.upsert(Samples.agent(id = "agt_fresh_$i", name = template.replaceFirstChar { it.uppercase() }, modelId = null, createdAt = 10L + i).copy(templateId = template))
        }
        providers.upsert(providers().last().copy(name = "Simulator"))
        models.upsert(models().last())
        aiApps.upsert(
            AiApp(
                id = AiAppId("app_scanned"), packageName = "com.example.chatbot", label = "Chatbot", source = DiscoverySource.CATALOG,
                firstSeenAt = 5, lastSeenAt = 5,
            ),
        )
        memories.upsertSpace(KnowledgeSpace(SpaceId("spc_seeded"), "Team", "Seeded", 20, 20))
    }

    /** Tool titles as the registry gives them. */
    val titles: (String) -> String? = { name -> if (name == "screen_read") "Read the screen" else null }

    val memoryIds = listOf(MemoryId("mem_private"), MemoryId("mem_team"))
}
