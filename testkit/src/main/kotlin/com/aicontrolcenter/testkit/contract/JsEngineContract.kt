package com.aicontrolcenter.testkit.contract

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/*
 * Phase 6 (WP6, rule 11): what every engine of `scripts.run_js` promises, whatever runs the script and wherever: the
 * Rhino sandbox in the app's process, the sandbox in the isolated script process behind the app's half of the engine,
 * and a script process that ignores every limit of its own (a hostile engine).
 *
 * The engine's own types (`JsEngine`, `JsHost`, `JsOutcome`, `JsLimits`) live in :framework, which :testkit does not
 * see, so the contract speaks in the types below, and each module's test passes its engine through a small adapter
 * (framework: `JsSandboxContractTest`; app: the isolated engine's contract tests).
 */

/** An engine under test: runs [code] with [inputJson] as `input` and [host] as `workspace`, for [timeoutMs] of the script's time. */
fun interface ScriptEngineUnderTest {
    suspend fun run(code: String, inputJson: String?, host: ScriptHostUnderTest?, timeoutMs: Long): ScriptEnded
}

/**
 * The workspace functions a script may be given (`JsHost`). A refusal throws [ScriptHostRefusal], which the adapter
 * turns into the engine's own refusal (`JsHostError`); any other exception stands for a failure of the workspace.
 */
interface ScriptHostUnderTest {
    val canWrite: Boolean

    fun read(path: String): String

    fun list(path: String): List<Triple<String, Boolean, Long?>>

    fun write(path: String, text: String): String
}

/** A refusal of a [ScriptHostUnderTest] function, with the sentence the script is to read. */
class ScriptHostRefusal(message: String) : RuntimeException(message)

/** How a run ended (`JsOutcome`), in the contract's words. */
sealed interface ScriptEnded {
    val log: List<String>
    val logTruncated: Boolean

    data class Finished(val resultJson: String?, val resultTruncated: Boolean, override val log: List<String>, override val logTruncated: Boolean) : ScriptEnded

    data class Failed(val message: String, val line: Int?, override val log: List<String>, override val logTruncated: Boolean) : ScriptEnded

    data class Stopped(val reason: String, override val log: List<String>, override val logTruncated: Boolean) : ScriptEnded
}

/** The bounds an engine under test is made with (`JsLimits`); the defaults are `JsLimits`'s. */
data class ScriptLimitsUnderTest(
    val timeoutMs: Long = 5_000,
    val maxCodeChars: Int = 50_000,
    val maxInputChars: Int = 200_000,
    val maxResultChars: Int = 100_000,
    val maxLogChars: Int = 16_000,
    val maxLogLines: Int = 500,
    val maxStringChars: Int = 5_000_000,
) {
    companion object {
        /** The most files one run may create (`JsSandbox.MAX_FILES_WRITTEN`). */
        const val MAX_FILES_WRITTEN = 20

        /** How long a run may overrun its time limit before its caller is answered without it (`JsSandbox.GRACE_MS`). */
        const val GRACE_MS = 2_000L

        /** The longest reason or message an engine may hand back, whatever its script process wrote. */
        const val MAX_REASON_CHARS = 2_000
    }
}

/** A workspace of texts in memory that records every call it serves, and when. */
class RecordingScriptHost(
    override val canWrite: Boolean,
    files: Map<String, String> = emptyMap(),
    /** Called for a read of a path that is not in [files] (null: refused as missing). */
    private val onMissing: ((String) -> String)? = null,
) : ScriptHostUnderTest {
    val files: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap(files)

    /** Every call served, as "read a", "list ", "write a". */
    val calls: MutableList<String> = CopyOnWriteArrayList()

    /** Files created, in order. */
    val written: MutableList<String> = CopyOnWriteArrayList()

    override fun read(path: String): String {
        calls += "read $path"
        return files[path] ?: onMissing?.invoke(path) ?: throw ScriptHostRefusal("\"$path\" does not exist.")
    }

    override fun list(path: String): List<Triple<String, Boolean, Long?>> {
        calls += "list $path"
        return files.keys.sorted().map { Triple(it, false, files[it]!!.length.toLong()) }
    }

    override fun write(path: String, text: String): String {
        calls += "write $path"
        if (!canWrite) throw ScriptHostRefusal("This workspace may not be changed.")
        if (files.containsKey(path)) throw ScriptHostRefusal("\"$path\" already exists; scripts never replace files.")
        files[path] = text
        written += path
        return path
    }
}

/**
 * The limits that protect the app, for every engine, including one whose script process ignores every limit of its
 * own (rule 11): at most [ScriptLimitsUnderTest.MAX_FILES_WRITTEN] files, none without write access, no host call once
 * [ScriptEngineUnderTest.run] has returned, what comes back within the limits, an answer about the time limit plus the
 * grace after the script's own code began, a cancelled caller freed, and failures as outcomes, never as exceptions.
 *
 * The scripts below are honest attempts at each thing; a hostile engine is free to ignore them and do worse, and the
 * checks still hold.
 */
abstract class JsEngineContract {
    /** A fresh engine made for [limits]. */
    protected abstract fun engine(limits: ScriptLimitsUnderTest = ScriptLimitsUnderTest()): ScriptEngineUnderTest

    /**
     * How long this engine may take to get ready before the script's own code, at most, in this test's environment
     * (a thread and Rhino in this process; for an engine in another process, starting that process too).
     */
    protected open val readyMs: Long = 10_000

    protected fun <T> blocking(block: suspend () -> T): T = runBlocking(Dispatchers.Default) { block() }

    @Test(timeout = 120_000)
    fun `a script creates at most 20 files, and none without write access`() = blocking {
        val code = "var made = 0; for (var i = 0; i < 25; i++) { try { workspace.write('f' + i + '.txt', 'x'); made++; } catch (e) {} } made"
        val writer = RecordingScriptHost(canWrite = true)
        engine().run(code, null, writer, 5_000)
        assertTrue(writer.written.size <= ScriptLimitsUnderTest.MAX_FILES_WRITTEN, "created ${writer.written.size} files")
        val reader = RecordingScriptHost(canWrite = false)
        engine().run(code, null, reader, 5_000)
        assertEquals(emptyList(), reader.written.toList(), "a run without write access created files")
        assertTrue(reader.calls.none { it.startsWith("write") }, "a write reached a host without write access: ${reader.calls}")
    }

    @Test(timeout = 120_000)
    fun `no host call reaches the host once run has returned`() = blocking {
        val host = RecordingScriptHost(canWrite = true, files = mapOf("a.txt" to "a"))
        val ended = engine().run("var n = 0; for (;;) { workspace.read('a.txt'); workspace.list(''); n++; }", null, host, 300)
        assertIs<ScriptEnded.Stopped>(ended, "a script that never stops is stopped")
        val atReturn = host.calls.size
        delay(1_500)
        assertEquals(atReturn, host.calls.size, "host calls after the run returned: ${host.calls.drop(atReturn).take(5)}")
    }

    @Test(timeout = 120_000)
    fun `what comes back stays within the limits`() = blocking {
        val limits = ScriptLimitsUnderTest(maxResultChars = 1_000, maxLogChars = 2_000, maxLogLines = 10)
        val ended = engine(limits).run("for (var i = 0; i < 50; i++) console.log('line ' + i + ' ' + 'y'.repeat(300)); 'x'.repeat(5000)", null, null, 5_000)
        assertWithin(limits, ended)
        val failed = engine(limits).run("throw new Error('e'.repeat(10000))", null, null, 5_000)
        assertWithin(limits, failed)
    }

    @Test(timeout = 120_000)
    fun `a script that does not stop is answered about its time limit and the grace after its code began`() = blocking {
        val timeout = 500L
        val started = System.nanoTime()
        val ended = engine().run("for (;;) {}", null, null, timeout)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertIs<ScriptEnded.Stopped>(ended)
        val bound = readyMs + timeout + ScriptLimitsUnderTest.GRACE_MS + ANSWER_SLACK_MS
        assertTrue(tookMs <= bound, "answered after $tookMs ms (bound $bound ms)")
    }

    @Test(timeout = 120_000)
    fun `cancelling the caller ends the wait`() = blocking {
        val engine = engine()
        // Warmed up first, so the cancelled run is in the script's own code when it is cancelled.
        engine.run("1", null, null, 5_000)
        val run = kotlinx.coroutines.coroutineScope {
            val call = async(start = CoroutineStart.UNDISPATCHED) { engine.run("for (;;) {}", null, null, 10_000) }
            delay(300)
            val cancelledAt = System.nanoTime()
            call.cancel()
            runCatching { withTimeout(5_000) { call.join() } }.exceptionOrNull()?.let { throw AssertionError("the cancelled call did not end within 5 s", it) }
            (System.nanoTime() - cancelledAt) / 1_000_000
        }
        assertTrue(run <= 5_000, "the cancelled call ended after $run ms")
    }

    @Test(timeout = 120_000)
    fun `too long a script or input is an outcome, not an exception`() = blocking {
        val limits = ScriptLimitsUnderTest(maxCodeChars = 100, maxInputChars = 100)
        val code = engine(limits).run("1 + " + "1 + ".repeat(50) + "1", null, null, 5_000)
        assertIs<ScriptEnded.Stopped>(code)
        assertTrue("100" in code.reason, code.reason)
        val input = engine(limits).run("1", "\"" + "i".repeat(200) + "\"", null, 5_000)
        assertIs<ScriptEnded.Stopped>(input)
        assertTrue("100" in input.reason, input.reason)
    }

    protected fun assertWithin(limits: ScriptLimitsUnderTest, ended: ScriptEnded) {
        assertTrue(ended.log.size <= limits.maxLogLines, "${ended.log.size} log lines")
        assertTrue(ended.log.sumOf { it.length } <= limits.maxLogChars, "${ended.log.sumOf { it.length }} log characters")
        when (ended) {
            is ScriptEnded.Finished -> assertTrue((ended.resultJson?.length ?: 0) <= limits.maxResultChars, "result of ${ended.resultJson?.length} characters")
            is ScriptEnded.Failed -> assertTrue(ended.message.length <= ScriptLimitsUnderTest.MAX_REASON_CHARS, "message of ${ended.message.length} characters")
            is ScriptEnded.Stopped -> assertTrue(ended.reason.length <= ScriptLimitsUnderTest.MAX_REASON_CHARS, "reason of ${ended.reason.length} characters")
        }
    }

    companion object {
        /** Time an engine may take beyond the time limit and the grace to hand its answer over. */
        const val ANSWER_SLACK_MS = 3_000L
    }
}

/**
 * Everything of [JsEngineContract], and the script runs as written: for the in-process sandbox and for the isolated
 * engine with an honest script process (which may also start slowly and be ended by Android between runs).
 */
abstract class HonestJsEngineContract : JsEngineContract() {
    private fun finished(ended: ScriptEnded): ScriptEnded.Finished = assertIs<ScriptEnded.Finished>(ended, "$ended")

    @Test(timeout = 120_000)
    fun `a script's last value comes back as JSON`() = blocking {
        assertEquals("2", finished(engine().run("1 + 1", null, null, 5_000)).resultJson)
        assertEquals("{\"a\":[1,\"two\",null]}", finished(engine().run("({a: [1, 'two', null]})", null, null, 5_000)).resultJson)
        assertEquals(null, finished(engine().run("undefined", null, null, 5_000)).resultJson)
    }

    @Test(timeout = 120_000)
    fun `regular expressions work`() = blocking {
        // Spike F-B: Rhino loads its regular-expression engine once per process, through the loading thread's class loader.
        assertEquals("[true,\"b-c\"]", finished(engine().run("[/a+/.test('caa'), 'b c'.replace(/\\s/g, '-')]", null, null, 5_000)).resultJson)
    }

    @Test(timeout = 120_000)
    fun `the input arrives as it was given, and a large input and result make the round trip`() = blocking {
        val input = "{\"text\":\"Grüße, 世界 😀\",\"n\":[1,2,3]}"
        assertEquals("\"Grüße, 世界 😀 6\"", finished(engine().run("input.text + ' ' + input.n.reduce(function (a, b) { return a + b; })", input, null, 5_000)).resultJson)
        val big = "\"" + "é".repeat(199_990) + "\""
        val ended = finished(engine().run("input.length + ':' + 'r'.repeat(99_990)", big, null, 20_000))
        assertFalse(ended.resultTruncated)
        assertEquals("\"199990:" + "r".repeat(99_990) + "\"", ended.resultJson)
    }

    @Test(timeout = 120_000)
    fun `a result over the limit is cut and marked`() = blocking {
        val ended = finished(engine(ScriptLimitsUnderTest(maxResultChars = 100)).run("'x'.repeat(50) + 'y'.repeat(20)", null, null, 5_000))
        assertFalse(ended.resultTruncated)
        val cut = finished(engine(ScriptLimitsUnderTest(maxResultChars = 100)).run("[1, 'x'.repeat(200)]", null, null, 5_000))
        assertTrue(cut.resultTruncated)
        assertEquals(100, cut.resultJson!!.length)
    }

    @Test(timeout = 120_000)
    fun `console output comes back, and is cut at the limit`() = blocking {
        val ended = finished(engine().run("console.log('a', 1); console.info({b: 2}); 3", null, null, 5_000))
        assertEquals(listOf("a 1", "{\"b\":2}"), ended.log)
        assertFalse(ended.logTruncated)
        val cut = finished(engine(ScriptLimitsUnderTest(maxLogLines = 3)).run("for (var i = 0; i < 10; i++) console.log(i); 0", null, null, 5_000))
        assertTrue(cut.logTruncated)
        assertEquals(3, cut.log.size)
    }

    @Test(timeout = 120_000)
    fun `a script that throws or does not compile fails with its message and line`() = blocking {
        val thrown = assertIs<ScriptEnded.Failed>(engine().run("var a = 1;\nthrow new Error('broken here');", null, null, 5_000))
        assertTrue("broken here" in thrown.message, thrown.message)
        assertEquals(2, thrown.line)
        val syntax = assertIs<ScriptEnded.Failed>(engine().run("var a = ;", null, null, 5_000))
        assertEquals(1, syntax.line)
    }

    @Test(timeout = 120_000)
    fun `a loop that does not end is stopped at the time limit`() = blocking {
        val ended = assertIs<ScriptEnded.Stopped>(engine().run("var i = 0; for (;;) { i++; }", null, null, 300))
        assertTrue("300 ms" in ended.reason || "instructions" in ended.reason, ended.reason)
    }

    @Test(timeout = 120_000)
    fun `the workspace reads, lists and creates files through the host`() = blocking {
        val host = RecordingScriptHost(canWrite = true, files = mapOf("in.txt" to "seven"))
        val ended = finished(
            engine().run(
                "var t = workspace.read('in.txt'); var made = workspace.write('out.txt', t.toUpperCase()); [made, workspace.list('').map(function (e) { return e.name + ':' + e.size; })]",
                null,
                host,
                5_000,
            ),
        )
        assertEquals("[\"out.txt\",[\"in.txt:5\",\"out.txt:5\"]]", ended.resultJson)
        assertEquals("SEVEN", host.files["out.txt"])
    }

    @Test(timeout = 120_000)
    fun `refusals reach the script as errors it can read`() = blocking {
        val reader = RecordingScriptHost(canWrite = false)
        val noWrite = finished(engine().run("try { workspace.write('a.txt', 'x'); 'written' } catch (e) { e.message }", null, reader, 5_000))
        assertEquals("\"This script may only read the workspace.\"", noWrite.resultJson)
        val host = RecordingScriptHost(canWrite = true, files = mapOf("a.txt" to "old"))
        val exists = finished(engine().run("try { workspace.write('a.txt', 'new'); 'written' } catch (e) { e.message }", null, host, 5_000))
        assertEquals("\"\\\"a.txt\\\" already exists; scripts never replace files.\"", exists.resultJson)
        assertEquals("old", host.files["a.txt"])
        val missing = finished(engine().run("try { workspace.read('none.txt') } catch (e) { e.message }", null, host, 5_000))
        assertEquals("\"\\\"none.txt\\\" does not exist.\"", missing.resultJson)
    }

    @Test(timeout = 120_000)
    fun `the twenty-first file is refused with the plain sentence`() = blocking {
        val host = RecordingScriptHost(canWrite = true)
        val ended = finished(
            engine().run(
                "var made = 0, last = ''; for (var i = 0; i < 25; i++) { try { workspace.write('f' + i + '.txt', 'x'); made++; } catch (e) { last = e.message; } } [made, last]",
                null,
                host,
                10_000,
            ),
        )
        assertEquals("[20,\"A script may create at most 20 files.\"]", ended.resultJson)
        assertEquals(20, host.written.size)
    }

    @Test(timeout = 120_000)
    fun `a file longer than the string limit never reaches the script`() = blocking {
        val limits = ScriptLimitsUnderTest(maxStringChars = 1_000)
        val host = RecordingScriptHost(canWrite = false, files = mapOf("big.txt" to "b".repeat(1_001), "fits.txt" to "f".repeat(1_000)))
        val ended = finished(engine(limits).run("var r = []; ['fits.txt', 'big.txt'].forEach(function (p) { try { r.push(workspace.read(p).length) } catch (e) { r.push('refused') } }); r", null, host, 5_000))
        assertEquals("[1000,\"refused\"]", ended.resultJson)
    }

    @Test(timeout = 120_000)
    fun `a failing workspace is reported by its type only`() = blocking {
        val failures = AtomicInteger()
        val host = object : ScriptHostUnderTest {
            override val canWrite = false

            override fun read(path: String): String {
                failures.incrementAndGet()
                throw IllegalStateException("secret detail of the failure")
            }

            override fun list(path: String): List<Triple<String, Boolean, Long?>> = emptyList()

            override fun write(path: String, text: String): String = path
        }
        val ended = finished(engine().run("try { workspace.read('a') } catch (e) { e.message }", null, host, 5_000))
        assertTrue("failed" in ended.resultJson!! && "secret" !in ended.resultJson!!, ended.resultJson)
        assertEquals(1, failures.get())
    }
}
