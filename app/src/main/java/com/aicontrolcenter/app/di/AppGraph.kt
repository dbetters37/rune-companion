package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.BuildConfig
import com.aicontrolcenter.app.di.wiring.AgentsAssembly
import com.aicontrolcenter.app.di.wiring.BackgroundScreens
import com.aicontrolcenter.app.di.wiring.BackupWiring
import com.aicontrolcenter.app.di.wiring.ModelsAssembly
import com.aicontrolcenter.app.di.wiring.OperatorAssembly
import com.aicontrolcenter.app.di.wiring.RoutinesAssembly
import com.aicontrolcenter.app.di.wiring.TeamAssembly
import com.aicontrolcenter.app.operator.ScreenSessions
import com.aicontrolcenter.app.operator.SystemScreens
import com.aicontrolcenter.app.work.InMemoryNotificationAccess
import com.aicontrolcenter.app.work.NotificationAccess
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.backup.OwnerBackup
import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.device.DeviceInspector
import com.aicontrolcenter.core.health.BackgroundHealth
import com.aicontrolcenter.core.health.ExitHistory
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.health.StorageWatch
import com.aicontrolcenter.core.llm.ModelGateway
import com.aicontrolcenter.core.llm.ProviderAdapterFactory
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.llm.ProviderRechecks
import com.aicontrolcenter.core.memory.Embedder
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.core.pickup.OwnerPickUpRules
import com.aicontrolcenter.core.registry.AiAppLauncher
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.registry.AiAppScanner
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.routines.OwnerRoutines
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tasks.OwnerConversation
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.OwnerModelPrices
import com.aicontrolcenter.core.usage.OwnerUsage
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.framework.agents.AgentRunner
import com.aicontrolcenter.framework.agents.DefaultAgentManager
import com.aicontrolcenter.framework.llm.SimulatorProviderFactory
import com.aicontrolcenter.framework.memory.AgentMemoryCleanup
import com.aicontrolcenter.framework.memory.MemorySeeder
import com.aicontrolcenter.framework.operator.PermissionBroker
import com.aicontrolcenter.framework.operator.RequestWaiters
import com.aicontrolcenter.framework.registry.DefaultAiAppRegistry
import com.aicontrolcenter.framework.registry.LoggingAiAppLauncher
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.memory.DefaultMemoryService
import com.aicontrolcenter.memory.HashingEmbedder
import com.aicontrolcenter.memory.MemoryConfig
import com.aicontrolcenter.providers.HttpProviderAdapters
import com.aicontrolcenter.providers.anthropic.AnthropicAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.CoroutineContext

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
 * wiring keeps the dependency order readable. Since Phase 6 the parts each wave-2 package owns are
 * built in their own assemblies (di/wiring: models, operator, agents, team, routines), in that
 * order, between memory and start-up; this class keeps its public members as the same instances
 * the assemblies built, so screens and tests are unchanged. A later subsystem is added in its
 * package's assembly, or here, and its start-up work in [AppStartup].
 *
 * Agent-facing code gets the settings through a [ReadOnlySettings] view only (rule 3); the writer
 * ([settings]) is for the owner's screens, the agent manager and the memory seeder.
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
    /**
     * The process's one memory service, when the application built it (it is shared with the
     * upkeep job, which never builds this graph). Null: the graph builds its own over [ports].
     */
    memoryService: MemoryService? = null,
    /** The embedder [memoryService] was built with (the graph's own memory service uses it too). */
    embedder: Embedder = HashingEmbedder(),
) {
    val clock: Clock = ports.clock
    val idGenerator: IdGenerator = ports.idGenerator

    // --- ports that screens use directly ---
    val activityLog: ActivityLog = ports.activityLog

    /** The settings writer: the owner's screens only. Everything agent-facing gets [settingsView]. */
    val settings: SettingsRepository = ports.settings
    val secretVault: SecretVault = ports.secretVault
    val deviceInspector: DeviceInspector = ports.deviceInspector

    /**
     * Read-only settings: the only settings object agent-facing code may hold (rule 3). The
     * architecture test walks the graph's objects and fails on any other path to the writer.
     */
    val settingsView: SettingsView = ReadOnlySettings(ports.settings)

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

    // --- models and providers (WP4) ---

    /** The registry directory, the gateway and the provider manager. */
    internal val modelsAssembly = ModelsAssembly(ports, settingsView, activityRecorder, adapterFactories, appScope, ports.network)

    val registryDirectory: RegistryDirectory get() = modelsAssembly.registryDirectory
    val modelGateway: ModelGateway get() = modelsAssembly.modelGateway

    /** Owner-only: the Models screens and start-up. Nothing agent-facing holds it (F3). */
    val providerManager: ProviderManager get() = modelsAssembly.providerManager

    /** Agent-side: re-checking a provider that was not answering, and when the next re-check is due (WP2's wake lock reads it). */
    val providerRechecks: ProviderRechecks get() = modelsAssembly.providerRechecks

    // --- memory ---

    /**
     * Turns memories into vectors for semantic search. The hashing embedder needs no model and
     * no network, so search works offline and nothing a memory says leaves the phone for it.
     */
    val embedder: Embedder = embedder

    /**
     * The one memory service of the process. It serialises writes with an in-process lock, so
     * there must never be a second instance over the same repository: in the app it is the
     * application's, shared with the upkeep job.
     */
    val memoryService: MemoryService = memoryService ?: DefaultMemoryService(
        repository = ports.memories,
        agents = ports.agents,
        settings = settingsView,
        embedder = embedder,
        activity = activityRecorder,
        ids = ports.idGenerator,
        clock = ports.clock,
        config = memoryConfig,
        computeContext = backgroundContext,
    )

    // --- the phone operator (WP5) ---

    /** The phone operator, the tools, the executor, the broker and the owner's operator ports. */
    internal val operatorAssembly =
        OperatorAssembly(ports, settingsView, activityRecorder, appScope, registryDirectory, this.memoryService, modelsAssembly.ownerKeyCheck)

    /**
     * The agents' workspace, a folder inside this app. There is exactly one: the workspace tools
     * and the owner's Workspace screens share it, so both see every change at once.
     */
    val workspace: Workspace get() = operatorAssembly.workspace

    /**
     * The screen operator as agents and the broker get it, which also says who is operating which
     * app right now (for the "is operating" notification). It cannot stop or resume anything.
     */
    val screenSessions: ScreenSessions get() = operatorAssembly.screenSessions

    /** Owner-only: the Stop and Resume of screen operation. */
    val screenControl: ScreenControl get() = operatorAssembly.screenControl

    /** Which questions to the owner a live call of this process still waits for. */
    internal val requestWaiters: RequestWaiters get() = operatorAssembly.requestWaiters

    /** The capabilities tools need. */
    val capabilities: CapabilityRegistry get() = operatorAssembly.capabilities

    /** Every tool agents can be given, the team tools included once the orchestrator exists. */
    val toolRegistry: ToolRegistry get() = operatorAssembly.toolRegistry

    /** Runs every tool call through the broker. Internal so the architecture test can walk what it reaches. */
    internal val toolExecutor: ToolExecutor get() = operatorAssembly.toolExecutor

    /** The broker, for the architecture test. */
    internal val permissionBroker: PermissionBroker get() = operatorAssembly.permissionBroker

    // --- what only the owner's screens may use ---

    /**
     * Pending and past questions to the owner, and the only way to answer them. It takes an answer
     * only while a call still waits for it and what it is about is still there.
     */
    val ownerRequests: OwnerRequests get() = operatorAssembly.ownerRequests

    /**
     * Owner-only: answering an agent's question (Tier B, rule 13) goes through [OwnerRequests.answer], the same
     * port as [ownerRequests]; the question screen (WP11a) binds this name.
     */
    val ownerQuestions: OwnerRequests get() = operatorAssembly.ownerRequests

    /** What each agent has been granted; giving and revoking grants. */
    val ownerGrants: OwnerGrants get() = operatorAssembly.ownerGrants

    /** The folders the owner shares with agents. Removing one withdraws the questions about it. */
    val ownerFolders: OwnerFolders get() = operatorAssembly.ownerFolders

    /** The apps the owner authorised for screen operation. Withdrawing one withdraws the questions about it. */
    val ownerOperatorApps: OwnerOperatorApps get() = operatorAssembly.ownerOperatorApps

    /** Android's settings screens and the share sheet, for the owner's Tools and Workspace screens. */
    val systemScreens: SystemScreens get() = operatorAssembly.systemScreens

    /** The phone's launchable apps and their signing certificates, for authorising an app for screen control. */
    val installedApps: AiAppScanner get() = operatorAssembly.installedApps

    /** Whether agents never operate [packageName] (system settings, installers, this app); the Screen control picker marks them. */
    fun isProtectedPackage(packageName: String): Boolean = operatorAssembly.isProtectedPackage(packageName)

    /** Reads Android's accessibility settings again; Screen control calls it whenever it is shown. */
    fun refreshScreenState() = ports.operator.refreshScreenState()

    // --- agents at work (WP15) ---

    /** The activity monitor and the agent runner. */
    internal val agentsAssembly = AgentsAssembly(
        ports, settingsView, modelGateway, toolRegistry, toolExecutor, this.memoryService, activityRecorder, backgroundContext, screenSessions,
    )

    /** What every agent is doing right now; agents that are not in it are idle. */
    val activityMonitor: AgentActivityMonitor get() = agentsAssembly.activityMonitor

    private val agentRunner: AgentRunner get() = agentsAssembly.agentRunner

    // --- the Director and its team, and chat (WP7) ---

    /** Objectives and their tasks; screens observe them, only the orchestrator changes them. */
    val tasks: TaskRepository = ports.tasks

    /** What agents said to each other about each objective. */
    val messages: MessageRepository = ports.messages

    /**
     * The process's one maintenance gate (K13): a restore runs inside it (WP3); starting an objective, sending a
     * chat message and the upkeep job wait for it (WP7, WP2).
     */
    val maintenanceGate: MaintenanceGate = ports.maintenanceGate

    /** The orchestrator, the team tools and chat. */
    internal val teamAssembly = TeamAssembly(
        ports = ports,
        settings = settingsView,
        registryDirectory = registryDirectory,
        runner = agentRunner,
        toolRegistry = toolRegistry,
        capabilities = capabilities,
        monitor = agentsAssembly.monitor,
        activity = activityRecorder,
        appScope = appScope,
        memory = this.memoryService,
        operatorHooks = operatorAssembly.operatorHooks,
        providerRechecks = modelsAssembly.providerRechecks,
        maintenanceGate = maintenanceGate,
    ).also { team ->
        // Before any agent runs: the operator's tools that need the orchestrator (WP5).
        operatorAssembly.teamReady(team.orchestrator)
    }

    /**
     * Runs the owner's objectives: the Director plans, agents work, reviewers check, the Director
     * writes the result. Objectives run in the process scope, so they outlive the screen that started them.
     */
    val orchestrator: Orchestrator get() = teamAssembly.orchestrator

    val agentChat: AgentChat get() = teamAssembly.agentChat

    /** Owner-only: notes to the team during an objective (Tier B, C2). Unavailable until WP7 builds them. */
    val ownerConversation: OwnerConversation get() = teamAssembly.ownerConversation

    val agentManager: AgentManager = DefaultAgentManager(
        agents = ports.agents,
        settings = ports.settings,
        activity = activityRecorder,
        ids = ports.idGenerator,
        clock = ports.clock,
        // Deleting an agent stops its turns, removes its conversations and forgets its private and
        // task memory. What it shared into spaces stays there, with its provenance.
        deletionListeners = listOf(teamAssembly.chatDeletionListener, AgentMemoryCleanup(this.memoryService)),
    )

    /** Creates the starter "Team knowledge" space once, after the starter team exists. */
    private val memorySeeder = MemorySeeder(this.memoryService, agentManager, ports.agents, ports.settings, activityRecorder)

    // --- routines and folder pick-up (WP17, Tier B) ---

    /** Built after the orchestrator, which a routine starts its objective through. */
    internal val routinesAssembly = RoutinesAssembly(ports, settingsView, orchestrator, this.memoryService, activityRecorder, appScope)

    /** Owner-only: the owner's routines (Appendix A). Null until WP17 builds them; the screen then stays hidden. */
    val ownerRoutines: OwnerRoutines? get() = routinesAssembly.ownerRoutines

    /** Owner-only: the owner's folder pick-up rules (Appendix A). Null until WP17 builds them. */
    val ownerPickUpRules: OwnerPickUpRules? get() = routinesAssembly.ownerPickUpRules

    // --- usage, cost, background, backup and recovery for the owner's screens (Phase 6) ---

    /** Usage by day, month, model, agent and work item (numbers only, rule 7). */
    val usageReports: UsageReports = ports.usageReports

    /** The owner's prices, read only. */
    val modelPrices: ModelPrices = ports.modelPrices

    /** Owner-only: typing a model's price. */
    val ownerModelPrices: OwnerModelPrices = ports.ownerModelPrices

    /** Owner-only: clearing the usage history (keeps today). */
    val ownerUsage: OwnerUsage = ports.ownerUsage

    /** Whether Android lets the app work in the background (WP2, A3). */
    val backgroundHealth: BackgroundHealth = ports.background.health

    /** Android's screens for the background check (WP2). */
    val backgroundScreens: BackgroundScreens = ports.background.screens

    /** The phone's free room (WP2, R8). */
    val storageWatch: StorageWatch = ports.background.storage

    /** Why the app's main process ended before (WP2, R1). */
    val exitHistory: ExitHistory = ports.background.exits

    /** Owner-only: the backup file (WP3, Tier B). Null: no backup in this build; the screen stays hidden. */
    val ownerBackup: OwnerBackup? = ports.backup

    init {
        // A saved chat names each tool it used by the title the owner knows ("Read the screen"), not by its function name
        // (WP3). The backup gets a title look-up and nothing else of the registry; the registry never reaches the backup.
        BackupWiring.useToolTitles(ownerBackup, toolRegistry)
    }

    /**
     * Owner-only: the database check and Start over (WP2). The Recovery screen gets it from the application
     * class, never from this graph (rule 9); it is here for the owner's screens once the app is running.
     */
    val ownerRecovery: OwnerRecovery? = ports.background.recovery

    // --- start-up ---

    private val startup = AppStartup(
        scope = appScope,
        agentManager = agentManager,
        aiAppRegistry = aiAppRegistry,
        providerManager = providerManager,
        memorySeeder = memorySeeder,
        memory = this.memoryService,
        settings = settingsView,
        activityLog = ports.activityLog,
        activityRecorder = activityRecorder,
        packageChanges = ports.packageChanges,
        clock = ports.clock,
        orchestrator = orchestrator,
        closeOverdueRequests = ::closeOverdueRequests,
        stopShellLeftovers = ports.operator.stopShellLeftovers,
        tidyWorkspaceTrash = workspace::tidyTrash,
        agentChat = agentChat,
        hooks = ports.background.startup,
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
        operatorAssembly.operatorHooks.closeOverdue()
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
}
