package com.aicontrolcenter.device.operator.media

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.MediaAccess
import com.aicontrolcenter.core.operator.MediaId
import com.aicontrolcenter.core.operator.MediaItem
import com.aicontrolcenter.core.operator.MediaKind
import com.aicontrolcenter.core.operator.MediaLibrary
import com.aicontrolcenter.core.operator.MediaQuery
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.device.operator.WorkspaceCopy
import com.aicontrolcenter.device.platform.toSingleLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Photos and videos through MediaStore: metadata only ([list]), and a byte copy into the
 * workspace only when asked ([copyToWorkspace], never replacing a file).
 *
 * What may be read follows Android's media permissions: `READ_MEDIA_IMAGES` and
 * `READ_MEDIA_VIDEO` on Android 13+, the owner's partial choice
 * (`READ_MEDIA_VISUAL_USER_SELECTED`) on Android 14+, where MediaStore itself returns only the
 * chosen items, and `READ_EXTERNAL_STORAGE` on Android 9 to 12. [MediaAccess.SELECTED_ONLY]
 * also covers the rare case of only one of photos or videos being allowed. Without any of them
 * [list] and [copyToWorkspace] return [DocResult.NotAllowed].
 *
 * @param sdkInt the Android version whose permission rules apply (replaceable for tests).
 */
internal class MediaStoreLibrary(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaLibrary {
    private val appContext: Context = context.applicationContext ?: context
    private val resolver: ContentResolver = appContext.contentResolver

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    override suspend fun access(): MediaAccess = accessOf(sdkInt, ::granted)

    override suspend fun list(query: MediaQuery): DocResult<List<MediaItem>> = withContext(dispatcher) {
        val readable = readableKinds(sdkInt, ::granted)
        if (readable.isEmpty()) return@withContext DocResult.NotAllowed(NO_ACCESS)
        val kinds = query.kinds.intersect(readable)
        if (kinds.isEmpty()) {
            return@withContext DocResult.NotAllowed("This app may not see ${query.kinds.joinToString(" or ") { it.plural }}; the owner allowed only ${readable.joinToString(" and ") { it.plural }}.")
        }
        try {
            val items = kinds.flatMap { rows(it, query) }
                .filter { item -> query.takenAfter?.let { (item.takenAt ?: return@filter false) > it } ?: true }
                .filter { item -> query.takenBefore?.let { (item.takenAt ?: return@filter false) < it } ?: true }
                .sortedWith(NEWEST_FIRST)
                .take(query.limit)
            DocResult.Ok(items)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            DocResult.NotAllowed(NO_ACCESS)
        } catch (e: RuntimeException) {
            DocResult.Failed("The media library could not be read (${e.javaClass.simpleName}).")
        }
    }

    override suspend fun copyToWorkspace(id: MediaId, target: WorkspaceFile, maxBytes: Long): DocResult<Long> = withContext(dispatcher) {
        val (kind, rowId) = parse(id) ?: return@withContext DocResult.NotFound(id.value)
        if (kind !in readableKinds(sdkInt, ::granted)) return@withContext DocResult.NotAllowed(NO_ACCESS)
        val uri = ContentUris.withAppendedId(collection(kind), rowId)
        try {
            // One row, or none when the item is gone or not among what the owner allowed.
            val sizes = resolver.query(uri, arrayOf(BaseColumns._ID, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val column = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                listOf(if (column >= 0 && !c.isNull(column)) c.getLong(column).takeIf { it > 0 } else null)
            } ?: return@withContext DocResult.NotFound(id.value)
            WorkspaceCopy.copyNew(target, maxBytes, sizes.single(), "the ${kind.singular} ${id.value}") { resolver.openInputStream(uri) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            DocResult.NotAllowed(NO_ACCESS)
        } catch (_: java.io.FileNotFoundException) {
            DocResult.NotFound(id.value)
        } catch (e: RuntimeException) {
            DocResult.Failed("The ${kind.singular} could not be copied (${e.javaClass.simpleName}).")
        }
    }

    /** Every row of [kind] that may match [query], unsorted; MediaStore applies the owner's partial choice itself. */
    private fun rows(kind: MediaKind, query: MediaQuery): List<MediaItem> {
        val (selection, args) = selectionFor(query)
        val projection = if (kind == MediaKind.VIDEO) PROJECTION + DURATION else PROJECTION
        return resolver.query(collection(kind), projection, selection, args, "$DATE_ADDED DESC")?.use { c ->
            val id = c.getColumnIndex(BaseColumns._ID)
            val name = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val bytes = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val width = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val height = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val taken = c.getColumnIndex(DATE_TAKEN)
            val added = c.getColumnIndex(DATE_ADDED)
            val album = c.getColumnIndex(BUCKET)
            val duration = c.getColumnIndex(DURATION)
            fun long(column: Int): Long? = if (column >= 0 && !c.isNull(column)) c.getLong(column) else null
            fun text(column: Int): String? = if (column >= 0 && !c.isNull(column)) c.getString(column) else null
            buildList {
                while (c.moveToNext() && size < MAX_ROWS) {
                    val rowId = long(id) ?: continue
                    add(
                        MediaItem(
                            id = MediaId("${kind.prefix}:$rowId"),
                            kind = kind,
                            // Names come from whichever app saved the item: one bounded line each.
                            displayName = text(name).toSingleLine(MAX_NAME_CHARS),
                            mimeType = text(mime)?.toSingleLine(MAX_NAME_CHARS)?.ifEmpty { null },
                            sizeBytes = long(bytes)?.takeIf { it >= 0 },
                            widthPx = long(width)?.toInt()?.takeIf { it > 0 },
                            heightPx = long(height)?.toInt()?.takeIf { it > 0 },
                            durationMs = if (kind == MediaKind.VIDEO) long(duration)?.takeIf { it > 0 } else null,
                            takenAt = takenAt(long(taken), long(added)),
                            album = text(album)?.toSingleLine(MAX_NAME_CHARS)?.ifEmpty { null },
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    /** Constants and the permission rules, as pure functions. */
    companion object {
        private const val NO_ACCESS = "Photo and video access is off for this app. The owner can allow it in Android's app permissions."

        private const val MAX_NAME_CHARS = 255

        /** Rows read per kind at most, so a huge library cannot exhaust memory. */
        internal const val MAX_ROWS = 100_000

        // Column names that exist on every supported version (MediaColumns has them only from Android 10).
        private const val DATE_TAKEN = "datetaken"
        private const val DATE_ADDED = "date_added"
        private const val BUCKET = "bucket_display_name"
        private const val DURATION = "duration"

        private val PROJECTION = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            DATE_TAKEN,
            DATE_ADDED,
            BUCKET,
        )

        private val NEWEST_FIRST = compareByDescending<MediaItem> { it.takenAt ?: Long.MIN_VALUE }.thenBy { it.id.value }

        /** The permission for [kind] on Android 13+. */
        private fun permissionFor(kind: MediaKind): String = when (kind) {
            MediaKind.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
            MediaKind.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
        }

        /** Kinds the whole library of which may be read. */
        internal fun fullKinds(sdkInt: Int, granted: (String) -> Boolean): Set<MediaKind> = when {
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> MediaKind.entries.filter { granted(permissionFor(it)) }.toSet()
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaKind.entries.toSet()
            else -> emptySet()
        }

        /** Android 14+ partial access: MediaStore returns only the items the owner chose, of either kind. */
        internal fun hasPartialAccess(sdkInt: Int, granted: (String) -> Boolean): Boolean =
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

        /** Kinds that can be queried at all. */
        internal fun readableKinds(sdkInt: Int, granted: (String) -> Boolean): Set<MediaKind> =
            if (hasPartialAccess(sdkInt, granted)) MediaKind.entries.toSet() else fullKinds(sdkInt, granted)

        internal fun accessOf(sdkInt: Int, granted: (String) -> Boolean): MediaAccess {
            val full = fullKinds(sdkInt, granted)
            return when {
                full.size == MediaKind.entries.size -> MediaAccess.FULL
                full.isNotEmpty() || hasPartialAccess(sdkInt, granted) -> MediaAccess.SELECTED_ONLY
                else -> MediaAccess.NONE
            }
        }

        /** When it was taken, else when it was added (MediaStore stores that in seconds). */
        internal fun takenAt(dateTakenMs: Long?, dateAddedSeconds: Long?): Long? =
            dateTakenMs?.takeIf { it > 0 } ?: dateAddedSeconds?.takeIf { it > 0 }?.times(1000)

        /**
         * A pre-filter for the date range. It is never stricter than the exact filter applied
         * to [takenAt] afterwards, which stays the source of truth.
         */
        internal fun selectionFor(query: MediaQuery): Pair<String?, Array<String>?> {
            val clauses = ArrayList<String>()
            val args = ArrayList<String>()
            val noTaken = "($DATE_TAKEN IS NULL OR $DATE_TAKEN <= 0)"
            query.takenAfter?.let { after ->
                clauses += "($DATE_TAKEN > ? OR ($noTaken AND $DATE_ADDED > ?))"
                args += after.toString()
                args += Math.floorDiv(after, 1000L).toString()
            }
            query.takenBefore?.let { before ->
                clauses += "($DATE_TAKEN < ? OR ($noTaken AND $DATE_ADDED < ?))"
                args += before.toString()
                args += (-Math.floorDiv(-before, 1000L)).toString()
            }
            return if (clauses.isEmpty()) null to null else clauses.joinToString(" AND ") to args.toTypedArray()
        }

        internal fun collection(kind: MediaKind): Uri = when (kind) {
            MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        /** "image:42" or "video:42", as [list] makes them. */
        internal fun parse(id: MediaId): Pair<MediaKind, Long>? {
            val kind = MediaKind.entries.firstOrNull { id.value.startsWith(it.prefix + ":") } ?: return null
            val rowId = id.value.substringAfter(':').toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return kind to rowId
        }

        private val MediaKind.prefix: String get() = name.lowercase()
        private val MediaKind.singular: String get() = if (this == MediaKind.IMAGE) "photo" else "video"
        private val MediaKind.plural: String get() = if (this == MediaKind.IMAGE) "photos" else "videos"
    }
}
