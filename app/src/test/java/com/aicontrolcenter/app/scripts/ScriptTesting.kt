package com.aicontrolcenter.app.scripts

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.aicontrolcenter.framework.operator.JsEngine
import com.aicontrolcenter.framework.operator.JsHost
import com.aicontrolcenter.framework.operator.JsHostError
import com.aicontrolcenter.framework.operator.JsLimits
import com.aicontrolcenter.framework.operator.JsOutcome
import com.aicontrolcenter.framework.operator.JsSandbox
import com.aicontrolcenter.testkit.contract.ScriptEnded
import com.aicontrolcenter.testkit.contract.ScriptEngineUnderTest
import com.aicontrolcenter.testkit.contract.ScriptHostRefusal
import com.aicontrolcenter.testkit.contract.ScriptHostUnderTest
import com.aicontrolcenter.testkit.contract.ScriptLimitsUnderTest
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real pipes for the tests (java.nio), standing behind the descriptors that Robolectric makes of `createPipe` (a file
 * whose reader sees its end before the writer is done). In one process a binder call hands the very same descriptor
 * over, so the end handed over stays open: the receiver owns it now, as it owns its own copy on a phone.
 */
internal class TestPipes : ScriptPipes {
    private val sources = ConcurrentHashMap<ParcelFileDescriptor, Pipe.SourceChannel>()
    private val sinks = ConcurrentHashMap<ParcelFileDescriptor, Pipe.SinkChannel>()

    override fun open(): ScriptPipes.Pipe {
        val pipe = Pipe.open()
        val ends = ParcelFileDescriptor.createPipe()
        sources[ends[0]] = pipe.source()
        sinks[ends[1]] = pipe.sink()
        return ScriptPipes.Pipe(ends[0], ends[1])
    }

    override fun reader(end: ParcelFileDescriptor): InputStream = Channels.newInputStream(sources[end] ?: error("not a read end of these pipes"))

    override fun writer(end: ParcelFileDescriptor): OutputStream = Channels.newOutputStream(sinks[end] ?: error("not a write end of these pipes"))

    override fun close(end: ParcelFileDescriptor) {
        sources[end]?.let { runCatching { it.close() } }
        sinks[end]?.let { runCatching { it.close() } }
        runCatching { end.close() }
    }

    override fun handedOver(end: ParcelFileDescriptor) = Unit

    /** Pipe ends still open (a pipe counts twice while both ends are). */
    fun openEnds(): Int = sources.values.count { it.isOpen } + sinks.values.count { it.isOpen }
}

/**
 * Script processes for the tests: each bind makes a process ([makeProcess]; null refuses the bind) and connects it after
 * [connectDelayMs] on a thread of its own, as Android's callbacks come; [kill] ends a process as a death would.
 */
internal class DirectLauncher(
    private val connectDelayMs: Long = 0,
    private val makeProcess: (instance: String, launcher: DirectLauncher) -> IBinder?,
) : ScriptProcessLauncher {
    inner class Handle(val instance: String, val events: ScriptProcessLauncher.Events, val binder: IBinder) : ScriptProcessLauncher.Binding {
        @Volatile var bound = true
    }

    val binds: MutableList<String> = CopyOnWriteArrayList()
    val unbinds: MutableList<String> = CopyOnWriteArrayList()
    val handles: MutableList<Handle> = CopyOnWriteArrayList()

    override fun bind(instance: String, events: ScriptProcessLauncher.Events): ScriptProcessLauncher.Binding? {
        val binder = makeProcess(instance, this) ?: return null
        binds += instance
        val handle = Handle(instance, events, binder)
        handles += handle
        Thread({
            if (connectDelayMs > 0) Thread.sleep(connectDelayMs)
            if (handle.bound) events.connected(binder)
        }, "test-script-connect").apply { isDaemon = true }.start()
        return handle
    }

    override fun unbind(binding: ScriptProcessLauncher.Binding) {
        val handle = binding as Handle
        if (!handle.bound) return
        handle.bound = false
        unbinds += handle.instance
    }

    /** The process behind [binder] dies: every binding to it hears of it. */
    fun kill(binder: IBinder) {
        handles.filter { it.binder === binder && it.bound }.forEach { it.events.ended() }
    }

    /** Bindings the engine still holds. */
    fun bound(): List<String> = handles.filter { it.bound }.map { it.instance }
}

/** A script process for the tests: the real runner, whose watchdog's end of the process is a death [DirectLauncher] reports. */
internal fun honestProcess(pipes: ScriptPipes, ends: AtomicInteger = AtomicInteger(), sandboxes: (JsLimits) -> JsSandbox = { JsSandbox(it) }) =
    { _: String, launcher: DirectLauncher ->
        lateinit var runner: ScriptRunner
        runner = ScriptRunner(pipes, endProcess = {
            ends.incrementAndGet()
            launcher.kill(runner)
        }, pid = { 4_000 + ends.get() }, sandboxes = sandboxes)
        runner as IBinder
    }

/** The testkit's script-engine contract in the framework's types (`JsEngineContract`; :testkit does not see :framework). */
internal object JsContractAdapter {
    fun limits(l: ScriptLimitsUnderTest, base: JsLimits = JsLimits()): JsLimits = base.copy(
        maxCodeChars = l.maxCodeChars,
        maxInputChars = l.maxInputChars,
        timeoutMs = l.timeoutMs,
        maxResultChars = l.maxResultChars,
        maxLogChars = l.maxLogChars,
        maxLogLines = l.maxLogLines,
        maxStringChars = l.maxStringChars,
    )

    fun engine(engine: JsEngine): ScriptEngineUnderTest = ScriptEngineUnderTest { code, input, host, timeoutMs ->
        ended(engine.run(code, input, host?.let(::host), timeoutMs))
    }

    fun host(host: ScriptHostUnderTest): JsHost = object : JsHost {
        override val canWrite: Boolean get() = host.canWrite

        override fun read(path: String): String = refusals { host.read(path) }

        override fun list(path: String): List<Triple<String, Boolean, Long?>> = refusals { host.list(path) }

        override fun write(path: String, text: String): String = refusals { host.write(path, text) }
    }

    private inline fun <T> refusals(block: () -> T): T = try {
        block()
    } catch (e: ScriptHostRefusal) {
        throw JsHostError(e.message ?: "Refused.")
    }

    fun ended(outcome: JsOutcome): ScriptEnded = when (outcome) {
        is JsOutcome.Finished -> ScriptEnded.Finished(outcome.resultJson, outcome.resultTruncated, outcome.log, outcome.logTruncated)
        is JsOutcome.ScriptError -> ScriptEnded.Failed(outcome.message, outcome.line, outcome.log, outcome.logTruncated)
        is JsOutcome.Stopped -> ScriptEnded.Stopped(outcome.reason, outcome.log, outcome.logTruncated)
    }
}
