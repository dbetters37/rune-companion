package com.aicontrolcenter.app.di

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.aicontrolcenter.app.di.wiring.BackgroundPorts
import com.aicontrolcenter.app.di.wiring.BackgroundWiring
import com.aicontrolcenter.app.di.wiring.BackupWiring
import com.aicontrolcenter.app.di.wiring.ModelsWiring
import com.aicontrolcenter.app.di.wiring.RoutinesWiring
import com.aicontrolcenter.app.di.wiring.ScriptsWiring
import com.aicontrolcenter.app.operator.SystemScreens
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.DeviceAccessState
import com.aicontrolcenter.core.operator.FolderTrees
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
 * once per process, lazily, with the application context, often from the main thread. Nothing
 * here blocks: the encrypted database, the settings file and the keystore are opened on first
 * use, off the main thread, and the protected-app list and the screen operator's first look at
 * Android's settings are read in the background.
 *
 * The launcher is passed unwrapped; [AppGraph] adds the activity-log wrapper.
 *
 * @param data the process's one data layer, shared with the upkeep job (one Room instance on the encrypted file).
 * @param background the process's one background port; the application class passes the instance its gate and
 *   Recovery path use, so the graph shares it.
 * @param maintenanceGate the process's one maintenance gate, shared with the upkeep job in the same way.
 */
fun createProductionPorts(
    context: Context,
    data: DataLayer,
    background: BackgroundPorts = BackgroundWiring.production(context.applicationContext, data),
    maintenanceGate: MaintenanceGate = LocalMaintenanceGate(),
): Ports {
    val appContext = context.applicationContext
    val device = DeviceLayer(appContext)
    // Resolved once per process (about a dozen PackageManager queries and an autofill query) on its
    // own thread, and shared by everyone: the screen operator, the broker, the tools, app hand-overs
    // and the owner's screens read the same set, so they can never disagree about an app. A reader
    // that comes before it is done waits for the full set instead of checking against less.
    val protectedPackages = ProtectedPackagesResolver.startResolving(appContext)
    // Exactly one per process: it shares the one accessibility service and the persisted Stop.
    val screen = ScreenOperatorLayer(appContext, data.operatorApps, protectedPackages)
    // Agent-facing: the operator alone, never the layer, which also holds the owner's Stop and Resume (F10: the
    // production walk caught the app actions reaching them through this lambda when it captured `screen`).
    val screenOperator = screen.operator
    val phone = PhoneOperatorLayer(
        appContext,
        data.sharedFolders,
        protectedPackages = protectedPackages,
        // A bound accessibility service lets the app start activities from the background.
        accessibilityServiceBound = { screenOperator.state.value == ScreenServiceState.READY },
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
            screenOperator = screenOperator,
            screenControl = screen.control,
            shell = phone.shellRunner,
            stopShellLeftovers = { phone.stopShellLeftovers() },
            termux = phone.termuxRunner,
            protectedPackages = protectedPackages,
            ownPackage = appContext.packageName,
            // Called from the main thread (the app coming to the front, the Screen control screen): read off it.
            refreshScreenState = { screen.refreshStateInBackground() },
            systemScreens = AndroidSystemScreens(appContext, phone),
            // Owner-only (D7): only the owner's Screen control screen reads it, through AppGraph.
            screenSwitchedOffSentence = screen.switchedOffSentence,
        ),
        packageChanges = device.packageChanges.map { },
        // Phase 6 (K11): usage and prices live in the same encrypted database. Agent-facing code gets the read-only
        // price view; the writer and the history's owner side go to the owner's screens only (rule 3).
        usageLedger = data.usageLedger,
        usageReports = data.usageReports,
        modelPrices = data.modelPrices,
        ownerModelPrices = data.ownerModelPrices,
        ownerUsage = data.ownerUsage,
        // One line per wave-2 package: each fills in its own wiring file (di/wiring), not this one.
        background = background,
        network = ModelsWiring.network(appContext),
        scripts = ScriptsWiring.production(appContext),
        // Only the stores a backup holds: never the vault, grants, requests, folders or authorised apps (F13).
        backup = BackupWiring.production(data.backupStores, maintenanceGate, freeBytes = { appContext.filesDir.usableSpace }),
        routines = RoutinesWiring.production(appContext, data),
        maintenanceGate = maintenanceGate,
    )
}

/**
 * Android's screens for the owner, from `:device`: the accessibility settings and App info pages
 * the screen operator names, the share sheet through the workspace file provider, which serves
 * `files/workspace` and nothing else, read-only, to the one app picked, and the phone tools'
 * permissions and special access as [PhoneOperatorLayer] reads and names them.
 */
private class AndroidSystemScreens(private val context: Context, private val phone: PhoneOperatorLayer) : SystemScreens {
    override fun accessState(access: DeviceAccess): DeviceAccessState = phone.accessState(access)

    override fun accessPermissions(access: DeviceAccess): List<String> = phone.runtimePermissions(access)

    override fun accessSettings(access: DeviceAccess): Intent? = phone.settingsIntent(access)

    override fun accessibilitySettings(): Intent = ScreenOperatorLayer.accessibilitySettingsIntent()

    override fun appInfo(): Intent = ScreenOperatorLayer.appInfoIntent(context)

    override fun storageAppName(treeUri: String): String? {
        val authority = FolderTrees.otherAppProvider(treeUri) ?: return null
        return try {
            val manager = context.packageManager
            val provider = manager.resolveContentProvider(authority, 0) ?: return null
            (provider.loadLabel(manager)?.toString()?.takeIf { it.isNotBlank() } ?: provider.applicationInfo?.loadLabel(manager)?.toString())
                ?.lineSequence()?.firstOrNull()?.trim()?.take(MAX_APP_NAME)?.takeIf { it.isNotBlank() }
        } catch (_: RuntimeException) {
            // Package visibility, a provider that went away, or a PackageManager that failed: Android does not tell it.
            null
        }
    }

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

/** The longest app name the Folders note shows (the note cuts it to one line of at most 60 characters too). */
private const val MAX_APP_NAME = 60
