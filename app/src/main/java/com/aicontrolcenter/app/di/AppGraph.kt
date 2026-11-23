package com.aicontrolcenter.app.di

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.device.DeviceInspector
import com.aicontrolcenter.core.llm.ModelGateway
import com.aicontrolcenter.core.llm.ProviderAdapterFactory
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.memory.Embedder
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.operator.GrantLookup
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.WebReader
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.core.registry.AiAppLauncher
import com.aicontrolcenter.core.registry.AiAppScanner
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
import com.aicontrolcenter.framework.operator.DefaultCapabilityRequester
import com.aicontrolcenter.framework.operator.DefaultOwnerFolders
import com.aicontrolcenter.framework.operator.DefaultOwnerGrants
import com.aicontrolcenter.framework.operator.DefaultOwnerOperatorApps
import com.aicontrolcenter.framework.operator.DefaultOwnerRequests
import com.aicontrolcenter.framework.operator.LocalWorkspace
import com.aicontrolcenter.framework.operator.OperatorEgress
import com.aicontrolcenter.framework.operator.OperatorFacts
import com.aicontrolcenter.framework.operator.OperatorTaskHooks
import com.aicontrolcenter.framework.operator.OperatorToolDependencies
import com.aicontrolcenter.framework.operator.OperatorToolset
import com.aicontrolcenter.framework.operator.PermissionBroker
import com.aicontrolcenter.framework.operator.PermissionRequestTool
import com.aicontrolcenter.framework.operator.asAuthorisedApps
import com.aicontrolcenter.framework.operator.asDirectory
import com.aicontrolcenter.framework.orchestration.DefaultOrchestrator
import com.aicontrolcenter.framework.orchestration.TeamTools
import com.aicontrolcenter.framework.registry.DefaultAiAppRegistry
import com.aicontrolcenter.framework.registry.DefaultRegistryDirectory
import com.aicontrolcenter.framework.registry.LoggingAiAppLauncher
import com.aicontrolcenter.framework.tools.BuiltInTools
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.memory.DefaultMemoryService
import com.aicontrolcenter.memory.HashingEmbedder
import com.aicontrolcenter.memory.MemoryConfig
import com.aicontrolcenter.app.BuildConfig
import com.aicontrolcenter.app.operator.EgressLog
import com.aicontrolcenter.app.operator.GrantLookupView
import com.aicontrolcenter.app.operator.RequestQueueView
import com.aicontrolcenter.app.operator.ScreenSessions
import com.aicontrolcenter.app.operator.SystemScreens
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

    // --- the phone operator ---

    /** The apps that are never operated: resolved once at start-up, the same set everywhere. */
    private val protectedPackages: () -> ProtectedPackages = ports.operator.protectedPackages

    /**
     * The agents' workspace, a folder inside this app. There is exactly one: the workspace tools
     * and the owner's Workspace screens share it, so both see every change at once.
     */
    val workspace: Workspace = LocalWorkspace(ports.operator.workspaceRoot, ports.clock)

    /**
     * The screen operator as agents and the broker get it, which also says who is operating which
     * app right now (for the "is operating" notification). It cannot stop or resume anything.
     */
    val screenSessions = ScreenSessions(ports.operator.screenOperator, ports.operator.operatorApps.asAuthorisedApps(), appScope)

    /** Owner-only: the Stop and Resume of screen operation. */
    val screenControl: ScreenControl = ports.operator.screenControl

    /** What the broker, the requester and the task hooks hold: raise, watch and close requests, never answer them. */
    private val requestQueue: OwnerRequestQueue = RequestQueueView(ports.operator.ownerRequests)

    /** Find, use up and narrow grants, never give one. */
    private val grantLookup: GrantLookup = GrantLookupView(ports.operator.permissions)

    /** Reads web pages for `web.read_page`; every request that leaves the phone is logged as it is sent. */
    private val webReader: WebReader = ports.operator.webReader(EgressLog(activityRecorder, appScope)::sent)

    // --- tools ---

    /** The capabilities tools need. Declared before the tools, which register theirs into it. */
    val capabilities = CapabilityRegistry().also { registry -> OperatorCapabilities.all.forEach(registry::register) }

    /**
     * The facts every permission check reads fresh: the agent as stored, the settings, Stop, the
     * folder or app a call targets and the protected apps. Read-only.
     */
    private val operatorFacts = OperatorFacts(
        agents = ports.agents,
        settings = ports.settings,
        capabilities = capabilities,
        folders = ports.operator.sharedFolders,
        apps = ports.operator.operatorApps,
        screenStopped = screenSessions.stopped,
        protectedPackages = protectedPackages,
    )

    /**
     * Decides every tool call: the agent and the tool switched on, the master switches and
     * local-only mode, the agent's grants, and the owner's approval of every consequential action.
     * It asks the owner when it must and waits for the answer; it can never answer itself.
     */
    private val broker = PermissionBroker(
        facts = operatorFacts,
        requests = requestQueue,
        grants = grantLookup,
        tasks = ports.tasks,
        activity = activityRecorder,
        ids = ports.idGenerator,
        clock = ports.clock,
    )

    /** How an agent asks the owner for a permission itself (`permissions.request`). It never waits and can grant nothing. */
    private val capabilityRequester = DefaultCapabilityRequester(operatorFacts, requestQueue, grantLookup, activityRecorder, ports.idGenerator, ports.clock)

    /** Every tool agents can be given. The team tools are added below, once the orchestrator exists. */
    val toolRegistry: ToolRegistry = DefaultToolRegistry().also { registry ->
        BuiltInTools.registerAll(registry, ports.clock, registryDirectory)
        MemoryTools.registerAll(registry, memoryService, capabilities, ports.agents, ports.clock)
        // The phone operator's tools get gateways, the workspace and read-only lookups; nothing owner-only.
        OperatorToolset.create(
            OperatorToolDependencies(
                workspace = workspace,
                sharedFolders = ports.operator.folderAccess,
                folders = ports.operator.sharedFolders.asDirectory(),
                media = ports.operator.media,
                apps = ports.operator.appActions,
                web = webReader,
                screen = screenSessions,
                authorisedApps = ports.operator.operatorApps.asAuthorisedApps(),
                protectedPackages = protectedPackages,
                shell = ports.operator.shell,
                termux = ports.operator.termux,
                clock = ports.clock,
                ownPackage = ports.operator.ownPackage,
                // Commands and links that may send data off the phone are logged as data transfers when they run.
                egress = OperatorEgress.logTo(activityRecorder),
            ),
        ).forEach(registry::register)
        PermissionRequestTool.register(registry, capabilityRequester)
    }

    /** Runs every tool call through [broker]. Internal so the architecture test can walk what it reaches. */
    internal val toolExecutor = ToolExecutor(
        registry = toolRegistry,
        authorizer = broker,
        activity = activityRecorder,
        clock = ports.clock,
    )

    /** The broker, for the architecture test. */
    internal val permissionBroker: PermissionBroker get() = broker

    // --- what only the owner's screens may use ---

    /** Pending and past questions to the owner, and the only way to answer them. */
    val ownerRequests: OwnerRequests = DefaultOwnerRequests(ports.operator.ownerRequests, activityRecorder, ports.idGenerator, ports.clock)

    /** What each agent has been granted; giving and revoking grants. */
    val ownerGrants: OwnerGrants = DefaultOwnerGrants(
        grants = ports.operator.permissions,
        agents = ports.agents,
        capabilities = capabilities,
        folders = ports.operator.sharedFolders,
        apps = ports.operator.operatorApps,
        protectedPackages = protectedPackages,
        activity = activityRecorder,
        ids = ports.idGenerator,
        clock = ports.clock,
    )

    /** The folders the owner shares with agents. */
    val ownerFolders: OwnerFolders = DefaultOwnerFolders(
        ports.operator.folderAccess, ports.operator.sharedFolders, ports.operator.permissions, activityRecorder, ports.idGenerator, ports.clock,
    )

    /** The apps the owner authorised for screen operation. */
    val ownerOperatorApps: OwnerOperatorApps =
        DefaultOwnerOperatorApps(ports.operator.operatorApps, ports.operator.permissions, protectedPackages, activityRecorder, ports.clock)

    /** Android's settings screens and the share sheet, for the owner's Tools and Workspace screens. */
    val systemScreens: SystemScreens = ports.operator.systemScreens

    /** The phone's launchable apps and their signing certificates, for authorising an app for screen control. */
    val installedApps: AiAppScanner = ports.aiAppScanner

    /** Whether agents never operate [packageName] (system settings, installers, this app); the Screen control picker marks them. */
    fun isProtectedPackage(packageName: String): Boolean = protectedPackages().isProtected(packageName)

    /** Reads Android's accessibility settings again; Screen control calls it whenever it is shown. */
    fun refreshScreenState() = ports.operator.refreshScreenState()

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
        // The "is operating" indicator ends with the run that operated the screen.
        screen = screenSessions,
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
        // A task's permissions end with it, and questions left open when the app stopped are withdrawn at start-up.
        operatorHooks = OperatorTaskHooks(requestQueue, grantLookup, activityRecorder, ports.clock),
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
        closeOverdueRequests = ::closeOverdueRequests,
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

    private val approvalRequests = MutableStateFlow<RequestId?>(null)

    /** A request the owner asked to answer from outside the app (its notification); the shell opens its Approval screen. */
    val approvalToOpen: StateFlow<RequestId?> = approvalRequests.asStateFlow()

    fun requestApproval(id: RequestId) {
        approvalRequests.value = id
    }

    /** The shell has opened [approvalToOpen]. */
    fun approvalOpened() {
        approvalRequests.value = null
    }

    /**
     * Closes, as EXPIRED, requests still pending after their expiry. A waiting tool call closes
     * its own request when its time is up; an agent's own ask (`permissions.request`) has no
     * waiter, so this sweep is what ends it. [AppStartup] runs it after start-up and every minute.
     */
    private suspend fun closeOverdueRequests() {
        val closed = requestQueue.closeOverdue(ports.clock.now(), EXPIRED_NOTE)
        for (request in closed) {
            activityRecorder.record(
                type = if (request.kind == OwnerRequestKind.APPROVAL) ActivityType.APPROVAL else ActivityType.PERMISSION,
                title = "No answer in time: ${request.toolTitle ?: "permission asked by ${request.agentName ?: "an agent"}"}",
                detail = request.summary,
                severity = Severity.WARNING,
                agentId = request.agentId,
                taskId = request.taskId,
                rootTaskId = request.rootId,
            )
        }
    }

    /** Call when the app returns to the foreground: package broadcasts are best effort, and provider status goes stale. */
    fun onAppForegrounded() {
        foreground.value = true
        // The owner may have switched notifications on or off in the settings meanwhile.
        notifications.refresh()
        // Android 9 to 12 do not say when the owner switches the accessibility service on.
        ports.operator.refreshScreenState()
        startup.onAppForegrounded()
    }

    /** Call when the app leaves the foreground; periodic memory upkeep pauses until it returns. */
    fun onAppBackgrounded() {
        foreground.value = false
        startup.onAppBackgrounded()
    }

    private companion object {
        /** Why a request no one answered was closed. */
        const val EXPIRED_NOTE = "No answer in time."
    }
}
