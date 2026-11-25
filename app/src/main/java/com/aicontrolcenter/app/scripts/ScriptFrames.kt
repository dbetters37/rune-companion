package com.aicontrolcenter.app.scripts

import com.aicontrolcenter.framework.operator.JsLimits
import com.aicontrolcenter.framework.operator.JsOutcome
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** How a run ended, as the script process tells the app in `IScriptResult.done` (Phase 6, rule 11). */
internal object ScriptStatus {
    /** The outcome frame is in the pipe handed over with it. */
    const val OUTCOME = 0

    /** The script process could not read the request; nothing of the script ran. */
    const val BAD_REQUEST = 1

    /** The script process's watchdog found the run overdue and ends its own process. */
    const val TIMED_OUT = 2

    /** The script process ran out of memory and ends itself quietly. */
    const val OUT_OF_MEMORY = 3

    /** Something else failed in the script process, which ends itself quietly. */
    const val FAILED = 4
}

/** A frame that could not be read: cut off, larger than its cap, or not a frame of this version. */
internal class FrameProblem(message: String) : IOException(message)

/**
 * The frames that travel in the pipes between the app and the script process (Phase 6, rule 11; spike F-D).
 *
 * Every text is a count of UTF-16 units and the units themselves, so it arrives exactly as it was sent (also a lone
 * surrogate, which UTF-8 would turn into "?", and which would change a script), and every cap is a count of characters,
 * as the [JsLimits] are. Nothing is escaped and nothing is nested: a reader checks each count against its cap before it
 * reads a byte of the text, so a frame can never make it read more than the cap, recurse or allocate beyond it.
 *
 * The app writes requests and workspace replies, and reads outcomes and workspace texts with its own caps; the script
 * process is untrusted, so whatever the app reads from it is checked against the limits again ([readOutcome]).
 */
internal object ScriptFrames {
    private const val MAGIC = 0x41494353
    private const val VERSION = 1

    /** The longest stop reason or error message the app takes from the script process. */
    const val MAX_REASON_CHARS = 2_000

    /** The longest folder listing a workspace reply carries (64 KiB of UTF-16, rule 11). */
    const val MAX_LIST_CHARS = 32_768

    /** The longest path the app accepts from the script process. */
    const val MAX_PATH_CHARS = 4_096

    /** Absolute caps of the script process on a request, whatever its header says. */
    private const val MAX_CODE_CHARS = 1_000_000
    private const val MAX_INPUT_CHARS = 4_000_000

    /** A text in UTF-16 units; written in parts, so a long one needs no second copy of itself in bytes. */
    fun writeText(out: DataOutputStream, text: String) {
        out.writeInt(text.length)
        val buffer = ByteBuffer.allocate(CHUNK_CHARS * 2)
        var at = 0
        while (at < text.length) {
            val end = minOf(text.length, at + CHUNK_CHARS)
            buffer.clear()
            for (i in at until end) buffer.putChar(text[i])
            out.write(buffer.array(), 0, (end - at) * 2)
            at = end
        }
    }

    /** A text of at most [maxChars] characters; a longer one is refused before any of it is read. */
    fun readText(input: DataInputStream, maxChars: Int): String {
        val length = input.readInt()
        if (length < 0 || length > maxChars) throw FrameProblem("a text of $length characters, over the cap of $maxChars")
        val chars = CharArray(length)
        val bytes = ByteArray(CHUNK_CHARS * 2)
        var at = 0
        while (at < length) {
            val count = minOf(CHUNK_CHARS, length - at)
            input.readFully(bytes, 0, count * 2)
            val view = ByteBuffer.wrap(bytes, 0, count * 2)
            for (i in 0 until count) chars[at + i] = view.getChar()
            at += count
        }
        return String(chars)
    }

    private fun header(out: DataOutputStream) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
    }

    private fun checkHeader(input: DataInputStream) {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) throw FrameProblem("not a frame of this version")
    }

    // ---- The request: the app to the script process ----

    /** One run as the script process gets it. */
    class Request(val limits: JsLimits, val code: String, val input: String?, val hasHost: Boolean, val canWrite: Boolean)

    fun writeRequest(stream: OutputStream, request: Request) {
        val out = DataOutputStream(stream.buffered(BUFFER_BYTES))
        header(out)
        val l = request.limits
        out.writeLong(l.timeoutMs)
        out.writeLong(l.engineStartMs)
        out.writeLong(l.maxInstructions)
        out.writeInt(l.maxStackDepth)
        out.writeInt(l.maxResultChars)
        out.writeInt(l.maxLogChars)
        out.writeInt(l.maxLogLines)
        out.writeInt(l.maxStringChars)
        out.writeInt(l.maxArrayLength)
        out.writeLong(l.maxHeapGrowthBytes)
        out.writeLong(l.threadStackBytes)
        out.writeInt(l.maxCodeChars)
        out.writeInt(l.maxInputChars)
        out.writeBoolean(request.hasHost)
        out.writeBoolean(request.canWrite)
        writeText(out, request.code)
        out.writeBoolean(request.input != null)
        request.input?.let { writeText(out, it) }
        out.flush()
    }

    /** In the script process. Bounds that are not positive are refused (as [JsLimits] refuses them). */
    fun readRequest(stream: InputStream): Request {
        val input = DataInputStream(stream.buffered(BUFFER_BYTES))
        checkHeader(input)
        val timeoutMs = input.readLong()
        val engineStartMs = input.readLong()
        val maxInstructions = input.readLong()
        val maxStackDepth = input.readInt()
        val maxResultChars = input.readInt()
        val maxLogChars = input.readInt()
        val maxLogLines = input.readInt()
        val maxStringChars = input.readInt()
        val maxArrayLength = input.readInt()
        val maxHeapGrowthBytes = input.readLong()
        val threadStackBytes = input.readLong()
        val maxCodeChars = input.readInt()
        val maxInputChars = input.readInt()
        val limits = try {
            JsLimits(
                maxCodeChars = maxCodeChars, maxInputChars = maxInputChars, maxInstructions = maxInstructions, timeoutMs = timeoutMs,
                maxStackDepth = maxStackDepth, maxResultChars = maxResultChars, maxLogChars = maxLogChars, maxLogLines = maxLogLines,
                maxStringChars = maxStringChars, maxArrayLength = maxArrayLength, maxHeapGrowthBytes = maxHeapGrowthBytes,
                threadStackBytes = threadStackBytes, engineStartMs = engineStartMs,
            )
        } catch (e: IllegalArgumentException) {
            throw FrameProblem("limits that are not positive")
        }
        val hasHost = input.readBoolean()
        val canWrite = input.readBoolean()
        val code = readText(input, minOf(MAX_CODE_CHARS, maxCodeChars))
        val text = if (input.readBoolean()) readText(input, minOf(MAX_INPUT_CHARS, maxInputChars)) else null
        return Request(limits, code, text, hasHost, canWrite)
    }

    // ---- The outcome: the script process to the app ----

    private const val FINISHED = 0
    private const val SCRIPT_ERROR = 1
    private const val STOPPED = 2

    /** In the script process: [outcome], and whether the run's thread has ended there ([threadEnded]). */
    fun writeOutcome(stream: OutputStream, outcome: JsOutcome, threadEnded: Boolean) {
        val out = DataOutputStream(stream.buffered(BUFFER_BYTES))
        header(out)
        val (kind, main) = when (outcome) {
            is JsOutcome.Finished -> FINISHED to outcome.resultJson
            is JsOutcome.ScriptError -> SCRIPT_ERROR to outcome.message
            is JsOutcome.Stopped -> STOPPED to outcome.reason
        }
        out.writeByte(kind)
        out.writeBoolean(threadEnded)
        out.writeBoolean((outcome as? JsOutcome.Finished)?.resultTruncated == true)
        out.writeBoolean(outcome.logTruncated)
        out.writeBoolean(main != null)
        main?.let { writeText(out, it) }
        out.writeInt((outcome as? JsOutcome.ScriptError)?.line ?: 0)
        out.writeInt(outcome.log.size)
        outcome.log.forEach { writeText(out, it) }
        out.flush()
    }

    /** An outcome as the app takes it: within [JsLimits], whatever the script process wrote. */
    class Outcome(val outcome: JsOutcome, val threadEnded: Boolean)

    /**
     * In the app: the outcome frame, read with caps from [limits] (the result, each console line and all of them, the
     * number of lines, a reason or a message) and never more bytes than those caps allow in all.
     *
     * @throws FrameProblem for a frame over a cap, of another version, or cut off ([EOFException] is one too)
     */
    fun readOutcome(stream: InputStream, limits: JsLimits): Outcome {
        val mainCap = maxOf(limits.maxResultChars, MAX_REASON_CHARS)
        val byteCap = 64L + 2L * (mainCap + limits.maxLogChars) + 4L * limits.maxLogLines
        val input = DataInputStream(Capped(stream.buffered(BUFFER_BYTES), byteCap))
        try {
            checkHeader(input)
            val kind = input.readByte().toInt()
            if (kind !in FINISHED..STOPPED) throw FrameProblem("an outcome of an unknown kind")
            val threadEnded = input.readBoolean()
            val resultTruncated = input.readBoolean()
            val logTruncated = input.readBoolean()
            val main = if (input.readBoolean()) readText(input, if (kind == FINISHED) limits.maxResultChars else MAX_REASON_CHARS) else null
            val line = input.readInt().takeIf { it > 0 }
            val lines = input.readInt()
            if (lines < 0 || lines > limits.maxLogLines) throw FrameProblem("$lines console lines, over the cap of ${limits.maxLogLines}")
            val log = ArrayList<String>(lines)
            var chars = 0
            repeat(lines) {
                val text = readText(input, limits.maxLogChars - chars)
                chars += text.length
                log += text
            }
            val outcome = when (kind) {
                FINISHED -> JsOutcome.Finished(main, resultTruncated, log, logTruncated)
                SCRIPT_ERROR -> JsOutcome.ScriptError(main ?: "The script failed.", line, log, logTruncated)
                else -> JsOutcome.Stopped(main ?: "The script was stopped.", log, logTruncated)
            }
            return Outcome(outcome, threadEnded)
        } catch (e: EOFException) {
            throw FrameProblem("the frame was cut off")
        }
    }

    // ---- The workspace: replies of the app, texts of the script process ----

    /** In the app: a read's reply, the file's text ([ok]) or the refusal. */
    fun writeReply(stream: OutputStream, ok: Boolean, text: String) {
        val out = DataOutputStream(stream.buffered(BUFFER_BYTES))
        out.writeBoolean(ok)
        writeText(out, text)
        out.flush()
    }

    /** In the script process: a read's reply, a text of at most [maxChars] or a refusal; null text for a refusal is never sent. */
    fun readReply(stream: InputStream, maxChars: Int): Pair<Boolean, String> {
        val input = DataInputStream(stream.buffered(BUFFER_BYTES))
        val ok = input.readBoolean()
        return ok to readText(input, if (ok) maxChars else MAX_REASON_CHARS)
    }

    /** In the script process: the text of a file the script creates. */
    fun writeContent(stream: OutputStream, text: String) {
        val out = DataOutputStream(stream.buffered(BUFFER_BYTES))
        writeText(out, text)
        out.flush()
    }

    /** In the app: the text of a file the script creates, at most [maxChars]. */
    fun readContent(stream: InputStream, maxChars: Int): String = try {
        readText(DataInputStream(stream.buffered(BUFFER_BYTES)), maxChars)
    } catch (e: EOFException) {
        throw FrameProblem("the text was cut off")
    }

    /** "+" and the reply, or "!" and the refusal ([listReply], `IScriptHost.write`). */
    fun ok(text: String): String = "+$text"

    fun refused(reason: String): String = "!" + reason.take(MAX_REASON_CHARS)

    /**
     * In the app: a folder's entries as a listing reply, at most [MAX_LIST_CHARS] characters in all; entries that would
     * go over it are left out (a folder of a few hundred files fits).
     */
    fun listReply(entries: List<Triple<String, Boolean, Long?>>): String {
        val out = StringBuilder("+[")
        for ((name, folder, size) in entries) {
            val entry = JsonObject(mapOf("name" to JsonPrimitive(name), "folder" to JsonPrimitive(folder), "size" to (size?.let(::JsonPrimitive) ?: JsonNull))).toString()
            if (out.length + entry.length + 2 > MAX_LIST_CHARS) break
            if (out.length > 2) out.append(',')
            out.append(entry)
        }
        return out.append(']').toString()
    }

    /** In the script process: the entries of a listing reply's JSON (from the app, which is trusted). */
    fun listEntries(json: String): List<Triple<String, Boolean, Long?>> =
        kotlinx.serialization.json.Json.parseToJsonElement(json).jsonArray.map { element ->
            val entry = element.jsonObject
            Triple(entry["name"]!!.jsonPrimitive.content, entry["folder"]?.jsonPrimitive?.booleanOrNull == true, entry["size"]?.jsonPrimitive?.longOrNull)
        }

    /** A stream that refuses to give more than [cap] bytes in all. */
    private class Capped(input: InputStream, private val cap: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            if (count >= cap) throw FrameProblem("a frame over its cap of $cap bytes")
            return super.read().also { if (it >= 0) count++ }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (count >= cap) throw FrameProblem("a frame over its cap of $cap bytes")
            return super.read(b, off, minOf(len.toLong(), cap - count).toInt()).also { if (it > 0) count += it }
        }
    }

    private const val CHUNK_CHARS = 16_384
    private const val BUFFER_BYTES = 64 * 1024
}
