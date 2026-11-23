package com.aicontrolcenter.app.di

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.device.DeviceInspector
import com.aicontrolcenter.core.llm.ModelGateway
import com.aicontrolcenter.core.llm.ProviderAdapterFactory
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.registry.AiAppLauncher
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.framework.agents.AgentRunner
import com.aicontrolcenter.framework.agents.DefaultAgentActivityMonitor
import com.aicontrolcenter.framework.agents.DefaultAgentManager
import com.aicontrolcenter.framework.chat.DefaultAgentChat
import com.aicontrolcenter.framework.llm.DefaultModelGateway
import com.aicontrolcenter.framework.llm.DefaultProviderManager
import com.aicontrolcenter.framework.llm.ProviderConnections
import com.aicontrolcenter.framework.llm.SimulatorProviderFactory
import com.aicontrolcenter.framework.registry.DefaultAiAppRegistry
import com.aicontrolcenter.framework.registry.DefaultRegistryDirectory
import com.aicontrolcenter.framework.registry.LoggingAiAppLauncher
import com.aicontrolcenter.framework.tools.BuiltInTools
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import com.aicontrolcenter.framework.tools.DefaultToolAuthorizer
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.providers.HttpProviderAdapters
import com.aicontrolcenter.providers.anthropic.AnthropicAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

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
 */
class AppGraph(
    val ports: Ports,
    appScope: CoroutineScope,
    adapterFactories: List<ProviderAdapterFactory> = productionAdapterFactories(),
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

    // --- tools ---
    val toolRegistry: ToolRegistry = DefaultToolRegistry().also { BuiltInTools.registerAll(it, ports.clock, registryDirectory) }

    /** Later phases register the capabilities their tools need here. */
    val capabilities = CapabilityRegistry()

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
    )

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
        // Deleting an agent stops its turns and removes its conversations.
        deletionListeners = listOf(defaultAgentChat),
    )

    private val startup = AppStartup(
        scope = appScope,
        agentManager = agentManager,
        aiAppRegistry = aiAppRegistry,
        providerManager = providerManager,
        settings = ports.settings,
        activityLog = ports.activityLog,
        activityRecorder = activityRecorder,
        packageChanges = ports.packageChanges,
        clock = ports.clock,
    )

    /**
     * Registers the built-in simulator, runs first-run seeding and the initial registry
     * refresh, and begins reacting to package changes. Idempotent; the returned job completes
     * when the one-off part has finished. Provider checks start then and run in the background.
     */
    fun start(): Job = startup.start()

    /** Call when the app returns to the foreground: package broadcasts are best effort, and provider status goes stale. */
    fun onAppForegrounded() = startup.onAppForegrounded()
}
