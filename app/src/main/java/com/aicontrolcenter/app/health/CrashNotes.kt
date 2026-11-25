package com.aicontrolcenter.app.health

import java.io.File
import java.io.IOException

/**
 * Notes of the app's own crashes (Phase 6, R2; WP2 item 7), so the next start can say what stopped the app: an uncaught
 * exception handler of the main process, chained to the one before it, writes the exception's class and its first
 * [MAX_FRAMES] frames of the app's own code (`com.aicontrolcenter…`) into a small file in `noBackupFilesDir`, then hands
 * the exception on (Android still shows and records the crash).
 *
 * Never the exception's message, which can hold anything (an agent's words, a file's text, a key): class and frame names
 * only, which the app's own code wrote. At most [MAX_NOTES] notes are kept. Not installed in the script process (its
 * application object returns before any hook there).
 */
class CrashNotes(fileOf: () -> File) {
    /** The notes in [file]. */
    constructor(file: File) : this({ file })

    /**
     * Found on first use (wave 3, D45): the application installs the handler at every start on the main thread, and
     * asking Android for `noBackupFilesDir` there touches the disk (Android makes the folder if it is missing).
     */
    private val file: File by lazy(fileOf)
    private val lock = Any()

    /** One crash: when, the exception's class (and its causes' classes), and where in the app's own code. */
    data class Note(val at: Long, val exceptionClass: String, val causes: List<String>, val frames: List<String>) {
        /** For the activity log: classes and places, never a message. */
        val summary: String
            get() = buildString {
                append(exceptionClass)
                if (causes.isNotEmpty()) append(" (caused by ").append(causes.joinToString(", ")).append(')')
                if (frames.isNotEmpty()) append(" at ").append(frames.take(3).joinToString(", "))
            }
    }

    /** Writes a note for [failure] at [at]; never throws (it runs while the app is crashing). */
    fun write(failure: Throwable, at: Long) {
        try {
            val note = noteOf(failure, at)
            synchronized(lock) {
                val kept = (read() + note).takeLast(MAX_NOTES)
                file.parentFile?.mkdirs()
                val side = File(file.parentFile, file.name + ".tmp")
                side.writeText(kept.joinToString(SEPARATOR) { format(it) }, Charsets.UTF_8)
                if (!side.renameTo(file)) side.delete()
            }
        } catch (_: Throwable) {
            // The app is crashing already; a note that cannot be written is simply missing.
        }
    }

    /** Every note kept, oldest first. */
    fun read(): List<Note> = synchronized(lock) {
        val text = try {
            if (!file.isFile) return emptyList()
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return emptyList()
        }
        text.split(SEPARATOR).mapNotNull(::parse)
    }

    /** Notes written after [ms]. */
    fun since(ms: Long): List<Note> = read().filter { it.at > ms }

    /** The handler to install: notes, then whatever handled uncaught exceptions before. */
    fun handler(previous: Thread.UncaughtExceptionHandler?, now: () -> Long = System::currentTimeMillis): Thread.UncaughtExceptionHandler =
        NotingHandler(this, previous, now)

    private class NotingHandler(
        private val notes: CrashNotes,
        val previous: Thread.UncaughtExceptionHandler?,
        private val now: () -> Long,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, failure: Throwable) {
            notes.write(failure, now())
            previous?.uncaughtException(thread, failure)
        }
    }

    companion object {
        const val PATH: String = "health/crash-notes"
        const val MAX_FRAMES: Int = 20
        const val MAX_NOTES: Int = 5
        private const val APP_CODE = "com.aicontrolcenter."
        private const val SEPARATOR = "\n--\n"

        /** The app's crash notes in [noBackupFilesDir]. */
        fun inFolder(noBackupFilesDir: File): CrashNotes = CrashNotes(File(noBackupFilesDir, PATH))

        /** The app's crash notes in the folder [noBackupFilesDir] gives, asked for only when a note is read or written. */
        fun inFolder(noBackupFilesDir: () -> File): CrashNotes = CrashNotes { File(noBackupFilesDir(), PATH) }

        @Volatile
        private var installed: CrashNotes? = null

        /**
         * Installs the noting handler once per process, chained to the one before it; a later call (a new application
         * object in tests) only points it at [notes].
         */
        fun install(notes: CrashNotes) {
            synchronized(this) {
                val first = installed == null
                installed = notes
                if (first) {
                    val previous = Thread.getDefaultUncaughtExceptionHandler()
                    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
                        installed?.write(failure, System.currentTimeMillis())
                        previous?.uncaughtException(thread, failure)
                    }
                }
            }
        }

        /** The note for [failure]: classes and the app's own frames only. */
        fun noteOf(failure: Throwable, at: Long): Note {
            val causes = generateSequence(failure.cause) { it.cause.takeIf { cause -> cause !== it } }.take(5).map { it.javaClass.name }.toList()
            val chain = generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(6).toList()
            val frames = chain.flatMap { it.stackTrace.asList() }
                .filter { it.className.startsWith(APP_CODE) }
                .map { "${it.className}.${it.methodName}:${it.lineNumber}" }
                .distinct()
                .take(MAX_FRAMES)
            return Note(at, failure.javaClass.name, causes, frames)
        }

        private fun format(note: Note): String = buildString {
            append("at=").append(note.at).append('\n')
            append("class=").append(note.exceptionClass).append('\n')
            note.causes.forEach { append("cause=").append(it).append('\n') }
            note.frames.forEach { append("frame=").append(it).append('\n') }
        }

        private fun parse(block: String): Note? {
            var at: Long? = null
            var type: String? = null
            val causes = mutableListOf<String>()
            val frames = mutableListOf<String>()
            for (line in block.lines()) {
                val i = line.indexOf('=')
                if (i <= 0) continue
                val value = line.substring(i + 1)
                when (line.substring(0, i)) {
                    "at" -> at = value.toLongOrNull()
                    "class" -> type = value
                    "cause" -> causes += value
                    "frame" -> frames += value
                }
            }
            return if (at != null && type != null) Note(at, type, causes, frames.take(MAX_FRAMES)) else null
        }
    }
}
