package com.aicontrolcenter.app.scripts

import com.aicontrolcenter.framework.operator.JsLimits
import com.aicontrolcenter.framework.operator.JsOutcome
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 6 (WP6, rule 11; spike F-D): the frames between the app and the script process. Texts arrive exactly as sent;
 * whatever the script process writes, the app reads no more than its caps and nothing nested, and a frame that is cut
 * off, over a cap or of another version is refused.
 */
class ScriptFramesTest {
    private val limits = JsLimits(maxResultChars = 1_000, maxLogChars = 500, maxLogLines = 5)

    private fun bytes(write: (ByteArrayOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also(write).toByteArray()

    /** A stream that fails the test if more than [allowed] bytes are taken from it. */
    private class Counting(bytes: ByteArray, private val allowed: Int) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        var taken = 0

        override fun read(): Int = inner.read().also { if (it >= 0) taken++; check(taken <= allowed) { "read $taken bytes" } }

        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) taken += it; check(taken <= allowed) { "read $taken bytes" } }
    }

    @Test
    fun `a request arrives exactly as sent, lone surrogates and all`() {
        val code = "var s = '😀 \uD800 é'; /* ${"x".repeat(40_000)} */ s"
        val input = "\"" + "ü\uDC00".repeat(50_000) + "\""
        val request = ScriptFrames.Request(JsLimits(timeoutMs = 1_234, engineStartMs = 90_000, maxResultChars = 777), code, input, hasHost = true, canWrite = false)
        val read = ScriptFrames.readRequest(ByteArrayInputStream(bytes { ScriptFrames.writeRequest(it, request) }))
        assertEquals(code, read.code)
        assertEquals(input, read.input)
        assertEquals(request.limits, read.limits)
        assertTrue(read.hasHost)
        assertFalse(read.canWrite)
        val none = ScriptFrames.readRequest(ByteArrayInputStream(bytes { ScriptFrames.writeRequest(it, ScriptFrames.Request(JsLimits(), "1", null, false, false)) }))
        assertEquals(null, none.input)
    }

    @Test
    fun `a request over its own caps, or of another version, is refused`() {
        val long = bytes { ScriptFrames.writeRequest(it, ScriptFrames.Request(JsLimits(maxCodeChars = 10), "1 + 1 + 1 + 1", null, false, false)) }
        assertFailsWith<FrameProblem> { ScriptFrames.readRequest(ByteArrayInputStream(long)) }
        val other = bytes { ScriptFrames.writeRequest(it, ScriptFrames.Request(JsLimits(), "1", null, false, false)) }.also { it[7] = 9 }
        assertFailsWith<FrameProblem> { ScriptFrames.readRequest(ByteArrayInputStream(other)) }
    }

    @Test
    fun `every outcome makes the round trip`() {
        val outcomes = listOf(
            JsOutcome.Finished("[1,\"\uD800\"]", true, listOf("a", "b\uDFFF"), false),
            JsOutcome.Finished(null, false, emptyList(), true),
            JsOutcome.ScriptError("x is not defined", 3, listOf("log"), false),
            JsOutcome.ScriptError("no line", null, emptyList(), false),
            JsOutcome.Stopped("The script ran longer than 5 ms.", listOf("c"), true),
        )
        for (outcome in outcomes) {
            for (ended in listOf(true, false)) {
                val read = ScriptFrames.readOutcome(ByteArrayInputStream(bytes { ScriptFrames.writeOutcome(it, outcome, ended) }), limits)
                assertEquals(outcome, read.outcome)
                assertEquals(ended, read.threadEnded)
            }
        }
    }

    @Test
    fun `an outcome over any cap is refused before its text is read`() {
        val cases = listOf(
            JsOutcome.Finished("r".repeat(1_001), false, emptyList(), false),
            JsOutcome.Stopped("s".repeat(ScriptFrames.MAX_REASON_CHARS + 1), emptyList(), false),
            JsOutcome.ScriptError("m".repeat(ScriptFrames.MAX_REASON_CHARS + 1), 1, emptyList(), false),
            JsOutcome.Finished("1", false, List(6) { "l" }, false),
            JsOutcome.Finished("1", false, List(5) { "l".repeat(101) }, false),
            JsOutcome.Finished("1", false, listOf("l".repeat(501)), false),
        )
        for (outcome in cases) {
            val frame = bytes { ScriptFrames.writeOutcome(it, outcome, true) }
            val stream = Counting(frame, allowed = frame.size)
            assertFailsWith<FrameProblem>("$outcome") { ScriptFrames.readOutcome(stream, limits) }
        }
        // A count of two thousand million characters with nothing behind it: refused at the count, nothing allocated or awaited.
        val huge = bytes {
            val out = DataOutputStream(it)
            out.writeInt(0x41494353)
            out.writeInt(1)
            out.writeByte(0)
            repeat(3) { out.writeBoolean(false) }
            out.writeBoolean(true)
            out.writeInt(Int.MAX_VALUE)
        }
        assertFailsWith<FrameProblem> { ScriptFrames.readOutcome(ByteArrayInputStream(huge), limits) }
    }

    @Test
    fun `an outcome cut off, of an unknown kind, or of another version is refused`() {
        val frame = bytes { ScriptFrames.writeOutcome(it, JsOutcome.Finished("\"" + "x".repeat(100) + "\"", false, listOf("a"), false), true) }
        for (cut in listOf(0, 3, 9, 20, frame.size - 1)) {
            assertFailsWith<FrameProblem>("cut at $cut") { ScriptFrames.readOutcome(ByteArrayInputStream(frame.copyOf(cut)), limits) }
        }
        assertFailsWith<FrameProblem> { ScriptFrames.readOutcome(ByteArrayInputStream(frame.copyOf().also { it[8] = 7 }), limits) }
        assertFailsWith<FrameProblem> { ScriptFrames.readOutcome(ByteArrayInputStream(frame.copyOf().also { it[0] = 0 }), limits) }
    }

    @Test
    fun `the app never takes more bytes than an outcome within the caps can have`() {
        val endless = object : InputStream() {
            var given = 0L

            override fun read(): Int = 1.also { given++ }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 1)
                given += len
                return len
            }
        }
        // A header, then bytes for ever: the first count read (of a message) is over its cap, and reading stops there.
        val start = bytes {
            val out = DataOutputStream(it)
            out.writeInt(0x41494353)
            out.writeInt(1)
        }
        val stream = java.io.SequenceInputStream(ByteArrayInputStream(start), endless)
        assertFailsWith<FrameProblem> { ScriptFrames.readOutcome(stream, limits) }
        assertTrue(endless.given < 200_000, "${endless.given} bytes taken")
    }

    @Test
    fun `workspace replies and texts make the round trip, and a listing stays within its cap`() {
        val text = "Grüße \uD800 ${"z".repeat(70_000)}"
        assertEquals(true to text, ScriptFrames.readReply(ByteArrayInputStream(bytes { ScriptFrames.writeReply(it, true, text) }), 100_000))
        assertEquals(false to "no", ScriptFrames.readReply(ByteArrayInputStream(bytes { ScriptFrames.writeReply(it, false, "no") }), 1))
        assertFailsWith<FrameProblem> { ScriptFrames.readReply(ByteArrayInputStream(bytes { ScriptFrames.writeReply(it, true, text) }), 100) }
        assertEquals(text, ScriptFrames.readContent(ByteArrayInputStream(bytes { ScriptFrames.writeContent(it, text) }), text.length))
        assertFailsWith<FrameProblem> { ScriptFrames.readContent(ByteArrayInputStream(bytes { ScriptFrames.writeContent(it, text) }), text.length - 1) }

        val entries = listOf(Triple("a \"quoted\"\nname", false, 5L), Triple("folder", true, null))
        val reply = ScriptFrames.listReply(entries)
        assertTrue(reply.startsWith("+"))
        assertEquals(entries, ScriptFrames.listEntries(reply.substring(1)))
        val many = List(5_000) { Triple("file-$it.txt", false, it.toLong()) }
        val cut = ScriptFrames.listReply(many)
        assertTrue(cut.length <= ScriptFrames.MAX_LIST_CHARS, "${cut.length} characters")
        val kept = ScriptFrames.listEntries(cut.substring(1))
        assertEquals(many.take(kept.size), kept)
        assertTrue(kept.size in 100 until 5_000)
    }

    @Test
    fun `a text keeps every UTF-16 unit, whatever it is`() {
        val all = String(CharArray(70_000) { (it % 65_536).toChar() })
        val out = ByteArrayOutputStream()
        DataOutputStream(out).also { ScriptFrames.writeText(it, all) }.flush()
        assertEquals(all, ScriptFrames.readText(DataInputStream(ByteArrayInputStream(out.toByteArray())), all.length))
    }
}
