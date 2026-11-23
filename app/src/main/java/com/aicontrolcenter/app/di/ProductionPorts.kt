package com.aicontrolcenter.app.di

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.aicontrolcenter.app.operator.SystemScreens
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.device.DeviceLayer
import com.aicontrolcenter.device.operator.PhoneOperatorLayer
import com.aicontrolcenter.device.operator.apps.WorkspaceFileProvider
import com.aicontrolcenter.device.screen.ProtectedPackagesResolver
import com.aicontrolcenter.device.screen.ScreenOperatorLayer
import com.aicontrolcenter.providers.web.OkHttpWebReader
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Builds the real, on-device implementations of every port.
 *
 * This is the only place in the app that names the `:data` and `:device` modules, so swapping
 * a storage or platform implementation never touches a screen or a view model. It is called
 * once per process, lazily, with the application context. Nothing here blocks on storage: the
 * encrypted database, the settings file and the keystore are opened on first use, off the main
 * thread. The one exception is the protected-app list, below.
 *
 * The launcher is passed unwrapped; [AppGraph] adds the activity-log wrapper.
 */
fun createProductionPorts(context: Context): Ports {
    val appContext = context.applicationContext
    val data = DataLayer(appContext)
    val device = DeviceLayer(appContext)
    // Resolved once per process (about a dozen PackageManager queries) and shared by everyone:
    // the screen operator keeps this very set, and the broker, the tools, app hand-overs and the
    // owner's screens read the same one, so they can never disagree about an app.
    val protectedPackages = ProtectedPackagesResolver.resolve(appContext)
    // Exactly one per process: it shares the one accessibility service and the persisted Stop.
    val screen = ScreenOperatorLayer(appContext, data.operatorApps, protectedPackages)
    val phone = PhoneOperatorLayer(
        appContext,
        data.sharedFolders,
        protectedPackages = { protectedPackages },
        // A bound accessibility service lets the app start activities from the background.
        accessibilityServiceBound = { screen.operator.state.value == ScreenServiceState.READY },
    )
    return Ports(
        agents = data.agents,
        providers = data.providers,
        models = data.models,
        aiApps = data.aiApps,
        conversations = data.conversations,
        // The same encrypted database as everything else, so memories never sit in plain text on disk.
        memories = data.memories,
        // Same database again: task results and agent messages hold what agents found and wrote.
        tasks = data.tasks,
        messages = data.messages,
        activityLog = data.activityLog,
        settings = data.settings,
        secretVault = data.secretVault,
        aiAppScanner = device.scanner,
        aiAppLauncher = device.launcher,
        deviceInspector = device.deviceInspector,
        operator = OperatorPorts(
            permissions = data.permissions,
            ownerRequests = data.ownerRequests,
            sharedFolders = data.sharedFolders,
            operatorApps = data.operatorApps,
            workspaceRoot = phone.workspaceRoot,
            folderAccess = phone.sharedFolders,
            media = phone.mediaLibrary,
            appActions = phone.appActions,
            webReader = { onRequestSent -> OkHttpWebReader(onRequestSent = onRequestSent) },
            screenOperator = screen.operator,
            screenControl = screen.control,
            shell = phone.shellRunner,
            termux = phone.termuxRunner,
            protectedPackages = { protectedPackages },
            ownPackage = appContext.packageName,
            refreshScreenState = { screen.refreshState() },
            systemScreens = AndroidSystemScreens(appContext),
        ),
        packageChanges = device.packageChanges.map { },
    )
}

/**
 * Android's screens for the owner, from `:device`: the accessibility settings and App info pages
 * the screen operator names, and the share sheet through the workspace file provider, which serves
 * `files/workspace` and nothing else, read-only, to the one app picked.
 */
private class AndroidSystemScreens(private val context: Context) : SystemScreens {
    override fun accessibilitySettings(): Intent = ScreenOperatorLayer.accessibilitySettingsIntent()

    override fun appInfo(): Intent = ScreenOperatorLayer.appInfoIntent(context)

    override fun shareFile(file: File, mimeType: String, title: String): Intent {
        val uri = FileProvider.getUriForFile(context, WorkspaceFileProvider.authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            clipData = ClipData.newUri(context.contentResolver, title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share $title").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
