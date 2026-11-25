package com.aicontrolcenter.app

import android.app.Application
import android.os.Process
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.di.createProductionPorts
import com.aicontrolcenter.app.di.wiring.BackgroundPorts
import com.aicontrolcenter.app.di.wiring.BackgroundWiring
import com.aicontrolcenter.app.di.wiring.RoutinesWiring
import com.aicontrolcenter.app.health.AppGate
import com.aicontrolcenter.app.work.AndroidNotificationAccess
import com.aicontrolcenter.app.work.AppProcessHooks
import com.aicontrolcenter.app.work.ScreenSessionNotifications
import com.aicontrolcenter.app.work.UpkeepGraph
import com.aicontrolcenter.core.RandomIdGenerator
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.memory.Embedder
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.memory.DefaultMemoryService
import com.aicontrolcenter.memory.HashingEmbedder
import com.aicontrolcenter.memory.MemoryConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
class AiControlCenterApp : Application() {
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

    /** What the upkeep job may reach: never the graph, a model, a tool or a provider (rule 1). */
    val upkeep: UpkeepGraph by lazy {
        UpkeepGraph(this, dataLayer, sharedMemory, maintenanceGate, RoutinesWiring.upkeepSteps(this, dataLayer, sharedMemory))
    }

    /**
     * Whether the app's data opened; the main activity touches [graph] only once it is open. A gate that is open at
     * once when a test installed a graph (which never opens the real database).
     */
    val gate: AppGate
        get() = installedGate ?: synchronized(this) {
            installedGate ?: AppGate(recovery, appScope).also { installedGate = it }
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
            ).also {
                installedGraph = it
                AppProcessHooks.onGraphBuilt(this, it, appScope)
            }
        }

    /** The graph if it has been built, without building it: for background entry points that only signal (rule 1). */
    fun graphIfBuilt(): AppGraph? = installedGraph

    /** Replaces the composition root. Meant for tests and must happen before the first screen is shown. */
    fun installGraph(graph: AppGraph) {
        synchronized(this) {
            installedGraph = graph
            installedGate = AppGate.open()
        }
    }
}
