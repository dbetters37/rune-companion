package com.aicontrolcenter.device

import android.content.Context
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.device.DeviceInspector
import com.aicontrolcenter.core.registry.AiAppLauncher
import com.aicontrolcenter.core.registry.AiAppScanner
import com.aicontrolcenter.device.catalog.KnownAiApp
import com.aicontrolcenter.device.catalog.KnownAiApps
import com.aicontrolcenter.device.launch.ActivityStartGate
import com.aicontrolcenter.device.launch.AndroidAiAppLauncher
import com.aicontrolcenter.device.launch.VisibleProcessGate
import com.aicontrolcenter.device.packages.PackageChange
import com.aicontrolcenter.device.packages.PackageChangeMonitor
import com.aicontrolcenter.device.report.AndroidDeviceInspector
import com.aicontrolcenter.device.scan.AndroidAiAppScanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * Everything the app needs from the Android platform in Phase 1, built from one [Context].
 * The composition root creates a single instance and passes its members to the framework as
 * the core ports they implement.
 *
 * Construction touches no system service; all work happens when a member is used, off the
 * main thread on [ioDispatcher].
 *
 * @param context any context; only the application context is retained.
 * @param catalog the known AI apps to look for. Replaceable for tests.
 * @param startGate decides whether a hand-off may start an activity right now.
 */
class DeviceLayer(
    context: Context,
    clock: Clock = WallClock,
    catalog: List<KnownAiApp> = KnownAiApps.all,
    startGate: ActivityStartGate = VisibleProcessGate,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext: Context = context.applicationContext ?: context

    /** Discovers AI apps and lists installed apps through PackageManager. */
    val scanner: AiAppScanner = AndroidAiAppScanner(appContext, catalog, ioDispatcher)

    /** Opens registered apps and hands text to them. */
    val launcher: AiAppLauncher = AndroidAiAppLauncher(appContext, startGate, ioDispatcher)

    /**
     * Installs, removals, updates and enable/disable changes of other apps while it is
     * collected. Collect it for as long as the app is alive and refresh the registry on each
     * event.
     */
    val packageChanges: Flow<PackageChange> = PackageChangeMonitor(appContext).changes

    /** Reports what this phone can do. */
    val deviceInspector: DeviceInspector = AndroidDeviceInspector(appContext, clock, ioDispatcher)
}
