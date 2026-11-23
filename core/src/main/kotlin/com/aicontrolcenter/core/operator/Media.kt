package com.aicontrolcenter.core.operator

import kotlinx.serialization.Serializable

/** A photo or video in the device's media library, as Android identifies it. */
@Serializable
@JvmInline
value class MediaId(val value: String) {
    override fun toString(): String = value
}

/** Kinds of media the operator can list. */
enum class MediaKind { IMAGE, VIDEO }

/** How much of the media library Android lets this app see. */
enum class MediaAccess {
    FULL,

    /** Android 14+: only the items the owner selected. */
    SELECTED_ONLY,
    NONE,
}

/** Metadata of one photo or video. Never its pixels. */
@Serializable
data class MediaItem(
    val id: MediaId,
    val kind: MediaKind,
    val displayName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val durationMs: Long? = null,
    /** When it was taken, else when it was added (epoch ms). */
    val takenAt: Long? = null,
    /** The album (bucket) it is in, e.g. "Camera". */
    val album: String? = null,
)

/** Which media to list. Newest first. */
data class MediaQuery(
    val kinds: Set<MediaKind> = MediaKind.entries.toSet(),
    val takenAfter: Long? = null,
    val takenBefore: Long? = null,
    val limit: Int = 50,
) {
    init {
        require(limit in 1..500) { "limit must be 1..500" }
    }
}

/**
 * The photo and video library through MediaStore (`:device`). Needs Android's media permission;
 * without it, [list] and [copyToWorkspace] return [DocResult.NotAllowed]. Metadata only, except
 * for an explicit copy.
 */
interface MediaLibrary {
    suspend fun access(): MediaAccess

    suspend fun list(query: MediaQuery): DocResult<List<MediaItem>>

    /** Copies one item to [target]; never overwrites ([DocResult.AlreadyExists]). Returns the bytes copied. */
    suspend fun copyToWorkspace(id: MediaId, target: WorkspaceFile, maxBytes: Long): DocResult<Long>
}
