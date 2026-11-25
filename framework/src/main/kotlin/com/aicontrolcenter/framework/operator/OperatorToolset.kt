package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.operator.AppActions
import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.MediaLibrary
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.SharedFolderFiles
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.operator.ShellRunner
import com.aicontrolcenter.core.operator.TermuxRunner
import com.aicontrolcenter.core.operator.WebReadLimits
import com.aicontrolcenter.core.operator.WebReader
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tools.Tool

/**
 * The shared folders the owner picked, read-only: what folder tools need to turn the folder an
 * agent names into its id, and what the permission checks read. Never the repository itself,
 * which can add and remove folders.
 */
fun interface FolderDirectory {
    suspend fun all(): List<SharedFolder>

    /** The folder with [id] as stored now, or null. */
    suspend fun get(id: FolderId): SharedFolder? = all().firstOrNull { it.id == id }
}

/** The apps authorised for screen operation, read-only: the screen tools' and the permission checks' lookup. */
fun interface AuthorisedApps {
    suspend fun get(packageName: String): OperatorApp?
}

/**
 * [FolderDirectory] over the repository, exposing reads only. The app wires its own named view
 * (its architecture test allows a full store only inside those); tests use this one.
 */
fun SharedFolderRepository.asDirectory(): FolderDirectory = object : FolderDirectory {
    override suspend fun all(): List<SharedFolder> = getAll()

    override suspend fun get(id: FolderId): SharedFolder? = this@asDirectory.get(id)
}

/** [AuthorisedApps] over the repository, exposing reads only; see [asDirectory]. */
fun OperatorAppRepository.asAuthorisedApps(): AuthorisedApps = AuthorisedApps { get(it) }

/** Bounds the operator tools apply on top of the gateways' own. */
data class OperatorLimits(
    /** Default and largest read of a workspace or folder file, in bytes. */
    val readBytes: Int = 32_000,
    val maxReadBytes: Int = 200_000,
    /** Largest file workspace.replace_text edits (it is read whole). */
    val maxEditBytes: Long = 4L * 1024 * 1024,
    /** Largest text an agent writes in one call. */
    val maxWriteChars: Int = 1_000_000,
    /** Largest file copied into or out of the workspace, and largest file hashed for an exact preview. */
    val maxCopyBytes: Long = 50L * 1024 * 1024,
    /** Entries listed in one answer. */
    val maxListed: Int = 500,
    /** Entries counted when previewing the deletion of a shared folder's subfolder. */
    val maxCountedEntries: Int = 2_000,
    val web: WebReadLimits = WebReadLimits(),
    /** Default and largest page text returned. */
    val pageChars: Int = 20_000,
    val maxPageChars: Int = 100_000,
    val command: CommandLimits = CommandLimits(),
    /** Largest command time limit an agent may ask for (below the executor's own per-call limit). */
    val maxCommandMs: Long = 55_000,
    val js: JsLimits = JsLimits(),
    val maxScreenElements: Int = 300,
    val maxWaitMs: Long = 30_000,
    /**
     * After the owner answers a request for a screen action (in this app), how long the action
     * waits for the app it operates to be in front again. Time the owner spends, so it counts
     * against no time limit.
     */
    val screenReturnMs: Long = 30_000,
    /** Once that app is back, how long its window may take to settle into what was approved before the action is refused. */
    val screenSettleMs: Long = 3_000,
    /** How often the app in front and its window are looked at while waiting. */
    val screenPollMs: Long = 250,
    /** How long a screen tool waits for an app the same agent just opened to come to the front ([RecentLaunches]). */
    val screenOpenWaitMs: Long = 10_000,
    /** How recent a launch must be for a screen tool to wait for that app ([screenOpenWaitMs]). */
    val launchRecentMs: Long = 15_000,
)

/** Everything the operator tools use: gateways, the workspace and read-only lookups. Nothing owner-only. */
class OperatorToolDependencies(
    val workspace: Workspace,
    /** Files inside the owner's folders; never adding, removing or releasing a folder. */
    val sharedFolders: SharedFolderFiles,
    val folders: FolderDirectory,
    val media: MediaLibrary,
    val apps: AppActions,
    val web: WebReader,
    val screen: ScreenOperator,
    val authorisedApps: AuthorisedApps,
    /**
     * The owner's settings, read fresh on every screen call before anything of a screen is
     * looked at: the operator switches and local-only mode.
     */
    val settings: suspend () -> FrameworkSettings,
    /** Read on every call: `:device` resolves the device's protected handlers at start-up. */
    val protectedPackages: () -> ProtectedPackages,
    val shell: ShellRunner,
    val termux: TermuxRunner,
    val clock: Clock,
    /** This app's package, for link checks. */
    val ownPackage: String?,
    val limits: OperatorLimits = OperatorLimits(),
    /** Where hand-overs that may leave the phone (commands, links) are logged as they happen. */
    val egress: OperatorEgress = OperatorEgress.NONE,
    /** The files each agent changed recently: the bulk rule counts over a time window, not per call. */
    val recentChanges: RecentFileChanges = RecentFileChanges(),
    /** The file versions each task wrote itself a short while ago: an edit does not keep those in the trash again. */
    val ownVersions: OwnVersions = OwnVersions(),
    /** The app each agent opened last: a screen tool waits a short while for an app that is still opening. */
    val launches: RecentLaunches = RecentLaunches(),
)

/**
 * Builds every operator tool of [com.aicontrolcenter.core.operator.OperatorTools.all] except
 * `permissions.request` (it depends only on `CapabilityRequester` and is built with the broker).
 * Each tool's spec is its catalogue entry.
 */
object OperatorToolset {
    /** [js] runs `scripts.run_js`; by default the Rhino sandbox in this process, with the limits of [d]. */
    fun create(d: OperatorToolDependencies, js: JsEngine = JsSandbox(d.limits.js)): List<Tool> =
        WorkspaceTools.create(d) + FolderTools.create(d) + MediaTools.create(d) + AppTools.create(d) +
            WebTools.create(d) + ScreenTools.create(d) + ScriptTools.create(d, js)
}
