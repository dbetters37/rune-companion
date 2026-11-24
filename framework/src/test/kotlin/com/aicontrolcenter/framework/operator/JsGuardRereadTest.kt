package com.aicontrolcenter.framework.operator

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * F5: a guard that checks a length and then lets the built-in read it again is no guard. A
 * `length` that answers small once and 2^32-1 afterwards (a getter, or an object with valueOf)
 * must not put the endless native loop back: the built-ins only ever see what the guard read.
 * Real threads, real time.
 */
class JsGuardRereadTest {
    private val limit = 300L
    private val sandbox = JsSandbox(JsLimits(timeoutMs = limit))

    private fun run(code: String): JsOutcome = runBlocking { sandbox.run(code, null, null) }

    /**
     * Runs [code]; it must end by itself or at a budget, never be abandoned with its thread still
     * running (which is what happens two seconds after the time limit).
     */
    private fun ends(code: String): JsOutcome {
        val outcome = run(code)
        assertTrue(outcome !is JsOutcome.Stopped || "abandoned" !in outcome.reason, "$code: $outcome")
        return outcome
    }

    private val getter = "var n = 0; var o = { get length() { return n++ ? 4294967295 : 0; } };"
    private val valueOf = "var n = 0; var o = { length: { valueOf: function () { return n++ ? 4294967295 : 0; } } };"

    @Test
    fun `the verifier's two scripts end within the time limit and leave the sandbox usable`() {
        assertEquals("\"done\"", assertIs<JsOutcome.Finished>(ends("$getter Array.prototype.forEach.call(o, function () {}); 'done'")).resultJson)
        assertEquals("\"done\"", assertIs<JsOutcome.Finished>(ends("$valueOf Array.prototype.indexOf.call(o, 1); 'done'")).resultJson)
        assertEquals("2", assertIs<JsOutcome.Finished>(run("1 + 1")).resultJson)
    }

    @Test
    fun `three runs of the getter script do not lock the sandbox`() {
        repeat(3) { ends("$getter Array.prototype.forEach.call(o, function () {}); 'done'") }
        assertEquals("2", assertIs<JsOutcome.Finished>(run("1 + 1")).resultJson)
    }

    @Test
    fun `no array built-in reads a lying length a second time`() {
        val methods = listOf(
            "join", "toString", "toLocaleString", "indexOf", "lastIndexOf", "includes", "flat", "forEach", "map", "filter", "some", "every",
            "reduce", "reduceRight", "find", "findIndex", "findLast", "findLastIndex", "slice", "keys", "values", "entries", "flatMap",
            "toReversed", "toSorted", "toSpliced", "with", "fill", "reverse", "sort", "copyWithin", "splice", "shift", "unshift", "concat",
        )
        for (liar in listOf(getter, valueOf)) {
            for (name in methods) {
                // The callback serves the methods that take one; the others ignore it or treat it as a value.
                ends("$liar var m = Array.prototype['$name']; if (typeof m === 'function') m.call(o, function (a, b) { return a; }, 0); 'done'")
            }
            ends("$liar Math.max.apply(null, o); 'done'")
            ends("$liar Reflect.apply(Math.max, null, o); 'done'")
            ends("$liar Reflect.construct(Array, o); 'done'")
            ends("$liar Array.from(o); 'done'")
            ends("$liar o[Symbol.isConcatSpreadable] = true; [].concat(o); 'done'")
            ends("$liar [1].concat([2], o, 3); 'done'")
            ends("$liar String.fromCharCode.apply(null, o); 'done'")
        }
        // Answers that change between the guard and the built-in, in the other places a guard reads a value.
        ends("var n = 0; 'x'.repeat({ valueOf: function () { return n++ ? 2147483647 : 1; } }).length")
        ends("var n = 0; 'x'.padStart({ valueOf: function () { return n++ ? 2147483647 : 1; } }).length")
        ends("var n = 0; var o = { get [Symbol.iterator]() { return n++ ? undefined : function () { return [][Symbol.iterator](); }; }, length: 4294967295 }; Array.from(o); 'done'")
        ends("var n = 0; var o = { get [Symbol.isConcatSpreadable]() { return n++ > 0; }, length: 4294967295 }; [].concat(o).length")
        assertEquals("2", assertIs<JsOutcome.Finished>(run("1 + 1")).resultJson)
    }

    @Test
    fun `arrays reached through another array count too`() {
        val huge = "var a = []; a.length = 4294967295;"
        for (call in listOf(
            "[a].flat()", "[[a]].flat(2)", "[[a]].flat(Infinity)", "[1, 2].flatMap(function () { return a; })", "[].concat.apply([], [a])",
            "var many = []; var part = []; part.length = 900000; for (var i = 0; i < 3; i++) many.push(part); [].concat.apply([], many)",
            "var part = []; part.length = 900000; [1, 2, 3].flatMap(function () { return part; })",
            "Array.isArray = function () { return false; }; JSON.stringify({ x: a })",
            "Object.keys = function () { return []; }; JSON.stringify({ x: a })",
            "Array.prototype.indexOf = function () { return 0; }; JSON.stringify({ x: { y: a } })",
            "for (var x of a) {}", "[...a]", "new Set(a)", "new Map(a)", "Object.fromEntries(a)",
            // Rhino's own extras: the array methods as functions on Array, and the source form of a value.
            "Array.forEach(a, function () {})", "Array.map(a, function (x) { return x; })", "Array.some(a, function () { return false; })",
            "Array.reduce(a, function (x) { return x; }, 0)", "Array.indexOf(a, 1)", "Array.slice(a, 0)", "Array.concat([], a)", "Array.sort(a)",
            "a.toSource()", "({ x: a }).toSource()", "[[a]].toSource()", "uneval(a)", "uneval({ x: [a] })",
            // What JSON.stringify is handed after the first look: by toJSON, by a getter that answers differently, by a replacer.
            "JSON.stringify({ toJSON: function () { return a; } })",
            "JSON.stringify({ x: 1 }, function (k, v) { return k === 'x' ? a : v; })",
            "JSON.stringify([{ toJSON: function () { return { deep: [a] }; } }])",
        )) {
            val outcome = ends("$huge $call")
            val error = assertIs<JsOutcome.ScriptError>(outcome, "$call: $outcome")
            assertTrue("not allowed" in error.message, "$call: ${error.message}")
        }
        // Walking long arrays of holes is slow, not endless: it ends, by itself or at the instruction budget.
        ends("var many = []; var part = []; part.length = 900000; for (var i = 0; i < 3; i++) many.push(part); many.flat().length")
        // An element that is a getter, answering 1 first and the huge array afterwards, is read once too.
        val element = "$huge var n = 0; var b = []; Object.defineProperty(b, 0, { get: function () { return n++ ? a : 1; }, enumerable: true });"
        for (call in listOf("b.flat().length", "b.flat(Infinity).length", "[b].flat(2).length", "[].concat(b).length", "b.join().length", "JSON.stringify(b).length", "b.toSource().length")) {
            assertIs<JsOutcome.Finished>(ends("$element $call"), call)
        }
        // A getter that answers 1 first and the huge array afterwards is read once: what it said then is what is written.
        val once = ends("$huge var n = 0; JSON.stringify({ get x() { return n++ ? a : 1; } })")
        assertEquals("\"{\\\"x\\\":1}\"", assertIs<JsOutcome.Finished>(once).resultJson)
        assertEquals("2", assertIs<JsOutcome.Finished>(run("1 + 1")).resultJson)
    }

    @Test
    fun `a long plain result still fits the instruction budget`() {
        // 100,000 numbers, and 10,000 small objects, in well under half the default budget: the copy JSON.stringify
        // formats must not cost a call per plain value.
        val modest = JsSandbox(JsLimits(timeoutMs = 10_000, maxInstructions = 20_000_000))
        val numbers = runBlocking { modest.run("var a = []; for (var i = 0; i < 100000; i++) a[i] = i; a", null, null) }
        assertTrue(numbers is JsOutcome.Finished && numbers.resultTruncated, numbers.toString().take(200))
        val objects = runBlocking { modest.run("var a = []; for (var i = 0; i < 10000; i++) a[i] = { id: i, name: 'n' + i, ok: true }; JSON.stringify(a).length", null, null) }
        assertTrue(objects is JsOutcome.Finished, objects.toString().take(200))
    }

    @Test
    fun `things that only look like arrays still work, on a bounded copy`() {
        val outcome = assertIs<JsOutcome.Finished>(
            run(
                """
                function rest() { return Array.prototype.slice.call(arguments, 1); }
                function most() { return Math.max.apply(null, arguments); }
                var like = { length: 3, 0: 'a', 2: 'c' };
                var spread = { length: 2, 0: 'x', 1: 'y' };
                spread[Symbol.isConcatSpreadable] = true;
                var seen = [];
                for (var v of [7, 8]) seen.push(v);
                [
                  rest(1, 2, 3),
                  most(4, 9, 2),
                  Array.prototype.map.call('abc', function (c) { return c.toUpperCase(); }),
                  Array.prototype.join.call(like, '-'),
                  Array.prototype.indexOf.call(like, 'c'),
                  Array.from(like).length,
                  Array.from({ length: 3 }, function (x, i) { return i * 2; }),
                  Array.from(new Set([1, 1, 2])),
                  Array.from('héllo').length,
                  [1].concat([2], 3, spread, like).length,
                  [[1, [2]], [3]].flat(),
                  [1, 2].flatMap(function (x) { return [x, x * 10]; }),
                  'a-b'.split('-'), 'ab'.repeat(2), '5'.padStart(3, '0'), 'ab'.split(''), 'a1b'.split(/\d/),
                  String.fromCharCode.apply(null, [104, 105]),
                  Reflect.apply(Math.min, null, [4, 5]),
                  Reflect.construct(Date, [0]).getTime(),
                  seen, [...[1, 2], 3], JSON.stringify({ k: [1, { j: [2] }] }),
                  Array.join([1, 2], '-'), Array.map([1, 2], function (x) { return x + 1; }), String.split('a-b', '-'), String.toUpperCase('abc'),
                  [1, [2, [3, [4]]]].flat(Infinity), [1, , [2, , 3]].flat(), [[1], 2].flat(0).length, [3, 4].toSource(),
                  [3, 1, 2].sort(), [1, 2, 3].reverse(), [1, 2, 3].fill(0, 1), [1, 2, 3].splice(1, 1), [5, 6].shift()
                ]
                """.trimIndent(),
            ),
        )
        assertEquals(
            """[[2,3],9,["A","B","C"],"a--c",2,3,[0,2,4],[1,2],5,6,[1,[2],3],[1,10,2,20],["a","b"],"abab","005",["a","b"],["a","b"],"hi",4,0,""" +
                """[7,8],[1,2,3],"{\"k\":[1,{\"j\":[2]}]}","1-2",[2,3],["a","b"],"ABC",[1,2,3,4],[1,2,3],2,"[3, 4]",[1,2,3],[3,2,1],[1,0,0],[2],5]""",
            outcome.resultJson,
        )
    }

    @Test
    fun `JSON stringify formats its own copy of a value as it formatted the value`() {
        val outcome = assertIs<JsOutcome.Finished>(
            run(
                """
                var cyclic = {}; cyclic.self = cyclic;
                var failed; try { JSON.stringify(cyclic); } catch (e) { failed = e instanceof TypeError; }
                var holey = [1]; holey.length = 3;
                [
                  JSON.stringify({ b: 1, a: [true, null, 'x', undefined, function () {}], d: new Date(0), u: undefined, n: new Number(3), s: new String('q'), t: new Boolean(false) }),
                  JSON.stringify({ a: 1, b: { a: 2, c: 3 } }, ['a', 'b']),
                  JSON.stringify({ a: 1, b: [1, 2] }, function (k, v) { return typeof v === 'number' ? v + 1 : v; }),
                  JSON.stringify({ a: [1, { b: 2 }] }, null, 2),
                  JSON.stringify({ toJSON: function (key) { return 'as ' + key; } }),
                  JSON.stringify([{ toJSON: function (key) { return 'at ' + key; } }]),
                  JSON.stringify(holey), JSON.stringify('x'), JSON.stringify(5), JSON.stringify(null),
                  String(JSON.stringify(undefined)), String(JSON.stringify(function () {})),
                  JSON.stringify({ '__proto__': 1, 'k y': 2 }), JSON.stringify(new Proxy([1, 2], {})), JSON.stringify({ a: NaN, b: Infinity }),
                  failed
                ]
                """.trimIndent(),
            ),
        )
        val expected = listOf(
            """{"b":1,"a":[true,null,"x",null,null],"d":"1970-01-01T00:00:00.000Z","n":3,"s":"q","t":false}""",
            """{"a":1,"b":{"a":2}}""", """{"a":2,"b":[2,3]}""", "{\n  \"a\": [\n    1,\n    {\n      \"b\": 2\n    }\n  ]\n}",
            "\"as \"", """["at 0"]""", "[1,null,null]", "\"x\"", "5", "null", "undefined", "undefined",
            """{"k y":2}""", "[1,2]", """{"a":null,"b":null}""",
        )
        val json = kotlinx.serialization.json.Json.parseToJsonElement(outcome.resultJson!!) as kotlinx.serialization.json.JsonArray
        expected.forEachIndexed { i, text -> assertEquals(text, (json[i] as kotlinx.serialization.json.JsonPrimitive).content, "item $i") }
        assertEquals("true", json.last().toString())
    }

    @Test
    fun `built-ins that change an array in place take real arrays only, and say what to do`() {
        for (name in listOf("fill", "reverse", "sort", "copyWithin", "splice", "shift", "unshift")) {
            val error = assertIs<JsOutcome.ScriptError>(run("Array.prototype.$name.call({ length: 2, 0: 'a', 1: 'b' }, 0)"), name)
            assertTrue("real arrays" in error.message && "Array.from" in error.message, "$name: ${error.message}")
        }
        val missing = assertIs<JsOutcome.ScriptError>(run("Array.prototype.forEach.call(null, function () {})"))
        assertTrue("null or undefined" in missing.message, missing.message)
    }

    @Test
    fun `a script cannot take the guards away by replacing what they use`() {
        val huge = "var a = []; a.length = 4294967295;"
        for (sabotage in listOf(
            "Number = function () { return 0; };", "Object = function (v) { return v; };", "Math.floor = function () { return 0; };",
            "Function.prototype.call = function () {};", "Function.prototype.apply = function () {};", "RangeError = function () {};",
            "String = function () { return ''; };", "Object.defineProperty(Array.prototype, 'length', { get: function () { return 0; } });",
        )) {
            ends("$sabotage $huge try { a.forEach(function () {}); } catch (e) {} try { a.join(); } catch (e) {} try { [].concat(a); } catch (e) {} 'done'")
            ends("$sabotage $getter try { Array.prototype.map.call(o, function (x) { return x; }); } catch (e) {} 'done'")
        }
        assertEquals("2", assertIs<JsOutcome.Finished>(run("1 + 1")).resultJson)
    }
}
