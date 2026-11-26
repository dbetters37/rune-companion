package com.aicontrolcenter.app.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.core.pickup.PickUpLimits
import com.aicontrolcenter.core.operator.PreviewText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.CoroutineContext

/**
 * What another app asked [ShareReceiverActivity] to take in, read from its intent alone: no stream is opened here (rule
 * 34). Null from [ShareIntake.requestOf] when the intent is not a share of text, which the activity then closes on.
 *
 * @param texts the shared text (`EXTRA_TEXT`; with `ACTION_SEND_MULTIPLE` there may be several).
 * @param subject the share's subject or title, if the other app gave one: the suggested title.
 * @param streams the files (`EXTRA_STREAM`), at most [ShareIntake.MAX_FILES] of them.
 * @param streamsLeftOut how many more files were shared than are read.
 * @param type the share's own type (`text/…`), for a file whose type its app does not say.
 * @param unreadable true when the intent's contents could not be read at all.
 */
internal data class ShareRequest(
    val texts: List<String>,
    val subject: String?,
    val streams: List<Uri>,
    val streamsLeftOut: Int,
    val type: String?,
    val unreadable: Boolean = false,
)

/** What was read of a share: the items that can be saved, and a plain sentence for each part that was left out. */
data class SharedIntake(val items: List<SharedItem>, val leftOut: List<String>)

/**
 * How the activity opens what was shared. Production reads through Android's `ContentResolver` with the one-time read
 * grant the sharing app gave ([ContentResolverShareSource]); tests give their own.
 */
internal interface ShareSource {
    /** True when [uri] points at one of this app's own providers: a share never reads this app's own files (rule 34). */
    fun isOwnProvider(uri: Uri): Boolean

    /** The file's name as its app gives it, or null. */
    fun displayName(uri: Uri): String?

    /** The file's type as its app gives it, or null. */
    fun type(uri: Uri): String?

    /** Opens the file for reading, or null when its app gives nothing. */
    fun open(uri: Uri): InputStream?
}

/**
 * The caps and wording of receiving shares (owner request #43, rule 34, Tier B; WP16).
 *
 * - Only `ACTION_SEND` and `ACTION_SEND_MULTIPLE` whose type is `text/…` are taken; anything else (an app may start the
 *   activity by name with any action) is refused before anything is read.
 * - Shared text: at most [MAX_TEXT_CHARS] characters each.
 * - Files: at most [MAX_FILES], each of at most [MAX_FILE_BYTES], of a `text/…` type, readable as UTF-8 text; only
 *   `content:` addresses (what Android's share screen hands over with its one-time read grant), never a `file:` path and
 *   never one of this app's own providers, so a share can never make the app read its own private files.
 * - Nothing here stores anything; every part left out gets a sentence the owner sees.
 */
object ShareIntake {
    /** The most one shared text may hold. */
    const val MAX_TEXT_CHARS: Int = 200_000

    /** The most files one share may hand over. */
    const val MAX_FILES: Int = 10

    /** The largest file a share may hand over (the same 1 MB as folder pick-up). */
    const val MAX_FILE_BYTES: Long = PickUpLimits.MAX_FILE_BYTES

    /** The longest file name kept; a longer one is cut. */
    private const val MAX_NAME_CHARS = 120

    /** The longest subject kept as the suggested title. */
    private const val MAX_SUBJECT_CHARS = 160

    const val UNREADABLE: String = "What the other app shared could not be read. Nothing was saved."
    const val NOTHING: String = "The other app shared nothing this app can save: only text and text files can be saved here."

    /**
     * The share asked for by [intent], or null when it is not a share of text (another action, another type, no intent).
     * Reading the extras of a damaged or hostile intent can throw: that gives a request marked [ShareRequest.unreadable].
     */
    internal fun requestOf(intent: Intent?): ShareRequest? {
        if (intent == null) return null
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        val type = intent.type
        if (!PickUpLimits.accepts(type)) return null
        return try {
            val multiple = action == Intent.ACTION_SEND_MULTIPLE
            val texts = if (multiple) {
                intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.mapNotNull { it?.toString() }
                    ?: listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            } else {
                listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            }
            val streams = if (multiple) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.filterNotNull().orEmpty()
            } else {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }
            val subject = (intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TITLE))
                ?.toString()?.let { oneLine(it, MAX_SUBJECT_CHARS) }?.ifEmpty { null }
            ShareRequest(
                texts = texts,
                subject = subject,
                streams = streams.take(MAX_FILES),
                streamsLeftOut = (streams.size - MAX_FILES).coerceAtLeast(0),
                type = type,
            )
        } catch (e: RuntimeException) {
            // A damaged bundle (BadParcelableException and its kind): nothing of it is used.
            ShareRequest(emptyList(), null, emptyList(), 0, type, unreadable = true)
        }
    }

    /** Reads [request]'s text and files through [source], within the caps; blocking, so it runs off the main thread. */
    internal fun read(request: ShareRequest, source: ShareSource): SharedIntake {
        if (request.unreadable) return SharedIntake(emptyList(), listOf(UNREADABLE))
        val items = ArrayList<SharedItem>()
        val leftOut = ArrayList<String>()
        for (text in request.texts) {
            if (text.isBlank()) continue
            if (text.length > MAX_TEXT_CHARS) {
                leftOut += "The shared text was left out: it has ${count(text.length)} characters, and at most " +
                    "${count(MAX_TEXT_CHARS)} can be saved at once. Share a shorter part, or share it as a file."
                continue
            }
            items += SharedItem(request.subject, text)
        }
        request.streams.forEachIndexed { index, uri -> readFile(uri, index, request, source, items, leftOut) }
        if (request.streamsLeftOut > 0) {
            leftOut += "${files(request.streamsLeftOut)} left out: at most $MAX_FILES files can be saved at once."
        }
        if (items.isEmpty() && leftOut.isEmpty()) leftOut += NOTHING
        return SharedIntake(items, leftOut)
    }

    private fun readFile(uri: Uri, index: Int, request: ShareRequest, source: ShareSource, items: MutableList<SharedItem>, leftOut: MutableList<String>) {
        if (uri.scheme?.lowercase() != ContentResolver.SCHEME_CONTENT) {
            leftOut += "A file was left out: this app only reads files that Android's share screen hands over."
            return
        }
        if (source.isOwnProvider(uri)) {
            leftOut += "A file was left out: it points into this app's own storage, which a share cannot hand over."
            return
        }
        val name = safeName(attempt { source.displayName(uri) }) ?: "Shared file ${index + 1}"
        val type = attempt { source.type(uri) } ?: request.type
        if (!PickUpLimits.accepts(type)) {
            leftOut += "“$name” was left out: it is not a text file."
            return
        }
        val bytes = try {
            source.open(uri)?.use { readAtMost(it, MAX_FILE_BYTES + 1) }
        } catch (e: SecurityException) {
            leftOut += "“$name” could not be read: Android did not let this app open it."
            return
        } catch (e: Exception) {
            leftOut += "“$name” could not be read."
            return
        }
        if (bytes == null) {
            leftOut += "“$name” could not be opened."
            return
        }
        if (bytes.size > MAX_FILE_BYTES) {
            leftOut += "“$name” was left out: it is larger than 1 MB, the most one file may be."
            return
        }
        val text = utf8(bytes)
        when {
            text == null -> leftOut += "“$name” was left out: it could not be read as plain text."
            text.isBlank() -> leftOut += "“$name” was left out: it is empty."
            else -> items += SharedItem(name, text, fromFile = true, markdown = isMarkdown(type, name))
        }
    }

    /** At most [limit] bytes of [input]; reading stops there, so a larger file is never read whole. */
    internal fun readAtMost(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (total < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    /** [bytes] as UTF-8 text (a byte-order mark left out), or null when they are not valid UTF-8. */
    internal fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: CharacterCodingException) {
        null
    }

    /** A file's name for the screen and for titles: one line, no hidden characters, no folders, at most 120 characters. */
    internal fun safeName(raw: String?): String? =
        raw?.substringAfterLast('/')?.substringAfterLast('\\')?.let { oneLine(it, MAX_NAME_CHARS) }?.ifEmpty { null }

    private fun oneLine(raw: String, max: Int): String =
        raw.map { c -> if (c.isWhitespace()) ' ' else c }
            .filter { c -> !c.isISOControl() && !PreviewText.isHidden(c) && !PreviewText.isUnusualSpace(c.code) }
            .joinToString("")
            .replace(Regex(" {2,}"), " ")
            .trim()
            .take(max)
            .trim()

    private fun isMarkdown(type: String?, name: String): Boolean =
        type?.substringBefore(';')?.trim()?.lowercase() in setOf("text/markdown", "text/x-markdown") ||
            name.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }

    private inline fun <T> attempt(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    private fun files(n: Int) = if (n == 1) "1 more file was" else "$n more files were"

    private fun count(n: Int) = "%,d".format(java.util.Locale.ENGLISH, n)
}

/**
 * Reads through Android's `ContentResolver`, with the read grant the sharing app gave this activity for these files only.
 * A provider of this app's own package is never read ([isOwnProvider]).
 */
internal class ContentResolverShareSource(context: Context) : ShareSource {
    private val resolver = context.contentResolver
    private val packageManager = context.packageManager
    private val ownPackage = context.packageName

    override fun isOwnProvider(uri: Uri): Boolean {
        // "10@authority" names the user the provider runs for; the provider is the part after it.
        val authority = uri.authority?.substringAfterLast('@')?.lowercase() ?: return true
        if (authority == ownPackage.lowercase() || authority.startsWith(ownPackage.lowercase() + ".")) return true
        val provider = try {
            packageManager.resolveContentProvider(authority, 0)
        } catch (e: RuntimeException) {
            null
        }
        return provider?.packageName == ownPackage
    }

    override fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }

    override fun type(uri: Uri): String? = resolver.getType(uri)

    override fun open(uri: Uri): InputStream? = resolver.openInputStream(uri)
}

/**
 * Reads the share once, off the main thread, and keeps what it read for as long as the share screen lives (a rotation
 * does not read it again). Cancelled when the screen closes; nothing is stored here.
 */
internal class ShareIntakeViewModel(
    request: ShareRequest,
    source: ShareSource,
    io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {
    private val current = MutableStateFlow<SharedIntake?>(null)

    /** Null while reading; then what was read. */
    val intake: StateFlow<SharedIntake?> = current.asStateFlow()

    init {
        viewModelScope.launch {
            current.value = try {
                runInterruptible(io) { ShareIntake.read(request, source) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                SharedIntake(emptyList(), listOf(ShareIntake.UNREADABLE))
            }
        }
    }
}
