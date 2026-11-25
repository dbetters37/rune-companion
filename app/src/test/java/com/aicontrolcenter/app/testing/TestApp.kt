package com.aicontrolcenter.app.testing

import kotlin.coroutines.EmptyCoroutineContext
import android.content.Intent
import com.aicontrolcenter.app.operator.SystemScreens
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.DeviceAccessState
import java.io.File
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.di.OperatorPorts
import com.aicontrolcenter.app.di.Ports
import com.aicontrolcenter.app.di.wiring.BackgroundPorts
import com.aicontrolcenter.app.di.wiring.RoutinesPorts
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.UsageLedger
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.testkit.FakeBackgroundHealth
import com.aicontrolcenter.testkit.FakeExitHistory
import com.aicontrolcenter.testkit.FakeNetworkStatus
import com.aicontrolcenter.testkit.FakeOwnerRecovery
import com.aicontrolcenter.testkit.FakeStorageWatch
import com.aicontrolcenter.testkit.InMemoryModelPrices
import com.aicontrolcenter.testkit.InMemoryPickUpRuleRepository
import com.aicontrolcenter.testkit.InMemoryRoutineRepository
import com.aicontrolcenter.testkit.InMemoryUsageStore
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.EgressOrigin
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.WebReadLimits
import com.aicontrolcenter.core.operator.WebReadResult
import com.aicontrolcenter.core.operator.WebReader
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.testkit.FakeAppActions
import com.aicontrolcenter.testkit.FakeMediaLibrary
import com.aicontrolcenter.testkit.FakeScreenOperator
import com.aicontrolcenter.testkit.FakeSharedFolders
import com.aicontrolcenter.testkit.FakeShellRunner
import com.aicontrolcenter.testkit.FakeTermuxRunner
import com.aicontrolcenter.testkit.FakeWebReader
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import com.aicontrolcenter.testkit.InMemoryOperatorAppRepository
import com.aicontrolcenter.testkit.InMemoryOwnerRequestRepository
import com.aicontrolcenter.testkit.InMemoryPermissionRepository
import com.aicontrolcenter.testkit.InMemorySharedFolderRepository
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
import com.aicontrolcenter.testkit.InMemoryMessageRepository
import com.aicontrolcenter.testkit.InMemoryTaskRepository
import com.aicontrolcenter.app.work.InMemoryNotificationAccess
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
    val tasks = InMemoryTaskRepository()
    val messages = InMemoryMessageRepository()

    /** Notifications allowed, never asked: a test scripts a refusal with `set(false)`. */
    val notifications = InMemoryNotificationAccess()
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

    // --- the phone operator ---
    val permissions = InMemoryPermissionRepository()

    /** Decides into [permissions], as Room writes the grant in the same transaction. */
    val ownerRequestStore = InMemoryOwnerRequestRepository(permissions)
    val sharedFolders = InMemorySharedFolderRepository()
    val operatorApps = InMemoryOperatorAppRepository()
    val workspaceRoot = FakeWorkspaceRoot()
    val folderAccess = FakeSharedFolders(sharedFolders)
    val media = FakeMediaLibrary()
    val appActions = FakeAppActions()

    /** Pages served by exact URL; every host a read reaches is reported as sent, like the real reader does. */
    val web = FakeWebReader()

    /** No app in front and nothing authorised until a test sets [FakeScreenOperator.screen] and [FakeScreenOperator.authorised]. */
    val screen = FakeScreenOperator()
    val shell = FakeShellRunner()

    /** What the start-up sweep finds and stops: processes a shell command left when the app died under it. */
    @Volatile var shellLeftovers: Int = 0

    /** How often the start-up sweep ran. */
    val shellSweeps = java.util.concurrent.atomic.AtomicInteger()

    /** Called during the start-up sweep, so a test can see what the steps after it have not done yet. */
    @Volatile var duringShellSweep: (() -> Unit)? = null
    val termux = FakeTermuxRunner()
    val protectedPackages = ProtectedPackages(ownPackage = OWN_PACKAGE)

    /** Plain intents a test can recognise, for Android's settings pages and the share sheet. */
    val systemScreens = TestSystemScreens()

    /** Signing certificates the scanner reports for apps it lists without one (as a phone would for any installed app). */
    val certificates = ConcurrentHashMap<String, String>()

    // --- Phase 6 ---

    /**
     * The owner's time zone, UTC unless a test moves it: one "today" for the usage store, the daily limiter, the
     * orchestrator, the routines and the usage screens (wave 3), as production has one system zone.
     */
    @Volatile var zone: java.time.ZoneId = java.time.ZoneOffset.UTC
    private val ownerZone: () -> java.time.ZoneId = { zone }

    /** Usage records, reports and the owner's history in one store, as Room keeps them in one database. */
    val usage = InMemoryUsageStore(clock, zone = ownerZone)

    /** The owner's prices; agent-facing code gets [ModelPrices] through a read-only view, as in production. */
    val prices = InMemoryModelPrices(clock)

    /** Whether Android lets the app work in the background: unknown until a test sets it. */
    val backgroundHealth = FakeBackgroundHealth()

    /** The app's earlier exits: none until a test adds some. */
    val exits = FakeExitHistory()

    /** Plenty of free room until a test sets [FakeStorageWatch.freeBytes]. */
    val storage = FakeStorageWatch()

    /** A network until a test takes it away. */
    val network = FakeNetworkStatus()

    /** The database opened normally until a test says otherwise. */
    val recovery = FakeOwnerRecovery()

    /** Safe start off until a test turns it on ([TestSafeStart.state]). */
    val safeStart = TestSafeStart()
    val routineStore = InMemoryRoutineRepository()
    val pickUpRuleStore = InMemoryPickUpRuleRepository()
    val maintenanceGate = LocalMaintenanceGate()

    val ports = Ports(
        agents = agents,
        providers = providers,
        models = models,
        aiApps = aiApps,
        conversations = conversations,
        memories = memories,
        tasks = tasks,
        messages = messages,
        activityLog = activityLog,
        settings = this.settings,
        secretVault = vault,
        aiAppScanner = object : AiAppScanner {
            override suspend fun scanForAiApps() = failOr { scanner.scanForAiApps() }
            override suspend fun listInstalledApps() = failOr { scanner.listInstalledApps() }
            override suspend fun inspect(packageName: String) = failOr {
                scanner.inspect(packageName)?.let { app -> app.copy(signingCertSha256 = app.signingCertSha256 ?: certificates[packageName]) }
            }
        },
        aiAppLauncher = launcher,
        deviceInspector = deviceInspector,
        operator = OperatorPorts(
            permissions = permissions,
            ownerRequests = ownerRequestStore,
            sharedFolders = sharedFolders,
            operatorApps = operatorApps,
            workspaceRoot = workspaceRoot,
            folderAccess = folderAccess,
            media = media,
            appActions = appActions,
            webReader = { onRequestSent -> ReportingWebReader(web, onRequestSent) },
            screenOperator = screen,
            // A separate object, as the device's: nothing agent-facing holding the screen can stop or resume.
            screenControl = screen.asControl(),
            shell = shell,
            stopShellLeftovers = {
                shellSweeps.incrementAndGet()
                duringShellSweep?.invoke()
                shellLeftovers.also { shellLeftovers = 0 }
            },
            termux = termux,
            // Captures the set, not this test app (which holds the owner's services).
            protectedPackages = protectedPackages.let { fixed -> { fixed } },
            ownPackage = OWN_PACKAGE,
            systemScreens = systemScreens,
        ),
        clock = clock,
        idGenerator = SequentialIdGenerator(),
        packageChanges = packageChanges,
        // Narrow views, as production's Room classes are one per port: agent-facing code never holds the owner's side.
        usageLedger = UsageLedgerView(usage),
        usageReports = UsageReportsView(usage),
        modelPrices = ModelPricesView(prices),
        ownerModelPrices = prices,
        ownerUsage = usage,
        background = BackgroundPorts(health = backgroundHealth, exits = exits, storage = storage, recovery = recovery, safeStart = safeStart),
        network = network,
        routines = RoutinesPorts(routines = routineStore, pickUpRules = pickUpRuleStore, zone = ownerZone),
        maintenanceGate = maintenanceGate,
        zone = ownerZone,
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
        notifications = notifications,
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
        const val OWN_PACKAGE = "com.aicontrolcenter.hq"

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

/** The ledger side of [InMemoryUsageStore] alone, as `RoomUsageLedger` is in production. */
class UsageLedgerView(store: InMemoryUsageStore) : UsageLedger by store

/** The reports side of [InMemoryUsageStore] alone, as `RoomUsageReports` is in production. */
class UsageReportsView(store: InMemoryUsageStore) : UsageReports by store

/** The read side of [InMemoryModelPrices] alone, as `DataLayer.modelPrices` is in production. */
class ModelPricesView(store: InMemoryModelPrices) : ModelPrices by store

/**
 * [FakeWebReader] that reports each host a page read reached to [onRequestSent], as the real
 * reader reports every request it sends.
 */
class ReportingWebReader(private val pages: FakeWebReader, private val onRequestSent: (EgressInfo, EgressOrigin?) -> Unit) : WebReader {
    override suspend fun read(url: String, limits: WebReadLimits): WebReadResult {
        // As the real reader does: the origin is read in the caller's coroutine and handed back with every report.
        val origin = EgressOrigin.current()
        return pages.read(url, limits).also { result ->
            (result as? WebReadResult.Page)?.hostsContacted?.forEach { host ->
                onRequestSent(EgressInfo(host, Locality.CLOUD, purpose = "web page request (the page address and standard headers)"), origin)
            }
        }
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

/** Safe start a test turns on and off; [leave] is the owner's "Back to normal" and is counted. */
class TestSafeStart : com.aicontrolcenter.app.health.SafeStartControl {
    override val state = kotlinx.coroutines.flow.MutableStateFlow(com.aicontrolcenter.app.health.SafeStartState.OFF)

    /** How often the owner chose to leave safe start. */
    val left = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun leave() {
        left.incrementAndGet()
        state.value = com.aicontrolcenter.app.health.SafeStartState.OFF
    }
}

/** [SystemScreens] whose intents name what they stand for, so a test can check which screen the app asked Android for. */
class TestSystemScreens : SystemScreens {
    /** What [accessState] reports; everything is off until a test turns it on. */
    val access: MutableMap<DeviceAccess, DeviceAccessState> = java.util.concurrent.ConcurrentHashMap()

    override fun accessibilitySettings(): Intent = Intent(ACCESSIBILITY_SETTINGS)

    override fun appInfo(): Intent = Intent(APP_INFO)

    override fun shareFile(file: File, mimeType: String, title: String): Intent =
        Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(SHARED_FILE, file.path).putExtra(Intent.EXTRA_TITLE, title)

    override fun accessState(access: DeviceAccess): DeviceAccessState = this.access[access] ?: DeviceAccessState.NOT_ALLOWED

    override fun accessPermissions(access: DeviceAccess): List<String> = when (access) {
        DeviceAccess.MEDIA -> listOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        DeviceAccess.TERMUX_RUN_COMMAND -> listOf("com.termux.permission.RUN_COMMAND")
        else -> emptyList()
    }

    override fun accessSettings(access: DeviceAccess): Intent? =
        if (access == DeviceAccess.ACCESSIBILITY_SERVICE) null else Intent(ACCESS_SETTINGS).putExtra(ACCESS, access.name)

    /** What [storageAppName] answers, by a folder's tree URI; nothing until a test sets it (Android does not tell). */
    val storageApps: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    override fun storageAppName(treeUri: String): String? = storageApps[treeUri]

    companion object {
        const val ACCESSIBILITY_SETTINGS = "test.ACCESSIBILITY_SETTINGS"
        const val APP_INFO = "test.APP_INFO"
        const val SHARED_FILE = "test.SHARED_FILE"
        const val ACCESS_SETTINGS = "test.ACCESS_SETTINGS"
        const val ACCESS = "test.ACCESS"
    }
}
