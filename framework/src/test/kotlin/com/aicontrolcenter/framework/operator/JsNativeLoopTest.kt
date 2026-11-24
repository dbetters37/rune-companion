package com.aicontrolcenter.framework.operator

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * One call of a built-in over a sparse array of length 2^32-1 is a single native loop the budget
 * cannot interrupt. Every such built-in refuses it instead, so a run never outlives its limit and
 * later runs are not refused because earlier threads are still spinning. Real threads, real time.
 */
class JsNativeLoopTest {
    private val sandbox = JsSandbox(JsLimits(timeoutMs = 1_000))

    private fun run(code: String): JsOutcome = runBlocking { sandbox.run(code, null, null) }

    @Test
    fun `array built-ins that walk the length refuse a huge sparse array at once`() {
        val calls = listOf(
            "a.forEach(function () {})", "a.map(function (x) { return x; })", "a.filter(function () { return true; })",
            "a.some(function () { return false; })", "a.every(function () { return true; })", "a.reduce(function (x) { return x; }, 0)",
            "a.reduceRight(function (x) { return x; }, 0)", "a.find(function () { return false; })", "a.findIndex(function () { return false; })",
            "a.slice(0)", "a.splice(0, 1)", "a.shift()", "a.unshift(1)", "[].concat(a)", "a.concat([1])",
            "Math.max.apply(null, a)", "JSON.stringify({ x: [a] })", "Array.prototype.forEach.call({ length: 4294967295 }, function () {})",
        )
        for (call in calls) {
            val started = System.nanoTime()
            val outcome = run("var a = []; a.length = 4294967295; $call")
            val ms = (System.nanoTime() - started) / 1_000_000
            val error = assertIs<JsOutcome.ScriptError>(outcome, "$call: $outcome")
            assertTrue("not allowed" in error.message, "$call: ${error.message}")
            assertTrue(ms < 1_000, "$call took $ms ms")
        }
    }

    @Test
    fun `a huge result or console value is refused, not walked`() {
        val result = run("var a = []; a.length = 4294967295; a")
        assertTrue(result is JsOutcome.ScriptError && "not allowed" in result.message, "$result")
        val logged = run("var a = []; a.length = 4294967295; console.log({ a: a }); 1")
        assertTrue(logged !is JsOutcome.Stopped, "$logged")
    }

    @Test
    fun `after such scripts, new scripts still run`() {
        repeat(3) { run("var a = []; a.length = 4294967295; a.forEach(function () {})") }
        assertEquals("2", assertIs<JsOutcome.Finished>(run("1 + 1")).resultJson)
    }

    @Test
    fun `the guards keep ordinary array work and apply working`() {
        val outcome = assertIs<JsOutcome.Finished>(
            run(
                """
                var xs = [3, 1, 2].map(function (x) { return x * 2; }).filter(function (x) { return x > 2; });
                var sum = xs.reduce(function (a, b) { return a + b; }, 0);
                [sum, Math.max.apply(null, xs), [1].concat([2], 3).length, JSON.stringify({ k: xs }), Reflect.apply(Math.min, null, [4, 5])]
                """.trimIndent(),
            ),
        )
        assertEquals("""[10,6,3,"{\"k\":[6,4]}",4]""", outcome.resultJson)
    }
}
