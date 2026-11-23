package com.aicontrolcenter.app.testing

import kotlin.coroutines.EmptyCoroutineContext
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.di.Ports
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.AiAppScanner
import com.aicontrolcenter.core.registry.DiscoveredApp
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.testkit.FakeAiAppLauncher
import com.aicontrolcenter.testkit.FakeAiAppScanner
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeDeviceInspector
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryAgentRepository
import com.aicontrolcenter.testkit.InMemoryAiAppRepository
import com.aicontrolcenter.testkit.InMemoryConversationRepository
import com.aicontrolcenter.testkit.InMemoryMemoryRepository
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.FakeProviderAdapterFactory
import com.aicontrolcenter.framework.llm.SimulatorProviderFactory
import com.aicontrolcenter.testkit.InMemoryModelRepository
import com.aicontrolcenter.testkit.InMemoryProviderRepository
import com.aicontrolcenter.testkit.InMemorySecretVault
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The real composition root over in-memory ports: everything a test needs to drive the app the
 * way production does, with handles on the fakes to script the device and inspect the outcome.
 *
 * @param scope stands in for the process scope; pass the test's background scope.
 */
class TestApp(scope: CoroutineScope, settings: FrameworkSettings = FrameworkSettings()) {
    val agents = InMemoryAgentRepository()
    val providers = InMemoryProviderRepository()
    val models = InMemoryModelRepository()
    val aiApps = InMemoryAiAppRepository()
    val conversations = InMemoryConversationRepository()
    val memories = InMemoryMemoryRepository()
    val vault = InMemorySecretVault()
    val activityLog = InMemoryActivityLog()
    val settings = InMemorySettingsRepository(settings)
    val scanner = FakeAiAppScanner()

    /** When set, every read of the device fails with it, as a broken PackageManager would. */
    var scanFailure: Exception? = null
    val launcher = FakeAiAppLauncher()
    val deviceInspector = FakeDeviceInspector()

    /** Starts at 2026-11-14 12:00 UTC and moves on with every reading, so events have distinct, ordered times. */
    val clock = FakeClock(current = START_TIME, autoAdvanceMs = 37_000)
    val packageChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val ports = Ports(
        agents = agents,
        providers = providers,
        models = models,
        aiApps = aiApps,
        conversations = conversations,
        memories = memories,
        activityLog = activityLog,
        settings = this.settings,
        secretVault = vault,
        aiAppScanner = object : AiAppScanner {
            override suspend fun scanForAiApps() = failOr { scanner.scanForAiApps() }
            override suspend fun listInstalledApps() = failOr { scanner.listInstalledApps() }
            override suspend fun inspect(packageName: String) = failOr { scanner.inspect(packageName) }
        },
        aiAppLauncher = launcher,
        deviceInspector = deviceInspector,
        clock = clock,
        idGenerator = SequentialIdGenerator(),
        packageChanges = packageChanges,
    )

    /**
     * The adapter each fake provider factory hands out, by the provider's base URL (an empty key
     * for providers without one); anything else gets [fallbackAdapter]. Nothing reaches a network.
     */
    val adaptersByUrl = ConcurrentHashMap<String, ProviderAdapter>()
    var fallbackAdapter: () -> ProviderAdapter = {
        FakeProviderAdapter(
            health = ProviderHealth(ConnectionStatus.OFFLINE, "Nothing is listening at that address."),
            listError = LlmException.Network("Could not reach the server."),
        )
    }
    val factories: Map<ProviderKind, FakeProviderAdapterFactory> =
        listOf(ProviderKind.OPENAI_COMPATIBLE, ProviderKind.ANTHROPIC, ProviderKind.GEMINI, ProviderKind.OLLAMA).associateWith { kind ->
            FakeProviderAdapterFactory(kind) { connection -> adaptersByUrl[connection.provider.baseUrl.orEmpty()] ?: fallbackAdapter() }
        }

    val graph = AppGraph(
        ports, scope, factories.values.toList() + SimulatorProviderFactory(chunkDelayMs = 0),
        backgroundContext = EmptyCoroutineContext,
    )

    private inline fun <T> failOr(block: () -> T): T = scanFailure?.let { throw it } ?: block()

    suspend fun snapshot() = graph.registryDirectory.snapshot()

    suspend fun agentNamed(name: String): AgentEntry = snapshot().agents.first { it.profile.name == name }

    suspend fun director(): AgentEntry = snapshot().agents.first { it.profile.kind == AgentKind.DIRECTOR }

    suspend fun appNamed(label: String): AiApp = snapshot().apps.first { it.label == label }

    /** Registers one provider and one model, the state of a later phase, so "has models" paths can be tested. */
    suspend fun registerSampleModel() {
        providers.upsert(Samples.provider())
        models.upsert(Samples.model())
    }

    /** Scripts what a device scan finds: two catalog apps and one that only looks like an AI app. */
    fun installSampleApps() {
        scanner.aiApps = listOf(chatbot, assistant, lookalike)
        scanner.installed = listOf(
            InstalledAppInfo("org.example.notes", "Notes", "2.0"),
            InstalledAppInfo("com.android.settings", "Settings", "16", isSystemApp = true),
            InstalledAppInfo("org.example.terminal", "Terminal", null),
        )
    }

    companion object {
        const val START_TIME = 1_794_657_600_000L

        val chatbot = DiscoveredApp(
            packageName = "com.example.chatbot", label = "Example Chatbot", versionName = "4.2.1", vendor = "Example Inc.",
            capabilities = setOf(AiAppCapability.LAUNCH, AiAppCapability.SHARE_TEXT, AiAppCapability.PROCESS_TEXT),
            source = DiscoverySource.CATALOG, firstInstallTime = 500, signingCertSha256 = "ab".repeat(32),
        )
        val assistant = DiscoveredApp(
            packageName = "com.sample.assistant", label = "Sample Assistant", versionName = "12.0", vendor = "Sample Labs",
            capabilities = setOf(AiAppCapability.LAUNCH, AiAppCapability.ASSISTANT, AiAppCapability.SHARE_IMAGE),
            source = DiscoverySource.INTENT_QUERY, firstInstallTime = 600, signingCertSha256 = "cd".repeat(32),
        )
        val lookalike = DiscoveredApp(
            packageName = "io.unknown.aihelper", label = "AI Helper", versionName = "0.9",
            capabilities = setOf(AiAppCapability.LAUNCH),
            source = DiscoverySource.HEURISTIC, firstInstallTime = 700, signingCertSha256 = "ef".repeat(32),
        )
    }
}

/**
 * Keeps a `WhileSubscribed` state flow active for the rest of the test, the way a visible
 * screen does, and returns it so the test can read `value`.
 */
fun <T> StateFlow<T>.activeIn(scope: CoroutineScope): StateFlow<T> {
    scope.launch { collect { } }
    return this
}
