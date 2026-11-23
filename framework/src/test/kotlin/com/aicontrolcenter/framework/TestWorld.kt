package com.aicontrolcenter.framework

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.GenerationSettings
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.framework.agents.AgentDeletionListener
import com.aicontrolcenter.framework.agents.AgentRunner
import com.aicontrolcenter.framework.agents.DefaultAgentActivityMonitor
import com.aicontrolcenter.framework.agents.DefaultAgentManager
import com.aicontrolcenter.framework.chat.DefaultAgentChat
import com.aicontrolcenter.framework.llm.DefaultModelGateway
import com.aicontrolcenter.framework.llm.DefaultProviderManager
import com.aicontrolcenter.framework.llm.ProviderConnections
import com.aicontrolcenter.framework.llm.RetryPolicy
import com.aicontrolcenter.framework.llm.SimulatorProviderFactory
import com.aicontrolcenter.framework.memory.AgentMemoryCleanup
import com.aicontrolcenter.framework.memory.AutoRecall
import com.aicontrolcenter.framework.memory.FakeMemoryService
import com.aicontrolcenter.framework.memory.MemorySeeder
import com.aicontrolcenter.framework.memory.MemoryTools
import com.aicontrolcenter.framework.operator.DefaultCapabilityRequester
import com.aicontrolcenter.framework.operator.DefaultOwnerRequests
import com.aicontrolcenter.framework.operator.OperatorFacts
import com.aicontrolcenter.framework.operator.OperatorTaskHooks
import com.aicontrolcenter.framework.operator.PermissionBroker
import com.aicontrolcenter.framework.registry.DefaultAiAppRegistry
import com.aicontrolcenter.framework.registry.DefaultRegistryDirectory
import com.aicontrolcenter.framework.tools.BuiltInTools
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.framework.tools.ToolLimits
import com.aicontrolcenter.testkit.FakeAiAppScanner
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.FakeProviderAdapterFactory
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryAgentRepository
import com.aicontrolcenter.testkit.InMemoryAiAppRepository
import com.aicontrolcenter.testkit.InMemoryConversationRepository
import com.aicontrolcenter.testkit.InMemoryModelRepository
import com.aicontrolcenter.testkit.InMemoryOperatorAppRepository
import com.aicontrolcenter.testkit.InMemoryOwnerRequestRepository
import com.aicontrolcenter.testkit.InMemoryPermissionRepository
import com.aicontrolcenter.testkit.InMemoryProviderRepository
import com.aicontrolcenter.testkit.InMemorySecretVault
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.InMemorySharedFolderRepository
import com.aicontrolcenter.testkit.InMemoryTaskRepository
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.ZoneOffset
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.coroutines.EmptyCoroutineContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The time source of a [TestWorld]'s pausable limits: real time until a test points it at its
 * scheduler (`world.time.source = testTimeSource`), so a limit then ends at an exact virtual time.
 */
class SwitchableTimeSource : TimeSource {
    @Volatile
    var source: TimeSource = TimeSource.Monotonic

    override fun markNow(): TimeMark = source.markNow()
}

/**
 * A complete in-memory framework for tests, wired the way the app wires it: tool calls are
 * decided by the [PermissionBroker] over in-memory grants, requests, folders and apps, which
 * the owner answers through [ownerRequests].
 *
 * @param scope where chat turns run. The default is a real dispatcher, as in the app; pass a
 * test scope to run turns in virtual time.
 */
class TestWorld(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    retry: RetryPolicy = RetryPolicy(initialDelayMs = 1, maxDelayMs = 4),
    toolLimits: ToolLimits = ToolLimits(),
) {
    val clock = FakeClock(autoAdvanceMs = 1)
    val time = SwitchableTimeSource()
    val ids = SequentialIdGenerator()
    val tasks = InMemoryTaskRepository()
    val agents = InMemoryAgentRepository()
    val providers = InMemoryProviderRepository()
    val models = InMemoryModelRepository()
    val apps = InMemoryAiAppRepository()
    val settings = InMemorySettingsRepository()
    val conversations = InMemoryConversationRepository()
    val vault = InMemorySecretVault()
    val log = InMemoryActivityLog()
    val scanner = FakeAiAppScanner()
    val activity = ActivityRecorder(log, ids, clock)
    val appRegistry = DefaultAiAppRegistry(apps, scanner, activity, ids, clock)
    val directory = DefaultRegistryDirectory(agents, providers, models, apps, settings)

    /** The adapter each fake factory hands out, by provider base URL; anything else gets [fallbackAdapter]. */
    val adaptersByUrl = ConcurrentHashMap<String, ProviderAdapter>()
    var fallbackAdapter: () -> ProviderAdapter = { FakeProviderAdapter() }
    val factories: Map<ProviderKind, FakeProviderAdapterFactory> =
        listOf(ProviderKind.OPENAI_COMPATIBLE, ProviderKind.ANTHROPIC, ProviderKind.GEMINI, ProviderKind.OLLAMA).associateWith { kind ->
            FakeProviderAdapterFactory(kind) { connection -> adaptersByUrl[connection.provider.baseUrl.orEmpty()] ?: fallbackAdapter() }
        }

    val connections = ProviderConnections(providers, vault, factories.values + SimulatorProviderFactory(chunkDelayMs = 0), clock)
    val gateway = DefaultModelGateway(providers, models, settings, connections, activity, clock, retry)
    val providerManager = DefaultProviderManager(providers, models, settings, vault, connections, activity, ids, clock)

    /** Memory as the framework sees it: the contract, here a fake. Days are shown in UTC. */
    val memory = FakeMemoryService(agents, clock, ids)
    val capabilities = CapabilityRegistry(Capabilities.builtIn + OperatorCapabilities.all)
    val toolRegistry = DefaultToolRegistry().also {
        BuiltInTools.registerAll(it, clock, directory)
        MemoryTools.registerAll(it, memory, capabilities, agents, clock) { ZoneOffset.UTC }
    }

    // The phone operator's stores and the permission broker, as the app wires them.
    val grants = InMemoryPermissionRepository()
    val requests = InMemoryOwnerRequestRepository(grants)
    val folders = InMemorySharedFolderRepository()
    val operatorApps = InMemoryOperatorAppRepository()
    val screenStopped = MutableStateFlow(false)
    val protectedPackages = ProtectedPackages(OWN_PACKAGE)
    val facts = OperatorFacts(agents, settings, capabilities, folders, operatorApps, screenStopped) { protectedPackages }
    val broker = PermissionBroker(facts, requests, grants, tasks, activity, ids, clock)
    val requester = DefaultCapabilityRequester(facts, requests, grants, activity, ids, clock)
    val operatorHooks = OperatorTaskHooks(requests, grants, activity, clock)

    /** How the owner answers requests in these tests: the owner-only port, as the approval screen uses it. */
    val ownerRequests = DefaultOwnerRequests(requests, activity, ids, clock)

    val executor = ToolExecutor(toolRegistry, broker, activity, clock, toolLimits, time)
    val monitor = DefaultAgentActivityMonitor(clock)
    /** Looks up on the caller's dispatcher, so that recall follows virtual time in coroutine tests. */
    val recall = AutoRecall(memory, activity, zone = { ZoneOffset.UTC }, lookupContext = EmptyCoroutineContext)
    val runner = AgentRunner(gateway, toolRegistry, executor, agents, settings, monitor, clock, recall = recall, timeSource = time)
    val chat = DefaultAgentChat(conversations, agents, models, providers, settings, runner, activity, ids, clock, scope)

    private val nextPort = java.util.concurrent.atomic.AtomicInteger()

    val deleted = mutableListOf<String>()
    val agentManager = DefaultAgentManager(
        agents, settings, activity, ids, clock,
        deletionListeners = listOf(AgentDeletionListener { deleted += it.name }, chat, AgentMemoryCleanup(memory)),
    )
    val memorySeeder = MemorySeeder(memory, agentManager, agents, settings, activity)

    /** Registers a provider at [baseUrl] whose adapter is [adapter], and stores [apiKey] for it when given. */
    suspend fun provider(
        adapter: ProviderAdapter = FakeProviderAdapter(),
        baseUrl: String = "http://127.0.0.1:${9000 + nextPort.getAndIncrement()}/v1",
        name: String = "Test server",
        kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
        apiKey: String? = null,
        enabled: Boolean = true,
    ): ModelProvider {
        val id = ProviderId(ids.newId("prv"))
        val alias = apiKey?.let { "provider/${id.value}" }
        if (alias != null) vault.put(alias, apiKey)
        adaptersByUrl[baseUrl] = adapter
        val at = clock.now()
        val provider = ModelProvider(
            id = id, name = name, kind = kind, baseUrl = baseUrl, apiKeyAlias = alias,
            locality = Locality.infer(kind, baseUrl), enabled = enabled, createdAt = at, updatedAt = at,
        )
        providers.upsert(provider)
        return provider
    }

    suspend fun model(
        provider: ModelProvider,
        modelName: String = "scripted-1",
        capabilities: ModelCapabilities = ModelCapabilities(toolCalling = true),
        enabled: Boolean = true,
    ): ModelDescriptor {
        val at = clock.now()
        val model = ModelDescriptor(
            id = ModelId(ids.newId("mdl")), providerId = provider.id, modelName = modelName, displayName = "Test model",
            capabilities = capabilities, source = ModelSource.MANUAL, enabled = enabled, createdAt = at, updatedAt = at,
        )
        models.upsert(model)
        return model
    }

    /** A provider on this phone serving one model through [client]. */
    suspend fun serve(client: LlmClient, capabilities: ModelCapabilities = ModelCapabilities(toolCalling = true)): ModelDescriptor =
        model(provider(FakeProviderAdapter(client = client)), capabilities = capabilities)

    suspend fun agent(
        model: ModelDescriptor?,
        tools: Set<ToolId> = emptySet(),
        name: String = "Scout",
        generation: GenerationSettings = GenerationSettings(),
    ): AgentProfile {
        val at = clock.now()
        val agent = AgentProfile(
            id = AgentId(ids.newId("agt")), name = name, kind = AgentKind.SPECIALIST, description = "Finds things out.",
            systemInstructions = "Answer briefly.", personality = "Dry.", responsibilities = listOf("Look things up"),
            skills = setOf("research"), modelId = model?.id, generation = generation, toolIds = tools, createdAt = at, updatedAt = at,
        )
        agents.upsert(agent)
        return agent
    }

    fun <T : Tool> register(tool: T): T = tool.also(toolRegistry::register)

    fun events(type: ActivityType): List<ActivityEvent> = log.all.filter { it.type == type }

    /** Everything the log holds as one string, for asserting that a secret is nowhere in it. */
    fun logText(): String = log.all.joinToString("\n") { "${it.title} ${it.detail} ${it.data} ${it.egress}" }

    companion object {
        const val OWN_PACKAGE = "com.aicontrolcenter.app"
    }
}
