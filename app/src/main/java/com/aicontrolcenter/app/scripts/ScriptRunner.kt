package com.aicontrolcenter.app.scripts

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.aicontrolcenter.framework.operator.JsHost
import com.aicontrolcenter.framework.operator.JsHostError
import com.aicontrolcenter.framework.operator.JsLimits
import com.aicontrolcenter.framework.operator.JsOutcome
import com.aicontrolcenter.framework.operator.JsRunEvents
import com.aicontrolcenter.framework.operator.JsSandbox
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The script process's half of the engine (Phase 6, D1-D3, rules 11 and 31): it runs each script in the Rhino sandbox
 * ([JsSandbox]) of this process, on a thread of its own, with a watchdog of its own ([ScriptWatchdog]), and answers
 * through pipes of length-prefixed frames ([ScriptFrames]). It runs in `ScriptProcessService`'s isolated process,
 * which has no access to the app's files, database, keys or network; in the tests it runs in the test's process.
 *
 * Nothing here protects the app: the app checks whatever comes from here against its own limits. What this side adds is
 * that a script that does not stop ends with its process ([endProcess]), so it never keeps burning the processor and the
 * next run starts in a fresh process.
 *
 * @param endProcess ends this process (`Process.killProcess(myPid())` in the service); the tests stand in for it.
 * @param pid this process's id, for [IScriptRunner.pid].
 * @param sandboxes the sandbox for a run's limits; one is kept while the limits stay the same.
 */
internal class ScriptRunner(
    private val pipes: ScriptPipes,
    private val endProcess: () -> Unit,
    private val pid: () -> Int,
    private val sandboxes: (JsLimits) -> JsSandbox = { JsSandbox(it) },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : IScriptRunner.Stub() {
    private val active: MutableSet<Run> = ConcurrentHashMap.newKeySet()
    private val counter = AtomicLong()

    @Volatile
    private var sandbox: Pair<JsLimits, JsSandbox>? = null

    override fun pid(): Int = pid.invoke()

    override fun run(request: ParcelFileDescriptor?, host: IScriptHost?, result: IScriptResult?) {
        if (request == null || result == null) {
            request?.let(pipes::close)
            return
        }
        val n = counter.incrementAndGet()
        // Until the request is read, the default bound of getting ready; the request's own once it is.
        val run = Run(result, nowMs() + JsLimits().engineStartMs + ScriptWatchdog.PAST_BOUND_MS)
        active += run
        ScriptWatchdog(run, endProcess, nowMs).start("aicc-script-watchdog-$n")
        // Off the binder thread: a binder thread must not run a script, and has no class loader of the app's (spike F-B).
        Thread({ serve(run, request, host) }, "aicc-script-run-$n").apply { isDaemon = true }.start()
    }

    /**
     * The process is ending on an error nothing caught (an out-of-memory error outside a script's own thread, which
     * the sandbox catches itself): every run still going is told, by status alone, before the process ends quietly
     * (spike Q8: no crash record, no dialog, no 30-minute wait before Android starts the service again).
     */
    fun failAll(error: Throwable) {
        val status = if (error is OutOfMemoryError) ScriptStatus.OUT_OF_MEMORY else ScriptStatus.FAILED
        for (run in active) runCatching { run.result.done(status, null) }
    }

    private fun serve(run: Run, request: ParcelFileDescriptor, host: IScriptHost?) {
        try {
            val req = try {
                pipes.reader(request).use { ScriptFrames.readRequest(it) }
            } catch (e: IOException) {
                run.endedCleanly = true
                runCatching { run.result.done(ScriptStatus.BAD_REQUEST, null) }
                return
            }
            val limits = req.limits
            run.deadlineMs = nowMs() + limits.engineStartMs + ScriptWatchdog.PAST_BOUND_MS
            val workspace = if (req.hasHost && host != null) RemoteJsHost(host, req.canWrite, pipes, limits.maxStringChars) else null
            val events = object : JsRunEvents {
                override fun codeStarted() {
                    run.deadlineMs = nowMs() + limits.timeoutMs + ScriptWatchdog.PAST_BOUND_MS
                    runCatching { run.result.started() }
                }

                override fun abandoned() {
                    run.abandoned = true
                }
            }
            val outcome = runBlocking { sandboxFor(limits).run(req.code, req.input, workspace, limits.timeoutMs, events) }
            // Every outcome but a given-up run means the script's thread has ended (F-G).
            val threadEnded = !run.abandoned
            if (threadEnded) run.endedCleanly = true
            answer(run, outcome, threadEnded)
        } finally {
            if (run.endedCleanly) active -= run
        }
    }

    private fun sandboxFor(limits: JsLimits): JsSandbox {
        sandbox?.let { (kept, s) -> if (kept == limits) return s }
        return sandboxes(limits).also { sandbox = limits to it }
    }

    /** The outcome frame, written by a thread of its own into a pipe whose read end goes with `done`. */
    private fun answer(run: Run, outcome: JsOutcome, threadEnded: Boolean) {
        val pipe = try {
            pipes.open()
        } catch (e: IOException) {
            runCatching { run.result.done(ScriptStatus.FAILED, null) }
            return
        }
        Thread({
            try {
                pipes.writer(pipe.write).use { ScriptFrames.writeOutcome(it, outcome, threadEnded) }
            } catch (e: IOException) {
                pipes.close(pipe.write)
            }
        }, "aicc-script-answer").apply { isDaemon = true }.start()
        try {
            run.result.done(ScriptStatus.OUTCOME, pipe.read)
        } catch (e: RemoteException) {
            // The app is gone; nothing to tell.
        } finally {
            pipes.handedOver(pipe.read)
        }
    }

    private class Run(val result: IScriptResult, @Volatile override var deadlineMs: Long) : ScriptWatchdog.Watched {
        @Volatile
        override var endedCleanly = false

        @Volatile
        var abandoned = false

        override fun overdue() {
            result.done(ScriptStatus.TIMED_OUT, null)
        }
    }
}

/**
 * The workspace as a script in the script process sees it: every call goes to the app's [RunHost] for this run, which
 * decides and keeps every limit. Called on the script's thread, which blocks until the app answers; a call the app
 * cannot answer (its process went, or the run is over and the pipe closed) is a refusal the script reads.
 */
internal class RemoteJsHost(
    private val host: IScriptHost,
    override val canWrite: Boolean,
    private val pipes: ScriptPipes,
    private val maxChars: Int,
) : JsHost {
    override fun read(path: String): String {
        val end = call { host.read(path) } ?: throw JsHostError(NO_ANSWER)
        val (ok, text) = try {
            pipes.reader(end).use { ScriptFrames.readReply(it, maxChars) }
        } catch (e: IOException) {
            throw JsHostError(NO_ANSWER)
        }
        if (!ok) throw JsHostError(text)
        return text
    }

    override fun list(path: String): List<Triple<String, Boolean, Long?>> = ScriptFrames.listEntries(reply(call { host.list(path) }))

    override fun write(path: String, text: String): String {
        val pipe = try {
            pipes.open()
        } catch (e: IOException) {
            throw JsHostError(NO_ANSWER)
        }
        Thread({
            try {
                pipes.writer(pipe.write).use { ScriptFrames.writeContent(it, text) }
            } catch (e: IOException) {
                // The app stopped reading: it refused, or the run is over. Its reply says which.
                pipes.close(pipe.write)
            }
        }, "aicc-script-content").apply { isDaemon = true }.start()
        return try {
            reply(call { host.write(path, pipe.read) })
        } finally {
            pipes.handedOver(pipe.read)
        }
    }

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: RemoteException) {
        throw JsHostError(NO_ANSWER)
    }

    private fun reply(text: String?): String = when {
        text == null -> throw JsHostError(NO_ANSWER)
        text.startsWith("+") -> text.substring(1)
        text.startsWith("!") -> throw JsHostError(text.substring(1))
        else -> throw JsHostError(NO_ANSWER)
    }

    private companion object {
        const val NO_ANSWER = "The app did not answer the workspace call."
    }
}
