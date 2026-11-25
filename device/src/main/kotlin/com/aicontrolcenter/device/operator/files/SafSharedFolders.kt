package com.aicontrolcenter.device.operator.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.DocumentEntry
import com.aicontrolcenter.core.operator.FileContent
import com.aicontrolcenter.core.operator.FolderAccess
import com.aicontrolcenter.core.operator.FolderTrees
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.operator.SharedFolders
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.device.operator.WorkspaceCopy
import com.aicontrolcenter.device.platform.toSingleLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * [SharedFolders] over the Storage Access Framework: the persisted tree grants the owner gave
 * with the system folder picker, used through `DocumentsContract` on the folder's document
 * provider. Nothing outside a picked tree is reachable, and no storage permission is used.
 *
 * Every call re-reads the folder from [folders] and Android's persisted grants: a removed
 * folder, a read-only one (for changes), one that holds or is inside a read-only folder shared on
 * its own (for changes, [FolderTrees.overlap]), or a grant that is gone (the owner revoked it, the
 * folder was moved or deleted, or the app's storage was cleared) is [DocResult.NotAllowed] with
 * a sentence that says which. Paths are walked one display name at a time from the tree's root;
 * two children with the same name (some providers allow it) are [DocResult.Ambiguous] and
 * nothing is done. The root itself is never renamed or deleted.
 */
internal class SafSharedFolders(
    context: Context,
    private val folders: SharedFolderRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SharedFolders {
    private val resolver: ContentResolver = (context.applicationContext ?: context).contentResolver

    /** A folder ready for one call: its stored record, tree URI and the root document id. */
    private class Tree(val folder: SharedFolder, val uri: Uri, val rootId: String) {
        val name: String get() = folder.displayName
    }

    /** One document as the provider describes it. */
    private class Doc(val id: String, val name: String, val mimeType: String?, val size: Long?, val modifiedAt: Long?, val flags: Int) {
        val isFolder: Boolean get() = mimeType == Document.MIME_TYPE_DIR

        fun has(flag: Int): Boolean = flags and flag != 0
    }

    /** The tree's root no longer answers: access is gone. */
    private class AccessLost : Exception()

    override suspend fun takeAccess(treeUri: String): FolderAccess = withContext(dispatcher) {
        val uri = parseTree(treeUri) ?: return@withContext FolderAccess.Refused("That is not a folder chosen with the system folder picker.")
        val persisted = persist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ||
            persist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!persisted) {
            return@withContext FolderAccess.Refused("Android did not let this app keep access to that folder. Pick it again with the folder picker.")
        }
        val root = try {
            document(uri, DocumentsContract.getTreeDocumentId(uri))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (root == null) {
            release(uri)
            return@withContext FolderAccess.Refused("The folder could not be read. Pick it again with the folder picker.")
        }
        // The provider's name for it is another app's text: one bounded line before anyone sees it.
        val fallback = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
        FolderAccess.Granted(root.name.toSingleLine(MAX_FOLDER_NAME_CHARS).ifEmpty { fallback.toSingleLine(MAX_FOLDER_NAME_CHARS) }.ifEmpty { "Folder" })
    }

    override suspend fun releaseAccess(folder: SharedFolder) = withContext(dispatcher) {
        parseTree(folder.treeUri)?.let(::release)
        Unit
    }

    override suspend fun hasAccess(folder: SharedFolder): Boolean = withContext(dispatcher) {
        val uri = parseTree(folder.treeUri) ?: return@withContext false
        if (grantFor(uri)?.isReadPermission != true) return@withContext false
        try {
            document(uri, DocumentsContract.getTreeDocumentId(uri)) != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun list(folder: FolderId, path: RelativePath): DocResult<List<DocumentEntry>> = withFolder(folder, forWriting = false) { tree ->
        locate(tree, path).then { doc ->
            if (!doc.isFolder) return@then DocResult.Failed("\"${path.value}\" is a file, not a folder.")
            val entries = children(tree, doc.id).mapNotNull { child -> childPath(path, child.name)?.let { entryOf(it, child) } }
            DocResult.Ok(entries.sortedWith(compareBy<DocumentEntry>({ !it.isFolder }, { it.name.lowercase(Locale.ROOT) }, { it.name })))
        }
    }

    override suspend fun stat(folder: FolderId, path: RelativePath): DocResult<DocumentEntry> = withFolder(folder, forWriting = false) { tree ->
        locate(tree, path).then { DocResult.Ok(entryOf(path, it)) }
    }

    override suspend fun read(folder: FolderId, path: RelativePath, maxBytes: Long): DocResult<FileContent> = withFolder(folder, forWriting = false) { tree ->
        locate(tree, path).then { doc ->
            if (doc.isFolder) return@then DocResult.Failed("\"${path.value}\" is a folder, not a file.")
            val input = resolver.openInputStream(uriOf(tree, doc)) ?: return@then DocResult.NotFound(path.value)
            input.use { DocResult.Ok(readUpTo(it, maxBytes, doc.size)) }
        }
    }

    override suspend fun write(folder: FolderId, path: RelativePath, bytes: ByteArray, mimeType: String?, overwrite: Boolean): DocResult<DocumentEntry> =
        withFolder(folder, forWriting = true) { tree -> writeFrom(tree, path, mimeType, overwrite) { ByteArrayInputStream(bytes) } }

    override suspend fun makeFolder(folder: FolderId, path: RelativePath): DocResult<DocumentEntry> = withFolder(folder, forWriting = true) { tree ->
        ensureFolder(tree, path).then { (doc, actual) -> DocResult.Ok(entryOf(actual, doc)) }
    }

    override suspend fun rename(folder: FolderId, path: RelativePath, newName: String): DocResult<DocumentEntry> = withFolder(folder, forWriting = true) { tree ->
        if (path.isRoot) return@withFolder DocResult.NotAllowed("The shared folder itself cannot be renamed.")
        val target = childPath(path.parent!!, newName) ?: return@withFolder DocResult.Failed("\"${newName.take(80)}\" is not a valid file or folder name.")
        locate(tree, path.parent!!).then { parent ->
            val matches = children(tree, parent.id)
            val doc = matches.filter { it.name == path.name }.let { found ->
                when (found.size) {
                    0 -> return@then DocResult.NotFound(path.value)
                    1 -> found.single()
                    else -> return@then DocResult.Ambiguous(path.value, found.size)
                }
            }
            if (newName == doc.name) return@then DocResult.Ok(entryOf(path, doc))
            if (matches.any { it.name == newName }) return@then DocResult.AlreadyExists(target.value)
            if (!doc.has(Document.FLAG_SUPPORTS_RENAME)) return@then DocResult.NotAllowed("The app that provides ${tree.name} does not allow renaming \"${path.value}\".")
            val renamedUri = DocumentsContract.renameDocument(resolver, uriOf(tree, doc), newName)
            val renamedId = renamedUri?.let(DocumentsContract::getDocumentId) ?: doc.id
            val renamed = document(tree.uri, renamedId)
            when {
                renamed == null -> DocResult.Failed("\"${path.value}\" could not be renamed.")
                renamed.name == doc.name -> DocResult.Failed("\"${path.value}\" could not be renamed.")
                else -> DocResult.Ok(entryOf(childPath(path.parent!!, renamed.name) ?: target, renamed))
            }
        }
    }

    override suspend fun delete(folder: FolderId, path: RelativePath): DocResult<Unit> = withFolder(folder, forWriting = true) { tree ->
        if (path.isRoot) return@withFolder DocResult.NotAllowed("The shared folder itself is never deleted; remove it in Tools instead.")
        locate(tree, path).then { doc ->
            if (!doc.has(Document.FLAG_SUPPORTS_DELETE)) return@then DocResult.NotAllowed("The app that provides ${tree.name} does not allow deleting \"${path.value}\".")
            if (DocumentsContract.deleteDocument(resolver, uriOf(tree, doc))) DocResult.Ok(Unit) else DocResult.Failed("\"${path.value}\" could not be deleted.")
        }
    }

    override suspend fun copyToWorkspace(folder: FolderId, path: RelativePath, target: WorkspaceFile, maxBytes: Long): DocResult<Long> =
        withFolder(folder, forWriting = false) { tree ->
            locate(tree, path).then { doc ->
                if (doc.isFolder) return@then DocResult.Failed("\"${path.value}\" is a folder; only files are copied.")
                WorkspaceCopy.copyNew(target, maxBytes, doc.size, path.value) { resolver.openInputStream(uriOf(tree, doc)) }
            }
        }

    override suspend fun copyFromWorkspace(source: WorkspaceFile, folder: FolderId, path: RelativePath, overwrite: Boolean): DocResult<DocumentEntry> =
        withFolder(folder, forWriting = true) { tree ->
            if (!source.file.isFile) return@withFolder DocResult.NotFound(source.path.value)
            writeFrom(tree, path, null, overwrite) { FileInputStream(source.file) }
        }

    // --- Access -----------------------------------------------------------------------------

    /** Opens [id] for one call and maps provider failures to results. */
    private suspend fun <T> withFolder(id: FolderId, forWriting: Boolean, block: (Tree) -> DocResult<T>): DocResult<T> = withContext(dispatcher) {
        val folder = folders.get(id) ?: return@withContext DocResult.NotAllowed("That shared folder was removed, so it cannot be used any more.")
        if (forWriting && folder.readOnly) {
            return@withContext DocResult.NotAllowed("${folder.displayName} is read-only: the owner allows no changes in it.")
        }
        // A read-only folder can also be shared a second time from around or inside it ("Documents" and "Documents/Inbox"):
        // a change through the other one would land in it (security review of wave 1, F1). No change in either then.
        if (forWriting) {
            folders.getAll().firstOrNull { other -> other.id != folder.id && other.readOnly && FolderTrees.overlap(folder.treeUri, other.treeUri) == true }
                ?.let { readOnly ->
                    return@withContext DocResult.NotAllowed(
                        "${folder.displayName} holds, or is inside, the read-only folder ${readOnly.displayName}, so nothing can be changed in it.",
                    )
                }
        }
        val uri = parseTree(folder.treeUri) ?: return@withContext DocResult.NotAllowed(lost(folder))
        val grant = grantFor(uri)
        if (grant == null || !grant.isReadPermission) return@withContext DocResult.NotAllowed(lost(folder))
        if (forWriting && !grant.isWritePermission) {
            return@withContext DocResult.NotAllowed("Android gave this app read-only access to ${folder.displayName}, so nothing can be changed in it.")
        }
        val tree = Tree(folder, uri, DocumentsContract.getTreeDocumentId(uri))
        try {
            block(tree)
        } catch (e: CancellationException) {
            throw e
        } catch (_: AccessLost) {
            DocResult.NotAllowed(lost(folder))
        } catch (_: SecurityException) {
            DocResult.NotAllowed(lost(folder))
        } catch (_: UnsupportedOperationException) {
            DocResult.NotAllowed("The app that provides ${folder.displayName} does not support this.")
        } catch (_: FileNotFoundException) {
            DocResult.Failed("A file in ${folder.displayName} was not found or could not be opened; it may have changed meanwhile.")
        } catch (e: IOException) {
            DocResult.Failed("Reading or writing in ${folder.displayName} failed (${e.javaClass.simpleName}).")
        } catch (e: RuntimeException) {
            // Whatever else the provider throws across the binder. Only the class name is
            // reported: a provider's message is not ours to repeat.
            DocResult.Failed("The app that provides ${folder.displayName} reported an error (${e.javaClass.simpleName}).")
        }
    }

    private fun lost(folder: SharedFolder): String =
        "Access to ${folder.displayName} is gone (the folder may have been moved or deleted, or access was withdrawn). " +
            "The owner can add it again in Tools > Folders."

    private fun parseTree(text: String): Uri? {
        val uri = try {
            Uri.parse(text)
        } catch (_: RuntimeException) {
            return null
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrEmpty()) return null
        return if (DocumentsContract.isTreeUri(uri)) uri else null
    }

    private fun grantFor(uri: Uri) = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }

    private fun persist(uri: Uri, flags: Int): Boolean = try {
        resolver.takePersistableUriPermission(uri, flags)
        true
    } catch (_: SecurityException) {
        false
    }

    private fun release(uri: Uri) {
        val grant = grantFor(uri) ?: return
        val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            resolver.releasePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Not held any more: nothing to give up.
        }
    }

    // --- Walking the tree -------------------------------------------------------------------

    /** The document at [path] inside [tree], found by display name from the root. */
    private fun locate(tree: Tree, path: RelativePath): DocResult<Doc> {
        var current = document(tree.uri, tree.rootId) ?: throw AccessLost()
        var walked = RelativePath.ROOT
        for (segment in path.segments) {
            if (!current.isFolder) return DocResult.Failed("\"${walked.value}\" is a file, not a folder.")
            walked = walked.child(segment)
            val matches = children(tree, current.id).filter { it.name == segment }
            current = when (matches.size) {
                0 -> return DocResult.NotFound(walked.value)
                1 -> matches.single()
                else -> return DocResult.Ambiguous(walked.value, matches.size)
            }
        }
        return DocResult.Ok(current)
    }

    /**
     * The folder at [path], creating missing folders on the way, with its path as the provider
     * named it (a provider may adjust a new name).
     */
    private fun ensureFolder(tree: Tree, path: RelativePath): DocResult<Pair<Doc, RelativePath>> {
        var current = document(tree.uri, tree.rootId) ?: throw AccessLost()
        var walked = RelativePath.ROOT
        for (segment in path.segments) {
            val requested = walked.child(segment)
            val matches = children(tree, current.id).filter { it.name == segment }
            current = when (matches.size) {
                0 -> {
                    if (!current.has(Document.FLAG_DIR_SUPPORTS_CREATE)) {
                        return DocResult.NotAllowed("The app that provides ${tree.name} does not allow creating \"${requested.value}\".")
                    }
                    val created = DocumentsContract.createDocument(resolver, uriOf(tree, current), Document.MIME_TYPE_DIR, segment)
                        ?.let { document(tree.uri, DocumentsContract.getDocumentId(it)) }
                        ?: return DocResult.Failed("The folder \"${requested.value}\" could not be created.")
                    created
                }
                1 -> matches.single().also { if (!it.isFolder) return DocResult.AlreadyExists(requested.value) }
                else -> return DocResult.Ambiguous(requested.value, matches.size)
            }
            walked = childPath(walked, current.name) ?: requested
        }
        return DocResult.Ok(current to walked)
    }

    /** Creates or replaces the file at [path] with what [open] returns. */
    private fun writeFrom(tree: Tree, path: RelativePath, mimeType: String?, overwrite: Boolean, open: () -> InputStream): DocResult<DocumentEntry> {
        if (path.isRoot) return DocResult.NotAllowed("A file needs a name; the shared folder itself cannot be written.")
        return ensureFolder(tree, path.parent!!).then { (parent, parentPath) ->
            val matches = children(tree, parent.id).filter { it.name == path.name }
            when (matches.size) {
                0 -> create(tree, parent, parentPath, path.name, mimeType, open)
                1 -> {
                    val doc = matches.single()
                    when {
                        doc.isFolder || !overwrite -> DocResult.AlreadyExists(path.value)
                        !doc.has(Document.FLAG_SUPPORTS_WRITE) ->
                            DocResult.NotAllowed("The app that provides ${tree.name} does not allow changing \"${path.value}\".")
                        else -> {
                            copy(open, uriOf(tree, doc))
                            val updated = document(tree.uri, doc.id) ?: return@then DocResult.Failed("\"${path.value}\" was written but cannot be read back.")
                            DocResult.Ok(entryOf(childPath(parentPath, updated.name) ?: path, updated))
                        }
                    }
                }
                else -> DocResult.Ambiguous(path.value, matches.size)
            }
        }
    }

    private fun create(tree: Tree, parent: Doc, parentPath: RelativePath, name: String, mimeType: String?, open: () -> InputStream): DocResult<DocumentEntry> {
        val requested = childPath(parentPath, name)!!
        if (!parent.has(Document.FLAG_DIR_SUPPORTS_CREATE)) {
            return DocResult.NotAllowed("The app that provides ${tree.name} does not allow creating \"${requested.value}\".")
        }
        val uri = DocumentsContract.createDocument(resolver, uriOf(tree, parent), mimeTypeFor(name, mimeType), name)
            ?: return DocResult.Failed("\"${requested.value}\" could not be created.")
        var written = false
        try {
            copy(open, uri)
            written = true
        } finally {
            if (!written) {
                try {
                    DocumentsContract.deleteDocument(resolver, uri)
                } catch (_: Exception) {
                    // The original failure is what is reported.
                }
            }
        }
        val created = document(tree.uri, DocumentsContract.getDocumentId(uri)) ?: return DocResult.Failed("\"${requested.value}\" was written but cannot be read back.")
        return DocResult.Ok(entryOf(childPath(parentPath, created.name) ?: requested, created))
    }

    /** Writes everything [open] returns into [target], replacing its content ("wt"). */
    private fun copy(open: () -> InputStream, target: Uri) {
        val out = resolver.openOutputStream(target, "wt") ?: throw FileNotFoundException()
        out.use { sink -> open().use { it.copyTo(sink) } }
    }

    /**
     * The type to create [name] with. Providers append an extension when the type does not match
     * the name's ("notes.md" as text/plain becomes "notes.md.txt"), so the extension decides; a
     * name with an unknown extension is created as a generic file, which keeps it unchanged.
     */
    private fun mimeTypeFor(name: String, requested: String?): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) return requested?.takeIf(::isMimeType) ?: GENERIC
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: GENERIC
    }

    private fun isMimeType(text: String): Boolean = MIME.matches(text)

    // --- Provider queries -------------------------------------------------------------------

    private fun uriOf(tree: Tree, doc: Doc): Uri = DocumentsContract.buildDocumentUriUsingTree(tree.uri, doc.id)

    private fun document(treeUri: Uri, documentId: String): Doc? =
        query(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))?.firstOrNull()

    private fun children(tree: Tree, documentId: String): List<Doc> =
        query(DocumentsContract.buildChildDocumentsUriUsingTree(tree.uri, documentId)) ?: throw AccessLost()

    // The Bundle form is what Android itself uses for document providers (they refuse the old one).
    private fun query(uri: Uri): List<Doc>? = resolver.query(uri, PROJECTION, null as Bundle?, null)?.use { c ->
        val id = c.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
        val name = c.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
        val mime = c.getColumnIndex(Document.COLUMN_MIME_TYPE)
        val size = c.getColumnIndex(Document.COLUMN_SIZE)
        val modified = c.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
        val flags = c.getColumnIndex(Document.COLUMN_FLAGS)
        buildList {
            while (c.moveToNext()) {
                val docId = if (id >= 0) c.getString(id) else null
                docId ?: continue
                add(
                    Doc(
                        id = docId,
                        name = if (name >= 0) c.getString(name).orEmpty() else "",
                        mimeType = if (mime >= 0) c.getString(mime) else null,
                        size = if (size >= 0 && !c.isNull(size)) c.getLong(size) else null,
                        modifiedAt = if (modified >= 0 && !c.isNull(modified)) c.getLong(modified).takeIf { it > 0 } else null,
                        flags = if (flags >= 0 && !c.isNull(flags)) c.getInt(flags) else 0,
                    ),
                )
            }
        }
    }

    /** [parent] plus [name], or null when the name cannot be a path name (it then cannot be addressed either). */
    private fun childPath(parent: RelativePath, name: String): RelativePath? = try {
        parent.child(name)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun entryOf(path: RelativePath, doc: Doc) = DocumentEntry(
        path = path,
        isFolder = doc.isFolder,
        sizeBytes = if (doc.isFolder) null else doc.size,
        mimeType = if (doc.isFolder) null else doc.mimeType,
        modifiedAt = doc.modifiedAt,
    )

    private inline fun <A, B> DocResult<A>.then(next: (A) -> DocResult<B>): DocResult<B> = when (this) {
        is DocResult.Ok -> next(value)
        is DocResult.NotFound -> this
        is DocResult.AlreadyExists -> this
        is DocResult.TooLarge -> this
        is DocResult.NotAllowed -> this
        is DocResult.Ambiguous -> this
        is DocResult.Failed -> this
    }

    /** Constants. */
    companion object {
        private const val GENERIC = "application/octet-stream"
        private const val MAX_FOLDER_NAME_CHARS = 80
        private val MIME = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$")

        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        /**
         * At most [maxBytes] from [input], with [FileContent.truncated] set from whether more
         * followed (not from [size], which a provider may not know or may get wrong).
         */
        internal fun readUpTo(input: InputStream, maxBytes: Long, size: Long?): FileContent {
            val limit = maxBytes.coerceIn(0, Int.MAX_VALUE.toLong() - 8).toInt()
            val out = ByteArrayOutputStream(minOf(limit, (size ?: 8192L).coerceIn(0, limit.toLong()).toInt()))
            val buffer = ByteArray(minOf(64 * 1024, limit.coerceAtLeast(1)))
            var remaining = limit
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (n < 0) break
                out.write(buffer, 0, n)
                remaining -= n
            }
            val more = remaining == 0 && input.read() >= 0
            return FileContent(out.toByteArray(), size, truncated = more)
        }
    }
}
