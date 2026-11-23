package com.aicontrolcenter.app.operator

import android.content.Intent
import java.io.File

/**
 * Android's own screens and sheets that the owner's Tools and Workspace screens hand over to:
 * the accessibility settings, this app's App info page, and the share sheet for a workspace
 * file. Production builds them from `:device` (which knows the service and the workspace file
 * provider) in `createProductionPorts`; tests pass plain intents they can recognise.
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
