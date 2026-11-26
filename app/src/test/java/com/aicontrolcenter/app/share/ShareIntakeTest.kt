package com.aicontrolcenter.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a share (rule 34; WP16): only `ACTION_SEND` / `ACTION_SEND_MULTIPLE` of text, at most 200,000 characters of
 * shared text, 10 files of at most 1 MB of UTF-8 text each, read only through `content:` addresses that are not this
 * app's own; every part left out is said in a sentence. Nothing is stored here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShareIntakeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Files by address; counts what was opened and how much of it was read. */
    private class Files(private val ownAuthorities: Set<String> = emptySet()) : ShareSource {
        val files = LinkedHashMap<Uri, Triple<String?, String?, ByteArray>>()
        val opened = ArrayList<Uri>()
        var bytesRead = 0L
        var refuse: Exception? = null

        fun put(name: String?, type: String?, bytes: ByteArray): Uri =
            Uri.parse("content://com.example.recorder.files/export/${files.size + 1}").also { files[it] = Triple(name, type, bytes) }

        fun put(name: String, text: String, type: String = "text/plain"): Uri = put(name, type, text.toByteArray())

        override fun isOwnProvider(uri: Uri): Boolean = uri.authority in ownAuthorities

        override fun displayName(uri: Uri): String? = files[uri]?.first

        override fun type(uri: Uri): String? = files[uri]?.second

        override fun open(uri: Uri): InputStream? {
            opened += uri
            refuse?.let { throw it }
            val bytes = files[uri]?.third ?: return null
            return object : InputStream() {
                private val inner = ByteArrayInputStream(bytes)

                override fun read(): Int = inner.read().also { if (it >= 0) bytesRead++ }

                override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) bytesRead += it }
            }
        }
    }

    private fun send(text: String? = null, type: String = "text/plain", subject: String? = null, stream: Uri? = null) =
        Intent(Intent.ACTION_SEND).setType(type).apply {
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            stream?.let { putExtra(Intent.EXTRA_STREAM, it) }
        }

    private fun sendMultiple(streams: List<Uri>, type: String = "text/*") =
        Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams))

    private fun read(intent: Intent, files: Files = Files()): SharedIntake = ShareIntake.read(assertNotNull(ShareIntake.requestOf(intent)), files)

    @Test
    fun `a SEND of text becomes one item, its subject the suggested title`() {
        val intake = read(send("The boiler is serviced in March.", subject = "Home\nnotes"))
        assertEquals(listOf(SharedItem("Home notes", "The boiler is serviced in March.")), intake.items)
        assertEquals(emptyList(), intake.leftOut)
    }

    @Test
    fun `a SEND_MULTIPLE of text files reads each one through the one-time grant`() {
        val files = Files()
        val a = files.put("monday.txt", "Recorded on Monday.")
        val b = files.put("plan.md", "# Plan\nTrain at nine.", type = "text/markdown")
        val intake = read(sendMultiple(listOf(a, b)), files)
        assertEquals(
            listOf(SharedItem("monday.txt", "Recorded on Monday.", fromFile = true), SharedItem("plan.md", "# Plan\nTrain at nine.", fromFile = true, markdown = true)),
            intake.items,
        )
        assertEquals(listOf(a, b), files.opened)
    }

    @Test
    fun `other actions and types are refused before anything is read`() {
        assertNull(ShareIntake.requestOf(null))
        assertNull(ShareIntake.requestOf(Intent(Intent.ACTION_VIEW).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "x")))
        assertNull(ShareIntake.requestOf(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "no type")))
        assertNull(ShareIntake.requestOf(send("x", type = "image/png")))
        assertNull(ShareIntake.requestOf(send("x", type = "application/octet-stream")))
        assertNull(ShareIntake.requestOf(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")))
        assertNotNull(ShareIntake.requestOf(send("x", type = "text/plain; charset=utf-8")))
    }

    @Test
    fun `shared text of up to 200,000 characters is taken, longer text is left out with a sentence`() {
        assertEquals(200_000, read(send("a".repeat(ShareIntake.MAX_TEXT_CHARS))).items.single().text.length)
        val over = read(send("a".repeat(ShareIntake.MAX_TEXT_CHARS + 1)))
        assertEquals(emptyList(), over.items)
        assertEquals(
            listOf("The shared text was left out: it has 200,001 characters, and at most 200,000 can be saved at once. Share a shorter part, or share it as a file."),
            over.leftOut,
        )
    }

    @Test
    fun `at most 10 files are read, and the others are counted`() {
        val files = Files()
        val uris = (1..12).map { files.put("note$it.txt", "Note $it.") }
        val intake = read(sendMultiple(uris), files)
        assertEquals(10, intake.items.size)
        assertEquals(uris.take(10), files.opened)
        assertEquals(listOf("2 more files were left out: at most 10 files can be saved at once."), intake.leftOut)
    }

    @Test
    fun `a file over 1 MB is left out without being read whole`() {
        val files = Files()
        val big = files.put("big.txt", "text/plain", ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() })
        val exact = files.put("exact.txt", "text/plain", ByteArray(1024 * 1024) { 'b'.code.toByte() })
        val intake = read(sendMultiple(listOf(big, exact)), files)
        assertEquals(listOf("exact.txt"), intake.items.map { it.name })
        assertEquals(listOf("“big.txt” was left out: it is larger than 1 MB, the most one file may be."), intake.leftOut)
        assertTrue(files.bytesRead <= 2 * (1024 * 1024 + 1), "read only up to the limit: ${files.bytesRead}")
    }

    @Test
    fun `a file that is not text, not UTF-8 or empty is left out, each with its reason`() {
        val files = Files()
        val picture = files.put("photo.png", "image/png", byteArrayOf(1, 2, 3))
        val latin1 = files.put("old.txt", "text/plain", byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte()))
        val empty = files.put("empty.txt", " \n")
        val good = files.put(null, "text/plain", "\uFEFFNo name given.".toByteArray())
        val intake = read(sendMultiple(listOf(picture, latin1, empty, good)), files)
        assertEquals(listOf(SharedItem("Shared file 4", "No name given.", fromFile = true)), intake.items, "the byte-order mark is dropped")
        assertEquals(
            listOf(
                "“photo.png” was left out: it is not a text file.",
                "“old.txt” was left out: it could not be read as plain text.",
                "“empty.txt” was left out: it is empty.",
            ),
            intake.leftOut,
        )
        assertFalse(picture in files.opened, "a file that is not text is never opened")
    }

    @Test
    fun `file paths and this app's own providers are never read`() {
        val files = Files(ownAuthorities = setOf("com.aicontrolcenter.hq.workspace"))
        val path = Uri.parse("file:///data/data/com.aicontrolcenter.hq/databases/aicc.db")
        val own = Uri.parse("content://com.aicontrolcenter.hq.workspace/root/notes.txt")
        val intake = read(sendMultiple(listOf(path, own)), files)
        assertEquals(emptyList(), intake.items)
        assertEquals(emptyList(), files.opened, "neither is opened")
        assertEquals(
            listOf(
                "A file was left out: this app only reads files that Android's share screen hands over.",
                "A file was left out: it points into this app's own storage, which a share cannot hand over.",
            ),
            intake.leftOut,
        )
    }

    @Test
    fun `a file Android does not let the app open is left out with its reason`() {
        val files = Files().apply { refuse = SecurityException("Permission Denial: opening provider") }
        val uri = files.put("secret.txt", "x")
        val intake = read(send(type = "text/plain", stream = uri), files)
        assertEquals(emptyList(), intake.items)
        assertEquals(listOf("“secret.txt” could not be read: Android did not let this app open it."), intake.leftOut)
    }

    @Test
    fun `a share with nothing readable says so, and damaged extras are never used`() {
        assertEquals(listOf(ShareIntake.NOTHING), read(send()).leftOut)
        val damaged = object : Intent(Intent.ACTION_SEND) {
            override fun getCharSequenceExtra(name: String?): CharSequence = throw BadParcelableException("ClassNotFoundException when unmarshalling")
        }.setType("text/plain")
        val request = assertNotNull(ShareIntake.requestOf(damaged))
        assertTrue(request.unreadable)
        assertEquals(SharedIntake(emptyList(), listOf(ShareIntake.UNREADABLE)), ShareIntake.read(request, Files()))
    }

    @Test
    fun `file names are one line without hidden characters or folders`() {
        assertEquals("notes.txt", ShareIntake.safeName("../../notes.txt"))
        assertEquals("a b.txt", ShareIntake.safeName("a\u202E\nb.txt"))
        assertNull(ShareIntake.safeName("\u200B "))
    }

    @Test
    fun `the phone's reader refuses every provider of this app, whatever user it names`() {
        val source = ContentResolverShareSource(context)
        val own = context.packageName
        assertTrue(source.isOwnProvider(Uri.parse("content://$own.workspace/files/a.txt")))
        assertTrue(source.isOwnProvider(Uri.parse("content://0@$own.exports/a.txt")))
        assertTrue(source.isOwnProvider(Uri.parse("content://10@${own.uppercase()}.EXPORTS/a.txt")))
        assertTrue(source.isOwnProvider(Uri.parse("content://$own/a.txt")))
        assertFalse(source.isOwnProvider(Uri.parse("content://com.example.recorder.files/export/1")))
        assertFalse(source.isOwnProvider(Uri.parse("content://${own}x.files/a.txt")), "only this app's own names")
    }
}
