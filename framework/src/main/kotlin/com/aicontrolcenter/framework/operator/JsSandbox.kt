package com.aicontrolcenter.framework.operator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.javascript.Callable
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.LambdaFunction
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.SerializableCallable
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.json.JsonParser
import org.mozilla.javascript.typedarrays.NativeTypedArrayView
import java.math.BigInteger
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Bounds on one script run. */
data class JsLimits(
    val maxCodeChars: Int = 50_000,
    val maxInputChars: Int = 200_000,
    /** Interpreter instructions, counted every [OBSERVE_EVERY]. */
    val maxInstructions: Long = 50_000_000,
    val timeoutMs: Long = 5_000,
    /** Nested JavaScript calls. */
    val maxStackDepth: Int = 500,
    /** The JSON result is cut at this many characters. */
    val maxResultChars: Int = 100_000,
    /** console.log output kept, in characters and lines. */
    val maxLogChars: Int = 16_000,
    val maxLogLines: Int = 500,
    /** Largest string a host function accepts or the guarded built-ins (repeat, padStart, padEnd) build. */
    val maxStringChars: Int = 5_000_000,
    /** Largest array length the guarded built-ins (join, fill, Array.from …) walk, and the longest typed array that is sorted. */
    val maxArrayLength: Int = 1_000_000,
    /**
     * Heap growth, measured roughly, that stops a run. A run is also stopped when less than an eighth of the whole heap
     * (or less than this number, if that is smaller) is left free.
     */
    val maxHeapGrowthBytes: Long = 96L * 1024 * 1024,
    /** Java stack of the script thread. */
    val threadStackBytes: Long = 4L * 1024 * 1024,
) {
    init {
        require(maxCodeChars > 0 && maxInputChars > 0 && maxInstructions > 0 && timeoutMs > 0 && maxStackDepth > 0) { "limits must be positive" }
        require(maxResultChars > 0 && maxLogChars > 0 && maxLogLines > 0 && maxStringChars > 0 && maxArrayLength > 0) { "limits must be positive" }
    }

    /**
     * Whether a run is over its memory limit: the heap grew by more than [maxHeapGrowthBytes] since the run began
     * ([baseBytes] in use then, [usedBytes] now), or less than the reserve is left of the whole heap of [maxHeapBytes]:
     * an eighth of it, and not more than the growth limit itself. Without the reserve, a heap that was nearly full
     * before the script began could never show the growth, and the script would fill it to the last byte instead of
     * being stopped.
     */
    internal fun overMemory(usedBytes: Long, baseBytes: Long, maxHeapBytes: Long): Boolean =
        usedBytes - baseBytes > maxHeapGrowthBytes || maxHeapBytes - usedBytes < minOf(maxHeapGrowthBytes, maxHeapBytes / 8)

    companion object {
        /** How often, in interpreter instructions, the budget is checked. */
        const val OBSERVE_EVERY = 10_000
    }
}

/** How a script run ended. [log] is what console.log printed, within the limits. */
sealed interface JsOutcome {
    val log: List<String>
    val logTruncated: Boolean

    /** The script ran to the end; [resultJson] is the JSON of its last expression (null for undefined or a function). */
    data class Finished(val resultJson: String?, val resultTruncated: Boolean, override val log: List<String>, override val logTruncated: Boolean) : JsOutcome

    /** The script threw, or did not compile; [line] when known. */
    data class ScriptError(val message: String, val line: Int?, override val log: List<String>, override val logTruncated: Boolean) : JsOutcome

    /** The sandbox stopped it: a budget (instructions, time, memory, recursion) or cancellation; [reason] says which. */
    data class Stopped(val reason: String, override val log: List<String>, override val logTruncated: Boolean) : JsOutcome
}

/**
 * Optional host functions, exposed to scripts as `workspace.read(path)`, `workspace.list(path)`
 * and `workspace.write(path, text)`. Called on the script thread; they may block. Throw
 * [JsHostError] with a sentence the script can read; anything else is reported by type only.
 */
interface JsHost {
    val canWrite: Boolean

    fun read(path: String): String

    /** Entries as (name, isFolder, sizeBytes). */
    fun list(path: String): List<Triple<String, Boolean, Long?>>

    /** Creates a new file; returns its path. */
    fun write(path: String, text: String): String
}

/** A refusal from a [JsHost] function, shown to the script as an `Error`. */
class JsHostError(message: String) : RuntimeException(message)

/**
 * Runs agent JavaScript with Mozilla Rhino in a sealed sandbox (research §5.4):
 * - interpreted mode only (ART cannot load generated bytecode), ES6 language level;
 * - `initSafeStandardObjects`: no `Packages`, `java`, `JavaImporter` or `getClass`;
 * - a [ClassShutter] that denies every Java class, so no Java object or exception is ever
 *   reachable, not even through a caught error;
 * - an instruction and wall-clock budget, plus a rough heap-growth check, enforced by throwing
 *   a [java.lang.Error] from the instruction observer, which scripts can neither `catch` nor
 *   run `finally` for (`FEATURE_ENHANCED_JAVA_ACCESS` is forced off); regular expressions count
 *   their backtracking against the same budget;
 * - a maximum interpreter stack depth, and a dedicated thread with its own stack, so deep
 *   recursion ends as an error rather than taking down a shared thread;
 * - built-ins that can build huge strings or walk huge arrays in one native call (`repeat`,
 *   `padStart`, `padEnd`, `split("")`, every array method that walks the length — `join`,
 *   `forEach`, `map`, `reduce`, `slice`, `concat`, `flat` and the rest, also as Rhino's
 *   `Array.forEach(list, …)` forms and `toSource` — `Array.from`, `apply` with an array-like,
 *   and `JSON.stringify`, also for the result and console output) refuse beyond
 *   [JsLimits.maxStringChars] / [JsLimits.maxArrayLength]. Their wrappers read each value
 *   a script controls once and hand the built-in what they read: a real array (whose length
 *   cannot lie), or a bounded copy of anything that only looks like one, so a `length` that
 *   answers small to the check and huge to the built-in gets nowhere. Host functions cap what
 *   they accept and return;
 * - where a real array can still change under a built-in (an element getter that grows it or
 *   another array), the loop is the sandbox's own or is counted: `concat` copies each part to a
 *   length read once; every step of a native iterator (`Array.from`, spread, `new Set`,
 *   `new Map`, `Promise.all` …) and every comparison of a sort is charged to the instruction
 *   budget, so the budget and the time limit reach those loops; a typed array longer than
 *   [JsLimits.maxArrayLength] is not sorted; and `JSON.stringify` formats a copy without
 *   prototypes, so nothing a script put on `Object.prototype` is asked while it formats;
 * - input is JSON (`input`), output is the JSON of the last expression, and `console.log`
 *   is captured.
 *
 * Residual risk, documented for the owner: native code between two budget checks (one call of a
 * linear built-in on a large value, such as `join` on a million numbers) is not interruptible,
 * and a callback without a loop or a call of its own costs no instructions when a built-in
 * calls it, so such work is bounded by the array limit and the time limit only. If a run does not stop within a grace period
 * after its time limit, the caller gets [JsOutcome.Stopped] at once and the thread is left to
 * finish on its own; while [MAX_STUCK] such threads are alive, new runs are refused.
 */
class JsSandbox(private val limits: JsLimits = JsLimits()) {
    private val stuck = AtomicInteger(0)
    private val counter = AtomicLong(0)

    /** Runs [code] with [inputJson] as `input`; [timeoutMs] overrides the limit for this run. */
    suspend fun run(code: String, inputJson: String?, host: JsHost?, timeoutMs: Long = limits.timeoutMs): JsOutcome {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        val limits = if (timeoutMs == this.limits.timeoutMs) this.limits else this.limits.copy(timeoutMs = timeoutMs)
        if (code.length > limits.maxCodeChars) return JsOutcome.Stopped("The script is longer than ${limits.maxCodeChars} characters.", emptyList(), false)
        if (inputJson != null && inputJson.length > limits.maxInputChars) {
            return JsOutcome.Stopped("The input is longer than ${limits.maxInputChars} characters.", emptyList(), false)
        }
        if (stuck.get() >= MAX_STUCK) {
            return JsOutcome.Stopped("An earlier script has not stopped yet, so no new script is started. Try again in a minute.", emptyList(), false)
        }
        val run = ScriptRun(code, inputJson, host, limits)
        val done = CompletableDeferred<JsOutcome>()
        val thread = Thread(null, {
            val outcome = run.execute()
            if (!run.state.compareAndSet(RUNNING, FINISHED)) stuck.decrementAndGet()
            done.complete(outcome)
        }, "aicc-js-${counter.incrementAndGet()}", limits.threadStackBytes)
        thread.isDaemon = true
        thread.priority = Thread.NORM_PRIORITY - 1
        try {
            thread.start()
            return withTimeoutOrNull(limits.timeoutMs + GRACE_MS) { done.await() }
                ?: run.logOutcome { log, cut -> JsOutcome.Stopped("The script did not stop within its time limit and was abandoned.", log, cut) }
        } finally {
            run.cancelled = true
            if (!done.isCompleted && run.state.compareAndSet(RUNNING, ABANDONED)) stuck.incrementAndGet()
        }
    }

    /** Thrown from the instruction observer; a [java.lang.Error], so scripts cannot catch it. */
    private class BudgetExceeded(message: String) : Error(message)

    private class ScriptRun(val code: String, val inputJson: String?, val host: JsHost?, val limits: JsLimits) {
        val state = AtomicInteger(RUNNING)

        @Volatile var cancelled = false

        /** How often this run had the heap collected to tell garbage from growth ([observe]). */
        private var collections = 0L

        private val log = ArrayList<String>()
        private var logChars = 0
        private var logCut = false
        private var written = 0

        /** JSON.stringify with the sandbox's array check (set before the script runs). */
        private var stringify: (Context, Scriptable, Any?) -> Any? = { cx, scope, value -> NativeJSON.stringify(cx, scope, value, null, null) }

        fun <T> logOutcome(make: (List<String>, Boolean) -> T): T = synchronized(log) { make(log.toList(), logCut) }

        fun execute(): JsOutcome = try {
            Factory(this).call { cx -> evaluate(cx) }
        } catch (e: BudgetExceeded) {
            stopped(e.message ?: "The script used up its budget.")
        } catch (e: StackOverflowError) {
            stopped("The script recursed too deeply.")
        } catch (e: OutOfMemoryError) {
            stopped("The script used too much memory.")
        } catch (e: RhinoException) {
            logOutcome { log, cut -> JsOutcome.ScriptError(clean(e.details()), e.lineNumber().takeIf { it > 0 }, log, cut) }
        } catch (e: Throwable) {
            stopped("The script could not run (${e::class.simpleName}).")
        }

        private fun stopped(reason: String) = logOutcome { log, cut -> JsOutcome.Stopped(reason, log, cut) }

        private fun evaluate(cx: Context): JsOutcome {
            val scope = cx.initSafeStandardObjects(null, false)
            ScriptableObject.defineProperty(scope, "console", console(cx, scope), ScriptableObject.DONTENUM)
            val input: Any? = inputJson?.let { JsonParser(cx, scope).parseValue(it) }
            ScriptableObject.defineProperty(scope, "input", input, ScriptableObject.DONTENUM)
            host?.let { ScriptableObject.defineProperty(scope, "workspace", workspace(cx, scope, it), ScriptableObject.DONTENUM or ScriptableObject.READONLY) }
            // The guards get the one test a script cannot fake or replace: is this value a real array (never a look-alike or a Proxy)?
            val isRealArray = function(scope, "isRealArray", 1) { _, _, args -> args.getOrNull(0) is NativeArray }
            (cx.evaluateString(scope, guards(), "sandbox", 1, null) as org.mozilla.javascript.Function)
                .call(cx, scope, scope, arrayOf<Any?>(isRealArray, helpers(cx, scope)))
            // The guarded JSON.stringify, held here so a script that replaces the global cannot take the check away.
            val jsonObject = ScriptableObject.getProperty(scope, "JSON") as Scriptable
            val guarded = ScriptableObject.getProperty(jsonObject, "stringify") as org.mozilla.javascript.Function
            stringify = { c, s, value -> guarded.call(c, s, jsonObject, arrayOf(value)) }
            val result = cx.evaluateString(scope, code, "script", 1, null)
            if (result is CharSequence && result.length > limits.maxResultChars) {
                return logOutcome { log, cut -> JsOutcome.Finished(null, true, log, cut) }
            }
            val json = stringify(cx, scope, result)
            val text = if (json == Undefined.instance || json == null) null else json.toString()
            val cut = text != null && text.length > limits.maxResultChars
            return logOutcome { log, logCut -> JsOutcome.Finished(if (cut) text.take(limits.maxResultChars) else text, cut, log, logCut) }
        }

        fun observe(instructionsSoFar: Long, startNanos: Long, heapBase: Long) {
            if (cancelled) throw BudgetExceeded("The script was stopped.")
            if (instructionsSoFar > limits.maxInstructions) throw BudgetExceeded("The script used more than ${limits.maxInstructions} instructions.")
            val elapsed = System.nanoTime() - startNanos
            if (elapsed > limits.timeoutMs * 1_000_000) throw BudgetExceeded("The script ran longer than ${limits.timeoutMs} ms.")
            if (tooMuchMemory(heapBase)) {
                // The number counts garbage that is not collected yet, and a loop that allocates much (a long sort with a
                // comparator) can show more of it than the limit. So before a script is stopped for memory the heap is
                // collected and measured again: about ten times a second at most, beyond which the number is believed.
                if (collections++ >= FREE_COLLECTIONS + elapsed / COLLECTION_INTERVAL_NANOS) throw BudgetExceeded("The script used too much memory.")
                System.gc()
                if (tooMuchMemory(heapBase)) throw BudgetExceeded("The script used too much memory.")
            }
        }

        /** [JsLimits.overMemory] for the heap as it is now; [heapBase] was measured after a collection, when the run began. */
        private fun tooMuchMemory(heapBase: Long): Boolean {
            val runtime = Runtime.getRuntime()
            return limits.overMemory(runtime.totalMemory() - runtime.freeMemory(), heapBase, runtime.maxMemory())
        }

        private fun console(cx: Context, scope: Scriptable): Scriptable {
            val console = cx.newObject(scope)
            val logFn = function(scope, "log", 1) { c, s, args ->
                val line = args.joinToString(" ") { show(c, s, it) }
                synchronized(log) {
                    when {
                        logCut -> Unit
                        log.size >= limits.maxLogLines || logChars + line.length > limits.maxLogChars -> {
                            val room = limits.maxLogChars - logChars
                            if (room > 0 && log.size < limits.maxLogLines) log += line.take(room)
                            logChars = limits.maxLogChars
                            logCut = true
                        }
                        else -> {
                            log += line
                            logChars += line.length
                        }
                    }
                }
                Undefined.instance
            }
            for (name in listOf("log", "info", "warn", "error", "debug")) ScriptableObject.putProperty(console, name, logFn)
            return console
        }

        /** One console.log argument as text, without ever building a string larger than the log can hold. */
        private fun show(cx: Context, scope: Scriptable, value: Any?): String = when {
            value is CharSequence && value.length > limits.maxLogChars -> "[a string of ${value.length} characters]"
            value is Scriptable && value !is org.mozilla.javascript.Function -> {
                val json = runCatching { stringify(cx, scope, value) }.getOrNull()
                if (json is CharSequence && json.length <= limits.maxLogChars) json.toString() else Context.toString(value).take(limits.maxLogChars)
            }
            else -> Context.toString(value).take(limits.maxLogChars)
        }

        private fun workspace(cx: Context, scope: Scriptable, host: JsHost): Scriptable {
            val api = cx.newObject(scope)
            ScriptableObject.putProperty(api, "read", function(scope, "read", 1) { _, _, args ->
                val text = hostCall { host.read(stringArg(args, 0, "path")) }
                if (text.length > limits.maxStringChars) throw ScriptRuntime.rangeError("The file is longer than ${limits.maxStringChars} characters.")
                text
            })
            ScriptableObject.putProperty(api, "list", function(scope, "list", 1) { c, s, args ->
                val entries = hostCall { host.list(if (args.isEmpty() || args[0] == Undefined.instance) "" else stringArg(args, 0, "path")) }
                c.newArray(s, entries.take(limits.maxArrayLength).map { (name, folder, size) ->
                    c.newObject(s).also {
                        ScriptableObject.putProperty(it, "name", name)
                        ScriptableObject.putProperty(it, "folder", folder)
                        ScriptableObject.putProperty(it, "size", size?.toDouble())
                    }
                }.toTypedArray<Any?>())
            })
            ScriptableObject.putProperty(api, "write", function(scope, "write", 2) { _, _, args ->
                if (!host.canWrite) throw ScriptRuntime.constructError("Error", "This script may only read the workspace.")
                if (written >= MAX_FILES_WRITTEN) throw ScriptRuntime.constructError("Error", "A script may create at most $MAX_FILES_WRITTEN files.")
                val path = stringArg(args, 0, "path")
                val text = stringArg(args, 1, "text")
                hostCall { host.write(path, text) }.also { written++ }
            })
            return api
        }

        /**
         * What the guards cannot do in script code at a bearable cost, done here. Each helper works on a count it was
         * given or read once, never on a length it reads again, and charges the script's budget as it goes ([charge]),
         * so the instruction budget, the time limit and a cancellation reach its loop like a loop in script code.
         * Scripts never see this object: only the guards hold it.
         */
        private fun helpers(cx: Context, scope: Scriptable): Scriptable {
            val api = cx.newObject(scope)
            fun put(name: String, arity: Int, body: (Context, Scriptable, Array<Any?>) -> Any?) = ScriptableObject.putProperty(api, name, function(scope, name, arity, body))
            // A typed array's own length, which no property a script defines can change; 0 for anything else.
            put("typedLength", 1) { _, _, args -> (args.getOrNull(0) as? NativeTypedArrayView<*>)?.arrayLength ?: 0 }
            // An iterator's `next` that counts every step: native loops (Array.from, spread, new Set, Promise.all …) call it.
            put("countedNext", 1) { _, _, args ->
                val original = args.getOrNull(0) as? Callable ?: throw ScriptRuntime.typeError("next is not a function.")
                LambdaFunction(scope, "next", 0, SerializableCallable { c, s, self, a ->
                    charge(c, ITERATOR_STEP_COST)
                    original.call(c, s, self, a)
                })
            }
            // copyInto(target, at, source, count): the first `count` elements of `source` to `target[at…]`, holes staying holes.
            put("copyInto", 4) { c, _, args ->
                val target = args.getOrNull(0) as? NativeArray ?: throw ScriptRuntime.typeError("The target is not an array.")
                val source = args.getOrNull(2) as? Scriptable ?: throw ScriptRuntime.typeError("The source is not an object.")
                val at = (args.getOrNull(1) as? Number)?.toInt() ?: 0
                val count = ((args.getOrNull(3) as? Number)?.toInt() ?: 0).coerceAtMost(limits.maxArrayLength)
                if (at < 0 || at > limits.maxArrayLength) throw ScriptRuntime.rangeError("Arrays longer than ${limits.maxArrayLength} elements are not allowed here.")
                var i = 0
                while (i < count) {
                    val end = minOf(count, i + CHARGE_EVERY)
                    charge(c, (end - i + COPY_PER_UNIT - 1) / COPY_PER_UNIT)
                    while (i < end) {
                        val value = ScriptableObject.getProperty(source, i)
                        if (value !== Scriptable.NOT_FOUND) target.put(at + i, target, value)
                        i++
                    }
                }
                Undefined.instance
            }
            // A comparator for the built-in sort that counts every comparison: `compare` itself, or the default order (as text).
            put("countedCompare", 1) { _, _, args ->
                val compare = args.getOrNull(0) as? Callable
                LambdaFunction(scope, "compare", 2, SerializableCallable { c, s, _, pair ->
                    charge(c, COMPARE_COST)
                    if (compare != null) compare.call(c, s, scope, pair)
                    else ScriptRuntime.toString(pair.getOrNull(0)).compareTo(ScriptRuntime.toString(pair.getOrNull(1)))
                })
            }
            val notPlain = cx.newObject(scope)
            ScriptableObject.putProperty(api, "notPlain", notPlain)
            // The built-in Date.prototype.toJSON, as it is before any script runs.
            val dateToJson = (ScriptableObject.getClassPrototype(scope, "Date") as? Scriptable)?.let { ScriptableObject.getProperty(it, "toJSON") } as? Callable
            put("plainCopy", 1) { c, s, args -> PlainCopy(c, s, dateToJson, notPlain).of(args.getOrNull(0)) }
            return api
        }

        /** Counts [units] against the script's instruction budget; the observer then checks the budget, the time limit and cancellation. */
        private fun charge(cx: Context, units: Int) {
            if (units > 0) ScriptRuntime.addInstructionCount(cx, units)
        }

        /**
         * The quick way to the copy `JSON.stringify` formats, for plain data: ordinary objects and arrays holding strings,
         * numbers, booleans, null, dates and each other (functions and symbols in them are left out, as the built-in leaves
         * them out). [of] returns a private copy of such a value, or [notPlain] as soon as it meets anything else (a
         * `toJSON` of the script's, a Proxy, a typed array, a wrapper object, a BigInt, a cycle, an array over the limit,
         * nesting deeper than [MAX_PLAIN_DEPTH] …); the guards then walk the value in script code as before. Every value
         * is read once; an array's length is read once and its loop runs to that number, whatever a getter does to the
         * array meanwhile. The copies have no prototype, so formatting them looks nothing up that a script could have
         * defined.
         */
        private inner class PlainCopy(private val cx: Context, private val scope: Scriptable, private val dateToJson: Callable?, private val notPlain: Any) {
            private val open = IdentityHashMap<Any, Boolean>()
            private var uncharged = 0

            /** [value] is an object the guards already asked for its toJSON (it has none of its own to call). */
            fun of(value: Any?): Any? = try {
                copy(value, 0, asked = true)
            } finally {
                charge(cx, uncharged * PLAIN_COPY_COST)
            }

            private fun copy(value: Any?, depth: Int, asked: Boolean = false): Any? {
                if (++uncharged >= CHARGE_EVERY) {
                    charge(cx, uncharged * PLAIN_COPY_COST)
                    uncharged = 0
                }
                return when {
                    isPrimitive(value) -> value
                    depth >= MAX_PLAIN_DEPTH -> notPlain
                    value is NativeArray && value.javaClass == NativeArray::class.java -> inside(value, asked) { array(value, depth) }
                    value is NativeObject && value.javaClass == NativeObject::class.java -> inside(value, asked) { members(value, depth) }
                    // A date with the built-in toJSON is its ISO text (or null); the built-in is asked, as the built-in stringify would.
                    dateToJson != null && value is ScriptableObject && value.className == "Date" && ScriptableObject.getProperty(value, "toJSON") === dateToJson ->
                        dateToJson.call(cx, scope, value, arrayOf<Any?>("")).let { if (isPrimitive(it)) it else notPlain }
                    else -> when (ScriptRuntime.`typeof`(value)) {
                        // Left out of JSON, unless a toJSON speaks for the function.
                        "symbol" -> Undefined.instance
                        "function" -> if (value is Scriptable && ScriptableObject.getProperty(value, "toJSON") !is Callable) Undefined.instance else notPlain
                        else -> notPlain
                    }
                }
            }

            /** A string, a number, a boolean, null or undefined: what the built-in formats without looking anything up. */
            private fun isPrimitive(value: Any?): Boolean =
                value == null || value === Undefined.instance || value is Boolean || value is CharSequence || (value is Number && value !is BigInteger)

            /** [block] with [value] marked as being copied, so a value that holds itself is not followed for ever. */
            private inline fun inside(value: ScriptableObject, asked: Boolean, block: () -> Any?): Any? {
                if (open.put(value, true) != null) return notPlain
                try {
                    // A toJSON method (its own or inherited) speaks for the value: that is script code, and for the guards to call.
                    if (!asked && ScriptableObject.getProperty(value, "toJSON") is Callable) return notPlain
                    return block()
                } finally {
                    open.remove(value)
                }
            }

            private fun array(value: NativeArray, depth: Int): Any? {
                val length = value.length
                if (length > limits.maxArrayLength) return notPlain
                val items = arrayOfNulls<Any?>(length.toInt())
                for (i in items.indices) {
                    // As `value[i]` in a script: a hole reads through the prototypes and is undefined when nothing is there.
                    val item = ScriptableObject.getProperty(value, i).let { if (it === Scriptable.NOT_FOUND) Undefined.instance else it }
                    items[i] = copy(item, depth + 1).also { if (it === notPlain) return notPlain }
                }
                return cx.newArray(scope, items).also { it.prototype = null }
            }

            private fun members(value: ScriptableObject, depth: Int): Any? {
                val out = NativeObject().also { it.parentScope = scope }
                // The object's own enumerable members, in the order Object.keys gives them.
                for (id in value.ids) {
                    val item = when (id) {
                        is Int -> ScriptableObject.getProperty(value, id)
                        is String -> ScriptableObject.getProperty(value, id)
                        else -> continue
                    }
                    val copied = copy(if (item === Scriptable.NOT_FOUND) Undefined.instance else item, depth + 1)
                    if (copied === notPlain) return notPlain
                    if (copied === Undefined.instance) continue
                    if (id is Int) out.put(id, out, copied) else out.defineProperty(id as String, copied, ScriptableObject.EMPTY)
                }
                return out
            }
        }

        private fun stringArg(args: Array<Any?>, index: Int, name: String): String {
            val value = args.getOrNull(index)
            if (value !is CharSequence) throw ScriptRuntime.typeError("\"$name\" must be a string.")
            if (value.length > limits.maxStringChars) throw ScriptRuntime.rangeError("\"$name\" is longer than ${limits.maxStringChars} characters.")
            return value.toString()
        }

        private fun <T> hostCall(block: () -> T): T = try {
            block()
        } catch (e: JsHostError) {
            throw ScriptRuntime.constructError("Error", e.message ?: "Refused.")
        } catch (e: RhinoException) {
            throw e
        } catch (e: Exception) {
            throw ScriptRuntime.constructError("Error", "The workspace operation failed (${e::class.simpleName}).")
        }

        private fun function(scope: Scriptable, name: String, arity: Int, body: (Context, Scriptable, Array<Any?>) -> Any?) =
            LambdaFunction(scope, name, arity, SerializableCallable { cx, s, _, args -> body(cx, s, args) })

        private fun guards(): String = GUARDS.replace("MAX_STR", limits.maxStringChars.toString()).replace("MAX_ARR", limits.maxArrayLength.toString())

        private fun clean(details: String): String = details.lineSequence().first().take(500)
    }

    private class Factory(private val run: ScriptRun) : ContextFactory() {
        private var instructions = 0L

        /**
         * What is in use before the script begins, measured after a collection: left as it is, the number would count
         * the garbage of whatever ran before, and a script could then keep that much more than its limit before the
         * check, which collects before it stops a script ([ScriptRun.observe]), counted it as growth. The collection
         * runs on the script's own thread, before its time starts.
         */
        private val heapBase = Runtime.getRuntime().let { runtime ->
            System.gc()
            runtime.totalMemory() - runtime.freeMemory()
        }
        private val start = System.nanoTime()

        override fun makeContext(): Context = super.makeContext().apply {
            isInterpretedMode = true
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = JsLimits.OBSERVE_EVERY
            maximumInterpreterStackDepth = run.limits.maxStackDepth
            isGeneratingDebug = false
            setClassShutter(DENY_ALL)
        }

        override fun hasFeature(cx: Context, featureIndex: Int): Boolean = when (featureIndex) {
            // Off: with it, scripts could catch the budget Error and see Java exceptions.
            Context.FEATURE_ENHANCED_JAVA_ACCESS -> false
            Context.FEATURE_E4X -> false
            Context.FEATURE_DYNAMIC_SCOPE -> false
            else -> super.hasFeature(cx, featureIndex)
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            instructions += instructionCount
            run.observe(instructions, start, heapBase)
        }
    }

    private companion object {
        const val RUNNING = 0
        const val FINISHED = 1
        const val ABANDONED = 2
        const val GRACE_MS = 2_000L
        const val MAX_STUCK = 2
        const val MAX_FILES_WRITTEN = 20

        /** Collections a run may ask for at once, and the time that earns it one more ([ScriptRun.observe]). */
        const val FREE_COLLECTIONS = 4L
        const val COLLECTION_INTERVAL_NANOS = 100_000_000L

        /** What the native helpers charge the script's instruction budget: per step of a native iterator, */
        const val ITERATOR_STEP_COST = 2

        /** per [COPY_PER_UNIT] elements copied by concat, */
        const val COPY_PER_UNIT = 8

        /** per comparison of a sort, */
        const val COMPARE_COST = 1

        /** and per value copied for JSON.stringify on the quick way. */
        const val PLAIN_COPY_COST = 2

        /** A native loop charges at least this often (in elements), so the observer is reached while it runs. */
        const val CHARGE_EVERY = 512

        /** Nesting the quick copy for JSON.stringify follows; deeper values are walked in script code. */
        const val MAX_PLAIN_DEPTH = 200

        val DENY_ALL = ClassShutter { false }

        /**
         * Wraps built-ins that can do unbounded native work in one call. A function taking the native test for a real
         * array; called once before the script, in its scope.
         *
         * The rule every wrapper follows: a value a script controls is read exactly once, here, and the built-in is then
         * called with what was read, so the built-in can never see a different length, count or list than the one that
         * was checked. A real array's length is its own and cannot lie. Anything else that looks like an array (an
         * object with a "length", which may be a getter or have a valueOf that answers differently each time) is copied
         * here, in script code the budget reaches, into a real array of at most MAX_ARR elements, and the built-in works
         * on that copy; the built-ins that change an array in place take real arrays only.
         */
        val GUARDS = """
            (function (isRealArray, native) {
              "use strict";
              var MAXS = MAX_STR, MAXA = MAX_ARR;
              // The sandbox's own helpers (JsSandbox.helpers): loops over a count read once, charged to the script's budget as they run.
              var typedLength = native.typedLength, countedNext = native.countedNext, copyInto = native.copyInto;
              var countedCompare = native.countedCompare, plainCopy = native.plainCopy, NOT_PLAIN = native.notPlain;
              // The originals, captured before anything is wrapped: the wrappers call through them, never through what a script can replace.
              var callApply = Function.prototype.call.bind(Function.prototype.apply);
              var toObject = Object, toNumber = Number, toText = String, floor = Math.floor, keysOf = Object.keys, RegExpType = RegExp;
              var NumberType = Number, StringType = String, BooleanType = Boolean, booleanValue = Boolean.prototype.valueOf;
              var isArray = Array.isArray, define = Object.defineProperty, getProto = Object.getPrototypeOf, setProto = Object.setPrototypeOf;
              var iteratorKey = typeof Symbol === "function" ? Symbol.iterator : undefined;
              var spreadKey = typeof Symbol === "function" ? Symbol.isConcatSpreadable : undefined;
              function replace(owner, name, make) {
                var original = owner[name];
                if (typeof original !== "function") return;
                Object.defineProperty(owner, name, { value: make(original), writable: true, configurable: true, enumerable: false });
              }
              function tooLong(n) { if (n > MAXS) throw new RangeError("Strings longer than " + MAXS + " characters are not allowed here."); }
              function tooMany(n) { if (n > MAXA) throw new RangeError("Arrays longer than " + MAXA + " elements are not allowed here."); }
              function isObject(v) { return v !== null && (typeof v === "object" || typeof v === "function"); }
              // The value as a real array of at most MAXA elements: itself when it is one, else a copy made here.
              function realArray(value) {
                if (isRealArray(value)) { tooMany(value.length); return value; }
                if (value === null || value === undefined) throw new TypeError("An array method was called on null or undefined.");
                var source = toObject(value);
                var n = toNumber(source.length);
                n = n > 0 ? floor(n) : 0;
                tooMany(n);
                var copy = [];
                for (var i = 0; i < n; i++) if (i in source) copy[i] = source[i];
                copy.length = n;
                return copy;
              }
              // An argument list (for apply): null, undefined and non-objects are left for the built-in to judge.
              function list(args) { return isObject(args) ? realArray(args) : args; }
              function text(value) { return typeof value === "string" ? value : toText(value); }

              replace(String.prototype, "repeat", function (original) {
                return function (count) {
                  if (this === null || this === undefined) return callApply(original, this, arguments);
                  var s = text(this), n = toNumber(count);
                  tooLong(s.length * n);
                  return callApply(original, s, [n]);
                };
              });
              ["padStart", "padEnd"].forEach(function (name) {
                replace(String.prototype, name, function (original) {
                  return function (length, fill) {
                    if (this === null || this === undefined) return callApply(original, this, arguments);
                    var s = text(this), n = toNumber(length);
                    tooLong(n);
                    return fill === undefined ? callApply(original, s, [n]) : callApply(original, s, [n, text(fill)]);
                  };
                });
              });
              // Splitting at every character makes one array element per character in a single native call.
              replace(String.prototype, "split", function (original) {
                return function (separator, limit) {
                  if (this === null || this === undefined) return callApply(original, this, arguments);
                  var s = text(this), sep = separator;
                  if (isObject(sep) && !(sep instanceof RegExpType)) sep = text(sep);
                  if (sep === "") tooMany(s.length);
                  return limit === undefined ? callApply(original, s, [sep]) : callApply(original, s, [sep, limit]);
                };
              });

              // Every built-in that walks an array's length in one native call: a sparse array of length 2^32-1 would keep it
              // busy past the time limit, where the budget check cannot reach it.
              var walking = ["join", "toString", "toLocaleString", "toSource", "indexOf", "lastIndexOf", "includes", "forEach", "map", "filter",
               "some", "every", "reduce", "reduceRight", "find", "findIndex", "findLast", "findLastIndex", "slice", "keys", "values", "entries",
               "toReversed", "toSpliced", "with"];
              if (iteratorKey !== undefined) {
                // A native iterator is stepped by native loops that no check reaches (Array.from, spread, new Set, new Map,
                // Promise.all, Object.groupBy …), and the array one reads the array's length again at every step: an element
                // getter that grows the array after the check would keep such a loop running. So every step of a native
                // iterator is counted against the script's budget, and the loop meets the instruction budget and the time
                // limit like a loop in script code. Arrays, typed arrays and arguments share one iterator prototype.
                var counted = [];
                [function () { return [][iteratorKey](); }, function () { return ""[iteratorKey](); },
                 function () { return new Set()[iteratorKey](); }, function () { return new Map()[iteratorKey](); },
                 function () { return new Int8Array(0)[iteratorKey](); }, function () { return "".matchAll(/x/g); }].forEach(function (make) {
                  var proto;
                  try { proto = getProto(make()); } catch (e) { return; }
                  for (var i = 0; i < counted.length; i++) if (counted[i] === proto) return;
                  counted[counted.length] = proto;
                  replace(proto, "next", countedNext);
                });
                walking.push(iteratorKey);
              }
              walking.forEach(function (name) {
                replace(Array.prototype, name, function (original) {
                  return function () { return callApply(original, realArray(this), arguments); };
                });
              });
              // These change the array itself, so a copy would not do: real arrays only.
              function realOnly(self, name) {
                if (!isRealArray(self)) throw new TypeError("Array.prototype." + name + " works only on real arrays here; copy the value with Array.from first.");
                tooMany(self.length);
              }
              ["fill", "reverse", "copyWithin", "splice", "shift", "unshift"].forEach(function (name) {
                replace(Array.prototype, name, function (original) {
                  return function () {
                    realOnly(this, name);
                    return callApply(original, this, arguments);
                  };
                });
              });
              // Sorting is slower than linear, and with the default order or a built-in function as comparator no script code
              // runs while it works. So the built-in sorts with a comparator of the sandbox that counts every comparison
              // (and then asks the script's comparator, or compares as text, the default order): the budget and the time
              // limit reach a long sort. Anything that is not a function is left for the built-in to refuse.
              function sorted(original, self, args) {
                var compare = args.length > 0 ? args[0] : undefined;
                return compare === undefined || typeof compare === "function" ? callApply(original, self, [countedCompare(compare)]) : callApply(original, self, args);
              }
              replace(Array.prototype, "sort", function (original) {
                return function () {
                  realOnly(this, "sort");
                  return sorted(original, this, arguments);
                };
              });
              replace(Array.prototype, "toSorted", function (original) {
                return function () { return sorted(original, realArray(this), arguments); };
              });
              // Typed arrays can be far longer than MAXA (their limit is memory). Their sort takes numbers without asking
              // any script code, so it is refused beyond the array limit; the linear methods are left alone.
              if (typeof Int8Array === "function") {
                // One prototype, shared by every kind of typed array, carries their methods.
                var typedProto = getProto(Int8Array.prototype);
                ["sort", "toSorted"].forEach(function (name) {
                  replace(typedProto, name, function (original) {
                    return function () {
                      tooMany(typedLength(this));
                      return callApply(original, this, arguments);
                    };
                  });
                });
              }
              // flat walks the arrays inside the array too, and an element can be a getter that hands over a different array each
              // time it is read. So flat is done here, in script code: every element is read once, and the result stays within the limit.
              replace(Array.prototype, "flat", function () {
                return function (depth) {
                  var levels = depth === undefined ? 1 : toNumber(depth), out = [];
                  (function spread(source, left) {
                    for (var i = 0, n = source.length; i < n; i++) {
                      var item = source[i];
                      if (item === undefined && !(i in source)) continue;
                      if (left >= 1 && typeof item === "object" && item !== null && isArray(item)) spread(realArray(item), left - 1);
                      else {
                        out[out.length] = item;
                        tooMany(out.length);
                      }
                    }
                  })(realArray(this), levels);
                  return out;
                };
              });
              // flatMap spreads what its callback returns: all of that together must stay within the limit.
              replace(Array.prototype, "flatMap", function (original) {
                return function (callback, thisArg) {
                  var self = realArray(this), total = 0;
                  if (typeof callback !== "function") return callApply(original, self, arguments);
                  return callApply(original, self, [function () {
                    var result = callApply(callback, this, arguments);
                    if (isRealArray(result)) tooMany(total += result.length);
                    return result;
                  }, thisArg]);
                };
              });
              // concat reads each part's length when it gets to that part, and an element getter of an earlier part can grow
              // a later one in between. So concat is done here: each part's length is read once, the total is checked, and the
              // sandbox copies exactly that many elements (holes stay holes), whatever happens to the part meanwhile.
              replace(Array.prototype, "concat", function (original) {
                return function () {
                  if (this === null || this === undefined) return callApply(original, this, arguments);
                  var out = [], n = 0, self = toObject(this);
                  for (var i = -1; i < arguments.length; i++) {
                    var item = i < 0 ? self : arguments[i], kind = typeof item, spread = false;
                    if (item !== null && (kind === "object" || kind === "function")) {
                      var flag = spreadKey === undefined ? undefined : item[spreadKey];
                      spread = flag === undefined ? isArray(item) : !!flag;
                    }
                    if (spread) {
                      // A real array's length is its own; anything else is copied to a real array of checked length first.
                      var part = isRealArray(item) ? item : realArray(item), count = part.length;
                      if (n + count > MAXA) tooMany(n + count);
                      copyInto(out, n, part, count);
                      n += count;
                    } else {
                      if (n >= MAXA) tooMany(n + 1);
                      out[n++] = item;
                    }
                  }
                  out.length = n;
                  return out;
                };
              });
              replace(Array, "from", function (original) {
                return function (source, mapFn, thisArg) {
                  var safe = source;
                  // A string or a real array is walked by the built-in through its iterator, whose steps are counted (above).
                  if (typeof source === "string") tooMany(source.length);
                  else if (isRealArray(source)) tooMany(source.length);
                  else if (isObject(source)) {
                    var method = iteratorKey === undefined ? undefined : source[iteratorKey];
                    if (method !== null && method !== undefined) {
                      // Walked here, in script code, so an endless iterator meets the budget and a long one the limit.
                      var iterator = callApply(method, source, []);
                      safe = [];
                      for (var step = iterator.next(); !step.done; step = iterator.next()) {
                        safe[safe.length] = step.value;
                        tooMany(safe.length);
                      }
                    } else safe = realArray(source);
                  }
                  return arguments.length > 1 ? callApply(original, this, [safe, mapFn, thisArg]) : callApply(original, this, [safe]);
                };
              });
              // Rhino also offers array and string methods as functions on Array and String themselves (Array.forEach(list, f),
              // String.split(text, "")), which would walk around every wrapper above: each goes through the method it stands for.
              [Array, String].forEach(function (owner) {
                Object.getOwnPropertyNames(owner).forEach(function (name) {
                  var method = owner.prototype[name];
                  if (typeof owner[name] !== "function" || typeof method !== "function" || name === "constructor") return;
                  Object.defineProperty(owner, name, {
                    value: function (target) {
                      var rest = [];
                      for (var i = 1; i < arguments.length; i++) rest[i - 1] = arguments[i];
                      return callApply(method, target, rest);
                    },
                    writable: true, configurable: true, enumerable: false
                  });
                });
              });
              // Calling with an array-like copies every element first, natively.
              replace(Function.prototype, "apply", function (original) {
                return function (self, args) { return callApply(original, this, [self, list(args)]); };
              });
              if (typeof Reflect === "object") {
                replace(Reflect, "apply", function (original) {
                  return function (target, self, args) { return callApply(original, this, [target, self, list(args)]); };
                });
                replace(Reflect, "construct", function (original) {
                  return function (target, args, newTarget) {
                    return arguments.length > 2 ? callApply(original, this, [target, list(args), newTarget]) : callApply(original, this, [target, list(args)]);
                  };
                });
              }
              // JSON.stringify reads what it serialises natively, and a getter, a toJSON or a replacer can hand it a huge array
              // that no check saw before. So the built-in only ever formats a copy made first: plain objects and real arrays
              // of checked length, holding strings, numbers, booleans and null. The copies have no prototype, so the built-in
              // finds no toJSON on them (a script could have put one on Object.prototype), and a BigInt, which the built-in
              // would ask for a toJSON too, never reaches it. Plain data is copied by the sandbox itself (plainCopy), at a
              // small charge per value; anything else (a toJSON, a replacer, a Date, a Proxy, a cycle …) is walked here, in
              // script code the budget reaches, with toJSON and the replacer applied as the built-in would.
              replace(JSON, "stringify", function (original) {
                return function (value, replacer, space) {
                  var change = typeof replacer === "function" ? replacer : null, pick = null, stack = [];
                  if (change === null && isArray(replacer)) {
                    var listed = realArray(replacer);
                    pick = [];
                    for (var p = 0; p < listed.length; p++) {
                      var name = listed[p];
                      if (typeof name === "number" || name instanceof NumberType || name instanceof StringType) name = text(name);
                      if (typeof name === "string") pick[pick.length] = name;
                    }
                  }
                  var quick = change === null && pick === null;
                  // Strings, numbers, booleans, null and undefined are copied in the loops below without a call.
                  function plain(holder, key, v) {
                    var kind = typeof v;
                    if (v !== null && (kind === "object" || kind === "function" || kind === "bigint")) {
                      var toJSON = v.toJSON;
                      if (typeof toJSON === "function") v = callApply(toJSON, v, [key]);
                    }
                    if (change !== null) v = callApply(change, holder, [key, v]);
                    kind = typeof v;
                    if (v === null || kind === "string" || kind === "number" || kind === "boolean" || kind === "undefined") return v;
                    if (kind === "bigint") throw new TypeError("Do not know how to serialize a BigInt");
                    if (kind !== "object") return undefined;
                    if (v instanceof NumberType) return toNumber(v);
                    if (v instanceof StringType) return text(v);
                    if (v instanceof BooleanType) return callApply(booleanValue, v, []) === true;
                    if (quick) {
                      var copy = plainCopy(v);
                      if (copy !== NOT_PLAIN) return copy;
                    }
                    for (var s = 0; s < stack.length; s++) if (stack[s] === v) throw new TypeError("Cyclic object value: it cannot be turned into JSON.");
                    stack[stack.length] = v;
                    var out, item;
                    if (isArray(v)) {
                      var n = isRealArray(v) ? v.length : toNumber(v.length);
                      n = n > 0 ? floor(n) : 0;
                      tooMany(n);
                      out = [];
                      setProto(out, null);
                      for (var i = 0; i < n; i++) {
                        item = v[i];
                        kind = typeof item;
                        out[i] = change === null && (item === null || kind === "string" || kind === "number" || kind === "boolean" || kind === "undefined")
                          ? item : plain(v, toText(i), item);
                      }
                      out.length = n;
                    } else {
                      out = { __proto__: null };
                      var keys = pick !== null ? pick : keysOf(v);
                      for (var k = 0; k < keys.length; k++) {
                        item = v[keys[k]];
                        kind = typeof item;
                        if (change !== null || !(item === null || kind === "string" || kind === "number" || kind === "boolean" || kind === "undefined")) {
                          item = plain(v, keys[k], item);
                        }
                        if (item === undefined) continue;
                        // Assigning to "__proto__" would set the copy's prototype instead of a member.
                        if (keys[k] === "__proto__") define(out, keys[k], { value: item, enumerable: true, writable: true, configurable: true });
                        else out[keys[k]] = item;
                      }
                    }
                    stack.length = stack.length - 1;
                    return out;
                  }
                  var indent = space instanceof NumberType ? toNumber(space) : space instanceof StringType ? text(space) : isObject(space) ? undefined : space;
                  return callApply(original, this, [plain({ "": value }, "", value), null, indent]);
                };
              });
            })
        """.trimIndent()
    }
}
