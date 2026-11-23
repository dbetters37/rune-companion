package com.aicontrolcenter.framework.operator

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Rhino sandbox against escape and exhaustion attempts. Runs on real threads with real time
 * (the sandbox's budget is wall-clock), with short limits so the suite stays fast.
 */
class JsSandboxTest {
    private val limits = JsLimits(timeoutMs = 1_500, maxInstructions = 20_000_000)
    private val sandbox = JsSandbox(limits)

    private fun run(code: String, input: String? = null, host: JsHost? = null): JsOutcome = runBlocking { sandbox.run(code, input, host) }

    private fun finished(code: String, input: String? = null, host: JsHost? = null): JsOutcome.Finished {
        val outcome = run(code, input, host)
        return assertIs<JsOutcome.Finished>(outcome, "expected the script to finish: $outcome")
    }

    @Test
    fun `input comes in as JSON, the last expression goes out as JSON, and console log is captured`() {
        val outcome = finished(
            """
            console.log("total of", input.items.length, "items");
            console.log({ a: 1 });
            ({ sum: input.items.reduce(function (a, b) { return a + b; }, 0), name: input.name.toUpperCase() })
            """.trimIndent(),
            input = """{"items":[1,2,3.5],"name":"ab"}""",
        )
        assertEquals("""{"sum":6.5,"name":"AB"}""", outcome.resultJson)
        assertEquals(listOf("total of 3 items", """{"a":1}"""), outcome.log)
    }

    @Test
    fun `modern syntax works in interpreted mode`() {
        val outcome = finished("const f = (x) => x * 2; let [a, b] = [1, 2]; `${'$'}{f(a)}-${'$'}{b}`")
        assertEquals("\"2-2\"", outcome.resultJson)
    }

    @Test
    fun `undefined and functions give no result`() {
        assertEquals(null, finished("undefined").resultJson)
        assertEquals(null, finished("(function () {})").resultJson)
    }

    @Test
    fun `no Java is reachable by name`() {
        for (code in listOf(
            "java.lang.System.exit(1)",
            "Packages.java.io.File",
            "new JavaImporter(java.io)",
            "importPackage(java.io)",
            "importClass(java.io.File)",
            "getClass",
            "javax",
            "org.mozilla.javascript.Context",
            "JavaAdapter",
        )) {
            val outcome = run(code)
            assertIs<JsOutcome.ScriptError>(outcome, "$code: $outcome")
            assertTrue("not defined" in outcome.message, "$code: ${outcome.message}")
        }
    }

    @Test
    fun `objects, functions and errors expose nothing of Java`() {
        val outcome = finished(
            """
            var found = [];
            function probe(name, value) { if (value !== undefined && value !== null) found.push(name); }
            probe("object.getClass", ({}).getClass);
            probe("function.getClass", (function () {}).getClass);
            probe("console.getClass", console.getClass);
            probe("log.getClass", console.log.getClass);
            probe("log.__javaObject__", console.log.__javaObject__);
            probe("ctor", Function("return typeof java")() === "undefined" ? undefined : "java");
            try { null.x; } catch (e) { probe("javaException", e.javaException); probe("rhinoException", e.rhinoException); }
            try { undefinedFunction(); } catch (e) { probe("javaException2", e.javaException); }
            found
            """.trimIndent(),
        )
        assertEquals("[]", outcome.resultJson)
    }

    @Test
    fun `host errors reach the script as plain errors without the Java exception`() {
        val host = object : JsHost {
            override val canWrite = false
            override fun read(path: String): String = throw IllegalStateException("secret internals")
            override fun list(path: String) = emptyList<Triple<String, Boolean, Long?>>()
            override fun write(path: String, text: String) = throw JsHostError("no")
        }
        val outcome = finished(
            """
            var r = [];
            try { workspace.read("a.txt"); } catch (e) { r.push(String(e.message), typeof e.javaException); }
            try { workspace.write("b.txt", "x"); } catch (e) { r.push(String(e.message)); }
            r
            """.trimIndent(),
            host = host,
        )
        assertEquals("""["The workspace operation failed (IllegalStateException).","undefined","This script may only read the workspace."]""", outcome.resultJson)
    }

    @Test
    fun `an endless loop is stopped, and neither catch nor finally runs`() {
        val outcome = run(
            """
            try { while (true) {} } catch (e) { console.log("caught", e); } finally { console.log("finally"); }
            """.trimIndent(),
        )
        assertIs<JsOutcome.Stopped>(outcome, outcome.toString())
        assertTrue(outcome.log.isEmpty(), outcome.log.toString())
    }

    @Test
    fun `an endless loop that keeps catching is still stopped`() {
        val outcome = run("for (;;) { try { for (;;) {} } catch (e) {} }")
        assertIs<JsOutcome.Stopped>(outcome, outcome.toString())
    }

    @Test
    fun `the instruction budget stops a busy script before its time runs out`() {
        val tight = JsSandbox(JsLimits(timeoutMs = 60_000, maxInstructions = 200_000))
        val outcome = runBlocking { tight.run("var i = 0; while (true) { i++; }", null, null) }
        assertIs<JsOutcome.Stopped>(outcome)
        assertTrue("instructions" in outcome.reason, outcome.reason)
    }

    @Test
    fun `catastrophic regular expressions count against the budget`() {
        val outcome = run("""/(a+)+${'$'}/.test("a".repeat(40) + "!")""")
        assertIs<JsOutcome.Stopped>(outcome, outcome.toString())
    }

    @Test
    fun `deep recursion ends as an error the script can see, not a crash`() {
        val outcome = run("function f(n) { return f(n + 1) + 1; } f(0)")
        assertIs<JsOutcome.ScriptError>(outcome, outcome.toString())
        assertTrue("stack" in outcome.message.lowercase(), outcome.message)
        // Recursion through native calls (map) is bounded by the thread's own stack.
        val native = run("function g() { return [1].map(g); } g()")
        assertTrue(native is JsOutcome.ScriptError || native is JsOutcome.Stopped, native.toString())
    }

    @Test
    fun `giant strings are refused or stopped, never built`() {
        val repeat = run("\"x\".repeat(1e9)")
        assertIs<JsOutcome.ScriptError>(repeat, repeat.toString())
        assertTrue("RangeError" in repeat.message, repeat.message)
        val pad = run("\"x\".padStart(2e9)")
        assertIs<JsOutcome.ScriptError>(pad, pad.toString())
        val join = run("new Array(1e9).join(\"x\")")
        assertIs<JsOutcome.ScriptError>(join, join.toString())
        val split = run("var s = \"ab\"; for (var i = 0; i < 21; i++) s += s; s.split(\"\")")
        assertIs<JsOutcome.ScriptError>(split, split.toString())
        assertTrue("RangeError" in split.message, split.message)
        assertEquals("\"a|b|c\"", finished("\"a,b,c\".split(\",\").join(\"|\")").resultJson)
        val doubling = run("var s = \"x\"; while (true) { s += s; s.length; }")
        assertTrue(doubling is JsOutcome.Stopped || doubling is JsOutcome.ScriptError, doubling.toString())
        val growing = run("var a = []; while (true) { a.push(\"abcdefghij\".repeat(1000)); }")
        assertIs<JsOutcome.Stopped>(growing, growing.toString())
    }

    @Test
    fun `console output and results are capped`() {
        val outcome = finished("for (var i = 0; i < 10000; i++) console.log(\"line \" + i); \"x\".repeat(200000)")
        assertTrue(outcome.logTruncated)
        assertTrue(outcome.log.sumOf { it.length } <= limits.maxLogChars)
        assertTrue(outcome.resultTruncated)
        assertEquals(null, outcome.resultJson)
        val big = finished("var a = []; for (var i = 0; i < 30000; i++) a.push(i); a")
        assertTrue(big.resultTruncated && big.resultJson!!.length == limits.maxResultChars)
    }

    @Test
    fun `syntax errors and thrown values are script errors with a line`() {
        val syntax = run("var x = ;")
        assertIs<JsOutcome.ScriptError>(syntax)
        assertEquals(1, syntax.line)
        val thrown = run("\n\nthrow new Error(\"boom\")")
        assertIs<JsOutcome.ScriptError>(thrown)
        assertTrue("boom" in thrown.message, thrown.message)
        assertEquals(3, thrown.line)
    }

    @Test
    fun `cancelling the caller stops the script`() = runBlocking<Unit> {
        val slow = JsSandbox(JsLimits(timeoutMs = 60_000, maxInstructions = Long.MAX_VALUE))
        val outcome = kotlinx.coroutines.withTimeoutOrNull(300) { slow.run("while (true) {}", null, null) }
        assertEquals(null, outcome)
        // The cancelled run released its thread: a new run starts and finishes.
        val next = slow.run("1 + 1", null, null)
        assertIs<JsOutcome.Finished>(next, next.toString())
    }

    @Test
    fun `each run has a fresh global scope`() {
        finished("globalThis.leak = 42; Object.prototype.polluted = true; 1")
        val outcome = finished("[typeof leak, typeof ({}).polluted]")
        assertEquals("""["undefined","undefined"]""", outcome.resultJson)
    }

    @Test
    fun `the workspace host functions read, list and create files`() {
        val files = linkedMapOf("notes/a.txt" to "hello")
        val host = object : JsHost {
            override val canWrite = true
            override fun read(path: String) = files[path] ?: throw JsHostError("No file \"$path\".")
            override fun list(path: String) = files.keys.map { Triple(it, false, files.getValue(it).length.toLong()) }
            override fun write(path: String, text: String): String {
                if (path in files) throw JsHostError("\"$path\" already exists.")
                files[path] = text
                return path
            }
        }
        val outcome = finished(
            """
            var t = workspace.read("notes/a.txt");
            workspace.write("notes/b.txt", t + " world");
            var again; try { workspace.write("notes/b.txt", "x"); } catch (e) { again = e.message; }
            [t, workspace.list("").length, again]
            """.trimIndent(),
            host = host,
        )
        assertEquals("""["hello",2,"\"notes/b.txt\" already exists."]""", outcome.resultJson)
        assertEquals("hello world", files["notes/b.txt"])
    }
}
