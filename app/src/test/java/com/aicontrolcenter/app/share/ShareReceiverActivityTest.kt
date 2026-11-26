package com.aicontrolcenter.app.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.AiControlCenterApp
import com.aicontrolcenter.app.health.AppGate
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.testkit.FakeOwnerRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real share activity (rule 34, Tier B; WP16), started as another app's share sheet starts it, over the test app's
 * in-memory graph: SEND and SEND_MULTIPLE, nothing stored before Save, what is stored and where, the caps said on
 * screen, other intents closed at once, the Recovery screen when the data does not open, and exact typing in the title.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ShareReceiverActivityTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val context: AiControlCenterApp get() = ApplicationProvider.getApplicationContext()

    @get:Rule val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<ShareReceiverActivity>? = null

    @After
    fun close() {
        scenario?.close()
        scope.cancel()
    }

    private fun app(settings: FrameworkSettings = FrameworkSettings()): TestApp = TestApp(scope, settings).also { context.installGraph(it.graph) }

    private fun TestApp.space(name: String): SpaceId = runBlocking { graph.memoryService.createSpace(MemoryActor.Owner, name).id }

    private fun TestApp.stored(): List<MemoryRecord> = runBlocking { graph.memoryService.observe(MemoryActor.Owner).first() }

    private fun launch(intent: Intent): ActivityScenario<ShareReceiverActivity> =
        ActivityScenario.launch<ShareReceiverActivity>(intent.setClass(context, ShareReceiverActivity::class.java)).also { scenario = it }

    private fun sendText(text: String, subject: String? = null) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).apply {
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }

    /** The activity finished itself (Robolectric does not go on to destroy it), or is gone already. */
    private fun closing(): Boolean {
        val current = scenario ?: return true
        if (current.state == Lifecycle.State.DESTROYED) return true
        var finishing = false
        current.onActivity { finishing = it.isFinishing }
        return finishing
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun tap(text: String) = compose.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()

    @Test
    fun `a shared text is shown with its size, nothing is stored until Save, and Save stores it as an import in the chosen space`() {
        val app = app()
        val notes = app.space("Screen notes")
        launch(sendText("Tuesday: the boiler engineer comes at nine.", subject = "Screen recorder export"))

        waitFor(ShareTexts.TITLE)
        waitFor("Tuesday: the boiler engineer comes at nine.", substring = true)
        compose.onNodeWithText("7 words · 43\u00A0bytes").assertExists()
        compose.onNodeWithText(ShareTexts.STORING).performScrollTo().assertExists()
        assertEquals(emptyList(), app.stored(), "nothing is stored before Save")

        compose.onNode(hasText(ShareTexts.SAVE) and hasClickAction()).performScrollTo().performClick()
        assertEquals(emptyList(), app.stored(), "Save without a place is not possible")

        tap("Memory space “Screen notes”")
        compose.onNodeWithText("Saves 1 memory in “Screen notes”.").performScrollTo().assertExists()
        assertEquals(emptyList(), app.stored(), "picking a place stores nothing")
        tap(ShareTexts.SAVE)
        waitFor(ShareTexts.SAVED)

        val memory = app.stored().single()
        assertEquals(MemoryScope.Shared(notes), memory.scope)
        assertEquals("Screen recorder export", memory.title)
        assertEquals("Tuesday: the boiler engineer comes at nine.", memory.content)
        assertEquals(ProvenanceOrigin.IMPORT, memory.provenance.origin)
        assertEquals(ShareSaving.SOURCE, memory.provenance.source)
        tap(ShareTexts.DONE)
        compose.waitUntil(5_000) { closing() }
    }

    @Test
    fun `Back and Don't save close the screen and store nothing`() {
        val app = app()
        app.space("Screen notes")
        launch(sendText("Do not keep this."))
        waitFor(ShareTexts.TITLE)
        tap("Memory space “Screen notes”")
        tap(ShareTexts.DONT_SAVE)
        compose.waitUntil(5_000) { closing() }
        assertEquals(emptyList(), app.stored())

        launch(sendText("Nor this."))
        waitFor(ShareTexts.TITLE)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { closing() }
        assertEquals(emptyList(), app.stored())
    }

    @Test
    fun `a SEND_MULTIPLE of files reads them through the other app's provider, says what was left out, and saves into the workspace`() {
        val app = app()
        val files = SharedFilesProvider.install()
        val monday = files.put("monday.txt", "text/plain", "Monday: three screens recorded.".toByteArray())
        val photo = files.put("photo.png", "image/png", byteArrayOf(1, 2, 3))
        val big = files.put("huge.txt", "text/plain", ByteArray((ShareIntake.MAX_FILE_BYTES + 10).toInt()) { 'a'.code.toByte() })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(monday, photo, big))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(intent)

        waitFor("monday.txt")
        compose.onNodeWithText("“photo.png” was left out: it is not a text file.", substring = true).assertExists()
        compose.onNodeWithText("“huge.txt” was left out: it is larger than 1 MB", substring = true).assertExists()
        tap(ShareTexts.WORKSPACE)
        tap(ShareTexts.SAVE)
        waitFor(ShareTexts.SAVED)

        val saved = runBlocking { app.graph.workspace.read(RelativePath.of("${ShareSaving.FOLDER}/monday.txt"), 0, 1_000) }
        assertEquals("Monday: three screens recorded.", (saved as DocResult.Ok).value.bytes.decodeToString())
        assertEquals(emptyList(), app.stored(), "no memory is made")
    }

    @Test
    fun `local-only mode allows a share`() {
        val app = app(FrameworkSettings(localOnlyMode = true))
        app.space("Screen notes")
        launch(sendText("Stays here."))
        waitFor(ShareTexts.LOCAL_ONLY_ON)
        tap("Memory space “Screen notes”")
        tap(ShareTexts.SAVE)
        waitFor(ShareTexts.SAVED)
        assertEquals("Stays here.", app.stored().single().content)
    }

    @Test
    fun `an intent that is not a share of text closes at once and reads and stores nothing`() {
        val app = app()
        app.space("Screen notes")
        for (intent in listOf(
            Intent(Intent.ACTION_VIEW).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "x"),
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_TEXT, "x"),
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "no type"),
        )) {
            launch(intent)
            compose.waitUntil(5_000) { scenario!!.state == Lifecycle.State.DESTROYED }
            scenario!!.close()
        }
        assertEquals(emptyList(), app.stored())
    }

    @Test
    fun `when the data cannot be opened the Recovery screen shows, and the graph is never built`() {
        context.installGate(AppGate(FakeOwnerRecovery(DatabaseState.KEY_DAMAGED), scope))
        launch(sendText("Shared while the data is broken."))
        waitFor(DatabaseState.KEY_DAMAGED.ownerText, substring = true)
        compose.onNodeWithText(ShareTexts.TITLE).assertDoesNotExist()
        assertNull(context.graphIfBuilt(), "nothing touched the graph")
    }

    @Test
    fun `the title is exactly what was typed, however fast, and is what is stored`() {
        val app = app()
        app.space("Screen notes")
        launch(sendText("Notes from the screen recorder."))
        waitFor(ShareTexts.TITLE)
        val field = compose.onNode(hasContentDescription(SHARE_TITLE_FIELD) and hasSetTextAction())
        field.performTextReplacement("")
        field.typeFast(TITLE)
        assertEquals(TITLE, field.typed())

        tap("Memory space “Screen notes”")
        tap(ShareTexts.SAVE)
        waitFor(ShareTexts.SAVED)
        assertEquals(TITLE, app.stored().single().title)
    }

    /** Types [text] one key at a time with the frame clock paused, letting a frame through every [keysPerFrame] keys. */
    private fun SemanticsNodeInteraction.typeFast(text: String, keysPerFrame: Int = 3): SemanticsNodeInteraction {
        performScrollTo()
        performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            text.forEachIndexed { index, key ->
                performTextInput(key.toString())
                if (index % keysPerFrame == keysPerFrame - 1) compose.mainClock.advanceTimeByFrame()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        return this
    }

    /** What the field holds now, exactly. */
    private fun SemanticsNodeInteraction.typed(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private companion object {
        const val TITLE = "Tuesday's screens: bank app, train times (8:14 & 9:02), café menu"
    }
}

/** Another app's files, served the way its share sheet hands them over: a name, a type and the bytes behind a content address. */
internal class SharedFilesProvider : ContentProvider() {
    private val storage: File = Files.createTempDirectory("aicc-share-files").toFile()
    private val entries = LinkedHashMap<String, Pair<String, String>>()

    fun put(name: String, type: String, bytes: ByteArray): Uri {
        val id = "file${entries.size + 1}"
        File(storage, id).writeBytes(bytes)
        entries[id] = name to type
        return Uri.parse("content://$AUTHORITY/$id")
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val entry = entries[uri.lastPathSegment] ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map<String, Any> { if (it == OpenableColumns.DISPLAY_NAME) entry.first else File(storage, uri.lastPathSegment!!).length() }.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String? = entries[uri.lastPathSegment]?.second

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.lastPathSegment ?: throw java.io.FileNotFoundException(uri.toString())
        if (id !in entries) throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(File(storage, id), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.example.recorder.files"

        fun install(): SharedFilesProvider {
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
            }
            return Robolectric.buildContentProvider(SharedFilesProvider::class.java).create(info).get()
        }
    }
}
