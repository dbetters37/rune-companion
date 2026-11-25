package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 6 (WP1c): `scripts.run_js` runs on whichever [JsEngine] the toolset is given, and hands
 * it plain data only; by default it is the Rhino sandbox in this process, as before.
 */
class JsEngineSeamTest {
    /** One call the engine received: the code, the input, the host's write permission (null: no host) and the time limit. */
    private data class Call(val code: String, val inputJson: String?, val canWrite: Boolean?, val timeoutMs: Long)

    private class RecordingEngine(private val outcome: (JsHost?) -> JsOutcome) : JsEngine {
        val calls = CopyOnWriteArrayList<Call>()

        override suspend fun run(code: String, inputJson: String?, host: JsHost?, timeoutMs: Long): JsOutcome {
            calls += Call(code, inputJson, host?.canWrite, timeoutMs)
            return outcome(host)
        }
    }

    private val f = OperatorFixture()

    private fun runJs(engine: JsEngine, vararg args: Pair<String, Any?>) = runBlocking {
        val tool = OperatorToolset.create(f.dependencies, engine).single { it.spec.id.value == "scripts.run_js" }
        val arguments = json(*args)
        val assessment = tool.assess(arguments, f.context)
        tool.execute(arguments, f.context, assessment)
    }

    @Test
    fun `run js hands the code, the input, the workspace access and the time limit to the engine it is given`() {
        val engine = RecordingEngine { JsOutcome.Finished("42", false, listOf("hello"), false) }
        val none = runJs(engine, "code" to "input.n * 2", "input" to mapOf("n" to 21))
        assertEquals("Result: 42\n--- console ---\nhello", none.content)
        assertTrue(none.ok)
        runJs(engine, "code" to "1", "workspace" to "read", "timeout_ms" to 300)
        runJs(engine, "code" to "2", "workspace" to "write")
        assertEquals(
            listOf(
                Call("input.n * 2", """{"n":21}""", null, f.dependencies.limits.js.timeoutMs),
                Call("1", null, false, 300),
                Call("2", null, true, f.dependencies.limits.js.timeoutMs),
            ),
            engine.calls.toList(),
        )
    }

    @Test
    fun `the engine's outcome becomes the answer as before`() {
        val stopped = runJs(RecordingEngine { JsOutcome.Stopped("The script ran longer than 5000 ms.", emptyList(), false) }, "code" to "1")
        assertTrue(!stopped.ok && stopped.content == "The script was stopped: The script ran longer than 5000 ms.", stopped.content)
        val failed = runJs(RecordingEngine { JsOutcome.ScriptError("x is not defined", 3, emptyList(), false) }, "code" to "x")
        assertTrue(!failed.ok && failed.content == "The script failed at line 3: x is not defined", failed.content)
    }

    @Test
    fun `the host an engine is handed reads and writes the workspace in this process, and never replaces a file`() {
        f.put("in.txt", "seven")
        val engine = RecordingEngine { host ->
            val read = host!!.read("in.txt")
            host.write("out.txt", read.uppercase())
            val again = runCatching { host.write("out.txt", "replaced") }.exceptionOrNull()
            JsOutcome.Finished("\"${again?.javaClass?.simpleName}\"", false, emptyList(), false)
        }
        val outcome = runJs(engine, "code" to "workspace", "workspace" to "write")
        assertEquals("Result: \"JsHostError\"", outcome.content)
        assertEquals("SEVEN", f.file("out.txt").readText())
    }

    @Test
    fun `the sandbox is an engine, and the toolset's default`() {
        val engine: JsEngine = JsSandbox(JsLimits(timeoutMs = 20_000))
        val outcome = runBlocking { engine.run("[1, 2].map(function (x) { return x * 3; })", null, null, 20_000) }
        assertEquals("[3,6]", assertIs<JsOutcome.Finished>(outcome).resultJson)
        assertEquals("Result: 6", runBlocking { f.ok("scripts.run_js", "code" to "2 * 3") }.content)
        assertNull(assertIs<JsOutcome.Finished>(runBlocking { JsSandbox(JsLimits(timeoutMs = 20_000)).run("undefined", null, null) }).resultJson)
    }
}
