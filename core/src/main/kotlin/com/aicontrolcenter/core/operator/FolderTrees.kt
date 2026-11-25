package com.aicontrolcenter.core.operator

/**
 * Whether two folders the owner shared are one inside the other (security review of wave 1, F1,
 * second review): the same place on the phone can be shared twice, as "Documents" and as
 * "Documents/Inbox", and a change made through the outer one lands in the inner one. Pure: it reads
 * only the tree URIs the system folder picker gave (`content://<authority>/tree/<document id>`).
 *
 * Two trees of one document provider overlap when one tree's document id is the other's, or starts
 * with it followed by "/" (or the other ends in "/" or ":", as a storage root such as "primary:"
 * does). Trees of different providers overlap only when both name a place on the phone's storage
 * that can be compared: the phone's own storage provider ("primary:Download/x" is
 * "/storage/emulated/0/Download/x", "<volume>:x" is "/storage/<volume>/x") and the downloads
 * provider's "raw:" ids (a path).
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
     * or two providers whose places cannot be compared and may be the same storage).
     */
    fun overlap(a: String, b: String): Boolean? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        if (x.authority == y.authority) return within(x.documentId, y.documentId) || within(y.documentId, x.documentId)
        val px = pathOf(x)
        val py = pathOf(y)
        if (px != null && py != null) return within(px, py) || within(py, px)
        // One of them is on the phone's storage and the other is a provider we cannot place (a cloud drive, an app's own
        // provider): they never share files. Two unknown providers of different names do not either.
        return false
    }

    /** The place on the phone's storage a tree of the storage or downloads provider names, or null. */
    private fun pathOf(tree: Tree): String? = when (tree.authority) {
        EXTERNAL_STORAGE -> {
            val colon = tree.documentId.indexOf(':')
            if (colon <= 0) {
                null
            } else {
                val volume = tree.documentId.substring(0, colon)
                val base = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
                (base + "/" + tree.documentId.substring(colon + 1)).trimEnd('/')
            }
        }
        DOWNLOADS -> tree.documentId.takeIf { it.startsWith("raw:/") }?.removePrefix("raw:")?.trimEnd('/')?.replace("/sdcard/", "/storage/emulated/0/")
        else -> null
    }

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
