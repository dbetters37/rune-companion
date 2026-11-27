package com.aicontrolcenter.app.share

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SEC-W3-2: one share brings in at most 10 texts or files, each file of at most 1 MB, the shared texts together at most
 * 200,000 characters, and everything together at most 2,000,000 characters. Whatever is over is named and left out,
 * and the share screen composes only the cards it shows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class ShareCapsTest {
    @get:Rule val compose = createComposeRule()

    private class Files : ShareSource {
        val files = LinkedHashMap<Uri, Pair<String, String>>()
        val opened = ArrayList<Uri>()

        fun put(name: String, text: String): Uri =
            Uri.parse("content://com.example.notes.files/export/${files.size + 1}").also { files[it] = name to text }

        override fun isOwnProvider(uri: Uri) = false
        override fun displayName(uri: Uri): String? = files[uri]?.first
        override fun type(uri: Uri): String? = "text/plain"
        override fun open(uri: Uri): InputStream? {
            opened += uri
            return files[uri]?.second?.let { ByteArrayInputStream(it.toByteArray()) }
        }
    }

    private fun texts(texts: List<String>, streams: List<Uri> = emptyList()) = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain")
        .putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, ArrayList<CharSequence>(texts))
        .apply { if (streams.isNotEmpty()) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams)) }

    private fun read(intent: Intent, files: ShareSource = Files()): SharedIntake = ShareIntake.read(assertNotNull(ShareIntake.requestOf(intent)), files)

    @Test
    fun `thousands of shared texts bring in 10, and the rest are counted in one sentence`() {
        val intake = read(texts(List(5_000) { "note $it" }))
        assertEquals(10, intake.items.size)
        assertEquals(List(10) { "note $it" }, intake.items.map { it.text })
        assertEquals(listOf("4,990 more shared texts were left out: at most 10 texts or files can be saved at once."), intake.leftOut)
    }

    @Test
    fun `eleven texts bring in ten`() {
        val intake = read(texts(List(11) { "note $it" }))
        assertEquals(10, intake.items.size)
        assertEquals(listOf("1 more shared text was left out: at most 10 texts or files can be saved at once."), intake.leftOut)
    }

    @Test
    fun `the shared texts together hold at most 200,000 characters`() {
        val intake = read(texts(List(3) { "x".repeat(150_000) }))
        assertEquals(1, intake.items.size)
        assertTrue(intake.items.sumOf { it.text.length } <= ShareIntake.MAX_TEXT_CHARS)
        assertEquals(
            listOf(
                "2 shared texts were left out: together the shared texts would have more than 200,000 characters, the most one share " +
                    "can save. Share them on their own.",
            ),
            intake.leftOut,
        )
    }

    @Test
    fun `texts and files count together, and files past the cap are not opened`() {
        val files = Files()
        val uris = (1..5).map { files.put("file$it.txt", "File $it.") }
        val intake = read(texts(List(8) { "note $it" }, uris), files)
        assertEquals(10, intake.items.size)
        assertEquals(listOf("file1.txt", "file2.txt"), intake.items.filter { it.fromFile }.map { it.name })
        assertEquals(uris.take(2), files.opened, "the files left out are never opened")
        assertEquals(listOf("3 more files were left out: at most 10 texts or files can be saved at once."), intake.leftOut)
    }

    @Test
    fun `one share holds at most 2,000,000 characters in all`() {
        val files = Files()
        val uris = (1..3).map { files.put("export$it.txt", "y".repeat(800_000)) }
        val intake = read(texts(emptyList(), uris), files)
        assertEquals(listOf("export1.txt", "export2.txt"), intake.items.map { it.name })
        assertTrue(intake.items.sumOf { it.text.length } <= ShareIntake.MAX_TOTAL_CHARS)
        assertEquals(
            listOf("“export3.txt” was left out: with it, this share would hold more than 2,000,000 characters, the most one share can save. Share it on its own."),
            intake.leftOut,
        )
    }

    @Test
    fun `the share screen composes only the cards it shows, and says how many more there are`() {
        val items = List(1_500) { SharedItemUi("Shared text $it", "1 word · 6 B", "note $it", previewIsStart = false, parts = 1) }
        val state = ShareUiState(isLoading = false, items = items, summary = "1,500 texts")
        val actions = ShareActions(onSelect = {}, onSave = {}, onStop = {}, onClose = {})
        compose.setContent { AppTheme(darkTheme = true) { ShareContent(state, TextFieldState(""), actions) } }
        compose.waitForIdle()
        val composed = compose.onAllNodesWithText("Shared text ", substring = true).fetchSemanticsNodes().size
        assertTrue(composed <= ShareIntake.MAX_ITEMS, "cards composed: $composed")
        assertEquals(1, compose.onAllNodesWithText("And 1,490 more.").fetchSemanticsNodes().size)
    }
}
