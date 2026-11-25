package com.aicontrolcenter.app.di.wiring

import com.aicontrolcenter.app.di.Ports
import com.aicontrolcenter.app.operator.AuthorisedAppsView
import com.aicontrolcenter.app.operator.EgressLog
import com.aicontrolcenter.app.operator.FolderDirectoryView
import com.aicontrolcenter.app.operator.GrantLookupView
import com.aicontrolcenter.app.operator.RequestQueueView
import com.aicontrolcenter.app.operator.ScreenSessions
import com.aicontrolcenter.app.operator.SharedFolderFilesView
import com.aicontrolcenter.app.operator.SystemScreens
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.llm.OwnerKeyCheck
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.operator.GrantLookup
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.WebReader
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.core.registry.AiAppScanner
import com.aicontrolcenter.core.registry.ReadOnlyRegistry
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.framework.memory.MemoryTools
import com.aicontrolcenter.framework.operator.AnsweredQuestions
import com.aicontrolcenter.framework.operator.DefaultCapabilityRequester
import com.aicontrolcenter.framework.operator.DefaultOwnerFolders
import com.aicontrolcenter.framework.operator.DefaultOwnerGrants
import com.aicontrolcenter.framework.operator.DefaultOwnerOperatorApps
import com.aicontrolcenter.framework.operator.DefaultOwnerRequests
import com.aicontrolcenter.framework.operator.JsEngine
import com.aicontrolcenter.framework.operator.JsSandbox
import com.aicontrolcenter.framework.operator.LocalWorkspace
import com.aicontrolcenter.framework.operator.OperatorEgress
import com.aicontrolcenter.framework.operator.OperatorFacts
import com.aicontrolcenter.framework.operator.OperatorTaskHooks
import com.aicontrolcenter.framework.operator.OperatorToolDependencies
import com.aicontrolcenter.framework.operator.OperatorToolset
import com.aicontrolcenter.framework.operator.OwnerAttentionGate
import com.aicontrolcenter.framework.operator.OwnerQuestions
import com.aicontrolcenter.framework.operator.OwnerWaitLedger
import com.aicontrolcenter.framework.operator.PermissionBroker
import com.aicontrolcenter.framework.operator.PermissionRequestTool
import com.aicontrolcenter.framework.operator.RecentFileChanges
import com.aicontrolcenter.framework.operator.RequestWaiters
import com.aicontrolcenter.framework.orchestration.TeamTools
import com.aicontrolcenter.framework.tools.BuiltInTools
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The phone operator: the permission broker, the tools and the owner's ports (Phase 6: WP5 owns this file in wave 2;
 * broker, questions and phone-operator fixes). Moved out of AppGraph unchanged in wave 1 (WP1d); AppGraph keeps its
 * public members as delegates.
 */

/**
 * The phone operator, the tool registry, the executor and the owner's operator ports.
 *
 * @param ports the app's stores and gateways. Read here only, never kept: the agent-facing objects built here get
 *   narrow views, and the owner's ports the full stores.
 * @param settings read-only: the permission checks and the tools are agent-facing (rule 3).
 * @param activity where every decision and every tool call is logged.
 * @param appScope lives as long as the process.
 * @param registryDirectory the registry as agents see it (the built-in tools read it).
 * @param memory the one memory service of the process (the memory tools use it).
 * @param ownerKeyCheck owner-only (WP5: answering a question checks the answer against the stored keys, rule 32). Only
 *   the owner's request port (`OwnerServices.answer`) may hold it, never the broker, the tools or the question tool
 *   (security review of wave 1, F3).
 */
class OperatorAssembly(
    ports: Ports,
    settings: SettingsView,
    activity: ActivityRecorder,
    appScope: CoroutineScope,
    registryDirectory: RegistryDirectory,
    memory: MemoryService,
    ownerKeyCheck: OwnerKeyCheck,
) {
    private val tasks = ports.tasks
    private val messages = ports.messages
    private val activityRecorder = activity
    private val ids = ports.idGenerator
    private val clock = ports.clock

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
    val screenSessions = ScreenSessions(ports.operator.screenOperator, AuthorisedAppsView(ports.operator.operatorApps), appScope)

    /** Owner-only: the Stop and Resume of screen operation. */
    val screenControl: ScreenControl = ports.operator.screenControl

    /** What the broker, the requester and the task hooks hold: raise, watch and close requests, never answer them. */
    private val requestQueue: OwnerRequestQueue = RequestQueueView(ports.operator.ownerRequests)

    /** Find, use up and narrow grants, never give one. */
    private val grantLookup: GrantLookup = GrantLookupView(ports.operator.permissions)

    /**
     * Which questions to the owner a live call of this process (or an agent's ask raised in it)
     * still waits for. A question nothing waits for can never be answered: the owner's port
     * closes it instead, and start-up withdraws exactly those.
     */
    internal val requestWaiters = RequestWaiters()

    /**
     * The one lock and the caps every request to the owner is raised under (Phase 6, rules 13 and 14, C3, D10): the
     * broker's, the capability requester's and the question tool's, so concurrent asks cannot pass a cap together.
     */
    private val attention = OwnerAttentionGate(requestQueue, requestWaiters, activity)

    /** How long agents waited for the owner (D12): the bulk rule's window does not run on while they wait. */
    private val ownerWaits = OwnerWaitLedger()

    /**
     * The tasks and objectives whose agents got the owner's answer to a question (rule 32): their next call off the
     * phone asks first. After a restart, an objective's stored answers count again (read once per objective).
     */
    private val answeredQuestions = AnsweredQuestions(messages)

    /** The owner's folders and authorised apps, read only, for the permission checks and the tools. */
    private val folderDirectory = FolderDirectoryView(ports.operator.sharedFolders)
    private val authorisedApps = AuthorisedAppsView(ports.operator.operatorApps)

    /** Reads web pages for `web.read_page`; every request that leaves the phone is logged as it is sent. */
    private val webReader: WebReader = ports.operator.webReader(EgressLog(activity, appScope)::sent)

    // --- tools ---

    /** The capabilities tools need. Declared before the tools, which register theirs into it. */
    val capabilities = CapabilityRegistry().also { registry -> OperatorCapabilities.all.forEach(registry::register) }

    /**
     * The facts every permission check reads fresh: the agent as stored, the settings, Stop, the
     * folder or app a call targets and the protected apps. Read-only.
     */
    private val operatorFacts = OperatorFacts(
        agents = ReadOnlyRegistry.agents(ports.agents),
        settings = settings,
        capabilities = capabilities,
        folders = folderDirectory,
        apps = authorisedApps,
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
        waiters = requestWaiters,
        tasks = ports.tasks,
        activity = activity,
        ids = ports.idGenerator,
        clock = ports.clock,
        attention = attention,
        ownerWaits = ownerWaits,
        answeredQuestions = answeredQuestions,
    )

    /** How an agent asks the owner for a permission itself (`permissions.request`). It never waits and can grant nothing. */
    private val capabilityRequester = DefaultCapabilityRequester(
        operatorFacts, requestQueue, grantLookup, requestWaiters, activity, ports.idGenerator, ports.clock,
        // A routine's objective: no screens without the owner's tick, and its own time-out (F12).
        tasks = ports.tasks,
        attention = attention,
    )

    /** Every tool agents can be given. The team tools are added once the orchestrator exists (TeamAssembly). */
    val toolRegistry: ToolRegistry = DefaultToolRegistry().also { registry ->
        BuiltInTools.registerAll(registry, ports.clock, registryDirectory)
        MemoryTools.registerAll(registry, memory, capabilities, ReadOnlyRegistry.agents(ports.agents), ports.clock)
        // The phone operator's tools get gateways, the workspace and read-only lookups; nothing owner-only.
        val dependencies = OperatorToolDependencies(
            workspace = workspace,
            sharedFolders = SharedFolderFilesView(ports.operator.folderAccess),
            folders = folderDirectory,
            media = ports.operator.media,
            apps = ports.operator.appActions,
            web = webReader,
            screen = screenSessions,
            authorisedApps = authorisedApps,
            // Read-only: the screen tools check the operator switches and local-only mode before they look at a screen.
            // A reference to the read-only view's own `get`, so the tools never hold the settings writer (rule 3).
            settings = settings::get,
            protectedPackages = protectedPackages,
            shell = ports.operator.shell,
            termux = ports.operator.termux,
            clock = ports.clock,
            ownPackage = ports.operator.ownPackage,
            // Commands and links that may send data off the phone are logged as data transfers when they run.
            egress = OperatorEgress.logTo(activity),
            // The bulk rule's window does not run on while an agent waits for the owner (D12).
            recentChanges = RecentFileChanges(ownerWaits = ownerWaits),
        )
        // JavaScript runs in the engine the scripts package provides (WP6: its own process), else in this process.
        val js: JsEngine = ports.scripts.engine?.invoke(dependencies.limits.js) ?: JsSandbox(dependencies.limits.js)
        OperatorToolset.create(dependencies, js).forEach(registry::register)
        PermissionRequestTool.register(registry, capabilityRequester)
    }

    /** Runs every tool call through [broker]. Internal so the architecture test can walk what it reaches. */
    internal val toolExecutor = ToolExecutor(
        registry = toolRegistry,
        authorizer = broker,
        activity = activity,
        clock = ports.clock,
        // Time a call waits for the owner outside the broker (Android's install confirmation, the wait for an operated
        // app after an approval) is written to the task's stored deadline, as the broker writes its own waits.
        tasks = ports.tasks,
    )

    /** The broker, for the architecture test. */
    internal val permissionBroker: PermissionBroker get() = broker

    /**
     * Start-up, task-end, expiry and Stop upkeep of the questions to the owner. It can close
     * questions, never answer one. The orchestrator gets it (TeamAssembly).
     */
    internal val operatorHooks = OperatorTaskHooks(requestQueue, grantLookup, requestWaiters, activity, ports.clock)

    // --- what only the owner's screens may use ---

    /**
     * Pending and past questions to the owner, and the only way to answer them. It takes an answer
     * only while a call still waits for it and what it is about is still there.
     */
    val ownerRequests: OwnerRequests = DefaultOwnerRequests(
        ports.operator.ownerRequests, requestWaiters, operatorFacts, activity, ports.idGenerator, ports.clock,
        // Rule 32: an answer holding a stored provider key is never sent. Only this owner port holds the check (F3).
        keyCheck = ownerKeyCheck,
    )

    /** What each agent has been granted; giving and revoking grants. */
    val ownerGrants: OwnerGrants = DefaultOwnerGrants(
        grants = ports.operator.permissions,
        agents = ports.agents,
        capabilities = capabilities,
        folders = ports.operator.sharedFolders,
        apps = ports.operator.operatorApps,
        protectedPackages = protectedPackages,
        // A direct grant lets calls waiting for it go on; a revocation withdraws the questions that relied on it.
        requests = ports.operator.ownerRequests,
        waiters = requestWaiters,
        activity = activity,
        ids = ports.idGenerator,
        clock = ports.clock,
    )

    /** The folders the owner shares with agents. Removing one withdraws the questions about it; making one writable stops its pick-up. */
    val ownerFolders: OwnerFolders = DefaultOwnerFolders(
        ports.operator.folderAccess, ports.operator.sharedFolders, ports.operator.permissions, requestQueue, activity, ports.idGenerator, ports.clock,
        // Making a folder writable switches folder pick-up from it off (security review of wave 1, F1).
        pickUpRules = ports.routines.pickUpRules,
    )

    /** The apps the owner authorised for screen operation. Withdrawing one withdraws the questions about it. */
    val ownerOperatorApps: OwnerOperatorApps =
        DefaultOwnerOperatorApps(ports.operator.operatorApps, ports.operator.permissions, protectedPackages, requestQueue, activity, ports.clock)

    /** Android's settings screens and the share sheet, for the owner's Tools and Workspace screens. */
    val systemScreens: SystemScreens = ports.operator.systemScreens

    /** The phone's launchable apps and their signing certificates, for authorising an app for screen control. */
    val installedApps: AiAppScanner = ports.aiAppScanner

    /** Whether agents never operate [packageName] (system settings, installers, this app); the Screen control picker marks them. */
    fun isProtectedPackage(packageName: String): Boolean = protectedPackages().isProtected(packageName)

    /**
     * The agent's side of a question to the owner (`team.ask_owner`, Phase 6, C1, Tier B): it raises a QUESTION and waits
     * as a tool call waits for an approval, under the same lock and caps; it can answer, grant or approve nothing. The
     * owner answers through [ownerRequests].
     */
    val ownerQuestions = OwnerQuestions(
        requests = requestQueue, waiters = requestWaiters, tasks = tasks, messages = messages, facts = operatorFacts,
        activity = activityRecorder, ids = ids, clock = clock, attention = attention, ownerWaits = ownerWaits, answered = answeredQuestions,
    )

    /**
     * Called once by the composition root, right after the orchestrator exists and its team tools are registered,
     * before any agent runs: registers `team.ask_owner`, which work tasks offer while the owner allows agents' questions.
     */
    fun teamReady(@Suppress("UNUSED_PARAMETER") orchestrator: Orchestrator) {
        TeamTools.registerAskOwner(toolRegistry, capabilities, ownerQuestions)
    }

    init {
        // The owner's Stop (here, on the banner or in the notification) withdraws the questions to see or use a screen:
        // every screen call is refused until the owner resumes. Only a change to stopped counts, not the value read at start-up.
        appScope.launch {
            var before: Boolean? = null
            screenSessions.stopped.collect { stopped ->
                if (stopped && before == false) {
                    try {
                        operatorHooks.screenStopped()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The broker refuses those calls after any answer; the questions then close with their calls.
                    }
                }
                before = stopped
            }
        }
    }
}
