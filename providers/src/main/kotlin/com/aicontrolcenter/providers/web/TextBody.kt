package com.aicontrolcenter.providers.web

import okhttp3.MediaType
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Which responses are read as text, and how their bytes become text. Kept apart from the
 * networking so each rule can be tested on bytes alone.
 */
internal object TextBody {
    /** Application types that are text in all but name. */
    private val TEXTUAL_APPLICATION_TYPES = setOf(
        "xhtml+xml", "xml", "json", "javascript", "ecmascript", "x-javascript", "x-www-form-urlencoded", "csv", "x-sh", "x-yaml", "yaml",
    )

    /** How far into an HTML document a `<meta charset>` is looked for, as browsers do. */
    private const val META_SCAN_BYTES = 1024

    private val META_CHARSET = Regex("""<meta\s[^>]*?charset\s*=\s*["']?\s*([A-Za-z0-9_:.\-]+)""", RegexOption.IGNORE_CASE)

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    /** Whether a response declared as [type] is read: text, and the application types that are text (JSON, XML, scripts). */
    fun isTextual(type: MediaType): Boolean = when (type.type) {
        "text" -> true
        "application" -> type.subtype in TEXTUAL_APPLICATION_TYPES || type.subtype.endsWith("+xml") || type.subtype.endsWith("+json")
        else -> false
    }

    /** For a response without a usable content type: text if it starts with a byte-order mark or has no NUL byte early on. */
    fun looksLikeText(bytes: ByteArray): Boolean =
        bomCharset(bytes) != null || (0 until minOf(bytes.size, META_SCAN_BYTES)).none { bytes[it] == 0.toByte() }

    /**
     * The charset of [bytes]: a byte-order mark first, then the `charset` of the content type,
     * then for HTML a `<meta charset>` near the start, else UTF-8. A charset this runtime does
     * not support counts as absent. Latin-1 and ASCII labels mean windows-1252, its superset,
     * as they do in browsers.
     */
    fun charsetOf(type: MediaType?, bytes: ByteArray): Charset {
        bomCharset(bytes)?.let { return it }
        type?.parameter("charset")?.let(::supported)?.let { return it }
        if (type == null || type.subtype == "html" || type.subtype == "xhtml+xml") {
            metaCharset(bytes)?.let { return it }
        }
        return StandardCharsets.UTF_8
    }

    /**
     * [bytes] as text in [charset], without a byte-order mark. Malformed input becomes U+FFFD.
     * When the body was cut at the size limit ([truncated]), an incomplete character at the
     * very end is dropped instead of being shown as U+FFFD.
     */
    fun decode(bytes: ByteArray, charset: Charset, truncated: Boolean): String {
        val start = bomLength(bytes, charset)
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        val input = ByteBuffer.wrap(bytes, start, bytes.size - start)
        val output = CharBuffer.allocate((input.remaining() * decoder.maxCharsPerByte().toDouble()).toInt() + 8)
        decoder.decode(input, output, !truncated)
        if (!truncated) decoder.flush(output)
        output.flip()
        return output.toString()
    }

    private fun bomCharset(bytes: ByteArray): Charset? = when {
        bytes.startsWith(UTF8_BOM) -> StandardCharsets.UTF_8
        bytes.startsWith(UTF16BE_BOM) -> StandardCharsets.UTF_16BE
        bytes.startsWith(UTF16LE_BOM) -> StandardCharsets.UTF_16LE
        else -> null
    }

    private fun bomLength(bytes: ByteArray, charset: Charset): Int = when {
        charset == StandardCharsets.UTF_8 && bytes.startsWith(UTF8_BOM) -> UTF8_BOM.size
        charset == StandardCharsets.UTF_16BE && bytes.startsWith(UTF16BE_BOM) -> UTF16BE_BOM.size
        charset == StandardCharsets.UTF_16LE && bytes.startsWith(UTF16LE_BOM) -> UTF16LE_BOM.size
        else -> 0
    }

    private fun metaCharset(bytes: ByteArray): Charset? {
        // Every byte stays one character in Latin-1, so the ASCII markup can be searched whatever the encoding.
        val head = String(bytes, 0, minOf(bytes.size, META_SCAN_BYTES), StandardCharsets.ISO_8859_1)
        val charset = META_CHARSET.find(head)?.groupValues?.get(1)?.let(::supported) ?: return null
        // A document whose markup could be read this way is not UTF-16, whatever it claims (as browsers decide).
        return if (charset.name().startsWith("UTF-16")) StandardCharsets.UTF_8 else charset
    }

    private fun supported(name: String): Charset? {
        val charset = try {
            Charset.forName(name.trim().trim('"', '\''))
        } catch (e: IllegalArgumentException) {
            // Unknown or illegal names.
            return null
        }
        return if (charset == StandardCharsets.ISO_8859_1 || charset == StandardCharsets.US_ASCII) windows1252 ?: charset else charset
    }

    private val windows1252: Charset? = try {
        Charset.forName("windows-1252")
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
