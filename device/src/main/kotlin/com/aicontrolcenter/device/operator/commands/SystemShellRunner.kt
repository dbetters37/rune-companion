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
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Android's own shell: `/system/bin/sh -c <command>`, run as this app's user (so with exactly
 * this app's file access, no more), in a workspace folder.
 *
 *  - The command is one argument to `sh -c`, never split or re-quoted here. A command that
 *    cannot reach the shell as the same text (a NUL, half of a surrogate pair) is not run.
 *  - The environment is replaced, not inherited: only [ENV_PATH], `HOME` and `TMPDIR`
 *    ([tempDir]) are set, so nothing of this app's environment leaks. `HOME` is [homeDir], a
 *    folder of the app outside the workspace that is made empty before every command, and `ENV`
 *    is not set: a shell the command starts (`sh -l`, `sh -i`) finds no start-up file there that
 *    an agent could have written. A command cannot keep that folder from being emptied by
 *    taking its own rights to it away; if it still cannot be emptied, it is put aside and the
 *    next command gets a new one ([emptyFolder]).
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
    /** How the home folder is emptied; tests make one of its steps fail. */
    private val folderOps: FolderOps = FolderOps(),
) : ShellRunner {
    private val oneAtATime = Mutex()

    /**
     * Stops what an earlier command left running: for the app's start, after it died under a
     * command. Returns how many processes were stopped. Waits for a command that is running.
     * Home folders that were put aside because they could not be emptied are deleted here too,
     * as far as that works now.
     */
    suspend fun stopLeftovers(): Int = withContext(dispatcher) {
        oneAtATime.withLock {
            val stopped = (sweeper.sweep() as? Sweep.Done)?.stopped ?: 0
            clearPutAside(homeDir(), folderOps)
            stopped
        }
    }

    override suspend fun run(command: String, workingDirectory: WorkspaceFile, limits: CommandLimits): CommandOutcome = withContext(dispatcher) {
        oneAtATime.withLock { runOne(command, workingDirectory, limits) }
    }

    private suspend fun runOne(command: String, workingDirectory: WorkspaceFile, limits: CommandLimits): CommandOutcome {
        if (command.isBlank()) return CommandOutcome.Failed("There is no command.")
        if ('\u0000' in command) return CommandOutcome.Failed("The command contains a NUL character, which a shell cannot take.")
        notExactProblem(command)?.let { return CommandOutcome.Failed(it) }
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
            emptyFolder(homeDir(), folderOps)
        } catch (e: IOException) {
            return CommandOutcome.Failed("No empty home folder could be made for the shell (${e.javaClass.simpleName}), so the command was not run.")
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
         * An existing, empty folder for `HOME`, and its path: [folder] itself, emptied, whenever
         * that is possible. An earlier command may have put anything there, links included:
         * nothing is followed. A link is removed as a link, also when [folder] itself was
         * replaced by one, so emptying it never reaches the workspace or the app's data.
         *
         * A command cannot switch the shell off for good by locking this folder (`chmod 000` on
         * it or on a folder in it, with a file inside):
         *  - Every real folder gets its owner's rights back BEFORE it is listed ([removeTree]).
         *    A file-tree walk that does so in its "before this folder" step is too late: it has
         *    opened the folder by then, or failed to.
         *  - If it still cannot be emptied (a path too long to name, a file system that refuses),
         *    [folder] is renamed aside as `<name>.stuck-<n>` in the same parent, which needs
         *    rights on the parent only, and a new empty [folder] is made.
         *  - If that fails too, a new folder `<name>.fresh-<random>` beside it is returned.
         * What was put aside or made that way is deleted, as far as possible, before the next
         * command and at the app's start ([clearPutAside]).
         *
         * @throws IOException only when no empty folder can be had at all
         */
        internal fun emptyFolder(folder: File, ops: FolderOps = FolderOps()): File {
            val path = folder.toPath().toAbsolutePath().normalize()
            val parent = path.parent ?: throw IOException("the home folder has no parent")
            val name = path.fileName.toString()
            clearPutAside(folder, ops)
            try {
                removeTree(path, ops)
                Files.createDirectories(parent)
                // Fails if something appeared in its place meanwhile, rather than using that.
                Files.createDirectory(path)
                return path.toFile()
            } catch (_: IOException) {
                // The safety net below.
            }
            // The parent is this app's own cache folder; a command may have taken the rights to that away as well.
            if (!Files.isReadable(parent) || !Files.isWritable(parent) || !Files.isExecutable(parent)) openForOwner(parent)
            try {
                putAside(path, parent, name, ops)
                Files.createDirectory(path)
                return path.toFile()
            } catch (_: IOException) {
                // The last way out below.
            }
            return Files.createTempDirectory(parent, "$name$FRESH").toFile()
        }

        /**
         * Deletes, as far as that works, the home folders [emptyFolder] put aside or made beside
         * [folder] for an earlier command. Nothing runs in them now: one command runs at a time.
         */
        internal fun clearPutAside(folder: File, ops: FolderOps = FolderOps()) {
            val path = folder.toPath().toAbsolutePath().normalize()
            val parent = path.parent ?: return
            val name = path.fileName.toString()
            val ours = DirectoryStream.Filter<Path> { it.fileName.toString().run { startsWith(name + STUCK) || startsWith(name + FRESH) } }
            val putAside = try {
                Files.newDirectoryStream(parent, ours).use { it.take(2 * MAX_STUCK) }
            } catch (_: IOException) {
                return
            } catch (_: DirectoryIteratorException) {
                return
            }
            for (other in putAside) {
                try {
                    removeTree(other, ops)
                } catch (_: IOException) {
                    // It stays for the next try; Android may also clear the cache folder by itself.
                }
            }
        }

        /** Renames [path] to the first free `<name>.stuck-<n>` in [parent]; nothing happens when [path] is not there. */
        private fun putAside(path: Path, parent: Path, name: String, ops: FolderOps) {
            for (n in 0 until MAX_STUCK) {
                try {
                    ops.rename(path, parent.resolve("$name$STUCK$n"))
                    return
                } catch (_: FileAlreadyExistsException) {
                    // That number is taken by one that could not be deleted yet.
                } catch (_: NoSuchFileException) {
                    return
                }
            }
            throw IOException("too many home folders were put aside")
        }

        /**
         * Removes [start] and everything in it, bottom-up, without following a link: a link is
         * removed as a link. A real folder first gets its owner's read, write and search rights,
         * and only then is it listed. There is no recursion and a folder is listed
         * [FolderOps.BATCH] entries at a time, so neither a deep nor a very full tree that a
         * command made can exhaust the stack or the memory.
         */
        private fun removeTree(start: Path, ops: FolderOps) {
            // A folder comes back after each batch of its entries, with one of them as the witness that the batch is gone.
            val pending = ArrayDeque<Pair<Path, Path?>>()
            pending.addLast(start to null)
            while (pending.isNotEmpty()) {
                val (path, witness) = pending.removeLast()
                if (witness != null && Files.exists(witness, LinkOption.NOFOLLOW_LINKS)) {
                    // Never go round again for what is still there: that would not end.
                    throw IOException("an entry of the home folder was not removed")
                }
                val attributes = try {
                    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) {
                    continue
                }
                if (attributes.isDirectory) {
                    openForOwner(path)
                    val children = ops.list(path, FolderOps.BATCH)
                    if (children.isNotEmpty()) {
                        pending.addLast(path to children.first())
                        children.forEach { pending.addLast(it to null) }
                        continue
                    }
                }
                ops.delete(path)
            }
        }

        /**
         * Gives the owner (this app) read, write and search rights on the folder [dir]; the other
         * rights stay as they are. Whether it worked shows in what is done with the folder next.
         */
        private fun openForOwner(dir: Path) {
            val file = dir.toFile()
            file.setReadable(true, true)
            file.setWritable(true, true)
            file.setExecutable(true, true)
        }

        private const val STUCK = ".stuck-"
        private const val FRESH = ".fresh-"
        private const val MAX_STUCK = 100
    }
}

/**
 * The file operations emptying the shell's home folder is made of. The real ones are the
 * defaults. Tests replace one to make it fail or to see the order of the steps: the build
 * machine runs as root, which no permission bit ever refuses, so a locked folder cannot be made
 * there (ShellLeftoversDeviceTest shows the real thing on a device).
 */
internal open class FolderOps {
    /** The first entries of the folder [dir], [limit] at most. */
    @Throws(IOException::class)
    open fun list(dir: Path, limit: Int): List<Path> = try {
        Files.newDirectoryStream(dir).use { it.take(limit) }
    } catch (e: DirectoryIteratorException) {
        throw e.cause ?: IOException("the folder could not be read")
    }

    /** Removes one file, link or empty folder; a link is removed itself, never what it points to. */
    @Throws(IOException::class)
    open fun delete(path: Path) {
        Files.delete(path)
    }

    /** Renames [from] to [to] in the same folder; fails when [to] exists. */
    @Throws(IOException::class)
    open fun rename(from: Path, to: Path) {
        Files.move(from, to)
    }

    /** How many entries of one folder are held in memory at a time. */
    companion object {
        const val BATCH: Int = 2_000
    }
}
