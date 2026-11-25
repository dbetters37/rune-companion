package com.aicontrolcenter.core.operator

/**
 * Whether two folders the owner shared are one inside the other (security review of wave 1, F1,
 * second review): the same place on the phone can be shared twice, as "Documents" and as
 * "Documents/Inbox", and a change made through the outer one lands in the inner one. Pure: it reads
 * only the tree URIs the system folder picker gave (`content://<authority>/tree/<document id>`).
 *
 * Two trees of one document provider overlap when one tree's document id is the other's, or starts
 * with it followed by "/" (or the other ends in "/" or ":", as a storage root such as "primary:"
 * does). Trees of the phone's own storage are compared by their place on it, whichever of the
 * picker's entries named them: the phone's storage provider ("primary:Download/x" is
 * "/storage/emulated/0/Download/x", "<volume>:x" is "/storage/<volume>/x", "home:x" is the
 * Documents folder's "x") and the downloads provider, whose "raw:" ids are paths and whose other
 * folders ("downloads", its root, and MediaStore folders "msf:<n>") lie somewhere in a Download
 * folder that the id does not tell (SEC-W2B-2). Folder names on the phone's storage ignore case.
 * The media provider's folders are somewhere on the phone's storage. Trees of any other provider
 * (a cloud drive, a file manager, an app's own provider: [otherAppProvider]) count as apart from
 * another provider's: Android does not tell whether such a provider also shows the phone's own
 * files, and counting them as overlapping would stop every change in them while any shared folder
 * is read-only. The Folders screen warns about the ones agents may change instead
 * ([OwnerFolders.fromOtherAppStorage], SEC-W2B-2-R).
 *
 * Profiles (SEC-W2B-2-R): "primary:" is the storage of the profile the app runs in, which a tree
 * id does not name. "/storage/emulated/0", "/sdcard" and "/storage/self/primary" in a "raw:" path
 * are taken as that storage; "/storage/emulated/<n>" of any other profile <n> (a work profile, a
 * second user, the private space) may be it or another profile's. Two such places of different
 * profiles are apart when they would be apart in one profile, and unknown otherwise. A tree of the
 * phone's storage or downloads provider whose URI names a profile ("content://10@...") and a "raw:"
 * path elsewhere than under "/storage" cannot be placed: unknown against the phone's storage.
 */
object FolderTrees {
    /** The phone's own storage provider. */
    const val EXTERNAL_STORAGE: String = "com.android.externalstorage.documents"

    /** The downloads provider, whose "raw:" document ids are paths. */
    const val DOWNLOADS: String = "com.android.providers.downloads.documents"

    /** The media provider, which shows files of the phone's own storage only. */
    const val MEDIA: String = "com.android.providers.media.documents"

    /** A tree as the picker described it: its provider and its document id (decoded). */
    data class Tree(val authority: String, val documentId: String)

    /** The provider and document id of [treeUri], or null when it is not a tree URI the folder picker gives. */
    fun parse(treeUri: String): Tree? {
        val prefix = "content://"
        if (!treeUri.startsWith(prefix, ignoreCase = true)) return null
        val rest = treeUri.substring(prefix.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val authority = rest.substring(0, slash).lowercase()
        val segments = rest.substring(slash + 1).split('/')
        if (segments.size < 2 || segments[0] != "tree" || segments[1].isEmpty()) return null
        val id = percentDecode(segments[1]) ?: return null
        return Tree(authority, id)
    }

    /**
     * The provider of [treeUri] when it is a tree of another app's storage provider (a cloud drive, a file manager, an
     * app's own provider): any provider but the phone's storage, downloads and media providers, of any profile. Null for
     * those, and for a URI that is not a picker's tree. The provider names the app (Android's
     * `PackageManager.resolveContentProvider`) for the Folders screen's warning ([OwnerFolders.otherAppStorageNote]).
     */
    fun otherAppProvider(treeUri: String): String? {
        val authority = withoutProfile(parse(treeUri)?.authority ?: return null)
        return authority.takeIf { it !in SYSTEM }
    }

    /**
     * True when the folders shared as [a] and [b] are the same place or one is inside the other;
     * false when they are apart; null when that cannot be told (a URI that is not a picker's tree,
     * or two folders on the phone's storage of which one cannot be placed exactly, such as a
     * downloads-provider folder "msf:<n>" and a folder in or around a Download folder). Callers
     * that protect something count null as overlapping.
     */
    fun overlap(a: String, b: String): Boolean? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        if (x.authority == y.authority && (within(x.documentId, y.documentId) || within(y.documentId, x.documentId))) return true
        val px = placeOf(x)
        val py = placeOf(y)
        // Not both on the phone's storage: the same other provider whose ids are apart, or two different providers of
        // which one is not the phone's storage (a cloud drive, a file manager, an app's own provider). They count as
        // apart; the Folders screen warns about another app's folder agents may change (OwnerFolders.fromOtherAppStorage).
        if (px == null || py == null) return false
        return overlap(px, py)
    }

    /** Where on the phone's storage a folder is, as far as its id tells. */
    private sealed interface Place {
        /** Exactly [path] (lower case), on the volume whose root is [volume] (null when the path names none). */
        data class At(val path: String, val volume: String?) : Place {
            /** The profile whose own storage the volume is ("/storage/emulated/<n>"), or null for any other volume. */
            val profile: String? get() = volume?.removePrefix(EMULATED)?.takeIf { volume.startsWith(EMULATED) }

            /** [path] from its volume's root: "" for the root, else "/" and the rest. */
            val relative: String get() = path.removePrefix(volume.orEmpty())
        }

        /** Some folder in a Download folder of the phone's storage, or that folder itself; which, the id does not tell. */
        data object InDownloads : Place

        /** Some folder on the phone's storage; where, the id does not tell. */
        data object Somewhere : Place
    }

    private fun overlap(p: Place, q: Place): Boolean? = when {
        p is Place.At && q is Place.At -> atBoth(p, q)
        p is Place.At && q == Place.InDownloads -> nearDownload(p)
        p == Place.InDownloads && q is Place.At -> nearDownload(q)
        else -> null
    }

    /**
     * Two exact places. On the storage of two different profiles, one of which may be the app's own (SEC-W2B-2-R): unknown
     * when they would overlap in one profile, else apart.
     */
    private fun atBoth(p: Place.At, q: Place.At): Boolean? {
        val pp = p.profile
        val qp = q.profile
        if (pp != null && qp != null && pp != qp) {
            return if (within(p.relative, q.relative) || within(q.relative, p.relative)) null else false
        }
        return within(p.path, q.path) || within(q.path, p.path)
    }

    /** For a folder in some Download folder: null when [p] is in or around its volume's Download folder (it may be the same place), else false. */
    private fun nearDownload(p: Place.At): Boolean? {
        val volume = p.volume ?: return null
        val download = "$volume/download"
        return if (within(p.path, download) || within(download, p.path)) null else false
    }

    /** The place on the phone's storage a tree of the storage, downloads or media provider names, or null for any other provider. */
    private fun placeOf(tree: Tree): Place? = when (tree.authority) {
        !in SYSTEM -> if (withoutProfile(tree.authority) in SYSTEM) Place.Somewhere else null
        MEDIA -> Place.Somewhere
        EXTERNAL_STORAGE -> {
            val colon = tree.documentId.indexOf(':')
            if (colon <= 0) {
                Place.Somewhere
            } else {
                val volume = tree.documentId.substring(0, colon)
                val rest = tree.documentId.substring(colon + 1)
                when {
                    volume.equals("primary", ignoreCase = true) -> at(PRIMARY, rest)
                    volume.equals("home", ignoreCase = true) -> at(PRIMARY, "Documents/$rest")
                    else -> at("/storage/$volume", rest)
                }
            }
        }
        DOWNLOADS -> when {
            tree.documentId.startsWith("raw:/") -> raw(tree.documentId.removePrefix("raw:"))
            tree.documentId == "downloads" || tree.documentId.startsWith("msf:") -> Place.InDownloads
            else -> Place.Somewhere
        }
        else -> null
    }

    /** [authority] without the profile a URI may name before it ("10@com.android.externalstorage.documents"). */
    private fun withoutProfile(authority: String): String {
        val at = authority.indexOf('@')
        return if (at > 0 && authority.substring(0, at).all { it in '0'..'9' }) authority.substring(at + 1) else authority
    }

    private fun at(volume: String, rest: String): Place.At =
        Place.At((volume + "/" + rest).trimEnd('/').lowercase(), volume.lowercase())

    /**
     * A "raw:" path of the downloads provider, with its volume when it names one: "/sdcard" and "/storage/self/primary"
     * are the app's own profile's storage; "/storage" and "/storage/emulated" hold every volume; any other path that is
     * not on a volume under "/storage" cannot be placed.
     */
    private fun raw(path: String): Place {
        val p = path.trimEnd('/').lowercase().replace(Regex("^(/sdcard|/storage/self/primary)(?=/|$)"), PRIMARY)
        val parts = p.split('/')
        return when {
            p == "/storage" || p == "/storage/emulated" -> Place.At(p, null)
            parts.size >= 4 && parts[1] == "storage" && parts[2] == "emulated" && parts[3].isNotEmpty() && parts[3].all { it in '0'..'9' } ->
                Place.At(p, EMULATED + parts[3])
            parts.size >= 3 && parts[1] == "storage" && parts[2].isNotEmpty() && parts[2] != "emulated" && parts[2] != "self" ->
                Place.At(p, "/storage/${parts[2]}")
            else -> Place.Somewhere
        }
    }

    private const val EMULATED = "/storage/emulated/"
    private const val PRIMARY = "/storage/emulated/0"

    /** The system's providers of the phone's own storage. */
    private val SYSTEM: Set<String> = setOf(EXTERNAL_STORAGE, DOWNLOADS, MEDIA)

    /** True when [inner] is [outer] or lies inside it. */
    private fun within(inner: String, outer: String): Boolean =
        inner == outer || inner.startsWith(outer) && (outer.endsWith("/") || outer.endsWith(":") || inner[outer.length] == '/')

    /** [text] with every %XX replaced by its byte (UTF-8), or null when an escape is broken. */
    private fun percentDecode(text: String): String? {
        val out = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val hex = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
