package com.aicontrolcenter.device.operator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.aicontrolcenter.core.operator.AppActions
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.MediaLibrary
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.operator.SharedFolders
import com.aicontrolcenter.core.operator.ShellRunner
import com.aicontrolcenter.core.operator.TermuxRunner
import com.aicontrolcenter.core.operator.WorkspaceRoot
import com.aicontrolcenter.device.launch.ActivityStartGate
import com.aicontrolcenter.device.launch.VisibleProcessGate
import com.aicontrolcenter.device.operator.apps.AndroidAppActions
import com.aicontrolcenter.device.operator.commands.SystemShellRunner
import com.aicontrolcenter.device.operator.commands.TermuxCommandRunner
import com.aicontrolcenter.device.operator.files.SafSharedFolders
import com.aicontrolcenter.device.operator.media.MediaStoreLibrary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * The phone operator's Android gateways other than the screen: the workspace folder, the
 * owner's shared folders, the photo library, hand-overs to other apps (including install and
 * uninstall), Android's shell and Termux. The composition root builds one and passes its members
 * to the framework as the core ports they implement. Construction touches no system service.
 *
 * @param folders the stored shared folders, re-read on every folder call.
 * @param protectedPackages the protected set as resolved at start-up (the static floor until then).
 * @param accessibilityServiceBound whether the screen operator's accessibility service is bound
 *   now; Android lets such an app start activities from the background.
 */
class PhoneOperatorLayer(
    context: Context,
    folders: SharedFolderRepository,
    protectedPackages: () -> ProtectedPackages,
    accessibilityServiceBound: () -> Boolean = { false },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext: Context = context.applicationContext ?: context

    /** `files/workspace`. */
    val workspaceRoot: WorkspaceRoot = AndroidWorkspaceRoot(appContext)

    /** The folders the owner picked, through the Storage Access Framework. */
    val sharedFolders: SharedFolders = SafSharedFolders(appContext, folders, ioDispatcher)

    /** Photo and video metadata, and copies into the workspace. */
    val mediaLibrary: MediaLibrary = MediaStoreLibrary(appContext, dispatcher = ioDispatcher)

    /** Opening apps and links, prefilled messages, sharing, install and uninstall. */
    val appActions: AppActions = AndroidAppActions(
        appContext,
        protectedPackages,
        startGate = ActivityStartGate { VisibleProcessGate.mayStartActivities() || accessibilityServiceBound() },
        dispatcher = ioDispatcher,
    )

    /** `/system/bin/sh` in a workspace folder. */
    val shellRunner: ShellRunner = SystemShellRunner(tempDir = { File(appContext.cacheDir, "shell") }, dispatcher = ioDispatcher)

    /** Termux's RUN_COMMAND. */
    val termuxRunner: TermuxRunner = TermuxCommandRunner(appContext)

    /** The runtime permissions to request, in one call, for the photo library on this Android version. */
    val mediaPermissions: List<String> = mediaPermissionsFor(Build.VERSION.SDK_INT)

    /**
     * The Android settings screen where the owner turns on [access], or null when another part
     * handles it (the accessibility service has its own enable flow in the screen operator).
     */
    fun settingsIntent(access: DeviceAccess): Intent? {
        val own = Uri.fromParts("package", appContext.packageName, null)
        return when (access) {
            DeviceAccess.INSTALL_UNKNOWN_APPS -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, own)
            // Both are permissions of this app: "Photos and videos", and Termux's "Run commands in Termux environment".
            DeviceAccess.MEDIA, DeviceAccess.TERMUX_RUN_COMMAND -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, own)
            DeviceAccess.ACCESSIBILITY_SERVICE -> null
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Version rules. */
    companion object {
        /**
         * Android 14+: photos, videos and the partial "selected items" choice; Android 13:
         * photos and videos; Android 9 to 12: the old storage read permission.
         */
        fun mediaPermissionsFor(sdkInt: Int): List<String> = when {
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
