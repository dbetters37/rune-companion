package com.aicontrolcenter.app.di

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.AiControlCenterApp
import com.aicontrolcenter.app.di.wiring.RoutinesWiring
import com.aicontrolcenter.app.operator.PickUpFoldersView
import com.aicontrolcenter.app.work.UpkeepHost
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.RandomIdGenerator
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.pickup.PickUpFolders
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.pickup.PickUpSafety
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.framework.pickup.DefaultOwnerPickUpRules
import com.aicontrolcenter.framework.pickup.PickUpRunner
import com.aicontrolcenter.memory.DefaultMemoryService
import com.aicontrolcenter.memory.HashingEmbedder
import com.aicontrolcenter.memory.MemoryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.IdentityHashMap
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Folder pick-up as the application builds it for the upkeep job (wave-2b merge, WP17 hand-off §2): the folders are
 * read through Android's folder access (the device's SAF reader behind [PickUpFoldersView]), not the empty stand-in of
 * wave 1 that found every folder gone, and what a run imported is logged.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UpkeepPickUpWiringTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val data = DataLayer(
        context, databaseName = "upkeep-pickup-wiring.db", databaseEncryption = DatabaseEncryption.None,
        settingsFileName = "upkeep-pickup-wiring-settings",
    )
    private val memory = DefaultMemoryService(
        repository = data.memories, agents = data.agents, settings = ReadOnlySettings(data.settings), embedder = HashingEmbedder(),
        activity = ActivityRecorder(data.activityLog, RandomIdGenerator, WallClock), ids = RandomIdGenerator, clock = WallClock,
        config = MemoryConfig(appVersion = "test"), computeContext = Dispatchers.Unconfined,
    )
    private val inbox = FolderId("fld_inbox")

    @After
    fun close() = runBlocking { data.close() }

    private fun ports() = AiControlCenterApp.pickUpStepPorts(
        context, data, memory, ReadOnlySettings(data.settings), LocalMaintenanceGate(), { ZoneOffset.UTC },
    )

    private suspend fun share(treeUri: Uri, persisted: Boolean) {
        if (persisted) context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        data.sharedFolders.upsert(SharedFolder(inbox, treeUri.toString(), "Inbox", addedAt = 1, readOnly = true, readOnlySince = 10))
    }

    @Test
    fun `a new text file in a read-only shared folder becomes an import through Android's folder access, and the run is logged`() = runBlocking {
        val documents = PickUpDocumentsProvider.install()
        share(documents.tree, persisted = true)
        documents.file("already-there.txt", "Was in the folder before the rule.")
        val ports = ports()
        assertIs<PickUpFoldersView>(ports.folders)
        val space = memory.createSpace(MemoryActor.Owner, "Inbox notes")
        val owner = DefaultOwnerPickUpRules(
            data.pickUpRules, ports.folders, { id -> memory.observeSpaces().first().any { it.id == id } },
            ActivityRecorder(data.activityLog, RandomIdGenerator, WallClock), WallClock, RandomIdGenerator,
        )
        val rule = assertIs<OwnerResult.Done<PickUpRule>>(owner.create(inbox, space.id)).value
        data.settings.update { it.copy(folderPickUpEnabled = true) }
        documents.file("later.txt", "Arrived after the rule was made.")

        RoutinesWiring.upkeepSteps(ports).single().run()

        val notes = memory.observe(MemoryActor.Owner).first()
        val note = notes.single { it.title == "later.txt" }
        assertEquals(MemoryScope.Shared(rule.spaceId), note.scope)
        assertEquals(ProvenanceOrigin.IMPORT, note.provenance.origin)
        assertEquals("Picked up from Inbox", note.provenance.source)
        assertTrue(notes.none { it.title == "already-there.txt" }, "files already in the folder are left out")
        assertEquals("Picked up 1 file.", data.pickUpRules.get(rule.id)!!.lastResult)
        assertTrue(data.activityLog.recent().any { it.title == "Folder pick-up: Inbox" && it.detail == "Picked up 1 file." }, "the run is logged")
    }

    @Test
    fun `a folder whose access Android withdrew is unreadable, not gone, and nothing is stored`() = runBlocking {
        share(DocumentsContract.buildTreeDocumentUri(PickUpDocumentsProvider.AUTHORITY, "nowhere"), persisted = false)
        val ports = ports()
        assertEquals("Inbox", ports.folders.folder(inbox)?.displayName, "folder records come from the owner's store")
        assertEquals(listOf(inbox), ports.folders.all().map { it.id })
        val listed = assertIs<DocResult.NotAllowed>(ports.folders.files(inbox))
        assertTrue(listed.reason.startsWith("Access to Inbox is gone"), "the device's folder reader answered: ${listed.reason}")
        val space = memory.createSpace(MemoryActor.Owner, "Inbox notes")
        val rule = PickUpRule(com.aicontrolcenter.core.PickUpRuleId("pur_1"), inbox, space.id, createdAt = 1, updatedAt = 1, folderReadOnlySince = 10)
        data.pickUpRules.upsert(rule)
        data.settings.update { it.copy(folderPickUpEnabled = true) }

        RoutinesWiring.upkeepSteps(ports).single().run()

        assertEquals(PickUpRunner.UNREADABLE, data.pickUpRules.get(rule.id)!!.lastResult)
        assertTrue(memory.observe(MemoryActor.Owner).first().none { it.provenance.origin == ProvenanceOrigin.IMPORT })
    }

    @Test
    fun `the application's upkeep job runs pick-up over these ports`() {
        val app = context as AiControlCenterApp
        val runner = reachable((app as UpkeepHost).upkeep).filterIsInstance<PickUpRunner>().single()
        fun field(name: String): Any? = PickUpRunner::class.java.getDeclaredField(name).apply { isAccessible = true }.get(runner)
        assertIs<PickUpFoldersView>(field("folders"))
        assertTrue(field("folders") !== PickUpFolders.None)
        assertNotNull(field("activity"), "what a run imported is logged")
        assertNull(app.graphIfBuilt(), "building the upkeep built no graph")
    }

    /** Every app, framework and lambda object reachable through fields (platform objects are leaves). */
    private fun reachable(root: Any): List<Any> {
        val seen = IdentityHashMap<Any, Unit>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val value = queue.removeFirst()
            if (seen.put(value, Unit) != null) continue
            if (value is Iterable<*>) {
                value.filterNotNull().forEach { queue += it }
                continue
            }
            var type: Class<*>? = value.javaClass
            while (type != null && (type.name.startsWith("com.aicontrolcenter.") || type.name.startsWith("kotlin.SynchronizedLazyImpl"))) {
                for (field in type.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                    field.isAccessible = true
                    field.get(value)?.let { queue += it }
                }
                type = type.superclass
            }
        }
        return seen.keys.toList()
    }
}

/** One shared folder ("Inbox") of text files, reached through the real `ContentResolver` and `DocumentsContract` calls. */
internal class PickUpDocumentsProvider : DocumentsProvider() {
    private val storage: File = Files.createTempDirectory("aicc-pickup-docs").toFile()
    private val names = LinkedHashMap<String, String>()

    val tree: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)

    fun file(name: String, text: String) {
        val id = "doc${names.size + 1}"
        File(storage, id).writeText(text)
        names[id] = name
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = when (documentId) {
        ROOT -> rows(projection, listOf(ROOT))
        in names -> rows(projection, listOf(documentId))
        else -> throw FileNotFoundException(documentId)
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (parentDocumentId != ROOT) throw FileNotFoundException(parentDocumentId)
        return rows(projection, names.keys.toList())
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (documentId !in names) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(File(storage, documentId), ParcelFileDescriptor.parseMode(mode))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = parentDocumentId == ROOT && documentId in names

    private fun rows(projection: Array<out String>?, ids: List<String>): Cursor {
        val columns = projection ?: arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
        val cursor = MatrixCursor(columns)
        for (id in ids) {
            val folder = id == ROOT
            cursor.addRow(
                columns.map<String, Any?> { column ->
                    when (column) {
                        Document.COLUMN_DOCUMENT_ID -> id
                        Document.COLUMN_DISPLAY_NAME -> if (folder) "Inbox" else names.getValue(id)
                        Document.COLUMN_MIME_TYPE -> if (folder) Document.MIME_TYPE_DIR else "text/plain"
                        Document.COLUMN_SIZE -> if (folder) null else File(storage, id).length()
                        Document.COLUMN_LAST_MODIFIED -> 1_700_000_000_000L + (names.keys.indexOf(id) + 1)
                        Document.COLUMN_FLAGS -> 0
                        else -> null
                    }
                }.toTypedArray(),
            )
        }
        return cursor
    }

    companion object {
        const val AUTHORITY = "com.aicontrolcenter.test.pickup.documents"
        private const val ROOT = "root"

        fun install(): PickUpDocumentsProvider {
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }
            return Robolectric.buildContentProvider(PickUpDocumentsProvider::class.java).create(info).get()
        }
    }
}
