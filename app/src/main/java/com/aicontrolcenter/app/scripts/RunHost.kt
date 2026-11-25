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
import java.util.concurrent.Semaphore
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
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
 * [ScriptFrames.MAX_PATH_CHARS]; and nothing once the run is over ([end]) or its workspace time is up ([deadlineMs]):
 * no new call is served, a call still waiting is given up, and its pipes are closed.
 *
 * The work runs on [io], never on the binder thread that brings the call: a read answers at once with a pipe that the
 * text is written into; a listing and a write wait for their work until the run's time is up. A call's work holds the
 * run's one call until it has really ended, however the call was answered, and once it is in the workspace it is not
 * interrupted: a workspace call goes on to its end whatever its caller does (an interrupted `runBlocking` returns at
 * once while its write goes on), so a file is counted when it is made, and the cap is checked there (SEC-W2B-1). A call
 * given up because the time was up ends the run's workspace: nothing more is done for the run.
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

    /**
     * One call at a time: held from a call's admission to the end of its work (its reply's transport included), even
     * when the call was answered before its work ended.
     */
    private val busy = Semaphore(1)

    /** Calls waiting for [busy]: at most one, so a script process cannot tie up the app's binder threads. */
    private val waiting = AtomicInteger(0)
    private val created = AtomicInteger(0)
    private val openEnds: MutableSet<ParcelFileDescriptor> = ConcurrentHashMap.newKeySet()
    private val work: MutableSet<Work<*>> = ConcurrentHashMap.newKeySet()

    /** Calls that reached [host], for the tests: "read a", "list ", "write a". */
    internal val served: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** Files this run created (counted when [host] has made them). */
    val filesCreated: Int get() = created.get()

    /**
     * The run is over: no further call is served, work still waiting is given up (its thread interrupted, as the
     * in-process sandbox interrupts its own; never inside the workspace), and every pipe still open is closed, which
     * wakes a script process that waits on one.
     */
    fun end() {
        if (!over.compareAndSet(false, true)) return
        work.forEach { it.cancel(true) }
        openEnds.forEach { pipes.close(it) }
        openEnds.clear()
    }

    val isOver: Boolean get() = over.get()

    /** Why nothing more is done for this run, or null while it may go on: once its workspace time is up, it is [end]ed. */
    private fun stopped(): String? {
        if (over.get()) return OVER
        if (nowMs() >= deadlineMs()) {
            end()
            return OVER
        }
        return null
    }

    override fun read(path: String?): ParcelFileDescriptor {
        val pipe = pipes.open()
        val refusal = admit(path)
        if (refusal != null) {
            // A refusal is a few bytes, well within a pipe's buffer: written here, at once.
            writeReply(pipe.write, false, refusal)
            return pipe.read
        }
        // This call holds the run's one call now; its work lets go of it when it has ended.
        openEnds += pipe.write
        val started = start(Call()) { call ->
            try {
                val (ok, text) = call.inWorkspace {
                    val text = host.read(path!!).also { served += "read $path" }
                    if (text.length > limits.maxStringChars) false to "The file is longer than ${limits.maxStringChars} characters." else true to text
                }
                if (over.get()) writeReply(pipe.write, false, OVER) else writeReply(pipe.write, ok, text)
            } finally {
                openEnds -= pipe.write
            }
        }
        if (started == null) {
            openEnds -= pipe.write
            writeReply(pipe.write, false, OVER)
        }
        return pipe.read
    }

    override fun list(path: String?): String {
        admit(path)?.let { return ScriptFrames.refused(it) }
        val (ok, reply) = waitFor(changes = false) { call -> call.inWorkspace { true to ScriptFrames.listReply(host.list(path!!).also { served += "list $path" }) } }
        return when {
            !ok -> ScriptFrames.refused(reply)
            over.get() -> ScriptFrames.refused(OVER)
            else -> reply
        }
    }

    override fun write(path: String?, content: ParcelFileDescriptor?): String {
        if (content == null) return ScriptFrames.refused(NO_TEXT)
        openEnds += content
        try {
            admit(path)?.let { return ScriptFrames.refused(it) }
            // This call holds the run's one call now: let go of here when refused at once, else by its work.
            val refusal = when {
                !host.canWrite -> READ_ONLY
                created.get() >= JsSandbox.MAX_FILES_WRITTEN -> TOO_MANY_FILES
                else -> null
            }
            if (refusal != null) {
                busy.release()
                return ScriptFrames.refused(refusal)
            }
            val (ok, reply) = waitFor(changes = true) { call ->
                val text = try {
                    pipes.reader(content).use { ScriptFrames.readContent(it, limits.maxStringChars) }
                } catch (e: FrameProblem) {
                    return@waitFor false to "\"text\" is longer than ${limits.maxStringChars} characters, or did not arrive whole."
                } catch (e: IOException) {
                    return@waitFor false to "The text did not arrive whole."
                }
                // The run may have ended, or its time may be up, while the text came: then nothing is written. The cap
                // is checked again where the file is made, and the file counted there, under the run's one call.
                call.inWorkspace {
                    if (created.get() >= JsSandbox.MAX_FILES_WRITTEN) {
                        false to TOO_MANY_FILES
                    } else {
                        val made = host.write(path!!, text)
                        created.incrementAndGet()
                        served += "write $path"
                        true to made
                    }
                }
            }
            return if (ok) ScriptFrames.ok(reply) else ScriptFrames.refused(reply)
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
        stopped()?.let { return it }
        when {
            path == null -> return "A path is needed."
            path.length > ScriptFrames.MAX_PATH_CHARS -> return "The path is longer than ${ScriptFrames.MAX_PATH_CHARS} characters."
        }
        if (busy.tryAcquire()) return null
        if (waiting.incrementAndGet() > 1) {
            waiting.decrementAndGet()
            return BUSY
        }
        return try {
            if (!busy.tryAcquire(ADMIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                BUSY
            } else {
                stopped()?.also { busy.release() }
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

    /**
     * [block] on [io] for a call that holds the run's one call (handed to its work here), waited for until the run's
     * time is up. Then the call is given up and the run's workspace ends: nothing more is done for it. A call that
     * [changes] the workspace and was given up while in there is told that it may have been done.
     */
    private fun waitFor(changes: Boolean, block: (Call) -> Pair<Boolean, String>): Pair<Boolean, String> {
        stopped()?.let {
            busy.release()
            return false to it
        }
        val call = Call()
        val task = start(call, block) ?: return false to OVER
        val left = deadlineMs() - nowMs()
        return try {
            if (left <= 0) throw TimeoutException()
            task.get(left, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            end()
            false to if (changes && call.reachedWorkspace) IN_FLIGHT else TIME_UP
        } catch (e: ExecutionException) {
            false to "The workspace operation failed (${e.cause?.let { it::class.simpleName }})."
        } catch (e: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            false to if (changes && call.reachedWorkspace) IN_FLIGHT else OVER
        } catch (e: java.util.concurrent.CancellationException) {
            false to if (changes && call.reachedWorkspace) IN_FLIGHT else OVER
        }
    }

    /**
     * [block] on [io] as the work of a call that holds the run's one call: it lets go of it when it has ended, run or
     * given up before it began. Null when [io] takes no more work (the run's one call is let go of then).
     */
    private fun <T> start(call: Call, block: (Call) -> T): Work<T>? {
        val task = Work(call, Callable { block(call) })
        work += task
        try {
            io.execute(task)
        } catch (e: RejectedExecutionException) {
            work -= task
            busy.release()
            return null
        }
        if (over.get()) task.cancel(true)
        return task
    }

    /** One admitted call: whether it was given up, and whether its work is (or was) in the workspace. */
    private inner class Call {
        val lock = Any()

        /** Under [lock]. */
        var givenUp = false

        /** Under [lock]: its work is in [host] now, and is not to be interrupted. */
        var inside = false

        /** Its work went into [host]: what it did there may have been done whatever the call was answered. */
        @Volatile
        var reachedWorkspace = false

        /**
         * [block] in the workspace, unless the call was given up, the run is over or its time is up; not interrupted
         * while in there, so that what [host] does is known when it returns.
         */
        fun inWorkspace(block: () -> Pair<Boolean, String>): Pair<Boolean, String> {
            stopped()?.let { return false to it }
            synchronized(lock) {
                if (givenUp || over.get()) return false to OVER
                inside = true
                reachedWorkspace = true
            }
            try {
                return answer(block)
            } finally {
                synchronized(lock) { inside = false }
            }
        }
    }

    /** A call's work on [io]: lets go of the run's one call when it has really ended; never interrupted in the workspace. */
    private inner class Work<T>(private val call: Call, callable: Callable<T>) : FutureTask<T>(callable) {
        override fun run() {
            try {
                super.run()
            } finally {
                busy.release()
            }
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = synchronized(call.lock) {
            call.givenUp = true
            super.cancel(mayInterruptIfRunning && !call.inside)
        }

        override fun done() {
            work -= this
        }
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
        const val IN_FLIGHT = "The script's time was up while the workspace was still doing this; it may have been done. Nothing more is done for the script."
        const val NO_TEXT = "No text came with the file."
    }
}
