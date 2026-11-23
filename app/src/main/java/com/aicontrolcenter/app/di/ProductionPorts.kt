package com.aicontrolcenter.app.di

import android.content.Context
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.device.DeviceLayer
import kotlinx.coroutines.flow.map

/**
 * Builds the real, on-device implementations of every port.
 *
 * This is the only place in the app that names the `:data` and `:device` modules, so swapping
 * a storage or platform implementation never touches a screen or a view model. It is called
 * once per process, lazily, with the application context. Nothing here blocks: the encrypted
 * database, the settings file and the keystore are opened on first use, off the main thread.
 *
 * The launcher is passed unwrapped; [AppGraph] adds the activity-log wrapper.
 */
fun createProductionPorts(context: Context): Ports {
    val appContext = context.applicationContext
    val data = DataLayer(appContext)
    val device = DeviceLayer(appContext)
    return Ports(
        agents = data.agents,
        providers = data.providers,
        models = data.models,
        aiApps = data.aiApps,
        conversations = data.conversations,
        // The same encrypted database as everything else, so memories never sit in plain text on disk.
        memories = data.memories,
        activityLog = data.activityLog,
        settings = data.settings,
        secretVault = data.secretVault,
        aiAppScanner = device.scanner,
        aiAppLauncher = device.launcher,
        deviceInspector = device.deviceInspector,
        packageChanges = device.packageChanges.map { },
    )
}
