@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicontrolcenter.app.scripts

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.aicontrolcenter.framework.operator.JsEngine
import com.aicontrolcenter.framework.operator.JsHost
import com.aicontrolcenter.framework.operator.JsLimits
import com.aicontrolcenter.framework.operator.JsOutcome
import com.aicontrolcenter.framework.operator.JsSandbox
import com.aicontrolcenter.framework.operator.withNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs each script in an isolated process of its own (Phase 6, D1-D3, rules 11 and 31), so that a script that does not
 * stop can never hold the app again: the app lets go of the process, and Android ends it.
 *
 * - **Two slots** of one process each on Android 10 and later (`js0`, `js1`); a third run waits for a free slot. On
 *   Android 9 both slots share the service's one process, and a run ended because the other one did not stop is told
 *   so ([OTHER_RUN_ENDED]).
 * - **Bounds kept here, in the app** (the script process is untrusted): starting the process ([JsLimits.bindTimeoutMs]),
 *   getting ready ([JsLimits.engineStartMs]) and the script's own time from its code (D2), each plus [PAST_BOUND_MS];
 *   past a bound, the app lets go of the process. Workspace calls go through a fresh [RunHost] per run, which keeps
 *   every workspace limit; the answer is read with caps from [JsLimits] and a time limit of its own ([ScriptFrames]).
 * - **Death** (spike Q6, F5): when the process dies, or Android drops the binding, or a run's thread is left running
 *   there, or a caller is cancelled, the app unbinds at once and binds afresh for the next run, so Android's restart
 *   waits never apply. A process that died while idle is replaced and the run goes ahead; nothing of it had run.
 * - **Fallback**: when Android refuses to start the process at all, the run goes to the Rhino sandbox in the app's
 *   own process ([fallback], with its rule on given-up threads), and the answer says so ([FALLBACK_NOTE]).
 */
class IsolatedJsEngine internal constructor(
    private val limits: JsLimits,
    private val launcher: ScriptProcessLauncher,
    private val pipes: ScriptPipes,
    private val fallback: JsEngine,
    /** Android 9: one process for both slots (no instance names before Android 10). */
    sharedProcess: Boolean,
    private val io: ExecutorService = newIo(),
    /** How long the answer frame may take to arrive once the script process has said it is done. */
    private val answerReadMs: Long = ANSWER_READ_MS,
) : JsEngine {
    /** The engine of the app: [limits] are the script tool's. */
    constructor(context: Context, limits: JsLimits, sdkInt: Int = Build.VERSION.SDK_INT) : this(
        limits = limits,
        launcher = AndroidScriptLauncher(context, sdkInt),
        pipes = ScriptPipes.Android,
        fallback = JsSandbox(limits),
        sharedProcess = sdkInt < Build.VERSION_CODES.Q,
    )

    private val slots = Semaphore(SLOTS)
    private val slotTaken = BooleanArray(SLOTS)
    private val processes: List<Process> = List(if (sharedProcess) 1 else SLOTS) { Process("js$it") }

    override suspend fun run(code: String, inputJson: String?, host: JsHost?, timeoutMs: Long): JsOutcome {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        // The same refusals, in the same words, as the sandbox's own.
        if (code.length > limits.maxCodeChars) return JsOutcome.Stopped("The script is longer than ${limits.maxCodeChars} characters.", emptyList(), false)
        if (inputJson != null && inputJson.length > limits.maxInputChars) {
            return JsOutcome.Stopped("The input is longer than ${limits.maxInputChars} characters.", emptyList(), false)
        }
        return slots.withPermit {
            val slot = takeSlot()
            try {
                runIn(processes[if (processes.size == 1) 0 else slot], code, inputJson, host, timeoutMs, retry = true)
            } finally {
                releaseSlot(slot)
            }
        }
    }

    /** The script processes' ids, one per process that is up now (tests: the app's own id is never among them). */
    internal fun processIds(): List<Int> = processes.mapNotNull { process ->
        process.binding.get()?.takeIf { it.runner.isCompleted && !it.ended.isCompleted }?.let { runCatching { it.runner.getCompleted().pid() }.getOrNull() }
    }

    private fun takeSlot(): Int = synchronized(slotTaken) {
        val free = slotTaken.indexOfFirst { !it }
        check(free >= 0) { "a permit without a free slot" }
        slotTaken[free] = true
        free
    }

    private fun releaseSlot(slot: Int) = synchronized(slotTaken) { slotTaken[slot] = false }

    private suspend fun runIn(process: Process, code: String, inputJson: String?, host: JsHost?, timeoutMs: Long, retry: Boolean): JsOutcome {
        val binding = when (val bound = bind(process)) {
            Bound.Refused -> return fallback.run(code, inputJson, host, timeoutMs).withNote(FALLBACK_NOTE)
            Bound.Slow -> return JsOutcome.Stopped(BIND_SLOW, emptyList(), false)
            is Bound.Up -> bound.binding
        }
        val runner = binding.runner.getCompleted()
        val current = ScriptRun(timeoutMs)
        val runHost = host?.let { RunHost(it, limits, pipes, io, current::hostDeadlineMs, ::nowMs) }
        current.runHost = runHost
        binding.runs += current
        try {
            val pipe = try {
                pipes.open()
            } catch (e: IOException) {
                return JsOutcome.Stopped(BIND_SLOW, emptyList(), false)
            }
            current.handedAtMs = nowMs()
            try {
                runner.run(pipe.read, runHost, current.receiver)
            } catch (e: RemoteException) {
                // The process died while idle (Android needed the memory): nothing of the script ran. A fresh one, once.
                pipes.close(pipe.read)
                pipes.close(pipe.write)
                letGo(process, binding, culprit = null)
                return if (retry) runIn(process, code, inputJson, host, timeoutMs, retry = false) else JsOutcome.Stopped(PROCESS_ENDED, emptyList(), false)
            }
            pipes.handedOver(pipe.read)
            current.requestEnd = pipe.write
            val request = ScriptFrames.Request(limits.copy(timeoutMs = timeoutMs), code, inputJson, hasHost = host != null, canWrite = host?.canWrite == true)
            io.execute {
                try {
                    pipes.writer(pipe.write).use { ScriptFrames.writeRequest(it, request) }
                } catch (e: IOException) {
                    // The process stopped reading (it died, or the run ended): its answer, or its death, says what happened.
                    pipes.close(pipe.write)
                }
            }
            return follow(process, binding, current, runHost)
        } catch (e: CancellationException) {
            // The caller stopped waiting: a script in another process can only be stopped by letting its process go.
            letGo(process, binding, culprit = current)
            throw e
        } finally {
            runHost?.end()
            current.requestEnd?.let(pipes::close)
            binding.runs -= current
        }
    }

    /** Waits for the run's answer within its bounds; past one, lets the process go. */
    private suspend fun follow(process: Process, binding: Binding, current: ScriptRun, runHost: RunHost?): JsOutcome {
        // Getting ready: the script process's own bound, then [PAST_BOUND_MS] for its answer to arrive.
        when (val first = withTimeoutOrNull(limits.engineStartMs + PAST_BOUND_MS) { next(binding, current, started = true) }) {
            null -> {
                current.overdue = true
                letGo(process, binding, culprit = current)
                return JsOutcome.Stopped(JsSandbox.engineSlow(limits.engineStartMs), emptyList(), false)
            }
            is Event.Done -> return finish(process, binding, current, runHost, first.reply)
            is Event.Ended -> return endedAnswer(process, binding, current, runHost, first.culprit)
            Event.Started -> Unit
        }
        // The script's own time, from its code (D2).
        return when (val second = withTimeoutOrNull(current.timeoutMs + PAST_BOUND_MS) { next(binding, current, started = false) }) {
            null -> {
                current.overdue = true
                letGo(process, binding, culprit = current)
                JsOutcome.Stopped(JsSandbox.ABANDONED_TEXT, emptyList(), false)
            }
            is Event.Done -> finish(process, binding, current, runHost, second.reply)
            is Event.Ended -> endedAnswer(process, binding, current, runHost, second.culprit)
            Event.Started -> error("started twice")
        }
    }

    private suspend fun next(binding: Binding, current: ScriptRun, started: Boolean): Event = select {
        // An answer first, when both are there: the quiet end of the process tells the run before the process dies.
        current.done.onAwait { Event.Done(it) }
        if (started) current.started.onAwait { Event.Started }
        binding.ended.onAwait { Event.Ended(it) }
    }

    /** The script process said the run is done: no workspace call is served from now on; then its answer is read. */
    private suspend fun finish(process: Process, binding: Binding, current: ScriptRun, runHost: RunHost?, done: Reply): JsOutcome {
        runHost?.end()
        val stopped = { reason: String -> JsOutcome.Stopped(reason, emptyList(), false) }
        if (done.status != ScriptStatus.OUTCOME) {
            done.outcome?.let(pipes::close)
            if (done.status == ScriptStatus.BAD_REQUEST) return stopped(BAD_REQUEST)
            // The script process ends itself after these; it is let go of now.
            letGo(process, binding, culprit = current)
            return stopped(
                when (done.status) {
                    ScriptStatus.TIMED_OUT -> JsSandbox.ABANDONED_TEXT
                    ScriptStatus.OUT_OF_MEMORY -> OUT_OF_MEMORY
                    ScriptStatus.FAILED -> FAILED
                    else -> UNREADABLE
                },
            )
        }
        val answerEnd = done.outcome ?: kotlin.run {
            letGo(process, binding, culprit = current)
            return stopped(UNREADABLE)
        }
        val read = readAnswer(answerEnd)
        if (read == null) {
            letGo(process, binding, culprit = current)
            return stopped(NO_ANSWER)
        }
        val answer = read.getOrElse {
            letGo(process, binding, culprit = current)
            return stopped(UNREADABLE)
        }
        // Its thread still runs there (given up at its time limit): that process is let go of at once.
        if (!answer.threadEnded) letGo(process, binding, culprit = current)
        return answer.outcome
    }

    /** The answer frame, read on [io] within [answerReadMs]; null when it did not come in time (its pipe is closed then). */
    private suspend fun readAnswer(end: ParcelFileDescriptor): Result<ScriptFrames.Outcome>? {
        val read = CompletableDeferred<Result<ScriptFrames.Outcome>>()
        io.execute {
            read.complete(
                try {
                    Result.success(pipes.reader(end).use { ScriptFrames.readOutcome(it, limits) })
                } catch (e: IOException) {
                    Result.failure(e)
                } catch (e: RuntimeException) {
                    Result.failure(e)
                },
            )
        }
        return try {
            withTimeoutOrNull(answerReadMs) { read.await() } ?: kotlin.run {
                pipes.close(end)
                null
            }
        } catch (e: CancellationException) {
            pipes.close(end)
            throw e
        }
    }

    /** The process ended under a run that had no answer yet; an answer that comes with its end still counts. */
    private suspend fun endedAnswer(process: Process, binding: Binding, current: ScriptRun, runHost: RunHost?, culprit: ScriptRun?): JsOutcome {
        withTimeoutOrNull(LATE_ANSWER_MS) { current.done.await() }?.let { return finish(process, binding, current, runHost, it) }
        val other = culprit != null && culprit !== current || binding.runs.any { it !== current && it.overdue }
        return JsOutcome.Stopped(if (other) OTHER_RUN_ENDED else PROCESS_ENDED, emptyList(), false)
    }

    /** A running process for [process], binding one when there is none; [Bound.Refused] when Android will not. */
    private suspend fun bind(process: Process): Bound {
        val binding = process.lock.withLock {
            process.binding.get()?.takeIf { !it.ended.isCompleted } ?: kotlin.run {
                val fresh = Binding()
                val handle = launcher.bind(
                    process.instance,
                    object : ScriptProcessLauncher.Events {
                        override fun connected(binder: android.os.IBinder) {
                            fresh.runner.complete(IScriptRunner.Stub.asInterface(binder))
                        }

                        override fun ended() = letGo(process, fresh, culprit = null)
                    },
                ) ?: return Bound.Refused
                fresh.handle = handle
                process.binding.set(fresh)
                // An end that came before the handle was kept still lets the process go.
                if (fresh.ended.isCompleted) launcher.unbind(handle)
                fresh
            }
        }
        val up = withTimeoutOrNull(limits.bindTimeoutMs) {
            select<Boolean> {
                binding.runner.onAwait { true }
                binding.ended.onAwait { false }
            }
        }
        return when (up) {
            true -> Bound.Up(binding)
            false -> Bound.Slow
            null -> {
                letGo(process, binding, culprit = null)
                Bound.Slow
            }
        }
    }

    /** Lets go of [binding]'s process, once (F5: unbind at once; the next run binds afresh). */
    private fun letGo(process: Process, binding: Binding, culprit: ScriptRun?) {
        if (!binding.ended.complete(culprit)) return
        process.binding.compareAndSet(binding, null)
        binding.handle?.let { runCatching { launcher.unbind(it) } }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    private class Process(val instance: String) {
        val lock = Mutex()
        val binding = AtomicReference<Binding?>(null)
    }

    private class Binding {
        @Volatile
        var handle: ScriptProcessLauncher.Binding? = null
        val runner = CompletableDeferred<IScriptRunner>()

        /** Completed when the process is let go of or ends: with the run it was let go of for, or null. */
        val ended = CompletableDeferred<ScriptRun?>()
        val runs: MutableSet<ScriptRun> = ConcurrentHashMap.newKeySet()
    }

    private sealed interface Bound {
        data object Refused : Bound

        data object Slow : Bound

        class Up(val binding: Binding) : Bound
    }

    private sealed interface Event {
        data object Started : Event

        class Done(val reply: Reply) : Event

        class Ended(val culprit: ScriptRun?) : Event
    }

    private class Reply(val status: Int, val outcome: ParcelFileDescriptor?)

    /** One run: what the script process tells of it, through a binder made for it alone. */
    private inner class ScriptRun(val timeoutMs: Long) {
        val started = CompletableDeferred<Long>()
        val done = CompletableDeferred<Reply>()

        @Volatile
        var handedAtMs = 0L

        @Volatile
        var overdue = false

        @Volatile
        var requestEnd: ParcelFileDescriptor? = null

        /** The run's workspace; ended the moment the script process says the run is done, before any later call of its. */
        @Volatile
        var runHost: RunHost? = null

        val receiver = object : IScriptResult.Stub() {
            override fun started() {
                started.complete(nowMs())
            }

            override fun done(status: Int, outcome: ParcelFileDescriptor?) {
                runHost?.end()
                if (!done.complete(Reply(status, outcome))) outcome?.let(pipes::close)
            }
        }

        /** Until the script's code begins, getting ready; then its time limit and the grace from there. */
        fun hostDeadlineMs(): Long =
            if (started.isCompleted) started.getCompleted() + timeoutMs + JsSandbox.GRACE_MS else handedAtMs + limits.engineStartMs + PAST_BOUND_MS
    }

    companion object {
        /** Runs at once; a third waits for one of them to end. */
        const val SLOTS = 2

        /**
         * How long past a bound (starting, getting ready, the script's time) the app waits before it lets the process go:
         * the sandbox's grace and the watchdog's own margin (each process ends itself at the bound + 5 s), and one second.
         */
        const val PAST_BOUND_MS = JsSandbox.GRACE_MS + ScriptWatchdog.EXTRA_MS + 1_000L

        internal const val ANSWER_READ_MS = 10_000L

        /** How long an answer may follow the death of its process (the quiet end tells the run, then ends). */
        private const val LATE_ANSWER_MS = 300L

        const val BIND_SLOW = "The script engine did not start; the phone is busy. Nothing of the script ran."
        const val FALLBACK_NOTE = "This script ran inside the app itself, because its own sealed process could not be started on this phone. " +
            "It still had no access to the phone, the network or Java."
        const val PROCESS_ENDED = "The script's process ended before the script finished (it may have used too much memory). Nothing more of it ran."
        const val OTHER_RUN_ENDED = "The script's process was ended because another script running in it at the same time did not stop. " +
            "Nothing more of this script ran; it can be run again."
        const val OUT_OF_MEMORY = "The script used too much memory."
        const val FAILED = "The script's process failed. Nothing more of the script ran."
        const val BAD_REQUEST = "The script's process could not read the script. Nothing of it ran."
        const val UNREADABLE = "The script's process sent an answer the app could not read, so it was not used."
        const val NO_ANSWER = "The script's process did not hand its answer over in time."

        private fun newIo(): ExecutorService = Executors.newCachedThreadPool { Thread(it, "aicc-script-io").apply { isDaemon = true } }
    }
}
