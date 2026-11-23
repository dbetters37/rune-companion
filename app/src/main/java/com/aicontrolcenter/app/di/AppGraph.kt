package com.aicontrolcenter.app.di

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.device.DeviceInspector
import com.aicontrolcenter.core.llm.ModelGateway
import com.aicontrolcenter.core.llm.ProviderAdapterFactory
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.memory.Embedder
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.registry.AiAppLauncher
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.framework.agents.AgentRunner
import com.aicontrolcenter.framework.agents.DefaultAgentActivityMonitor
import com.aicontrolcenter.framework.agents.DefaultAgentManager
import com.aicontrolcenter.framework.chat.DefaultAgentChat
import com.aicontrolcenter.framework.llm.DefaultModelGateway
import com.aicontrolcenter.framework.llm.DefaultProviderManager
import com.aicontrolcenter.framework.llm.ProviderConnections
import com.aicontrolcenter.framework.llm.SimulatorProviderFactory
import com.aicontrolcenter.framework.memory.AgentMemoryCleanup
import com.aicontrolcenter.framework.memory.AutoRecall
import com.aicontrolcenter.framework.memory.MemorySeeder
import com.aicontrolcenter.framework.memory.MemoryTools
import com.aicontrolcenter.framework.orchestration.DefaultOrchestrator
import com.aicontrolcenter.framework.orchestration.TeamTools
import com.aicontrolcenter.framework.registry.DefaultAiAppRegistry
import com.aicontrolcenter.framework.registry.DefaultRegistryDirectory
import com.aicontrolcenter.framework.registry.LoggingAiAppLauncher
import com.aicontrolcenter.framework.tools.BuiltInTools
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import com.aicontrolcenter.framework.tools.DefaultToolAuthorizer
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.memory.DefaultMemoryService
import com.aicontrolcenter.memory.HashingEmbedder
import com.aicontrolcenter.memory.MemoryConfig
import com.aicontrolcenter.app.BuildConfig
import com.aicontrolcenter.app.work.InMemoryNotificationAccess
import com.aicontrolcenter.app.work.NotificationAccess
import com.aicontrolcenter.providers.HttpProviderAdapters
import com.aicontrolcenter.providers.anthropic.AnthropicAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The adapters that connect real providers: the built-in simulator, the HTTP protocols
 * (OpenAI-compatible, Ollama, Gemini) and Anthropic. Built once per process: the HTTP
 * factories share one connection pool and remember per-model quirks for the life of the app.
 */
fun productionAdapterFactories(): List<ProviderAdapterFactory> =
    listOf(SimulatorProviderFactory()) + HttpProviderAdapters.factories() + AnthropicAdapterFactory()

/**
 * The composition root: builds every framework service once from a [Ports] value and exposes
 * what screens and background work need.
 *
 * It is created by hand rather than by a DI framework because the graph is small and explicit
 * wiring keeps the dependency order readable. A later subsystem (memory engine, message bus, …)
 * is added by constructing it here from [ports] and the services above it, and by registering
 * its start-up work in [AppStartup]; nothing else needs to change.
 *
 * @param appScope lives as long as the process; start-up, background work and chat turns run in it.
 * @param adapterFactories how each provider kind is reached. Tests pass fakes, so nothing they
 *   do can touch the network.
 * @param memoryConfig tunables of the memory engine (short-term lifetime, recall weights). The
 *   app version it names is written into every exported memory graph.
 */
class AppGraph(
    val ports: Ports,
    appScope: CoroutineScope,
    adapterFactories: List<ProviderAdapterFactory> = productionAdapterFactories(),
    val memoryConfig: MemoryConfig = MemoryConfig(appVersion = BuildConfig.VERSION_NAME),
    /**
     * Where heavy work (memory recall and scoring) runs. The background pool in the app; tests
     * pass [kotlin.coroutines.EmptyCoroutineContext] so that work stays on their test dispatcher.
     */
    backgroundContext: CoroutineContext = Dispatchers.Default,
    /**
     * Whether progress notifications can be shown. Android's answer in the app; an always-allowed
     * stand-in elsewhere, which a test replaces to script a refusal.
     */
    val notifications: NotificationAccess = InMemoryNotificationAccess(),
) {
    val clock: Clock = ports.clock
    val idGenerator: IdGenerator = ports.idGenerator

    // --- ports that screens use directly ---
    val activityLog: ActivityLog = ports.activityLog
    val settings: SettingsRepository = ports.settings
    val secretVault: SecretVault = ports.secretVault
    val deviceInspector: DeviceInspector = ports.deviceInspector

    // --- registry ---
    val activityRecorder = ActivityRecorder(ports.activityLog, ports.idGenerator, ports.clock)

    /**
     * Hand-offs to other apps always go through the logging decorator, so every time text
     * leaves this app's control there is an activity entry for it.
     */
    val aiAppLauncher: AiAppLauncher = LoggingAiAppLauncher(ports.aiAppLauncher, activityRecorder)

    val aiAppRegistry: AiAppRegistry = DefaultAiAppRegistry(
        apps = ports.aiApps,
        scanner = ports.aiAppScanner,
        activity = activityRecorder,
        ids = ports.idGenerator,
        clock = ports.clock,
    )

    val registryDirectory: RegistryDirectory = DefaultRegistryDirectory(
        agents = ports.agents,
        providers = ports.providers,
        models = ports.models,
        apps = ports.aiApps,
        settings = ports.settings,
    )

    // --- model providers ---

    /** One open adapter per provider, shared by the gateway and the provider manager. */
    private val providerConnections = ProviderConnections(ports.providers, ports.secretVault, adapterFactories, ports.clock)

    val modelGateway: ModelGateway = DefaultModelGateway(
        providers = ports.providers,
        models = ports.models,
        settings = ports.settings,
        connections = providerConnections,
        activity = activityRecorder,
        clock = ports.clock,
    )

    val providerManager: ProviderManager = DefaultProviderManager(
        providers = ports.providers,
        models = ports.models,
        settings = ports.settings,
        vault = ports.secretVault,
        connections = providerConnections,
        activity = activityRecorder,
        ids = ports.idGenerator,
        clock = ports.clock,
    )

    // --- memory ---

    /**
     * Turns memories into vectors for semantic search. The hashing embedder needs no model and
     * no network, so search works offline and nothing a memory says leaves the phone for it.
     */
    val embedder: Embedder = HashingEmbedder()

    /**
     * The one memory service of the process. It serialises writes with an in-process lock, so
     * there must never be a second instance over the same repository.
     */
    val memoryService: MemoryService = DefaultMemoryService(
        repository = ports.memories,
        agents = ports.agents,
        settings = ports.settings,
        embedder = embedder,
        activity = activityRecorder,
        ids = ports.idGenerator,
        clock = ports.clock,
        config = memoryConfig,
        computeContext = backgroundContext,
    )

    // --- tools ---

    /** The capabilities tools need. Declared before the tools, which register theirs into it. */
    val capabilities = CapabilityRegistry()

    /** Every tool agents can be given. The team tools are added below, once the orchestrator exists. */
    val toolRegistry: ToolRegistry = DefaultToolRegistry().also {
        BuiltInTools.registerAll(it, ports.clock, registryDirectory)
        MemoryTools.registerAll(it, memoryService, capabilities, ports.agents, ports.clock)
    }

    private val toolExecutor = ToolExecutor(
        registry = toolRegistry,
        authorizer = DefaultToolAuthorizer(ports.settings, capabilities),
        activity = activityRecorder,
        clock = ports.clock,
    )

    // --- agents at work ---
    private val monitor = DefaultAgentActivityMonitor(ports.clock)

    /** What every agent is doing right now; agents that are not in it are idle. */
    val activityMonitor: AgentActivityMonitor = monitor

    private val agentRunner = AgentRunner(
        gateway = modelGateway,
        tools = toolRegistry,
        executor = toolExecutor,
        agents = ports.agents,
        settings = ports.settings,
        monitor = monitor,
        clock = ports.clock,
        // Relevant memories are added to the prompt before each turn, per the agent's memory policy.
        recall = AutoRecall(memoryService, activityRecorder, lookupContext = backgroundContext),
    )

    // --- the Director and its team ---

    /** Objectives and their tasks; screens observe them, only the orchestrator changes them. */
    val tasks: TaskRepository = ports.tasks

    /** What agents said to each other about each objective. */
    val messages: MessageRepository = ports.messages

    /**
     * Runs the owner's objectives: the Director plans, agents work, reviewers check, the Director
     * writes the result. It shares the runner and the activity monitor with chat, so every limit,
     * permission check and "working now" indicator applies to orchestrated work exactly as to a
     * conversation. Objectives run in [appScope], so they outlive the screen that started them.
     */
    private val defaultOrchestrator = DefaultOrchestrator(
        tasks = ports.tasks,
        messages = ports.messages,
        conversations = ports.conversations,
        agents = ports.agents,
        directory = registryDirectory,
        settings = ports.settings,
        runner = agentRunner,
        toolRegistry = toolRegistry,
        monitor = monitor,
        activity = activityRecorder,
        ids = ports.idGenerator,
        clock = ports.clock,
        scope = appScope,
        // The Director may save what an objective taught the team, when the owner allows it.
        memory = memoryService,
    ).also {
        // Once, before any agent runs: asking a teammate and delegating are tools like any other.
        TeamTools.registerAll(toolRegistry, capabilities, it)
    }

    val orchestrator: Orchestrator = defaultOrchestrator

    private val defaultAgentChat = DefaultAgentChat(
        conversations = ports.conversations,
        agents = ports.agents,
        models = ports.models,
        providers = ports.providers,
        settings = ports.settings,
        runner = agentRunner,
        activity = activityRecorder,
        ids = ports.idGenerator,
        clock = ports.clock,
        // Turns outlive the screen that started them.
        scope = appScope,
    )

    val agentChat: AgentChat = defaultAgentChat

    val agentManager: AgentManager = DefaultAgentManager(
        agents = ports.agents,
        settings = ports.settings,
        activity = activityRecorder,
        ids = ports.idGenerator,
        clock = ports.clock,
        // Deleting an agent stops its turns, removes its conversations and forgets its private and
        // task memory. What it shared into spaces stays there, with its provenance.
        deletionListeners = listOf(defaultAgentChat, AgentMemoryCleanup(memoryService)),
    )

    /** Creates the starter "Team knowledge" space once, after the starter team exists. */
    private val memorySeeder = MemorySeeder(memoryService, agentManager, ports.agents, ports.settings, activityRecorder)

    private val startup = AppStartup(
        scope = appScope,
        agentManager = agentManager,
        aiAppRegistry = aiAppRegistry,
        providerManager = providerManager,
        memorySeeder = memorySeeder,
        memory = memoryService,
        settings = ports.settings,
        activityLog = ports.activityLog,
        activityRecorder = activityRecorder,
        packageChanges = ports.packageChanges,
        clock = ports.clock,
        orchestrator = orchestrator,
    )

    private val foreground = MutableStateFlow(false)

    /**
     * True while the app is on screen. Android lets a foreground service be started only then, so
     * the service that keeps objectives alive in the background is started from this state.
     */
    val isInForeground: StateFlow<Boolean> = foreground.asStateFlow()

    /**
     * Registers the built-in simulator, runs first-run seeding and the initial registry
     * refresh, and begins reacting to package changes. Idempotent; the returned job completes
     * when the one-off part has finished. Provider checks start then and run in the background.
     */
    fun start(): Job = startup.start()

    private val objectiveRequests = MutableStateFlow<TaskId?>(null)

    /** An objective the owner asked to see from outside the app (the notification); the shell opens it. */
    val objectiveToOpen: StateFlow<TaskId?> = objectiveRequests.asStateFlow()

    fun requestObjective(id: TaskId) {
        objectiveRequests.value = id
    }

    /** The shell has opened [objectiveToOpen]. */
    fun objectiveOpened() {
        objectiveRequests.value = null
    }

    /**
     * Waits for the one-off start-up work (seeding, recovery of interrupted objectives) if it has
     * been started; returns at once otherwise. New objectives wait for it, so recovery never
     * mistakes a just-started objective for one the app was killed in the middle of.
     */
    suspend fun awaitStartup() = startup.awaitInitial()

    /** Call when the app returns to the foreground: package broadcasts are best effort, and provider status goes stale. */
    fun onAppForegrounded() {
        foreground.value = true
        // The owner may have switched notifications on or off in the settings meanwhile.
        notifications.refresh()
        startup.onAppForegrounded()
    }

    /** Call when the app leaves the foreground; periodic memory upkeep pauses until it returns. */
    fun onAppBackgrounded() {
        foreground.value = false
        startup.onAppBackgrounded()
    }
}
