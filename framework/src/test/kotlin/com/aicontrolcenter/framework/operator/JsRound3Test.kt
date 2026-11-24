package com.aicontrolcenter.framework.operator

import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 3 (J1, J2): the loops a guard cannot bound by looking once.
 *
 * J1: a real array can change after its length was checked (an element getter grows it or
 * another array), and `concat`, `Array.from`, spread, `new Set` and the other built-ins that step
 * an iterator then loop over the new length in native code. The sandbox now does `concat` itself
 * and counts every step of a native iterator and every comparison of a sort against the script's
 * budget; a typed array over the array limit is not sorted; and what `JSON.stringify` formats has
 * no prototype a script could have put a `toJSON` on.
 *
 * J2: the copy `JSON.stringify` and a script's result are formatted from costs next to nothing
 * for plain data, so 200,000 small objects serialise.
 *
 * Real threads, real time.
 */
class JsRound3Test {
    private val limit = 400L
    private val sandbox = JsSandbox(JsLimits(timeoutMs = limit))

    /** A fresh JVM spends longer than a short time limit on its first script; that is not what these tests measure. */
    @Before
    fun warmUp() {
        val warm = JsSandbox(JsLimits(timeoutMs = 20_000))
        repeat(2) { runBlocking { warm.run("[3, 1, 2].map(function (x) { return x; }).concat([4]).sort(); JSON.stringify({ a: [1] }); for (var x of [1]) {} 1", null, null) } }
    }

    private fun run(code: String, on: JsSandbox = sandbox): JsOutcome = runBlocking { on.run(code, null, null) }

    private fun result(code: String, on: JsSandbox = sandbox): String? {
        val outcome = run(code, on)
        return assertIs<JsOutcome.Finished>(outcome, "$code: $outcome").resultJson
    }

    /** Runs [code]; it must end by itself or at a budget, never be abandoned with its thread still running. */
    private fun ends(code: String, on: JsSandbox = sandbox): JsOutcome {
        val outcome = run(code, on)
        assertTrue(outcome !is JsOutcome.Stopped || "abandoned" !in outcome.reason, "$code: $outcome")
        return outcome
    }

    private fun refused(code: String, on: JsSandbox = sandbox) {
        val outcome = ends(code, on)
        val error = assertIs<JsOutcome.ScriptError>(outcome, "$code: $outcome")
        assertTrue("not allowed" in error.message, "$code: ${error.message}")
    }

    private val growsItself = "var a = [0, 1]; Object.defineProperty(a, 0, { get: function () { a.length = 30000000; return 1; } });"

    // ---- J1: the verifier's reproduction ----

    @Test
    fun `the verifier's four expressions end within the time limit, three runs in a row, and 1 + 1 runs afterwards`() {
        val four = listOf(
            "var b = [1]; var a = [0]; Object.defineProperty(a, 0, { get: function () { b.length = 4294967295; return 1; } }); a.concat(b).length",
            "$growsItself Array.from(a).length",
            "$growsItself [...a].length",
            "$growsItself new Set(a).size",
        )
        for (code in four) {
            repeat(3) {
                val started = System.nanoTime()
                ends(code)
                val ms = (System.nanoTime() - started) / 1_000_000
                // Abandoning happens two seconds after the limit; ending "within the limit" leaves room for a busy machine only.
                assertTrue(ms < limit + 1_500, "$code took $ms ms")
            }
            assertEquals("2", result("1 + 1"), "after three runs of: $code")
        }
    }

    @Test
    fun `the other built-ins that step through an array end too when it grows under them`() {
        for (code in listOf(
            "var c = [1]; var b = [0]; Object.defineProperty(b, 0, { get: function () { c.length = 4294967295; return 1; } }); [].concat(b, c).length",
            "var c = [1]; var b = [0]; Object.defineProperty(b, 0, { get: function () { c.length = 4294967295; return 1; } }); Array.concat([], b, c).length",
            "$growsItself Promise.all(a); 'done'",
            "$growsItself Object.groupBy(a, function () { return 1; }); 'done'",
            "$growsItself new Map(a)",
            "$growsItself Object.fromEntries(a)",
            // The growing is done by what the built-in calls for every element, with no getter at all.
            "var a = [0, 1]; Array.from(a, function () { a.length = 30000000; return 1; }).length",
            "var a = [0, 1]; var add = Set.prototype.add; Set.prototype.add = function (v) { a.length = 30000000; return add.call(this, v); }; new Set(a).size",
            // Long strings and typed arrays are walked by the same native loops.
            "[...new Uint8Array(20000000)].length",
            "new Set('x'.repeat(5000000)).size",
        )) ends(code)
        assertEquals("2", result("1 + 1"))
    }

    @Test
    fun `every step of a native iterator is counted against the script's budget`() {
        val small = JsSandbox(JsLimits(timeoutMs = 20_000, maxInstructions = 600_000))
        // The values are made by one built-in call each; walking their 400,000 elements is what uses the budget up.
        for (code in listOf(
            "var s = 'x'.repeat(400000); [...s].length",
            "var t = new Uint8Array(400000); new Set(t).size",
            "var m = new Set(new Array(1000).fill(0).map(Math.random)); var n = 0; for (var i = 0; i < 400; i++) n += [...m].length; n",
        )) {
            val outcome = run(code, small)
            assertTrue(outcome is JsOutcome.Stopped && "instructions" in outcome.reason, "$code: $outcome")
        }
        // An array made by `new Array(n).fill(1)` costs Rhino about 130 bytes an element: with 400,000 elements it alone is
        // more than half the memory limit, and a script that keeps that much and throws much away (every step of these
        // walks leaves an object behind) may be stopped for memory instead, depending on when the heap was last collected
        // (round 4: the whole suite running in parallel saw that). This test is about the instruction budget, so its arrays
        // stay far below that: 100,000 elements, and a budget in the same proportion.
        val smaller = JsSandbox(JsLimits(timeoutMs = 20_000, maxInstructions = 150_000))
        for (code in listOf(
            "var a = new Array(100000).fill(1); new Set(a).size",
            "var a = new Array(100000).fill(1); Array.from(a).length",
            "var a = new Array(100000).fill(1); [...a].length",
        )) {
            val outcome = run(code, smaller)
            assertTrue(outcome is JsOutcome.Stopped && "instructions" in outcome.reason, "$code: $outcome")
        }
        // The same walks fit an ordinary budget.
        val usual = JsSandbox(JsLimits(timeoutMs = 20_000))
        assertEquals("300001", result("var a = new Array(100000).fill(1); new Set(a).size + Array.from(a).length + [...a].length + a.length", usual))
    }

    // ---- J1: concat, done by the sandbox ----

    @Test
    fun `concat gives the standard results`() {
        val outcome = result(
            """
            var like = { length: 2, 0: 'x', 1: 'y' };
            var spread = { length: 2, 0: 'x', 1: 'y' }; spread[Symbol.isConcatSpreadable] = true;
            var whole = [1, 2]; whole[Symbol.isConcatSpreadable] = false;
            var holes = [1, , 2].concat([, 3]);
            var grown = []; for (var i = 0; i < 300; i++) grown = grown.concat([i, [i]]);
            [
              [].concat(1, [2], [[3]]),
              [1].concat('ab', null, undefined, like).length,
              [0].concat(spread),
              [0].concat(whole).length,
              [0].concat(new Proxy([7, 8], {})),
              holes.length, 1 in holes, 3 in holes, holes[4],
              Array.prototype.concat.call('s', [1]).length,
              typeof Array.prototype.concat.call('s', [1])[0],
              Array.prototype.concat.call({ a: 1 }, 2),
              Array.concat([1], [2], 3),
              [].concat.apply([], [[1], [2, [3]]]),
              [1, 2].concat().length, [].concat([]).length, Array.isArray([1].concat(2)),
              grown.length, grown[599][0],
              (function () { try { Array.prototype.concat.call(null, 1); } catch (e) { return e.name; } })()
            ]
            """.trimIndent(),
        )
        assertEquals(
            """[[1,2,[3]],5,[0,"x","y"],2,[0,7,8],5,false,false,3,2,"object",[{"a":1},2],[1,2,3],[1,2,[3]],2,0,true,600,299,"TypeError"]""",
            outcome,
        )
    }

    @Test
    fun `concat reads each part's length once and refuses a total over the limit`() {
        val huge = "var h = []; h.length = 4294967295;"
        refused("$huge [].concat(h)")
        refused("$huge [1].concat([2], h)")
        refused("var part = []; part.length = 600000; part.concat(part)")
        // A part's length is read when its turn comes, as the standard says, and then once: a part that an earlier part's
        // getter emptied or grew is copied as it is by then, and a part that grows itself while it is copied is copied as
        // long as it was when its turn came.
        assertEquals("1", result("var b = [1, 2]; var a = [0]; Object.defineProperty(a, 0, { get: function () { b.length = 0; return 9; } }); a.concat(b).length"))
        assertEquals("[9,1,2,3]", result("var b = [1, 2]; var a = [0]; Object.defineProperty(a, 0, { get: function () { b.push(3); return 9; } }); a.concat(b)"))
        assertEquals("[9,1]", result("var a = [0, 1]; Object.defineProperty(a, 0, { get: function () { a.push(7, 8); return 9; } }); a.concat()"))
        // The verifier's expression: the later part is huge by the time its length is read, and that is refused.
        refused("var b = [1]; var a = [0]; Object.defineProperty(a, 0, { get: function () { b.length = 4294967295; return 1; } }); a.concat(b).length")
    }

    // ---- J1: sorting ----

    @Test
    fun `a typed array longer than the array limit is not sorted`() {
        // The verifier's expression: 3.4 seconds of native sorting before.
        refused("var t = new Float64Array(20000000); t.sort(); 'done'")
        refused("new Float64Array(1000001).toSorted()")
        refused("new Uint8Array(1000001).sort()")
        refused("new Int32Array(1000001).sort(function (a, b) { return a - b; })")
        // A length a script defines on the array does not hide the real one.
        refused("var t = new Uint8Array(1000001); try { Object.defineProperty(t, 'length', { value: 1 }); } catch (e) {} t.sort()")
        refused("Object.getPrototypeOf(Int8Array.prototype).sort.call(new Uint16Array(1000001))")
        assertEquals(
            "[[1,2,3],[3,2,1],[-1,3,5],1000000]",
            result(
                "var t = new Uint8Array([3, 1, 2]); t.sort(); var f = new Float64Array([1, 3, 2]); f.sort(function (a, b) { return b - a; }); " +
                    "[Array.from(t), Array.from(f), Array.from(new Int16Array([5, -1, 3]).toSorted()), new Uint8Array(1000000).sort().length]",
                JsSandbox(JsLimits(timeoutMs = 20_000)),
            ),
        )
    }

    @Test
    fun `every comparison of a sort is counted against the script's budget, whatever compares`() {
        val small = JsSandbox(JsLimits(timeoutMs = 20_000, maxInstructions = 600_000))
        val numbers = "var a = new Array(100000).fill(0).map(Math.random);"
        // A built-in as comparator runs no script code: here the text order, through a bound built-in.
        val builtIn = "Function.prototype.call.bind(String.prototype.localeCompare)"
        for (sort in listOf(
            "a.sort()", "a.sort(function (x, y) { return x - y; })", "a.sort($builtIn)", "a.toSorted()", "a.toSorted(function (x, y) { return y - x; })",
            "a.toSorted($builtIn)", "Array.sort(a)",
        )) {
            val outcome = run("$numbers $sort; 'done'", small)
            assertTrue(outcome is JsOutcome.Stopped && "instructions" in outcome.reason, "$sort: $outcome")
        }
        assertEquals("\"done\"", result("$numbers 'done'", small), "making the numbers is not what uses the budget up")
        // A built-in that answers the same for every pair (Math.atan2 of two positive numbers) lets the sort end after one
        // pass, 99,999 comparisons: each of them is counted too, so a budget below that number stops it.
        val smaller = JsSandbox(JsLimits(timeoutMs = 20_000, maxInstructions = 50_000))
        val onePass = run("$numbers a.sort(Math.atan2); 'done'", smaller)
        assertTrue(onePass is JsOutcome.Stopped && "instructions" in onePass.reason, "a.sort(Math.atan2): $onePass")
        assertEquals("\"done\"", result("$numbers 'done'", smaller), "making the numbers fits the smaller budget too")
        assertEquals("\"done\"", result("$numbers a.sort(Math.atan2); 'done'", small), "one pass fits the budget the full sorts use up")
        // The same sort fits an ordinary budget, and sorts.
        val usual = JsSandbox(JsLimits(timeoutMs = 20_000))
        assertEquals("true", result("$numbers a.sort(function (x, y) { return x - y; }); var ok = true; for (var i = 1; i < a.length; i++) if (a[i - 1] > a[i]) ok = false; ok", usual))
    }

    @Test
    fun `a long sort ends at the time limit`() {
        // A million random numbers in the default order: several seconds of native work before, with no script code in between.
        // Round 4: the numbers are made by Array.from, whose steps are counted, under a limit that leaves time to make them.
        // Made by `new Array(1000000).fill(0).map(Math.random)` (about a second of built-in work that no limit reaches, and
        // a quarter of a gigabyte) under the 400 ms limit, a busy machine spent the limit and its two seconds of grace on
        // the making alone, and the run was abandoned before any sorting began: the test failed whenever the whole suite
        // ran in parallel, with or without that round's changes.
        val roomy = JsSandbox(JsLimits(timeoutMs = 3_000))
        val numbers = "var a = Array.from({ length: 1000000 }, Math.random);"
        ends("$numbers a.sort(); 'done'", roomy)
        ends("$numbers a.toSorted(Math.atan2); 'done'", roomy)
        assertEquals("2", result("1 + 1", roomy))
    }

    @Test
    fun `sorting gives the standard results`() {
        assertEquals(
            """[[1,10,100,9],[1,9,10,100],["a","b","c",null,null],[1,2,3],[3,2,1],["B","C","a"],[2,1],["TypeError","TypeError","TypeError"],[3,1,2]]""",
            result(
                """
                var kept = [3, 1, 2]; kept.toSorted();
                var errors = [];
                try { [1, 2].sort(5); } catch (e) { errors.push(e.name); }
                try { [1, 2].sort(null); } catch (e) { errors.push(e.name); }
                try { [1, 2].toSorted('x'); } catch (e) { errors.push(e.name); }
                [
                  [10, 9, 1, 100].sort(), [10, 9, 1, 100].sort(function (a, b) { return a - b; }), ['b', undefined, 'a', , 'c'].sort(),
                  [3, 1, 2].toSorted(), [3, 1, 2].toSorted(function (a, b) { return b - a; }), ['B', 'a', 'C'].sort(),
                  [1, 2].sort(function () { return 1; }).length === 2 ? [2, 1].sort(function () { return 0; }) : null, errors, kept
                ]
                """.trimIndent(),
            ),
        )
    }

    // ---- J1: what JSON.stringify formats ----

    @Test
    fun `a toJSON put on a prototype is asked once, in script code, and never by the built-in formatting its copy`() {
        val huge = "var a = []; a.length = 4294967295; var n = 0;"
        // Answering "this" first and the huge array the second time: the built-in used to ask the copy again, natively.
        assertEquals("\"{\\\"x\\\":1}\"", (ends("$huge Object.prototype.toJSON = function () { return n++ < 1 ? this : a; }; JSON.stringify({ x: 1 })") as? JsOutcome.Finished)?.resultJson)
        assertEquals("\"[1]\"", (ends("$huge Array.prototype.toJSON = function () { return n++ < 1 ? this : a; }; JSON.stringify([1])") as? JsOutcome.Finished)?.resultJson)
        assertEquals("{\"x\":1}", (ends("$huge Object.prototype.toJSON = function () { return n++ < 1 ? this : a; }; ({ x: 1 })") as? JsOutcome.Finished)?.resultJson)
        assertEquals("\"{\\\"x\\\":{\\\"y\\\":[2]}}\"", (ends("$huge Object.prototype.toJSON = function () { return n++ < 3 ? this : a; }; JSON.stringify({ x: { y: [2] } })") as? JsOutcome.Finished)?.resultJson)
        // A BigInt is asked for its toJSON as the built-in would, here; without one it cannot be written.
        refused("$huge BigInt.prototype.toJSON = function () { return a; }; JSON.stringify({ x: BigInt(1) })")
        assertEquals("\"{\\\"x\\\":\\\"1n\\\"}\"", result("BigInt.prototype.toJSON = function () { return this.toString() + 'n'; }; JSON.stringify({ x: BigInt(1) })"))
        val error = assertIs<JsOutcome.ScriptError>(run("JSON.stringify({ x: BigInt(5) })"))
        assertTrue("BigInt" in error.message, error.message)
        assertEquals("\"{}\"", result("$huge Symbol.prototype.toJSON = function () { return a; }; JSON.stringify({ x: Symbol() })"))
        assertEquals("2", result("1 + 1"))
    }

    // ---- J2 ----

    @Test
    fun `two hundred thousand small objects serialise within the default budget`() {
        val usual = JsSandbox(JsLimits())
        // The verifier's expression.
        assertEquals("true", result("var a = []; for (var i = 0; i < 200000; i++) a.push({ i: i }); JSON.stringify(a).length > 1000000", usual))
        // As a result it is cut at the result limit, not stopped.
        val outcome = assertIs<JsOutcome.Finished>(run("var a = []; for (var i = 0; i < 200000; i++) a[i] = { i: i }; a", usual))
        assertTrue(outcome.resultTruncated && outcome.resultJson!!.startsWith("[{\"i\":0},{\"i\":1},"), outcome.resultJson?.take(60))
    }

    @Test
    fun `plain data, dates and objects made by a constructor are copied at a small charge`() {
        // Building 50,000 records takes most of this budget; formatting them took several times the budget before.
        val modest = JsSandbox(JsLimits(timeoutMs = 20_000, maxInstructions = 18_000_000))
        val records = "function Tag(n) { this.n = n; } var a = []; for (var i = 0; i < 50000; i++) a[i] = { id: i, name: 'n' + i, ok: true, when: new Date(0), tags: [new Tag(i)], none: null };"
        assertEquals("\"built\"", result("$records 'built'", modest))
        assertEquals("true", result("$records JSON.stringify(a).length > 4000000", modest))
        assertEquals("true", result("$records JSON.stringify(a, null, 1).length > 4000000", modest))
    }

    @Test
    fun `the quick copy and the walk in script code give the same text`() {
        // A replacer that changes nothing sends the value the long way, through the guards' script code.
        val mismatches = result(
            """
            function P(x) { this.x = x; this.when = new Date(86400000 * x); }
            var shared = { s: 1 };
            var values = [
              { a: 1, b: [1, 2, { c: null }], d: 'x' }, [1, , 3], { b: 1, 2: 1, a: 1, 1: 1, '-1': 1, '01': 1, 4294967295: 1 },
              JSON.parse('{"__proto__": 1, "a": {"__proto__": [2]}}'), [new P(1), new P(2)],
              { u: undefined, f: function () {}, n: null, s: Symbol('q') }, [undefined, function () {}, Symbol('q')],
              { get g() { return [7]; } }, Object.create(null), Object.create({ inherited: 1 }), [[], {}, [[]], { a: {} }],
              { a: -0, b: NaN, c: Infinity, d: 1e21, e: 0.1 }, 'line\n"quote" ', [true, false, null], 5, null, undefined,
              { d: new Date(0), bad: new Date(NaN) }, [new Number(1), new String('s'), new Boolean(false)], new Map([[1, 2]]), new Uint8Array([1, 2]),
              { toJSON: function (k) { return 'as ' + k; } }, [{ toJSON: function (k) { return { at: k }; } }], { nested: { deep: [{ x: [1, [2, [3]]] }] } },
              [shared, shared, { again: shared }], new Proxy([1, { p: 2 }], {}), new Proxy({ q: [1] }, {}), (function () { return arguments; })(1, 'two')
            ];
            var bad = [];
            values.forEach(function (v, i) {
              var same = function (k, x) { return x; };
              if (JSON.stringify(v) !== JSON.stringify(v, same)) bad.push(i + ': ' + JSON.stringify(v) + ' / ' + JSON.stringify(v, same));
              if (JSON.stringify(v, null, 2) !== JSON.stringify(v, same, 2)) bad.push(i + ' indented');
            });
            bad
            """.trimIndent(),
        )
        assertEquals("[]", mismatches)
    }

    @Test
    fun `values are still read once, and what is not plain data is still walked with care`() {
        // A getter beside a function and a date: read once.
        assertEquals("[1,\"{\\\"a\\\":1,\\\"b\\\":\\\"1970-01-01T00:00:00.000Z\\\"}\"]", result("var n = 0; var o = { get a() { n++; return 1; }, b: new Date(0), c: function () {} }; var s = JSON.stringify(o); [n, s]"))
        // A toJSON that is a getter is read twice, as the standard has it: once when the object is asked for its toJSON,
        // and once as the member it also is. Its second answer is only data by then: a function there is left out, not called.
        assertEquals("2", result("var n = 0; var o = { get toJSON() { n++; return undefined; }, x: 1 }; JSON.stringify(o); n"))
        assertEquals(
            "[2,\"{\\\"x\\\":1}\",0]",
            result("var n = 0, called = 0; var o = { get toJSON() { return n++ ? function () { called++; return 'spoken'; } : undefined; }, x: 1 }; var s = JSON.stringify(o); [n, s, called]"),
        )
        // A getter that grows a sibling array, or hands over a huge one the second time it is read.
        refused("var b = [1]; var o = { get a() { b.length = 4294967295; return 1; }, b: b }; JSON.stringify(o)")
        assertEquals("\"{\\\"x\\\":1}\"", result("var a = []; a.length = 4294967295; var n = 0; JSON.stringify({ get x() { return n++ ? a : 1; } })"))
        refused("var a = []; a.length = 4294967295; JSON.stringify({ deep: [{ x: a }] })")
        // Deeper than the quick copy follows, and a value that holds itself.
        assertEquals("602", result("var deep = []; var cur = deep; for (var i = 0; i < 300; i++) { var next = []; cur.push(next); cur = next; } JSON.stringify(deep).length"))
        val cyclic = assertIs<JsOutcome.ScriptError>(run("var o = { list: [1] }; o.list.push(o); JSON.stringify(o)"))
        assertTrue("Cyclic" in cyclic.message, cyclic.message)
        // A date whose toJSON the script replaced speaks through the script's function.
        assertEquals("\"{\\\"d\\\":\\\"mine\\\"}\"", result("Date.prototype.toJSON = function () { return 'mine'; }; JSON.stringify({ d: new Date(0) })"))
        assertEquals("\"{\\\"d\\\":\\\"own\\\"}\"", result("var d = new Date(0); d.toJSON = function () { return 'own'; }; JSON.stringify({ d: d })"))
    }

    @Test
    fun `the tool says where a large result goes, and that way works`() = runBlocking {
        val f = OperatorFixture()
        val description = f.tool("scripts.run_js").spec.description
        assertTrue("cut after 100,000 characters" in description && "workspace.write" in description && "5,000,000" in description, description)
        assertTrue("1,000,000 elements" in description, description)
        // 200,000 records as one result are cut; written to a workspace file they are all there.
        val records = "var a = []; for (var i = 0; i < 200000; i++) a[i] = { i: i };"
        val cut = f.ok("scripts.run_js", "code" to "$records a")
        assertTrue(cut.content.startsWith("Result (cut at 100000 characters): [{\"i\":0},"), cut.content.take(80))
        val written = f.ok("scripts.run_js", "code" to "$records workspace.write('out/records.json', JSON.stringify(a)); a.length", "workspace" to "write")
        assertEquals("Result: 200000", written.content)
        val text = f.file("out/records.json").readText()
        assertTrue(text.length > 2_000_000 && text.endsWith(",{\"i\":199999}]"), "${text.length} characters, ending ${text.takeLast(30)}")
    }

    // ---- What the counting must not change ----

    @Test
    fun `iterating gives the standard results`() {
        assertEquals(
            """[1,2,3,"a","b",7,[1,2],9,[1,2,3],[1,2],5,[[0,1],[1,2]],2,[0,1,2],"next",0,"{\"value\":5,\"done\":false}","{\"done\":true}",[1,2,3,4],6]""",
            result(
                """
                var out = [];
                for (var x of [1, 2, 3]) out.push(x);
                for (var c of 'ab') out.push(c);
                for (var e of new Set([7])) out.push(e);
                for (var kv of new Map([[1, 2]])) out.push(kv);
                for (var u of new Uint8Array([9])) out.push(u);
                var it = [5][Symbol.iterator](), proto = Object.getPrototypeOf(it);
                // An array that grows while a loop in script code walks it is followed, as the language says.
                var queue = [1, 2], seen = []; for (var q of queue) { seen.push(q); if (q < 3) queue.push(q + 2); }
                var args = (function () { var n = 0; for (var v of arguments) n += v; return n; })(1, 2, 3);
                out.push([...[1, 2], 3], Array.from(new Set([1, 1, 2])), [...'héllo'].length, Array.from([1, 2].entries()), [...'a1b2'.matchAll(/\d/g)].length,
                  Array.from([1, 2, 3].keys()), proto.next.name, proto.next.length, JSON.stringify(it.next()), JSON.stringify(it.next()), seen, args);
                out
                """.trimIndent(),
            ),
        )
    }

    // ---- The memory check, which the counting makes run during long built-in loops ----

    @Test
    fun `garbage alone does not stop a script for memory, growth does`() {
        val tight = JsSandbox(JsLimits(timeoutMs = 20_000, maxHeapGrowthBytes = 48L * 1024 * 1024))
        // Some 150 MB of short-lived objects, a few bytes kept.
        assertEquals("600000", result("var s = 0; for (var i = 0; i < 300000; i++) { var o = { a: i, b: [i, i], c: 'x' + i }; s += o.b.length; } s", tight))
        // Some 200 MB kept, four times the limit. The loop has an end on purpose: if the check ever failed, the script
        // would finish and this test would say so, where an endless loop would fill the test JVM's heap and hang the build.
        val keeps = "var a = []; for (var i = 0; i < 2000; i++) a.push('x'.repeat(100000) + i); 'kept ' + a.length"
        val kept = run(keeps, tight)
        assertTrue(kept is JsOutcome.Stopped && "memory" in kept.reason, kept.toString())
        // The same right after a script that left much garbage behind: what is in use before a run is measured after a
        // collection, so that garbage is not taken for room the next script may fill.
        assertEquals("600000", result("var s = 0; for (var i = 0; i < 300000; i++) { var o = { a: i, b: [i, i], c: 'x' + i }; s += o.b.length; } s", tight))
        val keptAfterGarbage = run(keeps, tight)
        assertTrue(keptAfterGarbage is JsOutcome.Stopped && "memory" in keptAfterGarbage.reason, keptAfterGarbage.toString())
        assertEquals("2", result("1 + 1", tight))
    }

    @Test
    fun `a script is stopped for memory before the heap is full, whatever its growth limit`() {
        // Decided on numbers alone: no test fills this JVM's heap to see it.
        val mb = 1024L * 1024
        val usual = JsLimits()
        assertEquals(96 * mb, usual.maxHeapGrowthBytes)
        // The growth limit, on a heap with room: 512 MB, 100 MB in use when the run began.
        assertFalse(usual.overMemory(usedBytes = 196 * mb, baseBytes = 100 * mb, maxHeapBytes = 512 * mb))
        assertTrue(usual.overMemory(usedBytes = 197 * mb, baseBytes = 100 * mb, maxHeapBytes = 512 * mb))
        // A heap that was nearly full before the run: the growth limit cannot be reached (400 + 96 is all but the whole heap),
        // so the run is stopped when less than an eighth of the heap (64 MB) is left.
        assertFalse(usual.overMemory(usedBytes = 448 * mb, baseBytes = 400 * mb, maxHeapBytes = 512 * mb))
        assertTrue(usual.overMemory(usedBytes = 449 * mb, baseBytes = 400 * mb, maxHeapBytes = 512 * mb))
        // A phone's smaller heap: 32 MB of 256 MB stay free.
        assertFalse(usual.overMemory(usedBytes = 224 * mb, baseBytes = 200 * mb, maxHeapBytes = 256 * mb))
        assertTrue(usual.overMemory(usedBytes = 225 * mb, baseBytes = 200 * mb, maxHeapBytes = 256 * mb))
        // The reserve is never more than the growth limit itself.
        val tight = JsLimits(maxHeapGrowthBytes = 16 * mb)
        assertFalse(tight.overMemory(usedBytes = 496 * mb, baseBytes = 490 * mb, maxHeapBytes = 512 * mb))
        assertTrue(tight.overMemory(usedBytes = 497 * mb, baseBytes = 490 * mb, maxHeapBytes = 512 * mb))
        // An ordinary small script on an ordinary heap is far from both.
        assertEquals("2", result("1 + 1"))
    }
}
