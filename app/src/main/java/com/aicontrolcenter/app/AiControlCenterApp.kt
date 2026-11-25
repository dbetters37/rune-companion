package com.aicontrolcenter.app

import android.app.Application
import android.content.Context
import android.os.Process
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.di.createProductionPorts
import com.aicontrolcenter.app.di.wiring.BackgroundPorts
import com.aicontrolcenter.app.di.wiring.BackgroundWiring
import com.aicontrolcenter.app.di.wiring.PickUpStepPorts
import com.aicontrolcenter.app.di.wiring.RoutinesWiring
import com.aicontrolcenter.app.health.AppGate
import com.aicontrolcenter.app.operator.PickUpFoldersView
import com.aicontrolcenter.app.operator.SharedFolderFilesView
import com.aicontrolcenter.app.usage.DayChanges
import com.aicontrolcenter.app.work.AndroidNotificationAccess
import com.aicontrolcenter.app.work.AppProcessHooks
import com.aicontrolcenter.app.work.ScreenSessionNotifications
import com.aicontrolcenter.app.work.UpkeepGraph
import com.aicontrolcenter.app.work.UpkeepHost
import com.aicontrolcenter.app.work.UpkeepPorts
import com.aicontrolcenter.core.RandomIdGenerator
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.memory.Embedder
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.device.operator.PhoneOperatorLayer
import com.aicontrolcenter.memory.DefaultMemoryService
import com.aicontrolcenter.memory.HashingEmbedder
import com.aicontrolcenter.memory.MemoryConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.ZoneId

/**
 * Owns the process-wide [AppGraph] and the few things the whole main process shares with it.
 *
 * The graph is built on first use rather than in [onCreate], for two reasons: opening the
 * database and the keystore is then paid for by the first screen that needs them instead of by
 * every process start (including starts for a broadcast or a job), and a test can [installGraph]
 * one built on in-memory ports before anything asks for the production one.
 *
 * Shared without the graph (Phase 6): one [dataLayer] (one Room instance on the encrypted file:
 * two would have separate invalidation trackers), one [sharedMemory] service (it serialises its
 * writes in-process), one [maintenanceGate], and the [background] port whose Recovery path and
 * gate never build the graph. The upkeep job reaches only [upkeep] (rule 1).
 *
 * The application object is also created in the isolated script process (`:scripts`), where it
 * must do nothing: every member is built on first use, and [onCreate] returns at once there.
 */
class AiControlCenterApp : Application(), UpkeepHost {
    /** Lives as long as the process. A failing child never cancels the others. Built on first use (never in `:scripts`). */
    private val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + AppProcessHooks.scopeContext(this))
    }

    @Volatile
    private var installedGraph: AppGraph? = null

    @Volatile
    private var installedGate: AppGate? = null

    override fun onCreate() {
        super.onCreate()
        // The isolated script process gets this application object too, with no access to system services, files
        // or the database: nothing below may run there (a notification call already throws in it).
        if (Process.isIsolated()) return
        // Debug builds only (D45): every disk read or write on the main thread, and every leak StrictMode sees, is
        // written to the log (never a crash, never a dialog), so main-thread work at a start is found.
        DebugStrictMode.install()
        // Screen sessions live in this process only: an "is operating" notification still showing now was left by a
        // process that died mid-session, and nothing is operating any more.
        ScreenSessionNotifications.cancel(this)
        AppProcessHooks.onCreate(this)
    }

    /** The process's one data layer; building it opens nothing (the database opens on first use, off the main thread). */
    val dataLayer: DataLayer by lazy { DataLayer(this) }

    /** Tunables of the memory engine; the version is written into exported memory graphs. */
    private val memoryConfig = MemoryConfig(appVersion = BuildConfig.VERSION_NAME)

    /** The offline embedder of [sharedMemory]: nothing a memory says leaves the phone to be indexed. */
    private val embedder: Embedder = HashingEmbedder()

    /**
     * The one memory service of the process, shared by the graph and the upkeep job (the service serialises writes
     * with an in-process lock, so there must never be a second one). It reads the settings through a read-only view:
     * the memory tools reach it (rule 3).
     */
    val sharedMemory: MemoryService by lazy {
        DefaultMemoryService(
            repository = dataLayer.memories,
            agents = dataLayer.agents,
            settings = ReadOnlySettings(dataLayer.settings),
            embedder = embedder,
            activity = ActivityRecorder(dataLayer.activityLog, RandomIdGenerator, WallClock),
            ids = RandomIdGenerator,
            clock = WallClock,
            config = memoryConfig,
            computeContext = Dispatchers.Default,
        )
    }

    /** The process's one maintenance gate: a restore runs inside it, and new work and the upkeep job wait for it. */
    val maintenanceGate: MaintenanceGate by lazy { LocalMaintenanceGate() }

    /** Background work and recovery (WP2), built without the graph. */
    val background: BackgroundPorts by lazy { BackgroundWiring.production(this, dataLayer) }

    /** Owner-only: the database check and Start over, for the gate and the Recovery screen (never through the graph). */
    val recovery: OwnerRecovery? get() = background.recovery

    /**
     * What the upkeep job may reach: never the graph, a model, a tool or a provider (rule 1). Built here from narrow
     * ports only (security review of wave 1, F11): neither the job nor its steps get the data layer, a context or
     * this application object, through which they could reach the owner's stores or build the graph.
     */
    override val upkeep: UpkeepGraph by lazy {
        val settings = ReadOnlySettings(dataLayer.settings)
        val zone = { ZoneId.systemDefault() }
        val ports = UpkeepPorts(
            memory = sharedMemory,
            activityLog = dataLayer.activityLog,
            usageHousekeeping = dataLayer.usageHousekeeping,
            settings = settings,
            gate = maintenanceGate,
            clock = WallClock,
            zone = zone,
            noBackupFilesDir = noBackupFilesDir,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
        )
        val pickUp = pickUpStepPorts(this, dataLayer, sharedMemory, settings, maintenanceGate, zone)
        UpkeepGraph(ports, RoutinesWiring.upkeepSteps(pickUp))
    }

    /**
     * Whether the app's data opened; the main activity touches [graph] only once it is open. A gate that is open at
     * once when a test installed a graph (which never opens the real database).
     */
    val gate: AppGate
        get() = installedGate ?: synchronized(this) {
            // Once the data opened, the graph is built off the main thread before the gate opens (wave 3, D45): the main
            // thread then finds it built instead of wiring every service itself at a cold start.
            installedGate ?: AppGate(recovery, appScope, prepare = { graph }).also { installedGate = it }
        }

    /**
     * The composition root; created from the production ports unless one was installed. The
     * production graph also gets the services of [AppProcessHooks.onGraphBuilt]: the controller
     * that keeps objectives running in the background, a notification for every request waiting
     * for the owner, and the "is operating" notification with its Stop while an agent drives
     * another app's screen.
     */
    val graph: AppGraph
        get() = installedGraph ?: synchronized(this) {
            installedGraph ?: AppGraph(
                createProductionPorts(this, dataLayer, background, maintenanceGate),
                appScope,
                memoryConfig = memoryConfig,
                notifications = AndroidNotificationAccess(this),
                memoryService = sharedMemory,
                embedder = embedder,
                // Screens that show "today" move on at the owner's midnight (wave 3).
                dayChanges = DayChanges.daily(WallClock) { ZoneId.systemDefault() },
            ).also {
                installedGraph = it
                AppProcessHooks.onGraphBuilt(this, it, appScope)
            }
        }

    /** The graph if it has been built, without building it: for background entry points that only signal (rule 1). */
    fun graphIfBuilt(): AppGraph? = installedGraph

    /** Replaces the gate without a graph (tests of what runs, or does not, before the data opened). */
    internal fun installGate(gate: AppGate) {
        synchronized(this) { installedGate = gate }
    }

    /** Replaces the composition root. Meant for tests and must happen before the first screen is shown. */
    fun installGraph(graph: AppGraph) {
        synchronized(this) {
            installedGraph = graph
            installedGate = AppGate.open()
        }
    }

    internal companion object {
        /**
         * What folder pick-up may reach while nobody watches (WP17, Appendix A; security review of wave 1, F11): the rules,
         * the owner's folders read only through [PickUpFoldersView] (a folder's record from the store, the files inside it
         * through Android's folder access, which the view offers for reading only, never taking or releasing access), the
         * one memory service, the settings read only, the maintenance gate, and the activity log for what a run did.
         * Building it opens nothing: the folder reader is the device's, made without the graph and without a system call.
         */
        fun pickUpStepPorts(
            context: Context,
            data: DataLayer,
            memory: MemoryService,
            settings: SettingsView,
            gate: MaintenanceGate,
            zone: () -> ZoneId,
        ): PickUpStepPorts {
            val app = context.applicationContext ?: context
            // Only the folder reader of the phone layer is kept; its other members are never used here.
            val reader = PhoneOperatorLayer(app, data.sharedFolders, protectedPackages = { ProtectedPackages(app.packageName) }).sharedFolders
            return PickUpStepPorts(
                rules = data.pickUpRules,
                folders = PickUpFoldersView(data.sharedFolders, SharedFolderFilesView(reader)),
                memory = memory,
                settings = settings,
                clock = WallClock,
                zone = zone,
                gate = gate,
                activity = ActivityRecorder(data.activityLog, RandomIdGenerator, WallClock),
            )
        }
    }
}
