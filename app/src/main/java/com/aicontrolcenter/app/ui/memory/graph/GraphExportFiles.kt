package com.aicontrolcenter.app.ui.memory.graph

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.aicontrolcenter.memory.MemoryGraphJson
import java.io.File
import java.io.IOException

/**
 * Writes a graph export where the app's FileProvider can serve it, and hands it to the
 * Android share sheet. This is the bridge to external graph viewers: a viewer registers for
 * [MemoryGraphJson.MIME_TYPE] (or any file) and receives a read-only content URI.
 *
 * Exports go to the app's cache, in their own folder, and each new export deletes the earlier
 * ones: they contain memory text, so they must not pile up, and the cache is never backed up.
 */
object GraphExportFiles {
    /** Folder under the cache directory; must match `res/xml/export_paths.xml`. */
    const val DIRECTORY = "exports"

    /** Writes [text] as [fileName] under [cacheDir]/[DIRECTORY], replacing earlier exports. */
    @Throws(IOException::class)
    fun write(cacheDir: File, fileName: String, text: String): File {
        require(fileName.isNotBlank() && '/' !in fileName && fileName != "." && fileName != "..") { "Not a plain file name." }
        val directory = File(cacheDir, DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("The export folder could not be created.")
        directory.listFiles()?.forEach { old -> if (old.isFile && old.name != fileName) old.delete() }
        val file = File(directory, fileName)
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    /** The authority declared for the provider in the manifest. */
    fun authority(context: Context): String = context.packageName + ".exports"

    /** The share intent for [file]: read permission only, for the one app the owner picks. */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MemoryGraphJson.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            putExtra(Intent.EXTRA_SUBJECT, "Memory graph")
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export memory graph")
    }
}
