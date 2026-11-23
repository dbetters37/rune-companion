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
 *  - At [CommandLimits.timeoutMs] the shell is killed and [CommandOutcome.TimedOut] carries
 *    what it printed. Cancelling the caller kills it too. Background jobs the command itself
 *    starts with `&` are not in Android's reach to kill from an app; they end with their work
 *    and are not waited for.
 *
 * Wall-clock time: the limit runs on [dispatcher], never on a caller's virtual clock.
 */
internal class SystemShellRunner(
    private val tempDir: () -> File,
    private val shell: String = SHELL,
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
        val builder = ProcessBuilder(shell, "-c", command).directory(directory)
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
        try {
            try {
                process.outputStream.close()
            } catch (_: IOException) {
                // The shell already exited; there was nothing to close.
            }
            val stdout = OutputCollector(process.inputStream, limits.maxOutputChars, "stdout")
            val stderr = OutputCollector(process.errorStream, limits.maxOutputChars, "stderr")
            val exitCode = withTimeoutOrNull(limits.timeoutMs) { runInterruptible { process.waitFor() } }
            if (exitCode == null) {
                process.destroyForcibly()
                process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)
                stdout.await()
                stderr.await()
                return@withContext CommandOutcome.TimedOut(stdout.text(), stderr.text(), elapsedMs(started))
            }
            stdout.await()
            stderr.await()
            CommandOutcome.Finished(exitCode, stdout.text(), stderr.text(), stdout.truncated || stderr.truncated, elapsedMs(started))
        } finally {
            // Cancellation, a timeout or a failure here: the shell does not outlive the call.
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun elapsedMs(startedNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    /** Reads one stream on its own thread, keeping the first [limit] characters. */
    private class OutputCollector(private val input: InputStream, private val limit: Int, name: String) {
        private val kept = StringBuilder()
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
                        synchronized(kept) {
                            val room = limit - kept.length
                            if (room >= n) {
                                kept.appendRange(buffer, 0, n)
                            } else {
                                if (room > 0) kept.appendRange(buffer, 0, room)
                                truncated = true
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                // The process was killed and its pipe closed: what was read is kept.
            }
        }

        /** Waits briefly for the stream to end; a background job holding it open is not waited for. */
        fun await() = thread.join(GRACE_MS)

        fun text(): String = synchronized(kept) { kept.toString() }
    }

    /** The shell, the stripped environment and limits. */
    companion object {
        const val SHELL: String = "/system/bin/sh"
        const val ENV_PATH: String = "/system/bin"
        const val MAX_COMMAND_CHARS: Int = 64_000
        private const val GRACE_MS = 500L

        /** The whole environment a command sees. */
        internal fun environmentFor(directory: File, tempDir: File): Map<String, String> {
            tempDir.mkdirs()
            return mapOf("PATH" to ENV_PATH, "HOME" to directory.path, "TMPDIR" to tempDir.path)
        }
    }
}
