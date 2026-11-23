package com.aicontrolcenter.device.operator.files

import android.Manifest
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * A document provider for SAF tests, reached through the real `ContentResolver` and
 * `DocumentsContract` calls. Content lives in temporary files; names live here, so two
 * children may share a display name (as some providers allow). Flags can be taken away per
 * document to imitate providers that do not support an operation, and [renameAppends] makes
 * renames and creations adjust names the way some providers do.
 */
internal class FakeDocumentsProvider : DocumentsProvider() {
    private class Node(val id: String, var parent: String?, var name: String, val isFolder: Boolean, val file: File?, var flags: Int, var mimeType: String?)

    private val nodes = LinkedHashMap<String, Node>()
    private val next = AtomicInteger(0)
    private val storage: File = Files.createTempDirectory("aicc-docs").toFile()

    /** Appended to every created or renamed name, imitating a provider that adjusts names. */
    var renameAppends: String = ""

    /** Names created by createDocument, in order. */
    val created = ArrayList<String>()

    override fun onCreate(): Boolean = true

    // --- Test setup ---------------------------------------------------------------------------

    /** A root folder; returns the tree URI the folder picker would give for it. */
    fun tree(name: String): Uri {
        val id = add(null, name, folder = true)
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, id)
    }

    fun rootIdOf(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    fun folder(parentId: String, name: String): String = add(parentId, name, folder = true)

    fun file(parentId: String, name: String, content: String): String = add(parentId, name, folder = false).also { id ->
        nodes.getValue(id).file!!.writeText(content)
    }

    /** Walks "a/b/c" from [rootId] by name; the first match at each step. */
    fun idAt(rootId: String, path: String): String? {
        var current = rootId
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            current = nodes.values.firstOrNull { it.parent == current && it.name == segment }?.id ?: return null
        }
        return current
    }

    fun textOf(id: String): String = nodes.getValue(id).file!!.readText()

    fun namesIn(parentId: String): List<String> = nodes.values.filter { it.parent == parentId }.map { it.name }.sorted()

    fun exists(id: String): Boolean = id in nodes

    fun removeFlag(id: String, flag: Int) {
        nodes.getValue(id).flags = nodes.getValue(id).flags and flag.inv()
    }

    /** Deletes a document behind the app's back (the folder was deleted in a file manager). */
    fun vanish(id: String) {
        removeTree(id)
    }

    private fun add(parentId: String?, name: String, folder: Boolean): String {
        val id = "doc${next.incrementAndGet()}"
        val file = if (folder) null else File(storage, id).also { it.createNewFile() }
        val flags = if (folder) {
            Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        } else {
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        nodes[id] = Node(id, parentId, name, folder, file, flags, if (folder) Document.MIME_TYPE_DIR else "text/plain")
        return id
    }

    private fun removeTree(id: String) {
        nodes.values.filter { it.parent == id }.map { it.id }.forEach(::removeTree)
        nodes.remove(id)?.file?.delete()
    }

    // --- DocumentsProvider --------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        return cursorOf(projection, listOf(node))
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (parentDocumentId !in nodes) throw FileNotFoundException(parentDocumentId)
        return cursorOf(projection, nodes.values.filter { it.parent == parentDocumentId })
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        val file = node.file ?: throw FileNotFoundException("$documentId is a folder")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = nodes[parentDocumentId] ?: throw FileNotFoundException(parentDocumentId)
        check(parent.isFolder)
        created += "$displayName ($mimeType)"
        val id = add(parentDocumentId, displayName + renameAppends, folder = mimeType == Document.MIME_TYPE_DIR)
        if (mimeType != Document.MIME_TYPE_DIR) nodes.getValue(id).mimeType = mimeType
        return id
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        node.name = displayName + renameAppends
        return null
    }

    override fun deleteDocument(documentId: String) {
        if (documentId !in nodes) throw FileNotFoundException(documentId)
        removeTree(documentId)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current = nodes[documentId]?.parent
        while (current != null) {
            if (current == parentDocumentId) return true
            current = nodes[current]?.parent
        }
        return false
    }

    private fun cursorOf(projection: Array<out String>?, rows: List<Node>): Cursor {
        val columns = projection ?: arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
        val cursor = MatrixCursor(columns)
        for (node in rows) {
            cursor.addRow(
                columns.map<String, Any?> { column ->
                    when (column) {
                        Document.COLUMN_DOCUMENT_ID -> node.id
                        Document.COLUMN_DISPLAY_NAME -> node.name
                        Document.COLUMN_MIME_TYPE -> node.mimeType
                        Document.COLUMN_SIZE -> node.file?.length()
                        Document.COLUMN_LAST_MODIFIED -> 1_700_000_000_000L
                        Document.COLUMN_FLAGS -> node.flags
                        else -> null
                    }
                }.toTypedArray(),
            )
        }
        return cursor
    }

    /** Registration. */
    companion object {
        const val AUTHORITY = "com.aicontrolcenter.test.documents"

        /** Builds and registers the provider with Robolectric's content resolver. */
        fun install(): FakeDocumentsProvider {
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }
            return Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(info).get()
        }
    }
}
