package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.operator.SystemScreens
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.RandomIdGenerator
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.device.DeviceInspector
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.operator.AppActions
import com.aicontrolcenter.core.operator.MediaLibrary
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.operator.SharedFolders
import com.aicontrolcenter.core.operator.ShellRunner
import com.aicontrolcenter.core.operator.TermuxRunner
import com.aicontrolcenter.core.operator.WebReader
import com.aicontrolcenter.core.operator.WorkspaceRoot
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.registry.AiAppLauncher
import com.aicontrolcenter.core.registry.AiAppRepository
import com.aicontrolcenter.core.registry.AiAppScanner
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Everything the app needs from outside the framework, expressed only as `:core` interfaces.
 *
 * This is the seam between the app and its platform: production fills it with the Room,
 * DataStore, Keystore and PackageManager implementations (see `createProductionPorts`), tests
 * fill it with the in-memory implementations from `:testkit`. Nothing above this type knows
 * which one it got.
 */
data class Ports(
    val agents: AgentRepository,
    val providers: ProviderRepository,
    val models: ModelRepository,
    val aiApps: AiAppRepository,
    /** Direct conversations between the owner and an agent, with every message and tool result. */
    val conversations: ConversationRepository,
    /**
     * Agents' private memories, shared knowledge spaces and task memory, with their links and
     * search vectors. Only the memory service talks to it; screens go through the service, which
     * checks who may see what.
     */
    val memories: MemoryRepository,
    /**
     * Objectives and the tasks the Director splits them into, with statuses, results and
     * reviews. The orchestrator writes every change here before acting on it, so this store is
     * what recovery after a crash starts from.
     */
    val tasks: TaskRepository,
    /** Every message agents (and the owner and the orchestrator) exchanged about an objective. */
    val messages: MessageRepository,
    val activityLog: ActivityLog,
    val settings: SettingsRepository,
    val secretVault: SecretVault,
    val aiAppScanner: AiAppScanner,
    val aiAppLauncher: AiAppLauncher,
    val deviceInspector: DeviceInspector,
    /** The phone operator's stores and gateways. */
    val operator: OperatorPorts,
    val clock: Clock = WallClock,
    val idGenerator: IdGenerator = RandomIdGenerator,
    /**
     * Emits once whenever another app is installed, removed, updated, enabled or disabled.
     * The payload is deliberately empty: the registry re-checks every registered app, so which
     * package changed does not matter. Collected for as long as the app process lives.
     */
    val packageChanges: Flow<Unit> = emptyFlow(),
)

/**
 * What the phone operator needs from storage and the platform: the grant and request stores,
 * the shared-folder and authorised-app lists, and the gateways its tools act through.
 *
 * Some of it is the owner's alone. [AppGraph] hands [screenControl] and the full [permissions]
 * and [ownerRequests] stores to the owner's services only (what the screens use to answer,
 * grant and stop); the permission broker, the tools and the agents get narrow views that can
 * raise, read and narrow, never decide.
 */
class OperatorPorts(
    /** Every grant the owner gave an agent. */
    val permissions: PermissionRepository,
    /** Questions to the owner: what an agent asked for and what the owner answered. */
    val ownerRequests: OwnerRequestRepository,
    /** The folders the owner picked for agents. */
    val sharedFolders: SharedFolderRepository,
    /** The apps the owner authorised for screen operation. */
    val operatorApps: OperatorAppRepository,
    /** The agents' workspace folder, inside this app. */
    val workspaceRoot: WorkspaceRoot,
    /** Reading and writing in the owner's picked folders. */
    val folderAccess: SharedFolders,
    val media: MediaLibrary,
    val appActions: AppActions,
    /**
     * Builds the one web page reader of the process. [onRequestSent][webReader] is told of every
     * request that leaves the phone, also when the read then fails; the graph logs each one.
     */
    val webReader: (onRequestSent: (EgressInfo) -> Unit) -> WebReader,
    /** Reads and drives authorised apps' screens. Agent-facing: it cannot stop or resume itself. */
    val screenOperator: ScreenOperator,
    /** The owner's Stop and Resume. Owner-only. */
    val screenControl: ScreenControl,
    val shell: ShellRunner,
    val termux: TermuxRunner,
    /** The apps that are never operated, resolved once at start-up and read on every check. */
    val protectedPackages: () -> ProtectedPackages,
    /** This app's package, for link checks. */
    val ownPackage: String?,
    /**
     * Reads Android's accessibility settings again. Android 9 to 12 send no signal when the owner
     * switches the service on, so the graph calls it whenever the app comes back to the screen.
     */
    val refreshScreenState: () -> Unit = {},
    /** Android's settings screens and share sheet, for the owner's Tools and Workspace screens only. */
    val systemScreens: SystemScreens,
)
