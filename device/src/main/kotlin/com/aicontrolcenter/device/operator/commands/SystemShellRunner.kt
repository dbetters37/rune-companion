package com.aicontrolcenter.device.operator.commands

import android.content.Context
import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.ShellRunner
import com.aicontrolcenter.core.operator.WorkspaceFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Android's own shell: `/system/bin/sh -c <command>`, run as this app's user (so with exactly
 * this app's file access, no more), in a workspace folder.
 *
 *  - The command is one argument to `sh -c`, never split or re-quoted here.
 *  - The environment is replaced, not inherited: only [ENV_PATH], `HOME` and `TMPDIR`
 *    ([tempDir]) are set, so nothing of this app's environment leaks. `HOME` is [homeDir], a
 *    folder of the app outside the workspace that is made empty before every command, and `ENV`
 *    is not set: a shell the command starts (`sh -l`, `sh -i`) finds no start-up file there that
 *    an agent could have written.
 *  - Standard input is closed at once; nothing can be typed into the command.
 *  - stdout and stderr are each kept up to [CommandLimits.maxOutputChars] (decoded as UTF-8);
 *    the rest is read and dropped so the command never blocks on a full pipe.
 *  - Everything the command starts is stopped when it ends, at [CommandLimits.timeoutMs] and when
 *    the caller is cancelled. First its process group is killed: the shell is started through
 *    toybox `setsid`, and a two-line wrapper reports the group's id (its own pid, on the first
 *    line of stderr, which is not passed on) before it `exec`s the real `sh -c <command>` in the
 *    same process. Then [sweeper] kills whatever left that group (a new session, a double fork,
 *    job control): every process of this app's user id that is not the app.
 *    [CommandOutcome.Finished.stoppedLeftovers] says when there was something to stop. Without
 *    `setsid`, or where the sweep is not possible, nothing runs.
 *  - One command runs at a time; the sweep would stop another one's processes.
 *  - At [CommandLimits.timeoutMs], [CommandOutcome.TimedOut] carries what it printed.
 *
 * Wall-clock time: the limit runs on [dispatcher], never on a caller's virtual clock.
 */
internal class SystemShellRunner(
    private val tempDir: () -> File,
    private val homeDir: () -> File,
    /** No default: the real one exists only in an Android app process ([AndroidLeftovers]); tests pass one over a table of their own. */
    private val sweeper: LeftoverSweeper,
    private val shell: String = SHELL,
    private val setsid: String = SETSID,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ShellRunner {
    private val oneAtATime = Mutex()

    /**
     * Stops what an earlier command left running: for the app's start, after it died under a
     * command. Returns how many processes were stopped. Waits for a command that is running.
     */
    suspend fun stopLeftovers(): Int = withContext(dispatcher) {
        oneAtATime.withLock { (sweeper.sweep() as? Sweep.Done)?.stopped ?: 0 }
    }

    override suspend fun run(command: String, workingDirectory: WorkspaceFile, limits: CommandLimits): CommandOutcome = withContext(dispatcher) {
        oneAtATime.withLock { runOne(command, workingDirectory, limits) }
    }

    private suspend fun runOne(command: String, workingDirectory: WorkspaceFile, limits: CommandLimits): CommandOutcome {
        if (command.isBlank()) return CommandOutcome.Failed("There is no command.")
        if ('\u0000' in command) return CommandOutcome.Failed("The command contains a NUL character, which a shell cannot take.")
        if (command.length > MAX_COMMAND_CHARS) {
            return CommandOutcome.Failed("The command is longer than $MAX_COMMAND_CHARS characters; put longer scripts in a workspace file and run that.")
        }
        // Before anything starts: the sweep must be possible here, and nothing of an earlier command may still run.
        when (val before = sweeper.sweep()) {
            is Sweep.NotPossible -> return CommandOutcome.Unavailable("${before.reason} Shell commands are not run here.")
            is Sweep.Done -> if (before.left > 0) return CommandOutcome.Failed(STILL_RUNNING)
        }
        val directory = workingDirectory.file
        if (!directory.isDirectory) return CommandOutcome.Failed("\"${workingDirectory.path.value}\" is not a folder in the workspace.")
        if (!File(shell).canExecute()) return CommandOutcome.Failed("The shell could not be started ($shell is missing).")
        if (!File(setsid).canExecute()) {
            return CommandOutcome.Unavailable(
                "This phone has no $setsid, so what a command starts could not be stopped when it ends; shell commands are not run here.",
            )
        }
        val home = try {
            emptyFolder(homeDir())
        } catch (e: IOException) {
            return CommandOutcome.Failed("The shell's home folder could not be made empty (${e.javaClass.simpleName}), so the command was not run.")
        }
        // setsid sh -c WRAPPER sh COMMAND: the wrapper prints its pid (the new group's id), then becomes `sh -c COMMAND`.
        val builder = ProcessBuilder(setsid, shell, "-c", WRAPPER, shell, command).directory(directory)
        builder.environment().apply {
            clear()
            putAll(environmentFor(home, tempDir()))
        }
        val started = System.nanoTime()
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return CommandOutcome.Failed("The shell could not be started (${e.javaClass.simpleName}).")
        }
        val stdout = OutputCollector(process.inputStream, limits.maxOutputChars, "stdout", header = false)
        val stderr = OutputCollector(process.errorStream, limits.maxOutputChars, "stderr", header = true)
        var stoppedAll = false
        try {
            try {
                process.outputStream.close()
            } catch (_: IOException) {
                // The shell already exited; there was nothing to close.
            }
            val exitCode = withTimeoutOrNull(limits.timeoutMs) { runInterruptible { process.waitFor() } }
            // Whatever it left running goes now, before its output is collected: its group first, then what left the group.
            val inGroup = killGroup(stderr.group())
            if (exitCode == null) {
                process.destroyForcibly()
                process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)
            }
            val swept = sweeper.sweep()
            stoppedAll = true
            val leftRunning = swept !is Sweep.Done || swept.left > 0
            stdout.await()
            stderr.await()
            if (exitCode == null) return CommandOutcome.TimedOut(stdout.text(), stderr.text(), elapsedMs(started), leftRunning = leftRunning)
            return CommandOutcome.Finished(
                exitCode, stdout.text(), stderr.text(), stdout.truncated || stderr.truncated, elapsedMs(started),
                stoppedLeftovers = inGroup || (swept is Sweep.Done && swept.stopped > 0),
                leftRunning = leftRunning,
            )
        } finally {
            // Cancellation (the owner's Stop, a time limit of the caller) or a failure here: neither the shell nor anything it started outlives the call.
            if (!stoppedAll) {
                killGroup(stderr.group())
                if (process.isAlive) process.destroyForcibly()
                sweeper.sweep()
            }
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
        /**
         * The runner of the app: `TMPDIR` and `HOME` are folders in the app's cache, outside the
         * workspace, and leftovers are swept with [AndroidLeftovers], which does nothing (and so
         * lets nothing run) anywhere but in an Android app process.
         */
        fun forApp(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.IO): SystemShellRunner = SystemShellRunner(
            tempDir = { File(context.cacheDir, "shell") },
            homeDir = { File(context.cacheDir, "shell-home") },
            sweeper = AndroidLeftovers.create(),
            dispatcher = dispatcher,
        )

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

        internal const val STILL_RUNNING: String =
            "Processes an earlier command started are still running and could not be stopped, so no new command is run. Closing the app stops them."

        /** The whole environment a command sees. */
        internal fun environmentFor(home: File, tempDir: File): Map<String, String> {
            tempDir.mkdirs()
            return mapOf("PATH" to ENV_PATH, "HOME" to home.path, "TMPDIR" to tempDir.path)
        }

        /**
         * Makes [folder] an existing, empty folder and returns it. An earlier command may have
         * put anything there, links included: nothing is followed. A link is removed as a link,
         * also when [folder] itself was replaced by one, so emptying it never reaches the
         * workspace or the app's data.
         *
         * @throws IOException when something in it cannot be removed, or it cannot be created
         */
        internal fun emptyFolder(folder: File): File {
            val path = folder.toPath().toAbsolutePath().normalize()
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                // walkFileTree does not follow links unless asked to: a link is visited as a file.
                Files.walkFileTree(
                    path,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            // A real folder (links are visited as files): a command may have taken its own rights away to keep it from being emptied.
                            dir.toFile().apply {
                                setReadable(true, true)
                                setWritable(true, true)
                                setExecutable(true, true)
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            Files.delete(file)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                            Files.delete(file)
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                            if (exc != null) throw exc
                            Files.delete(dir)
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            }
            Files.createDirectories(path.parent)
            // Fails if something appeared in its place meanwhile, rather than using that.
            Files.createDirectory(path)
            return path.toFile()
        }
    }
}
