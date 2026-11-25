package com.aicontrolcenter.app.scripts

import android.os.ParcelFileDescriptor
import com.aicontrolcenter.framework.operator.JsHost
import com.aicontrolcenter.framework.operator.JsHostError
import com.aicontrolcenter.framework.operator.JsLimits
import com.aicontrolcenter.framework.operator.JsSandbox
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The workspace of one script run, served in the app to the isolated script process (Phase 6, rule 11; F22: a fresh
 * binder for every run, so a script can never act as another run).
 *
 * The script process is untrusted: whatever its sandbox promises, every limit that protects the app is kept here, on
 * this binder. One call at a time; a write only with write access, at most [JsSandbox.MAX_FILES_WRITTEN] new files,
 * never replacing one ([host] creates only new files); texts within [JsLimits.maxStringChars] both ways and paths within
 * [ScriptFrames.MAX_PATH_CHARS]; and nothing once the run is over ([end]): no new call is served, a call still waiting
 * is given up, and its pipes are closed.
 *
 * The work runs on [io], never on the binder thread that brings the call: a read answers at once with a pipe that the
 * text is written into; a listing and a write wait for their work until the run's time is up ([deadlineMs]).
 */
internal class RunHost(
    private val host: JsHost,
    private val limits: JsLimits,
    private val pipes: ScriptPipes,
    private val io: ExecutorService,
    /** When the run's time is up for workspace work, in [nowMs]'s time. */
    private val deadlineMs: () -> Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : IScriptHost.Stub() {
    private val over = AtomicBoolean(false)

    /** One call at a time: held from a call's admission to the end of its work, its reply's transport included. */
    private val busy = Semaphore(1)

    /** Calls waiting for [busy]: at most one, so a script process cannot tie up the app's binder threads. */
    private val waiting = AtomicInteger(0)
    private val created = AtomicInteger(0)
    private val openEnds: MutableSet<ParcelFileDescriptor> = ConcurrentHashMap.newKeySet()
    private val work: MutableSet<Future<*>> = ConcurrentHashMap.newKeySet()

    /** Calls that reached [host], for the tests: "read a", "list ", "write a". */
    internal val served: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** Files this run created. */
    val filesCreated: Int get() = created.get()

    /**
     * The run is over: no further call is served, work still waiting is given up (its thread interrupted, as the
     * in-process sandbox interrupts its own), and every pipe still open is closed, which wakes a script process that
     * waits on one.
     */
    fun end() {
        if (!over.compareAndSet(false, true)) return
        work.forEach { it.cancel(true) }
        openEnds.forEach { pipes.close(it) }
        openEnds.clear()
    }

    val isOver: Boolean get() = over.get()

    override fun read(path: String?): ParcelFileDescriptor {
        val pipe = pipes.open()
        val refusal = admit(path)
        if (refusal != null) {
            // A refusal is a few bytes, well within a pipe's buffer: written here, at once.
            writeReply(pipe.write, false, refusal)
            return pipe.read
        }
        openEnds += pipe.write
        submit {
            try {
                val (ok, text) = answer {
                    val text = host.read(path!!).also { served += "read $path" }
                    if (text.length > limits.maxStringChars) false to "The file is longer than ${limits.maxStringChars} characters." else true to text
                }
                writeReply(pipe.write, ok, if (over.get()) OVER else text)
            } finally {
                openEnds -= pipe.write
                busy.release()
            }
        }
        return pipe.read
    }

    override fun list(path: String?): String {
        admit(path)?.let { return ScriptFrames.refused(it) }
        try {
            val (ok, reply) = waitFor { answer { true to ScriptFrames.listReply(host.list(path!!).also { served += "list $path" }) } }
            return when {
                over.get() -> ScriptFrames.refused(OVER)
                ok -> reply
                else -> ScriptFrames.refused(reply)
            }
        } finally {
            busy.release()
        }
    }

    override fun write(path: String?, content: ParcelFileDescriptor?): String {
        if (content == null) return ScriptFrames.refused(NO_TEXT)
        openEnds += content
        try {
            admit(path)?.let { return ScriptFrames.refused(it) }
            try {
                when {
                    !host.canWrite -> return ScriptFrames.refused(READ_ONLY)
                    created.get() >= JsSandbox.MAX_FILES_WRITTEN -> return ScriptFrames.refused(TOO_MANY_FILES)
                }
                val (ok, reply) = waitFor {
                    val text = try {
                        pipes.reader(content).use { ScriptFrames.readContent(it, limits.maxStringChars) }
                    } catch (e: FrameProblem) {
                        return@waitFor false to "\"text\" is longer than ${limits.maxStringChars} characters, or did not arrive whole."
                    } catch (e: IOException) {
                        return@waitFor false to "The text did not arrive whole."
                    }
                    // The run may have ended while the text came: then nothing is written.
                    if (over.get()) return@waitFor false to OVER
                    answer { true to host.write(path!!, text).also { served += "write $path" } }
                }
                if (!ok) return ScriptFrames.refused(reply)
                created.incrementAndGet()
                return ScriptFrames.ok(reply)
            } finally {
                busy.release()
            }
        } finally {
            openEnds -= content
            pipes.close(content)
        }
    }

    /**
     * Null when the call may go ahead (and this run is then busy with it), else why not. A call that comes while the
     * previous one finishes (a read's reply is still being closed when the script process has read all of it) waits
     * for it a little; a call that comes while another already waits is refused at once.
     */
    private fun admit(path: String?): String? {
        when {
            over.get() -> return OVER
            path == null -> return "A path is needed."
            path.length > ScriptFrames.MAX_PATH_CHARS -> return "The path is longer than ${ScriptFrames.MAX_PATH_CHARS} characters."
        }
        if (busy.tryAcquire()) return null
        if (waiting.incrementAndGet() > 1) {
            waiting.decrementAndGet()
            return BUSY
        }
        return try {
            when {
                !busy.tryAcquire(ADMIT_WAIT_MS, TimeUnit.MILLISECONDS) -> BUSY
                over.get() -> OVER.also { busy.release() }
                else -> null
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            BUSY
        } finally {
            waiting.decrementAndGet()
        }
    }

    /** A host function's answer: its value, or the refusal the script is to read (by type only for anything but a refusal). */
    private inline fun answer(block: () -> Pair<Boolean, String>): Pair<Boolean, String> = try {
        block()
    } catch (e: JsHostError) {
        false to (e.message ?: "Refused.")
    } catch (e: InterruptedException) {
        false to OVER
    } catch (e: Exception) {
        if (over.get()) false to OVER else false to "The workspace operation failed (${e::class.simpleName})."
    }

    /** [block] on [io], waited for until the run's time is up; given up (interrupted) then. */
    private fun waitFor(block: () -> Pair<Boolean, String>): Pair<Boolean, String> {
        val task = submit(Callable(block))
        val left = deadlineMs() - nowMs()
        return try {
            if (left <= 0) throw TimeoutException()
            task.get(left, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            false to TIME_UP
        } catch (e: ExecutionException) {
            false to "The workspace operation failed (${e.cause?.let { it::class.simpleName }})."
        } catch (e: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            false to OVER
        } catch (e: java.util.concurrent.CancellationException) {
            false to OVER
        }
    }

    /** [block] on [io], given up when the run ends; it leaves [work] when it is done. */
    private fun submit(block: () -> Unit): Future<*> = submit(Callable { block() })

    private fun <T> submit(callable: Callable<T>): Future<T> {
        val task = object : FutureTask<T>(callable) {
            override fun done() {
                work -= this
            }
        }
        work += task
        io.execute(task)
        if (over.get()) task.cancel(true)
        return task
    }

    private fun writeReply(end: ParcelFileDescriptor, ok: Boolean, text: String) {
        try {
            pipes.writer(end).use { ScriptFrames.writeReply(it, ok, text) }
        } catch (e: IOException) {
            // The script process stopped reading (it ended, or the run did): nothing to tell it.
            pipes.close(end)
        }
    }

    companion object {
        /** How long a call waits for the one before it to finish. */
        private const val ADMIT_WAIT_MS = 1_000L

        const val OVER = "The script's run is over; nothing more is done for it."
        const val BUSY = "Only one workspace call at a time."
        const val READ_ONLY = "This script may only read the workspace."
        const val TOO_MANY_FILES = "A script may create at most ${JsSandbox.MAX_FILES_WRITTEN} files."
        const val TIME_UP = "The script's time was up before the workspace answered; nothing more was done."
        const val NO_TEXT = "No text came with the file."
    }
}
