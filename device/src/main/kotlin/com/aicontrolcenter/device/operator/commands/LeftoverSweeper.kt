package com.aicontrolcenter.device.operator.commands

import java.io.File
import java.io.IOException

/** One process as the process table lists it. */
internal data class ProcessEntry(val pid: Int, val parentPid: Int, val uid: Int)

/** The processes this app can see. */
internal fun interface ProcessTable {
    /** Every process that is still running (zombies left out), or null when the table cannot be read. */
    fun read(): List<ProcessEntry>?
}

/**
 * A process table in the layout of Linux's `/proc`: one folder per process, named by its pid,
 * holding a `status` file with `State:`, `PPid:` and `Uid:` lines. The folder is a constructor
 * parameter, and nothing in this class names the real one: tests give it a temporary folder they
 * filled themselves.
 */
internal class ProcFolderTable(private val folder: File) : ProcessTable {
    override fun read(): List<ProcessEntry>? {
        val folders = folder.listFiles() ?: return null
        val entries = ArrayList<ProcessEntry>()
        for (dir in folders) {
            val pid = dir.name.takeIf { name -> name.isNotEmpty() && name.length <= 9 && name.all { it in '0'..'9' } }?.toInt() ?: continue
            val status = try {
                File(dir, "status").readText()
            } catch (_: IOException) {
                // It ended while the table was read, or it is another user's and hidden.
                continue
            } catch (_: SecurityException) {
                continue
            }
            var parent: Int? = null
            var uid: Int? = null
            var gone = false
            for (line in status.lineSequence()) {
                when {
                    line.startsWith("PPid:") -> parent = line.substringAfter(':').trim().toIntOrNull()
                    // Real, effective, saved and file-system uid: the real one counts.
                    line.startsWith("Uid:") -> uid = line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                    // A zombie has ended and only waits to be collected; it runs nothing.
                    line.startsWith("State:") -> gone = line.substringAfter(':').trim().firstOrNull().let { it == 'Z' || it == 'X' }
                }
            }
            if (gone) continue
            entries += ProcessEntry(pid, parent ?: continue, uid ?: continue)
        }
        return entries
    }
}

/** What one sweep did. */
internal sealed interface Sweep {
    /** [stopped] processes were killed; [left] were still there when the sweep gave up (0: none is left). */
    data class Done(val stopped: Int, val left: Int) : Sweep

    /** Nothing was looked at and nothing was killed; [reason] says why, as a sentence. */
    data class NotPossible(val reason: String) : Sweep
}

/** Stops what shell commands left running. */
internal interface LeftoverSweeper {
    fun sweep(): Sweep

    /**
     * The sweeper of everything that is not this app on a phone: it never looks at a process and
     * never kills one. A [SystemShellRunner] that has it runs no command.
     */
    object None : LeftoverSweeper {
        const val REASON = "This is not an Android app process, so what a command starts could not be found and stopped when it ends."

        override fun sweep(): Sweep = Sweep.NotPossible(REASON)
    }
}

/**
 * Kills every process a shell command of this app left running, whatever it did to get away
 * (a new session, a double fork, job control): every process in [table] that runs under
 * [ownUid], is not this app's own process ([ownPid]) and is not a child of the process that
 * started this app (Android's zygote, whose children are app processes) is killed, and the
 * table is read again until none is left, at most [maxRounds] times.
 *
 * The process table and the kill function are constructor parameters and have no defaults. On
 * the build machine every process runs as one user, so a sweep of the real table would kill the
 * build itself: tests therefore give a table in a temporary folder and a function that kills
 * nothing real, and the only code that connects the real ones is [AndroidLeftovers], which
 * refuses anywhere but in an Android app process.
 */
internal class ProcessSweeper(
    private val table: ProcessTable,
    private val kill: (pid: Int) -> Unit,
    private val ownPid: Int,
    private val ownUid: Int,
    private val maxRounds: Int = MAX_ROUNDS,
    private val pause: (ms: Long) -> Unit = { ms -> pauseUninterrupted(ms) },
) : LeftoverSweeper {

    override fun sweep(): Sweep {
        val stopped = HashSet<Int>()
        var left = 0
        for (round in 0..maxRounds) {
            val processes = table.read() ?: return Sweep.NotPossible(UNREADABLE)
            val own = processes.firstOrNull { it.pid == ownPid } ?: return Sweep.NotPossible(UNREADABLE)
            // The process that started this app. Without a real one (init itself, or none) an orphan could not be told from an app process.
            val starter = own.parentPid
            if (starter <= 1 || own.uid != ownUid) return Sweep.NotPossible(UNREADABLE)
            val leftovers = processes.filter { it.uid == ownUid && it.pid > 1 && it.pid != ownPid && it.pid != starter && it.parentPid != starter }
            left = leftovers.size
            if (leftovers.isEmpty() || round == maxRounds) break
            for (process in leftovers) {
                stopped += process.pid
                try {
                    kill(process.pid)
                } catch (_: RuntimeException) {
                    // It is gone already, or may not be signalled: the next round shows which.
                }
            }
            pause(ROUND_PAUSE_MS)
        }
        return Sweep.Done(stopped.size, left)
    }

    companion object {
        /** A process that keeps starting others is chased this many times, about two seconds in all. */
        const val MAX_ROUNDS = 100
        const val ROUND_PAUSE_MS = 20L
        const val UNREADABLE = "This phone does not let the app see its own processes, so what a command starts could not be found and stopped when it ends."

        /** Waits [ms] also when the thread was interrupted (a cancelled call still stops what it started); the interrupt is kept. */
        fun pauseUninterrupted(ms: Long) {
            val interrupted = Thread.interrupted()
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

/**
 * The one place that connects [ProcessSweeper] to the real process table and a real kill. It
 * does so only where both hold:
 *  - the runtime is Android's (`java.vm.name` is "Dalvik"), which no JVM on a build machine and
 *    no Robolectric test is, and
 *  - this process runs under an application uid (10000 or higher), so the kernel lets it signal
 *    nothing but this app's own processes.
 * With either false it returns [LeftoverSweeper.None] before any Android class is touched. The
 * kill function checks the runtime once more before every single kill.
 */
internal object AndroidLeftovers {
    const val ANDROID_VM = "Dalvik"
    const val FIRST_APPLICATION_UID = 10_000

    /** True only inside an Android app process. */
    fun allowed(vmName: String?, uid: Int?): Boolean = vmName == ANDROID_VM && uid != null && uid >= FIRST_APPLICATION_UID

    /** The sweeper for the process this is called in. */
    fun create(): LeftoverSweeper {
        // First, and on its own: off Android nothing below is even evaluated.
        if (System.getProperty(VM_PROPERTY) != ANDROID_VM) return LeftoverSweeper.None
        return create(
            vmName = System.getProperty(VM_PROPERTY),
            uid = android.os.Process.myUid(),
            pid = android.os.Process.myPid(),
            table = { ProcFolderTable(File("/proc")) },
            kill = { pid -> if (System.getProperty(VM_PROPERTY) == ANDROID_VM) android.os.Process.killProcess(pid) },
        )
    }

    /** [create] with everything it reads as a parameter, so a test can run its decision with a table and a kill that are not real. */
    fun create(vmName: String?, uid: Int?, pid: Int, table: () -> ProcessTable, kill: (Int) -> Unit): LeftoverSweeper =
        if (!allowed(vmName, uid)) LeftoverSweeper.None else ProcessSweeper(table(), kill, ownPid = pid, ownUid = uid!!)

    private const val VM_PROPERTY = "java.vm.name"
}
