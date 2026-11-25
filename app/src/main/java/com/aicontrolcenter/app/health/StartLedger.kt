package com.aicontrolcenter.app.health

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * A small file of start-up bookkeeping in `noBackupFilesDir` (Phase 6, R2, rules 1 and 10; WP2 item 7), kept outside the
 * database so it can be read when the database cannot be opened and without building the app's graph:
 * - "started" with the versionCode before the start-up steps, and "ok" after them: two starts in a row without "ok"
 *   turn safe start on, and the upkeep job does nothing until a start of this version reached "ok" (rule 1: it never
 *   opens the database first after an update);
 * - whether safe start is on, and why, until the owner chooses (rule 10);
 * - the time of the newest exit of the main process already looked at, so each is processed once (R1), and the times of
 *   the recent crashes (three within 15 minutes turn safe start on).
 *
 * Numbers and fixed words only; no secret, no text from Android or an agent. A missing or unreadable file reads as
 * [Entry.EMPTY]: nothing known, nothing held back. Writes replace the whole file atomically.
 */
class StartLedger(private val file: File) {

    /** What the ledger holds. */
    data class Entry(
        /** The versionCode of the last start that began its steps; null before the first. */
        val lastStartedVersion: Long? = null,
        /** When it began. */
        val lastStartedAt: Long? = null,
        /** Starts in a row that began their steps and never reached "ok" (this one included while it runs). */
        val startsWithoutOk: Int = 0,
        /** The versionCode of the last start that reached "ok". */
        val lastOkVersion: Long? = null,
        /** When it did. */
        val lastOkAt: Long? = null,
        /** Safe start is on: nothing resumes by itself until the owner chooses. */
        val safeStart: Boolean = false,
        /** Why it went on. */
        val safeStartReason: SafeStartReason? = null,
        /** The newest main-process exit already looked at (ms), 0 for none. */
        val lastExitSeenAt: Long = 0,
        /** Times of the most recent crashes of the main process (CRASH, CRASH_NATIVE, ANR), oldest first, at most 10. */
        val recentCrashes: List<Long> = emptyList(),
    ) {
        companion object {
            val EMPTY: Entry = Entry()
        }
    }

    /** The ledger now, or [Entry.EMPTY] when there is none or it cannot be read. */
    @Synchronized
    fun read(): Entry = load()

    /** Applies [transform] and stores the result; returns it. A write that fails leaves the old ledger and throws [IOException]. */
    @Synchronized
    fun update(transform: (Entry) -> Entry): Entry {
        val next = transform(load())
        store(next)
        return next
    }

    private fun load(): Entry {
        val text = try {
            if (!file.isFile) return Entry.EMPTY
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return Entry.EMPTY
        }
        return parse(text)
    }

    private fun store(entry: Entry) {
        val target = file.toPath()
        target.parent?.let(Files::createDirectories)
        val side = target.resolveSibling(file.name + ".tmp")
        FileChannel.open(side, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(format(entry).toByteArray(Charsets.UTF_8)))
            channel.force(true)
        }
        Files.move(side, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        /** Where the app keeps it. */
        const val PATH: String = "health/start-ledger"

        /** The app's ledger in [noBackupFilesDir]. */
        fun inFolder(noBackupFilesDir: File): StartLedger = StartLedger(File(noBackupFilesDir, PATH))

        private const val MAX_CRASHES = 10

        fun format(entry: Entry): String = buildString {
            fun line(key: String, value: Any?) {
                if (value != null) append(key).append('=').append(value).append('\n')
            }
            line("format", 1)
            line("lastStartedVersion", entry.lastStartedVersion)
            line("lastStartedAt", entry.lastStartedAt)
            line("startsWithoutOk", entry.startsWithoutOk)
            line("lastOkVersion", entry.lastOkVersion)
            line("lastOkAt", entry.lastOkAt)
            line("safeStart", entry.safeStart)
            line("safeStartReason", entry.safeStartReason?.name)
            line("lastExitSeenAt", entry.lastExitSeenAt)
            line("recentCrashes", entry.recentCrashes.sorted().takeLast(MAX_CRASHES).joinToString(","))
        }

        /** Reads what [format] wrote; anything it does not understand is left at its default. */
        fun parse(text: String): Entry {
            val values = text.lineSequence().mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }.toMap()
            return Entry(
                lastStartedVersion = values["lastStartedVersion"]?.toLongOrNull(),
                lastStartedAt = values["lastStartedAt"]?.toLongOrNull(),
                startsWithoutOk = values["startsWithoutOk"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                lastOkVersion = values["lastOkVersion"]?.toLongOrNull(),
                lastOkAt = values["lastOkAt"]?.toLongOrNull(),
                safeStart = values["safeStart"] == "true",
                safeStartReason = values["safeStartReason"]?.let { name -> SafeStartReason.entries.firstOrNull { it.name == name } },
                lastExitSeenAt = values["lastExitSeenAt"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                recentCrashes = values["recentCrashes"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.sorted().takeLast(MAX_CRASHES),
            )
        }
    }
}
