package com.aicontrolcenter.app.di

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.device.DeviceInspector
import com.aicontrolcenter.core.registry.AiAppLauncher
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.framework.agents.DefaultAgentManager
import com.aicontrolcenter.framework.registry.DefaultAiAppRegistry
import com.aicontrolcenter.framework.registry.DefaultRegistryDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * The composition root: builds every framework service once from a [Ports] value and exposes
 * what screens and background work need.
 *
 * It is created by hand rather than by a DI framework because the graph is small and explicit
 * wiring keeps the dependency order readable. A later subsystem (model gateway, memory engine,
 * message bus, …) is added by constructing it here from [ports] and the services above it, and
 * by registering its start-up work in [AppStartup]; nothing else needs to change.
 *
 * @param appScope lives as long as the process; start-up and background work run in it.
 */
class AppGraph(val ports: Ports, appScope: CoroutineScope) {
    val clock: Clock = ports.clock
    val idGenerator: IdGenerator = ports.idGenerator

    // --- ports that screens use directly ---
    val activityLog: ActivityLog = ports.activityLog
    val settings: SettingsRepository = ports.settings
    val secretVault: SecretVault = ports.secretVault
    val aiAppLauncher: AiAppLauncher = ports.aiAppLauncher
    val deviceInspector: DeviceInspector = ports.deviceInspector

    // --- framework services ---
    val activityRecorder = ActivityRecorder(ports.activityLog, ports.idGenerator, ports.clock)

    val agentManager: AgentManager = DefaultAgentManager(
        agents = ports.agents,
        settings = ports.settings,
        activity = activityRecorder,
        ids = ports.idGenerator,
        clock = ports.clock,
    )

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

    private val startup = AppStartup(
        scope = appScope,
        agentManager = agentManager,
        aiAppRegistry = aiAppRegistry,
        settings = ports.settings,
        activityLog = ports.activityLog,
        activityRecorder = activityRecorder,
        packageChanges = ports.packageChanges,
        clock = ports.clock,
    )

    /**
     * Runs first-run seeding and the initial registry refresh, and begins reacting to package
     * changes. Idempotent; the returned job completes when the one-off part has finished.
     */
    fun start(): Job = startup.start()

    /** Call when the app returns to the foreground: package broadcasts are best effort. */
    fun onAppForegrounded() = startup.onAppForegrounded()
}
