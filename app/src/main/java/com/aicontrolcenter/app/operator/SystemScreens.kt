package com.aicontrolcenter.app.operator

import android.content.Intent
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.DeviceAccessState
import java.io.File

/**
 * Android's own screens and sheets that the owner's Tools and Workspace screens hand over to:
 * the accessibility settings, this app's App info page, the share sheet for a workspace file,
 * and the permissions and special access phone tools need (photos and videos, "Install unknown
 * apps", Termux). Production builds them from `:device` (which knows the service, the workspace
 * file provider and the permissions) in `createProductionPorts`; tests pass plain intents they
 * can recognise.
 *
 * Owner-only: only screens use it. Nothing an agent can reach holds it.
 */
interface SystemScreens {
    /** Android's accessibility settings, where the owner switches the operator service on. */
    fun accessibilitySettings(): Intent

    /** This app's App info page; its ⋮ menu holds "Allow restricted settings". */
    fun appInfo(): Intent

    /** The share sheet for one workspace [file], readable only by the one app the owner picks, and only for that hand-off. */
    fun shareFile(file: File, mimeType: String, title: String): Intent

    /** Whether [access] is on now. */
    fun accessState(access: DeviceAccess): DeviceAccessState

    /** The runtime permissions Android's own dialog asks for to turn [access] on; empty when only [accessSettings] can. */
    fun accessPermissions(access: DeviceAccess): List<String>

    /** Android's settings screen where the owner turns [access] on or off, or null when there is none. */
    fun accessSettings(access: DeviceAccess): Intent?

    /**
     * The name of the app whose storage provider a shared folder's [treeUri] comes from (a cloud drive, a file manager),
     * for the Folders screen's note (`OwnerFolders.otherAppStorageNote`; wave 2b residual fixes, B1); null for the
     * phone's own storage, downloads and media providers, and whenever Android does not tell it (package visibility, a
     * provider that went away). It asks PackageManager: call it off the main thread.
     */
    fun storageAppName(treeUri: String): String? = null
}

/** The type Android is told a shared or previewed file has, from its name. Unknown kinds are shared as plain bytes. */
fun mimeTypeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "txt", "log" -> "text/plain"
    "md", "markdown" -> "text/markdown"
    "csv" -> "text/csv"
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "xml" -> "text/xml"
    "js" -> "text/javascript"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "mp4" -> "video/mp4"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    else -> "application/octet-stream"
}
