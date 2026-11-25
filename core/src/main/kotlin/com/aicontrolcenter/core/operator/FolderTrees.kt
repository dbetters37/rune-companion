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
 * Trees of any other provider (a cloud drive, an app's own provider) never share files with
 * another provider's.
 */
object FolderTrees {
    /** The phone's own storage provider. */
    const val EXTERNAL_STORAGE: String = "com.android.externalstorage.documents"

    /** The downloads provider, whose "raw:" document ids are paths. */
    const val DOWNLOADS: String = "com.android.providers.downloads.documents"

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
        // which one is not the phone's storage (a cloud drive, an app's own provider). They never share files.
        if (px == null || py == null) return false
        return overlap(px, py)
    }

    /** Where on the phone's storage a folder is, as far as its id tells. */
    private sealed interface Place {
        /** Exactly [path] (lower case), on the volume whose root is [volume] (null when the path names none). */
        data class At(val path: String, val volume: String?) : Place

        /** Some folder in a Download folder of the phone's storage, or that folder itself; which, the id does not tell. */
        data object InDownloads : Place

        /** Some folder on the phone's storage; where, the id does not tell. */
        data object Somewhere : Place
    }

    private fun overlap(p: Place, q: Place): Boolean? = when {
        p is Place.At && q is Place.At -> within(p.path, q.path) || within(q.path, p.path)
        p is Place.At && q == Place.InDownloads -> nearDownload(p)
        p == Place.InDownloads && q is Place.At -> nearDownload(q)
        else -> null
    }

    /** For a folder in some Download folder: null when [p] is in or around its volume's Download folder (it may be the same place), else false. */
    private fun nearDownload(p: Place.At): Boolean? {
        val volume = p.volume ?: return null
        val download = "$volume/download"
        return if (within(p.path, download) || within(download, p.path)) null else false
    }

    /** The place on the phone's storage a tree of the storage or downloads provider names, or null for any other provider. */
    private fun placeOf(tree: Tree): Place? = when (tree.authority) {
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

    private fun at(volume: String, rest: String): Place.At =
        Place.At((volume + "/" + rest).trimEnd('/').lowercase(), volume.lowercase())

    /** A "raw:" path of the downloads provider, with its volume when it names one. */
    private fun raw(path: String): Place.At {
        val p = path.replace(Regex("^/sdcard(?=/|$)"), PRIMARY).trimEnd('/').lowercase()
        val parts = p.split('/')
        val volume = when {
            parts.size >= 4 && parts[1] == "storage" && parts[2] == "emulated" -> "/storage/emulated/${parts[3]}"
            parts.size >= 3 && parts[1] == "storage" && parts[2] != "emulated" && parts[2] != "self" -> "/storage/${parts[2]}"
            else -> null
        }
        return Place.At(p, volume)
    }

    private const val PRIMARY = "/storage/emulated/0"

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
