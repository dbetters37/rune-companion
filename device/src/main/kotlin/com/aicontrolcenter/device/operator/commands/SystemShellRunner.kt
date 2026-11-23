package com.aicontrolcenter.device.operator.commands

import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.ShellRunner
import com.aicontrolcenter.core.operator.WorkspaceFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Android's own shell: `/system/bin/sh -c <command>`, run as this app's user (so with exactly
 * this app's file access, no more), in a workspace folder.
 *
 *  - The command is one argument to `sh -c`, never split or re-quoted here.
 *  - The environment is replaced, not inherited: only [ENV_PATH], `HOME` (the working
 *    directory) and `TMPDIR` ([tempDir]) are set, so nothing of this app's environment leaks.
 *  - Standard input is closed at once; nothing can be typed into the command.
 *  - stdout and stderr are each kept up to [CommandLimits.maxOutputChars] (decoded as UTF-8);
 *    the rest is read and dropped so the command never blocks on a full pipe.
 *  - The command runs in a session and process group of its own: the shell is started through
 *    toybox `setsid`, and a two-line wrapper reports the group's id (its own pid, on the first
 *    line of stderr, which is not passed on) before it `exec`s the real `sh -c <command>` in the
 *    same process. When the shell ends, at [CommandLimits.timeoutMs], and when the caller is
 *    cancelled, the whole group is killed (`kill -9 -<group>`), so work the command left running
 *    in the background ends with the call ([CommandOutcome.Finished.stoppedLeftovers] says when
 *    there was some). Only a process that leaves the group itself (`setsid`, job control) could
 *    stay; the shell tool refuses commands that ask for that. Without `setsid` nothing runs.
 *  - At [CommandLimits.timeoutMs], [CommandOutcome.TimedOut] carries what it printed.
 *
 * Wall-clock time: the limit runs on [dispatcher], never on a caller's virtual clock.
 */
internal class SystemShellRunner(
    private val tempDir: () -> File,
    private val shell: String = SHELL,
    private val setsid: String = SETSID,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ShellRunner {

    override suspend fun run(command: String, workingDirectory: WorkspaceFile, limits: CommandLimits): CommandOutcome = withContext(dispatcher) {
        if (command.isBlank()) return@withContext CommandOutcome.Failed("There is no command.")
        if ('\u0000' in command) return@withContext CommandOutcome.Failed("The command contains a NUL character, which a shell cannot take.")
        if (command.length > MAX_COMMAND_CHARS) {
            return@withContext CommandOutcome.Failed("The command is longer than $MAX_COMMAND_CHARS characters; put longer scripts in a workspace file and run that.")
        }
        val directory = workingDirectory.file
        if (!directory.isDirectory) return@withContext CommandOutcome.Failed("\"${workingDirectory.path.value}\" is not a folder in the workspace.")
        if (!File(shell).canExecute()) return@withContext CommandOutcome.Failed("The shell could not be started ($shell is missing).")
        if (!File(setsid).canExecute()) {
            return@withContext CommandOutcome.Unavailable(
                "This phone has no $setsid, so what a command starts could not be stopped when it ends; shell commands are not run here.",
            )
        }
        // setsid sh -c WRAPPER sh COMMAND: the wrapper prints its pid (the new group's id), then becomes `sh -c COMMAND`.
        val builder = ProcessBuilder(setsid, shell, "-c", WRAPPER, shell, command).directory(directory)
        builder.environment().apply {
            clear()
            putAll(environmentFor(directory, tempDir()))
        }
        val started = System.nanoTime()
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return@withContext CommandOutcome.Failed("The shell could not be started (${e.javaClass.simpleName}).")
        }
        val stdout = OutputCollector(process.inputStream, limits.maxOutputChars, "stdout", header = false)
        val stderr = OutputCollector(process.errorStream, limits.maxOutputChars, "stderr", header = true)
        var groupKilled = false
        try {
            try {
                process.outputStream.close()
            } catch (_: IOException) {
                // The shell already exited; there was nothing to close.
            }
            val exitCode = withTimeoutOrNull(limits.timeoutMs) { runInterruptible { process.waitFor() } }
            // Whatever it left running in its group goes now, before its output is collected.
            val leftovers = killGroup(stderr.group())
            groupKilled = true
            if (exitCode == null) {
                process.destroyForcibly()
                process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)
                stdout.await()
                stderr.await()
                return@withContext CommandOutcome.TimedOut(stdout.text(), stderr.text(), elapsedMs(started))
            }
            stdout.await()
            stderr.await()
            CommandOutcome.Finished(exitCode, stdout.text(), stderr.text(), stdout.truncated || stderr.truncated, elapsedMs(started), stoppedLeftovers = leftovers)
        } finally {
            // Cancellation or a failure here: neither the shell nor anything it started outlives the call.
            if (!groupKilled) killGroup(stderr.group())
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Sends SIGKILL to every process in [group]; true when one or more were still there. A null group (never reported) is skipped. */
    private fun killGroup(group: Long?): Boolean {
        if (group == null || group <= 1) return false
        return try {
            val killer = ProcessBuilder(shell, "-c", "kill -9 -$group").redirectErrorStream(true)
            killer.environment().clear()
            val p = killer.start()
            p.outputStream.close()
            p.inputStream.use { it.readBytes() }
            p.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0
        } catch (_: IOException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun elapsedMs(startedNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    /**
     * Reads one stream on its own thread, keeping the first [limit] characters. With [header],
     * the first line is the wrapper's report of the process group and is kept apart.
     */
    private class OutputCollector(private val input: InputStream, private val limit: Int, name: String, header: Boolean) {
        private val kept = StringBuilder()
        private val headerLine = StringBuilder()
        private val headerDone = java.util.concurrent.CountDownLatch(if (header) 1 else 0)
        @Volatile private var inHeader = header
        @Volatile var truncated: Boolean = false
            private set

        private val thread = Thread({ drain() }, "shell-$name").apply {
            isDaemon = true
            start()
        }

        private fun drain() {
            try {
                InputStreamReader(input, Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        var from = 0
                        if (inHeader) {
                            while (from < n && buffer[from] != '\n' && headerLine.length < MAX_HEADER) headerLine.append(buffer[from++])
                            if (from < n || headerLine.length >= MAX_HEADER) {
                                if (from < n && buffer[from] == '\n') from++
                                inHeader = false
                                headerDone.countDown()
                            }
                        }
                        keep(buffer, from, n - from)
                    }
                }
            } catch (_: IOException) {
                // The process was killed and its pipe closed: what was read is kept.
            } finally {
                inHeader = false
                headerDone.countDown()
            }
        }

        private fun keep(buffer: CharArray, from: Int, count: Int) {
            if (count <= 0) return
            synchronized(kept) {
                val room = limit - kept.length
                if (room >= count) {
                    kept.appendRange(buffer, from, from + count)
                } else {
                    if (room > 0) kept.appendRange(buffer, from, from + room)
                    truncated = true
                }
            }
        }

        /** The process group the wrapper reported, waiting briefly for it; null when none came. */
        fun group(): Long? {
            headerDone.await(GRACE_MS, TimeUnit.MILLISECONDS)
            return synchronized(kept) { headerLine.toString() }.trim().toLongOrNull()
        }

        /** Waits briefly for the stream to end. */
        fun await() = thread.join(GRACE_MS)

        fun text(): String = synchronized(kept) { kept.toString() }
    }

    /** The shell, the stripped environment and limits. */
    companion object {
        const val SHELL: String = "/system/bin/sh"
        const val SETSID: String = "/system/bin/setsid"
        const val ENV_PATH: String = "/system/bin"
        const val MAX_COMMAND_CHARS: Int = 64_000
        private const val GRACE_MS = 500L
        private const val KILL_WAIT_MS = 2_000L
        private const val MAX_HEADER = 32

        /**
         * Run as `sh -c WRAPPER <shell> <command>`: reports its pid, which `setsid` made the id of a
         * new session and process group, then replaces itself (same pid) with `<shell> -c <command>`.
         */
        internal const val WRAPPER: String = "echo \"\$\$\" >&2; exec \"\$0\" -c \"\$1\""

        /** The whole environment a command sees. */
        internal fun environmentFor(directory: File, tempDir: File): Map<String, String> {
            tempDir.mkdirs()
            return mapOf("PATH" to ENV_PATH, "HOME" to directory.path, "TMPDIR" to tempDir.path)
        }
    }
}
