package com.aicontrolcenter.device.operator.media

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.provider.MediaStore
import com.aicontrolcenter.core.operator.MediaKind
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/**
 * MediaStore's provider for tests: rows per kind, served for the images and video collections
 * and single items, with real bytes behind each item. Selection and sort are recorded and
 * ignored, like a provider that honours them only loosely: the library must filter itself.
 */
internal class FakeMediaProvider : ContentProvider() {
    class Row(
        val kind: MediaKind,
        val id: Long,
        val name: String,
        val mimeType: String? = null,
        val size: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
        val dateTaken: Long? = null,
        val dateAddedSeconds: Long? = null,
        val bucket: String? = null,
        val duration: Long? = null,
        val bytes: ByteArray? = null,
    )

    /** One query as the library made it. */
    data class Query(val uri: Uri, val projection: List<String>, val selection: String?, val args: List<String>?, val sortOrder: String?)

    val rows = ArrayList<Row>()
    val queries = ArrayList<Query>()

    /** Makes every query throw, as MediaStore does when the permission was withdrawn mid-call. */
    var denyAll = false

    private val storage: File = Files.createTempDirectory("aicc-media").toFile()

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (denyAll) throw SecurityException("Permission Denial")
        val columns = projection?.toList() ?: listOf(BaseColumns._ID)
        queries += Query(uri, columns, selection, selectionArgs?.toList(), sortOrder)
        val (kind, id) = parse(uri)
        val cursor = MatrixCursor(columns.toTypedArray())
        rows.filter { it.kind == kind && (id == null || it.id == id) }.forEach { row ->
            cursor.addRow(columns.map<String, Any?> { valueOf(row, it) }.toTypedArray())
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (denyAll) throw SecurityException("Permission Denial")
        val (kind, id) = parse(uri)
        val row = rows.firstOrNull { it.kind == kind && it.id == id } ?: throw FileNotFoundException(uri.toString())
        val file = File(storage, "${kind.name}-$id").apply { writeBytes(row.bytes ?: ByteArray(0)) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun parse(uri: Uri): Pair<MediaKind, Long?> {
        val kind = when {
            uri.toString().startsWith(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()) -> MediaKind.IMAGE
            uri.toString().startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()) -> MediaKind.VIDEO
            else -> throw IllegalArgumentException("Unknown URI $uri")
        }
        val id = uri.lastPathSegment?.toLongOrNull()?.takeIf { uri.pathSegments.size > 3 }
        return kind to id
    }

    private fun valueOf(row: Row, column: String): Any? = when (column) {
        BaseColumns._ID -> row.id
        MediaStore.MediaColumns.DISPLAY_NAME -> row.name
        MediaStore.MediaColumns.MIME_TYPE -> row.mimeType
        MediaStore.MediaColumns.SIZE -> row.size
        MediaStore.MediaColumns.WIDTH -> row.width
        MediaStore.MediaColumns.HEIGHT -> row.height
        "datetaken" -> row.dateTaken
        "date_added" -> row.dateAddedSeconds
        "bucket_display_name" -> row.bucket
        "duration" -> row.duration
        else -> null
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /** Registration. */
    companion object {
        fun install(): FakeMediaProvider = Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)

        fun itemUri(kind: MediaKind, id: Long): Uri = ContentUris.withAppendedId(MediaStoreLibrary.collection(kind), id)
    }
}
