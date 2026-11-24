package com.aicontrolcenter.framework.operator

import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 4 (J1): every entry into a function of the script is counted, whoever calls it.
 *
 * Before, the interpreter counted where code jumps and where script code calls. A function with neither (an iterator's
 * `next` that only returns `{ done: false, value: 1 }`) cost nothing when a built-in called it, so the loops inside
 * `new Set(o)`, `[...o]`, `Promise.all(o)` and `Array.from(arrayWithItsOwnIterator)` ran with no budget, no time limit
 * and no memory check: for ever, when the iterator never ended, and after two such runs every script was refused
 * until the app restarted.
 *
 * TEST SAFETY: nothing in this file is endless. Every iterator ends by itself after a fixed number of steps, so without
 * the fix a test fails on its assertion (the script finishes where it should have been stopped) and nothing hangs
 * Gradle's test JVM; every test has a timeout as well. The endless originals belong in a JVM of their own with a hard
 * time limit, outside Gradle, as the verifier ran them.
 *
 * Real threads, real time.
 */
class JsRound4Test {
    /** A budget that the sandbox's own setup fits several times, and that 200,000 counted steps do not fit. */
    private val counting = JsSandbox(JsLimits(timeoutMs = 20_000, maxInstructions = 100_000))

    /** A fresh JVM spends longer than a short time limit on its first script; that is not what these tests measure. */
    @Before
    fun warmUp() {
        val warm = JsSandbox(JsLimits(timeoutMs = 20_000))
        repeat(2) { runBlocking { warm.run("[3, 1, 2].map(function (x) { return x; }).sort(); new Set([1]); for (var x of [1]) {} 1", null, null) } }
    }

    private fun run(code: String, on: JsSandbox): JsOutcome = runBlocking { on.run(code, null, null) }

    private fun result(code: String, on: JsSandbox): String? {
        val outcome = run(code, on)
        return assertIs<JsOutcome.Finished>(outcome, "$code: $outcome").resultJson
    }

    private fun stoppedByBudget(code: String, on: JsSandbox) {
        val outcome = run(code, on)
        assertTrue(outcome is JsOutcome.Stopped && "instructions" in outcome.reason, "$code: $outcome")
    }

    /**
     * `o`, which a built-in can walk: its iterator's `next` has no loop, no call and no jump, and ends by itself after
     * [steps] steps (the comparison gives `done` without a jump). Each step hands out [value].
     */
    private fun ownIterator(steps: Int, value: String = "1", before: String = "") =
        "var n = 0, o = {}; $before o[Symbol.iterator] = function () { return { next: function () { return { done: ++n > $steps, value: $value }; } }; };"

    // ---- The verifier's reproduction, with iterators that end by themselves ----

    @Test(timeout = 120_000)
    fun `a built-in loop over the script's own iterator is counted step by step, three runs in a row, and 1 + 1 runs afterwards`() {
        assertEquals("2", result("1 + 1", counting), "the budget fits an ordinary script")
        val steps = 200_000
        val four = listOf(
            "${ownIterator(steps)} new Set(o).size",
            "${ownIterator(steps)} [...o].length",
            "${ownIterator(steps)} Promise.all(o); 'done'",
            "${ownIterator(steps)} var a = [1, 2, 3]; a[Symbol.iterator] = o[Symbol.iterator]; Array.from(a).length",
        )
        for (code in four) {
            repeat(3) { stoppedByBudget(code, counting) }
            assertEquals("2", result("1 + 1", counting), "after three runs of: $code")
        }
    }

    @Test(timeout = 120_000)
    fun `the other built-ins that walk an iterator count the script's steps too`() {
        val steps = 200_000
        for (code in listOf(
            "${ownIterator(steps, "pair", "var pair = [1, 2];")} new Map(o).size",
            "${ownIterator(steps, "pair", "var pair = ['a', 2];")} Object.keys(Object.fromEntries(o)).length",
            "${ownIterator(steps, "key", "var key = {};")} new WeakSet(o); 'done'",
            "${ownIterator(steps, "pair", "var pair = [{}, 2];")} new WeakMap(o); 'done'",
            "${ownIterator(steps)} Promise.allSettled(o); 'done'",
            "${ownIterator(steps)} Promise.race(o); 'done'",
            "${ownIterator(steps)} Promise.any(o); 'done'",
            "${ownIterator(steps)} Uint8Array.from(o).length",
            "${ownIterator(steps)} Array.from(o).length",
            // The iterator method of all arrays replaced, and the steps of the built-in array iterator itself.
            "${ownIterator(steps)} Array.prototype[Symbol.iterator] = o[Symbol.iterator]; new Set([1, 2, 3]).size",
            "${ownIterator(steps)} Object.getPrototypeOf([][Symbol.iterator]()).next = o[Symbol.iterator]().next; [...[1, 2, 3]].length",
        )) stoppedByBudget(code, counting)
        assertEquals("2", result("1 + 1", counting))
    }

    @Test(timeout = 120_000)
    fun `the time limit reaches a built-in loop over the script's own iterator`() {
        val limit = 400L
        val sandbox = JsSandbox(JsLimits(timeoutMs = limit))
        // Ten million steps that keep nothing: seconds of built-in work, far longer than the limit, and an end of its own.
        val code = "${ownIterator(10_000_000)} new Set(o).size"
        repeat(3) {
            val started = System.nanoTime()
            val outcome = run(code, sandbox)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(outcome is JsOutcome.Stopped && "abandoned" !in outcome.reason, "$outcome")
            // Abandoning happens two seconds after the limit; ending "within the limit" leaves room for a busy machine only.
            assertTrue(ms < limit + 1_500, "took $ms ms")
        }
        assertEquals("2", result("1 + 1", sandbox))
    }

    @Test(timeout = 120_000)
    fun `a stop inside a built-in loop is reported as that stop, also when the iterator has a return of its own`() {
        // A built-in that is stopped in the middle of a walk closes the iterator on its way out, which calls the script's
        // `return`: one more entry into a function of the script, in a run that is already over.
        val closing = "var n = 0, closed = 0, o = {}; o[Symbol.iterator] = function () { return { " +
            "next: function () { return { done: ++n > 200000, value: 1 }; }, 'return': function () { closed++; return {}; } }; };"
        for (walk in listOf("new Set(o).size", "[...o].length", "Promise.all(o); 'done'", "Uint8Array.from(o).length")) {
            repeat(2) { stoppedByBudget("$closing $walk", counting) }
        }
        // A walk that ends by itself gives what it gave before.
        assertEquals("[3,0]", result("$closing n = 199997; [[...o].length, new Set(o).size]", JsSandbox(JsLimits(timeoutMs = 20_000))))
        assertEquals("2", result("1 + 1", counting))
    }

    // ---- Every entry, whoever calls ----

    @Test(timeout = 120_000)
    fun `a function without a loop is counted whenever a built-in calls it`() {
        val calls = 300_000
        for (code in listOf(
            // Callbacks of the array and typed-array built-ins.
            "var c = 0; new Array($calls).fill(0).forEach(function () { c++; }); c",
            "new Array($calls).fill(0).map(function (v) { return v; }).length",
            "new Array($calls).fill(0).reduce(function (sum, v) { return sum + v; }, 0)",
            "var c = 0; new Uint8Array($calls).forEach(function () { c++; }); c",
            "new Uint8Array($calls).map(function (v) { return v; }).length",
            // What a built-in asks a value for: its text, a property with a getter.
            "var c = 0, e = { toString: function () { c++; return 'x'; } }; new Array($calls).fill(e).join('').length",
            "var c = 0, e = {}; Object.defineProperty(e, 'done', { get: function () { return ++c > $calls; } }); var o = {}; " +
                "o[Symbol.iterator] = function () { return { next: Object.bind(null, e) }; }; new Set(o).size",
            // Arrow functions and bound functions of the script are functions of the script.
            "var c = 0; new Array($calls).fill(0).forEach(() => { c++; }); c",
            "var c = 0; new Array($calls).fill(0).forEach(function () { c++; }.bind(null)); c",
        )) stoppedByBudget(code, counting)
        // A typed array's sort asks the script's comparator for every pair: a hundred thousand numbers, some 1.6 million pairs.
        val small = JsSandbox(JsLimits(timeoutMs = 20_000, maxInstructions = 600_000))
        val typed = "var t = new Float64Array(new Array(100000).fill(0).map(Math.random));"
        assertEquals("100000", result("$typed t.length", small), "making the numbers is not what uses the budget up")
        stoppedByBudget("$typed t.sort(function (x, y) { return x - y; }); 'done'", small)
        assertEquals("2", result("1 + 1", counting))
    }

    // ---- Where the counting does not reach ----

    /**
     * `o` with an iterator in which no function of the script takes part: its `next` is the built-in `pop`, bound to a
     * list of [steps] ready-made results that ends with the one that says done. It ends by itself.
     */
    private fun builtInIterator(steps: Int) =
        "var step = { done: false, value: 1 }, queue = new Array($steps).fill(step); queue[0] = { done: true }; " +
            "var o = {}; o[Symbol.iterator] = function () { return { next: Array.prototype.pop.bind(queue) }; };"

    @Test(timeout = 120_000)
    fun `Array from walks an array that has an iterator of its own in script code, whatever its next is`() {
        // Counted: the loop is the sandbox's own, in script code, so the budget reaches it although no function of the
        // script is entered for a step.
        stoppedByBudget("${builtInIterator(200_000)} var a = [1, 2, 3]; a[Symbol.iterator] = o[Symbol.iterator]; Array.from(a).length", counting)
        stoppedByBudget("${builtInIterator(200_000)} Array.from(o).length", counting)
        // An array with the iterator every array has is still walked by the built-in, at the price of a native step.
        assertEquals("[3,400000]", result("var a = new Array(400000).fill(1); [Array.from([1, 2, 3]).length, Array.from(a).length]", JsSandbox(JsLimits(timeoutMs = 20_000))))
        assertEquals("2", result("1 + 1", counting))
    }

    @Test(timeout = 120_000)
    fun `a loop in which built-ins call only built-ins is not counted - the limit that is documented`() {
        // No function of the script is entered, and Rhino offers no hook for a call from one built-in to another. This
        // pins what docs/LIMITATIONS.md says: such a loop runs to its own end, here after 200,000 steps, whatever the
        // budget. (An iterator like this one that never ends is the case the documentation warns of; it is not run here.)
        assertEquals("1", result("${builtInIterator(200_000)} new Set(o).size", counting))
        assertEquals("199999", result("${builtInIterator(200_000)} [...o].length", counting))
    }

    // ---- What ordinary scripts pay ----

    @Test(timeout = 120_000)
    fun `a comparison by the script's comparator costs one unit, as it did`() {
        // 100,000 numbers in a fixed order; sorting them takes about 1.5 million comparisons. Counted once each (the entry
        // into the comparator, in place of the charge per comparison), sort and setup need some 1.7 million units;
        // counted twice, more than 3 million.
        val numbers = "var a = new Array(100000).fill(0).map(function (v, i) { return (i * 7919) % 100003; }); var c = 0;"
        val sort = "a.sort(function (x, y) { c++; return x - y; }); c"
        val comparisons = result("$numbers $sort", JsSandbox(JsLimits(timeoutMs = 20_000)))!!.toInt()
        assertTrue(comparisons in 1_300_000..1_800_000, "comparisons: $comparisons")
        assertIs<JsOutcome.Finished>(run("$numbers $sort", JsSandbox(JsLimits(timeoutMs = 20_000, maxInstructions = 2_400_000))))
        stoppedByBudget("$numbers $sort", JsSandbox(JsLimits(timeoutMs = 20_000, maxInstructions = 1_200_000)))
    }

    @Test(timeout = 120_000)
    fun `ordinary scripts fit the usual budget as before`() {
        val usual = JsSandbox(JsLimits(timeoutMs = 20_000))
        assertEquals("300000", result("function f(x) { return x + 1; } var s = 0; for (var i = 0; i < 300000; i++) s = f(s); s", usual))
        assertEquals("46368", result("function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); } fib(24)", usual))
        assertEquals("2488891", result("var a = []; for (var i = 0; i < 200000; i++) a.push({ i: i }); JSON.stringify(a).length", usual))
        assertEquals(
            "[0,200002]",
            result("var a = []; for (var i = 0; i < 200000; i++) a.push((i * 7919) % 200003); a.sort(function (x, y) { return x - y; }); [a[0], a[199999]]", usual),
        )
        assertEquals(
            "29999700000",
            result(
                "var a = []; for (var i = 0; i < 300000; i++) a.push(i); " +
                    "a.map(function (x) { return x * 2; }).filter(function (x) { return x % 3 === 0; }).reduce(function (s, x) { return s + x; }, 0)",
                usual,
            ),
        )
        // The standard results of walking iterators of the script's own, which end.
        assertEquals(
            """[3,[1,2,3],[1,2,3],[[1,2],[2,4]],6,[1,2,3],"abc"]""",
            result(
                """
                function upTo(n) { var o = {}; o[Symbol.iterator] = function () { var i = 0; return { next: function () { return { done: ++i > n, value: i }; } }; }; return o; }
                var own = [7, 8, 9]; own[Symbol.iterator] = upTo(3)[Symbol.iterator];
                var sum = 0; for (var v of upTo(3)) sum += v;
                function* letters() { yield 'a'; yield 'b'; yield 'c'; }
                [new Set(upTo(3)).size, [...upTo(3)], Array.from(own), Array.from(new Map(Array.from(upTo(2), function (v) { return [v, v * 2]; }))), sum,
                  Array.from(upTo(3)), [...letters()].join('')]
                """.trimIndent(),
                usual,
            ),
        )
    }

    // ---- The memory check, which the counting now makes run during every built-in loop that calls the script ----

    @Test(timeout = 120_000)
    fun `garbage that a built-in loop leaves behind does not stop a script for memory, growth still does`() {
        val mb = 1024L * 1024
        val tight = JsSandbox(JsLimits(timeoutMs = 20_000, maxHeapGrowthBytes = 48 * mb))
        // Some nine million calls of the comparator, each leaving its frame and its numbers behind: many times the limit
        // in garbage within a second or two, a few megabytes kept.
        val garbage = "var t = new Float64Array(500000); for (var i = 0; i < t.length; i++) t[i] = (i * 7919) % 500009; t.sort(function (x, y) { return x - y; }); t.length"
        assertEquals("500000", result(garbage, tight))
        // Some 200 MB kept, four times the limit. The loop has an end on purpose: if the check ever failed, the script would
        // finish and this test would say so, where an endless loop would fill the test JVM's heap and hang the build.
        val keeps = "var a = []; for (var i = 0; i < 2000; i++) a.push('x'.repeat(100000) + i); 'kept ' + a.length"
        val kept = run(keeps, tight)
        assertTrue(kept is JsOutcome.Stopped && "memory" in kept.reason, kept.toString())
        // Kept by a built-in loop that calls the script, where no check ran before.
        val keptInside = run("var a = new Array(2000).fill(0).map(function (v, i) { return 'x'.repeat(100000) + i; }); 'kept ' + a.length", tight)
        assertTrue(keptInside is JsOutcome.Stopped && "memory" in keptInside.reason, keptInside.toString())
        assertEquals("2", result("1 + 1", tight))
    }

    @Test
    fun `a collection to tell garbage from growth is due a few times at once and then twice a second, and the last measure decides in between`() {
        // Decided on numbers alone: no test fills this JVM's heap to see it.
        val mb = 1024L * 1024
        val second = 1_000_000_000L
        val limits = JsLimits()
        for (done in 0L..3L) assertTrue(limits.collectionDue(collectionsDone = done, elapsedNanos = 0), "collection ${done + 1} at the start")
        assertFalse(limits.collectionDue(collectionsDone = 4, elapsedNanos = 0))
        assertFalse(limits.collectionDue(collectionsDone = 4, elapsedNanos = second / 2 - 1))
        assertTrue(limits.collectionDue(collectionsDone = 4, elapsedNanos = second / 2))
        assertFalse(limits.collectionDue(collectionsDone = 5, elapsedNanos = second / 2))
        assertTrue(limits.collectionDue(collectionsDone = 13, elapsedNanos = 5 * second))
        assertFalse(limits.collectionDue(collectionsDone = 14, elapsedNanos = 5 * second))
        // With no collection due, a run that had kept more than half its limit at the last one is taken to have grown.
        assertFalse(limits.keptMuch(keptBytes = 0))
        assertFalse(limits.keptMuch(keptBytes = 48 * mb))
        assertTrue(limits.keptMuch(keptBytes = 48 * mb + 1))
        assertTrue(JsLimits(maxHeapGrowthBytes = 16 * mb).keptMuch(keptBytes = 9 * mb))
        // Nearly full: less than an eighth of the heap left, and less than the growth limit.
        assertFalse(limits.heapNearlyFull(usedBytes = 448 * mb, maxHeapBytes = 512 * mb))
        assertTrue(limits.heapNearlyFull(usedBytes = 449 * mb, maxHeapBytes = 512 * mb))
        assertFalse(limits.heapNearlyFull(usedBytes = 224 * mb, maxHeapBytes = 256 * mb))
        assertTrue(limits.heapNearlyFull(usedBytes = 225 * mb, maxHeapBytes = 256 * mb))
        assertTrue(JsLimits(maxHeapGrowthBytes = 16 * mb).heapNearlyFull(usedBytes = 497 * mb, maxHeapBytes = 512 * mb))
        assertFalse(JsLimits(maxHeapGrowthBytes = 16 * mb).heapNearlyFull(usedBytes = 496 * mb, maxHeapBytes = 512 * mb))
        // Being over the limit means what it meant: grown by more than the limit, or nearly full.
        assertTrue(limits.overMemory(usedBytes = 197 * mb, baseBytes = 100 * mb, maxHeapBytes = 512 * mb))
        assertFalse(limits.overMemory(usedBytes = 196 * mb, baseBytes = 100 * mb, maxHeapBytes = 512 * mb))
        assertTrue(limits.overMemory(usedBytes = 449 * mb, baseBytes = 400 * mb, maxHeapBytes = 512 * mb))
    }
}
