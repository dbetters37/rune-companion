package com.aicontrolcenter.device.operator.commands

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A process table in a temporary folder, laid out like `/proc`, and a kill function that only
 * removes entries from it. Nothing here names or signals a real process: on the build machine
 * everything runs as one user, and a sweep of the real table would stop the build.
 *
 * It starts with a zygote ([ZYGOTE], started by init) and this "app" ([APP], started by the
 * zygote), both under [UID].
 */
internal class FakeProc {
    val folder: File = Files.createTempDirectory("aicc-fake-proc").toFile()
    val killed = CopyOnWriteArrayList<Int>()

    /** What a kill does besides being recorded; by default the entry goes. */
    @Volatile var onKill: (Int) -> Unit = { remove(it) }

    val kill: (Int) -> Unit = { pid ->
        killed += pid
        onKill(pid)
    }

    init {
        add(1, 0, 0)
        add(ZYGOTE, 1, 0)
        add(APP, ZYGOTE, UID)
    }

    fun add(pid: Int, parent: Int, uid: Int, state: String = "S (sleeping)") {
        File(folder, "$pid").mkdirs()
        File(folder, "$pid/status").writeText(statusText(pid, parent, uid, state))
    }

    fun remove(pid: Int) {
        File(folder, "$pid").deleteRecursively()
    }

    fun pids(): Set<Int> = folder.list().orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    fun table(): ProcessTable = ProcFolderTable(folder)

    /** A sweeper over this table for the fake app. [pause] does not wait by default. */
    fun sweeper(maxRounds: Int = ProcessSweeper.MAX_ROUNDS, pause: (Long) -> Unit = {}): ProcessSweeper =
        ProcessSweeper(table(), kill, ownPid = APP, ownUid = UID, maxRounds = maxRounds, pause = pause)

    /** A shell command line (builtins and /bin/mkdir only) that adds an entry: how a test's command "starts a process that got away". */
    fun commandAdding(pid: Int, parent: Int = 1, uid: Int = UID): String =
        "/bin/mkdir -p '${folder.path}/$pid' && printf 'Name:\\tsleep\\nState:\\tS (sleeping)\\nPid:\\t$pid\\nPPid:\\t$parent\\nUid:\\t$uid\\t$uid\\t$uid\\t$uid\\n' > '${folder.path}/$pid/status'"

    companion object {
        const val ZYGOTE = 500
        const val APP = 4000
        const val UID = 10_123

        fun statusText(pid: Int, parent: Int, uid: Int, state: String = "S (sleeping)"): String =
            "Name:\tprocess$pid\nUmask:\t0077\nState:\t$state\nTgid:\t$pid\nPid:\t$pid\nPPid:\t$parent\nTracerPid:\t0\nUid:\t$uid\t$uid\t$uid\t$uid\nGid:\t$uid\t$uid\t$uid\t$uid\n"
    }
}
