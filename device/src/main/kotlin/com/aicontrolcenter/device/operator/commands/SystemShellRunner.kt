package com.aicontrolcenter.device.operator.commands

import android.content.Context
import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.ShellRunner
import com.aicontrolcenter.core.operator.WorkspaceFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Android's own shell: `/system/bin/sh -c <command>`, run as this app's user (so with exactly
 * this app's file access, no more), in a workspace folder.
 *
 *  - The command is one argument to `sh -c`, never split or re-quoted here. A command that
 *    cannot reach the shell as the same text (a NUL, half of a surrogate pair) is not run.
 *  - The environment is replaced, not inherited: only [ENV_PATH], `HOME` and `TMPDIR`
 *    ([tempDir]) are set, so nothing of this app's environment leaks. `HOME` is [homeDir], a
 *    folder of the app outside the workspace that is empty before every command, and `ENV`
 *    is not set: a shell the command starts (`sh -l`, `sh -i`) finds no start-up file there that
 *    an agent could have written. What an earlier command left in that folder is never deleted
 *    before a command: the folder is put aside by a rename and a new one is made, which takes
 *    the same short time whatever was left ([foldersFor]). What was put aside is deleted
 *    afterwards, off the command's path and a few seconds at a time ([clearPutAside]).
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
    /** The file operations on the home folder; tests make one of them fail or count them. */
    private val folderOps: FolderOps = FolderOps(),
    /** How long one pass of deleting what was put aside may take. */
    private val tidyBudgetMs: Long = TIDY_BUDGET_MS,
) : ShellRunner {
    private val oneAtATime = Mutex()

    /**
     * Where what was put aside is deleted: a scope of the runner's own, so that this work goes
     * on after a command was answered and holds nothing of the caller's (the workspace's change
     * lock least of all).
     */
    private val ownScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** The pass that deletes what was put aside. Read and set only under [oneAtATime]. */
    private var tidying: Job? = null

    /** What the latest sweep said: nothing a command started is running. Only then is anything deleted. Under [oneAtATime]. */
    private var nothingRuns = false

    /**
     * Stops what an earlier command left running: for the app's start, after it died under a
     * command. Returns how many processes were stopped. Waits for a command that is running.
     * Afterwards one pass of deleting home folders that were put aside starts in the background
     * ([startTidying]); this call does not wait for it.
     */
    suspend fun stopLeftovers(): Int = withContext(dispatcher) {
        oneAtATime.withLock {
            try {
                stopTidying()
                (sweep() as? Sweep.Done)?.stopped ?: 0
            } finally {
                startTidying()
            }
        }
    }

    override suspend fun run(command: String, workingDirectory: WorkspaceFile, limits: CommandLimits): CommandOutcome = withContext(dispatcher) {
        oneAtATime.withLock {
            try {
                // Nothing is deleted while a command runs: it could put a link where a folder is being emptied.
                stopTidying()
                runOne(command, workingDirectory, limits)
            } finally {
                startTidying()
            }
        }
    }

    /** Waits for the pass that deletes what was put aside, when one runs. For tests: the app never waits for it. */
    internal suspend fun awaitTidying() {
        oneAtATime.withLock { tidying }?.join()
    }

    /**
     * Ends the pass that deletes what was put aside, when one runs. It looks for this between
     * two entries, so the wait is one file operation long, whatever is left to delete.
     */
    private suspend fun stopTidying() {
        val pass = tidying ?: return
        // Not left half-way when the caller is cancelled: a pass that still runs would overlap the next one.
        withContext(NonCancellable) { pass.cancelAndJoin() }
        tidying = null
        currentCoroutineContext().ensureActive()
    }

    /**
     * Starts one pass of deleting what was put aside ([clearPutAside]): after a command has
     * ended and after the sweep at the app's start, never before a command. It runs in
     * [ownScope], so the caller has its answer and has let go of whatever it holds; it ends by
     * itself after [tidyBudgetMs], and the next command or sweep ends it earlier
     * ([stopTidying]). What it leaves is continued by the next pass. Nothing is started while a
     * process of an earlier command may still run.
     */
    private fun startTidying() {
        if (!nothingRuns) return
        tidying = ownScope.launch {
            try {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(tidyBudgetMs)
                clearPutAside(homeDir(), tempDir(), folderOps) { !isActive || System.nanoTime() - deadline >= 0 }
            } catch (_: Throwable) {
                // Whatever it was, an OutOfMemoryError included: the pass ends, and what is left stays for the next one.
            }
        }
    }

    /** One sweep; what it says about processes still running decides whether anything is deleted afterwards. */
    private fun sweep(): Sweep = sweeper.sweep().also { nothingRuns = it is Sweep.Done && it.left == 0 }

    private suspend fun runOne(command: String, workingDirectory: WorkspaceFile, limits: CommandLimits): CommandOutcome {
        if (command.isBlank()) return CommandOutcome.Failed("There is no command.")
        // A NUL, half of a surrogate pair: the shell would get another text than the one that was shown.
        notExactProblem(command)?.let { return CommandOutcome.Failed(it) }
        if (command.length > MAX_COMMAND_CHARS) {
            return CommandOutcome.Failed("The command is longer than $MAX_COMMAND_CHARS characters; put longer scripts in a workspace file and run that.")
        }
        // Before anything starts: the sweep must be possible here, and nothing of an earlier command may still run.
        when (val before = sweep()) {
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
        // Constant work, whatever an earlier command left: nothing is walked or deleted here.
        val folders = when (val made = foldersFor(homeDir, tempDir, workspaceRootOf(workingDirectory), folderOps)) {
            is ShellFolders.Refused -> return CommandOutcome.Failed(made.reason)
            is ShellFolders.Ready -> made
        }
        // setsid sh -c WRAPPER sh COMMAND: the wrapper prints its pid (the new group's id), then becomes `sh -c COMMAND`.
        val builder = ProcessBuilder(setsid, shell, "-c", WRAPPER, shell, command).directory(directory)
        builder.environment().apply {
            clear()
            putAll(environmentFor(folders.home, folders.temp))
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
            val swept = sweep()
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
                sweep()
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

        /** The workspace's top folder: [dir] without the names of its path inside the workspace. */
        private fun workspaceRootOf(dir: WorkspaceFile): File = dir.path.segments.fold(dir.file) { folder, _ -> folder.parentFile ?: folder }

        /**
         * `HOME` and `TMPDIR` for one command: existing, empty folders outside the workspace, or
         * the sentence that says why the command is not run. [home] names the usual place of
         * `HOME`, in the app's cache folder; [temp] is treated in the same way when it lies
         * beside it, and is only made when it lies elsewhere.
         *
         * An earlier command may have left anything there, and as much as it could write in its
         * time. So nothing is walked and nothing is deleted here; the work is the same however
         * much was left, and no link is followed:
         *  - The cache folder itself: when a command put a link or a file in its place, that is
         *    removed and the folder is made anew (asking [home] again lets Android make its
         *    cache folder as it wants it). When it is a real folder without its owner's rights,
         *    it gets them back.
         *  - A real folder with its owner's rights and no entry is used as it is. Only the first
         *    entry is looked for, and a folder that cannot be listed counts as not empty.
         *  - Anything else (entries, a link, a file, a locked folder) is renamed aside as
         *    `<name>.old-<random>` in the cache folder, which needs rights on that folder only,
         *    and a new folder is made in its place.
         *  - If that does not work, a new folder `<name>.fresh-<random>` beside it is used.
         *  - The real paths of the folders must not lie inside the real path of
         *    [workspaceRoot]; otherwise the command is refused. For the cache folder this is
         *    looked at first, before anything in it is touched.
         * What was put aside or made beside is deleted later, off the command's path
         * ([clearPutAside]).
         *
         * Never throws: every failure, an [Error] included, ends in a new folder or in
         * [ShellFolders.Refused].
         */
        internal fun foldersFor(home: () -> File, temp: () -> File, workspaceRoot: File, ops: FolderOps = FolderOps()): ShellFolders = try {
            val homePath = home().toPath().toAbsolutePath().normalize()
            val tempPath = temp().toPath().toAbsolutePath().normalize()
            val cache = homePath.parent ?: throw IOException("the home folder has no parent")
            val workspace = workspaceRoot.toPath().toRealPath()
            // The cache folder will be a real folder with this name, so its real path is its parent's and that name.
            val cacheWillBe = (cache.parent ?: cache).toRealPath().resolve(cache.fileName ?: cache)
            if (cacheWillBe.startsWith(workspace)) {
                ShellFolders.Refused(HOME_IN_WORKSPACE)
            } else {
                realFolder(cache, ops) { home() }
                val readyHome = emptyOrNew(homePath, cache, ops)
                val readyTemp = when {
                    tempPath == homePath -> readyHome
                    tempPath.parent == cache -> emptyOrNew(tempPath, cache, ops)
                    else -> Files.createDirectories(tempPath)
                }
                when {
                    readyHome.toRealPath().startsWith(workspace) -> ShellFolders.Refused(HOME_IN_WORKSPACE)
                    readyTemp.toRealPath().startsWith(workspace) -> ShellFolders.Refused(TEMP_IN_WORKSPACE)
                    else -> ShellFolders.Ready(readyHome.toFile(), readyTemp.toFile())
                }
            }
        } catch (_: NoCacheFolder) {
            ShellFolders.Refused(NO_CACHE_FOLDER)
        } catch (e: IOException) {
            ShellFolders.Refused("No empty home folder could be made for the shell (${e.javaClass.simpleName}), so the command was not run.")
        } catch (_: Throwable) {
            ShellFolders.Refused(NO_FOLDERS)
        }

        /**
         * Makes sure [cache], the folder the home folder lies in, is a real folder this app can
         * use. A link or a file in its place is removed, never followed, and the folder is made
         * anew: first by [remake] (Android makes its cache folder with the group and rights it
         * gives it), then here if it is still missing.
         */
        private fun realFolder(cache: Path, ops: FolderOps, remake: () -> Unit) {
            val found = attributesOf(cache)
            if (found?.isDirectory == true) {
                // A command may have taken the rights to the app's own cache folder away.
                if (!found.permissions().containsAll(OWNER_ALL)) openForOwner(cache)
                return
            }
            try {
                if (found != null) ops.delete(cache)
                remake()
                Files.createDirectories(cache)
            } catch (e: IOException) {
                throw NoCacheFolder(e)
            }
            if (attributesOf(cache)?.isDirectory != true) throw NoCacheFolder(null)
        }

        /** [path] itself when it is a real, empty folder this app has all rights to; otherwise it is put aside in [cache] and made anew, or a new folder beside it. */
        private fun emptyOrNew(path: Path, cache: Path, ops: FolderOps): Path {
            val name = path.fileName.toString()
            val found = attributesOf(path)
            if (found != null) {
                if (found.isDirectory && found.permissions().containsAll(OWNER_ALL) && hasNoEntry(path, ops)) return path
                try {
                    ops.rename(path, cache.resolve("$name$OLD${UUID.randomUUID()}"))
                } catch (_: IOException) {
                    return Files.createTempDirectory(cache, "$name$FRESH")
                }
            }
            return try {
                Files.createDirectory(path)
            } catch (_: IOException) {
                Files.createTempDirectory(cache, "$name$FRESH")
            }
        }

        /** Looks for a first entry of [dir] and no further. A folder that cannot be listed, for whatever reason, counts as not empty. */
        private fun hasNoEntry(dir: Path, ops: FolderOps): Boolean = try {
            ops.entries(dir).use { !it.iterator().hasNext() }
        } catch (_: Throwable) {
            false
        }

        /**
         * One pass of deleting what [foldersFor] put aside or made beside [home] (and beside
         * [temp], when that lies in the same folder) for earlier commands. It is not on a
         * command's path: the runner starts it after a command has ended and after the sweep at
         * the app's start, in a scope of its own, and no command runs meanwhile.
         *
         *  - Memory does not grow with what is deleted ([removeTree]).
         *  - Time: [stop] is asked between two entries; when it says so (the pass's time is up, or
         *    the next command is waiting), the pass ends and the next one continues.
         *  - A link is removed as a link. The cache folder itself is not gone through when a
         *    command put a link in its place (the next command puts that right first), so nothing
         *    but the folders put aside is touched.
         *  - Whatever goes wrong, an [Error] such as OutOfMemoryError included, ends the pass
         *    quietly. What cannot be deleted (a path too deep to be named, a name the platform
         *    cannot pass on) stays in the cache folder until Android or the owner clears it.
         */
        internal fun clearPutAside(home: File, temp: File = home, ops: FolderOps = FolderOps(), stop: () -> Boolean = { false }) {
            try {
                val homePath = home.toPath().toAbsolutePath().normalize()
                val tempPath = temp.toPath().toAbsolutePath().normalize()
                val cache = homePath.parent ?: return
                if (attributesOf(cache)?.isDirectory != true) return
                val names = if (tempPath.parent == cache) setOf(homePath.fileName.toString(), tempPath.fileName.toString()) else setOf(homePath.fileName.toString())
                val ours = names.flatMap { listOf(it + OLD, it + FRESH) }
                ops.entries(cache).use { listing ->
                    for (entry in listing) {
                        if (stop()) return
                        val name = entry.fileName.toString()
                        if (ours.none { name.startsWith(it) }) continue
                        try {
                            if (!removeTree(entry, ops, stop)) return
                        } catch (_: IOException) {
                            // It stays for a later pass; Android may also clear its cache folder by itself.
                        }
                    }
                }
            } catch (_: Throwable) {
                // The pass ends here; what is left stays for the next one.
            }
        }

        /** One folder on the way down whose listing is open. */
        private class Level(val dir: Path, val listing: DirectoryStream<Path>) {
            val entries: Iterator<Path> = listing.iterator()

            /** Whether anything in it was removed or gone into since it was opened. */
            var progress = false
        }

        /**
         * Removes [start] and everything in it without following a link: a link is removed as a
         * link. Returns false when [stop] said so before the end; a later call removes the rest.
         *
         * Memory does not grow with the tree, however deep or full a command made it. What is no
         * folder is deleted as the listing hands it out, and a folder is gone into at once, so
         * no entry is ever kept waiting. At most [MAX_OPEN] listings are open at a time, those
         * of the deepest folders on the way down, each with the one entry it handed out last. A
         * folder further up is listed again when the walk comes back to it, and the way back is
         * the path's own parent, so there is no list of folders either, and no recursion.
         *
         * A real folder gets its owner's rights back before it is listed, through its [Path]:
         * that keeps the name's bytes, also when they are no valid text.
         *
         * @throws IOException when something cannot be removed; it stays, with what is around it
         */
        private fun removeTree(start: Path, ops: FolderOps, stop: () -> Boolean): Boolean {
            val what = attributesOf(start) ?: return true
            if (!what.isDirectory) {
                ops.delete(start)
                return true
            }
            val open = ArrayDeque<Level>()
            try {
                var current = start
                while (true) {
                    // Also on the way up and down, where no entry is handed out: a deep tree has a step per folder.
                    if (stop()) return false
                    val level = open.lastOrNull()?.takeIf { it.dir == current } ?: run {
                        // The listing furthest up is closed first, so that no more than MAX_OPEN are open even for a moment.
                        if (open.size >= MAX_OPEN) open.removeFirst().listing.close()
                        openForOwner(current)
                        Level(current, ops.entries(current)).also { open.addLast(it) }
                    }
                    var folder: Path? = null
                    while (folder == null && level.entries.hasNext()) {
                        if (stop()) return false
                        val entry = level.entries.next()
                        if (attributesOf(entry)?.isDirectory == true) folder = entry else deleteIfThere(entry, ops)
                        level.progress = true
                    }
                    if (folder != null) {
                        current = folder
                        continue
                    }
                    open.removeLast().listing.close()
                    try {
                        deleteIfThere(current, ops)
                    } catch (e: DirectoryNotEmptyException) {
                        // A listing may leave an entry out while others are removed. Once more, but only after a round that removed something: never round and round.
                        if (!level.progress) throw e
                        continue
                    }
                    if (current == start) return true
                    current = current.parent
                }
            } catch (e: DirectoryIteratorException) {
                throw e.cause ?: IOException("a folder could not be read")
            } finally {
                for (level in open) {
                    try {
                        level.listing.close()
                    } catch (_: IOException) {
                        // Nothing more is read from it either way.
                    }
                }
            }
        }

        private fun deleteIfThere(path: Path, ops: FolderOps) {
            try {
                ops.delete(path)
            } catch (_: NoSuchFileException) {
                // Gone already, which is all that was wanted.
            }
        }

        /** What [path] itself is, a link not followed; null when nothing is there. */
        private fun attributesOf(path: Path): PosixFileAttributes? = try {
            Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            null
        }

        /**
         * Gives the owner (this app) read, write and search rights on the real folder [dir]; the
         * other rights stay as they are. Through the [Path], never through a [File] and its
         * String: a name that is no valid text does not survive that, and the folder would stay
         * locked. Whether it worked shows in what is done with the folder next.
         */
        private fun openForOwner(dir: Path) {
            try {
                val rights = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS)
                if (!rights.containsAll(OWNER_ALL)) Files.setPosixFilePermissions(dir, rights + OWNER_ALL)
            } catch (_: IOException) {
                // The listing that follows fails, and the folder stays.
            }
        }

        /** The cache folder is no folder and could not be made one. */
        private class NoCacheFolder(cause: IOException?) : IOException(cause)

        internal const val NO_CACHE_FOLDER: String =
            "The app's cache folder, where the shell's home folder lies, is no longer a folder and could not be made anew, so the command was not run."

        internal const val HOME_IN_WORKSPACE: String =
            "The shell's home folder would lie inside the workspace, where a command could leave start-up files for the next one, so the command was not run."
        internal const val TEMP_IN_WORKSPACE: String =
            "The shell's folder for temporary files would lie inside the workspace, so the command was not run."
        private const val NO_FOLDERS = "No empty home folder could be made for the shell, so the command was not run."

        private val OWNER_ALL = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        private const val OLD = ".old-"
        private const val FRESH = ".fresh-"

        /** How many folder listings [removeTree] keeps open at most. */
        internal const val MAX_OPEN: Int = 16

        /** One pass of deleting what was put aside takes this long at most; the next pass continues. */
        const val TIDY_BUDGET_MS: Long = 3_000
    }
}

/** What [SystemShellRunner.foldersFor] made for one command. */
internal sealed interface ShellFolders {
    /** Both exist, are empty (when in the app's cache folder) and lie outside the workspace. */
    data class Ready(val home: File, val temp: File) : ShellFolders

    /** No such folders could be had; [reason] is the sentence for the command's answer. */
    data class Refused(val reason: String) : ShellFolders
}

/**
 * The file operations the shell's home folder is prepared and cleared with. The real ones are
 * the defaults. Tests replace one to make it fail, to count what is held, or to see the order of
 * the steps: the build machine runs as root, which no permission bit ever refuses, so a locked
 * folder cannot be made there (ShellLeftoversDeviceTest shows the real thing on a device).
 */
internal open class FolderOps {
    /** The entries of the folder [dir], handed out one at a time. Whoever opens the listing closes it. */
    @Throws(IOException::class)
    open fun entries(dir: Path): DirectoryStream<Path> = Files.newDirectoryStream(dir)

    /** Removes one file, link or empty folder; a link is removed itself, never what it points to. */
    @Throws(IOException::class)
    open fun delete(path: Path) {
        Files.delete(path)
    }

    /** Renames [from] to [to] in the same folder; fails when [to] exists. A link is renamed itself. */
    @Throws(IOException::class)
    open fun rename(from: Path, to: Path) {
        Files.move(from, to)
    }
}
