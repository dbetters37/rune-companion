package com.aicontrolcenter.data.recovery

import android.content.Context
import android.content.Intent
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.health.RecoveryResult
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.data.security.DurableFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The owner's way out when the data cannot be opened (Phase 6, K14, rules 9 and 30). Owner-only: only the Recovery
 * screen holds it, through the application, never through the app's graph.
 *
 * [startOver] never deletes anything. In this order:
 * 1. it checks the database once more and refuses when it opens normally;
 * 2. it resets the first-run bookkeeping and both screen-control switches (so the next start seeds a starter team and
 *    nothing operates the phone), while every other setting stays;
 * 3. it moves on to the next key generation ([KeyGenerations.advance]): from now on the app opens only new files under
 *    new keystore entries, so a damaged entry is never reused, and a Start over cut off from here on still leaves a
 *    working fresh start;
 * 4. it closes the database and stops this process from opening any database again (the restart opens the new one);
 * 5. it moves the database (and its journal files), its sealed key and the API-key vault of every older generation still
 *    in place into `noBackupFilesDir/set-aside-<date>/`, with a note of which keystore entries open them (the entries
 *    stay, so the copy stays openable on this phone);
 * 6. it releases Android's persisted folder grants (the shared folders were in the old database).
 * The caller then restarts the process (rule 30: the data layer and the graph are single instances).
 */
class FileOwnerRecovery internal constructor(
    private val health: DatabaseHealth,
    /** Read when Start over runs (off the main thread), never when this is built. */
    private val generationsOf: () -> KeyGenerations,
    private val settings: SettingsRepository,
    /** Read when Start over runs, never when this is built: finding the folders may create them. */
    private val filesOf: () -> RecoveryFiles,
    private val beforeSettingAside: suspend () -> Unit,
    private val releaseFolderGrants: () -> Int,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val io: CoroutineDispatcher,
) : OwnerRecovery {
    private val mutex = Mutex()

    /** Set once a Start over is done: the process restarts next, and a second tap must not move on again. */
    @Volatile
    private var done: RecoveryResult.Done? = null

    override suspend fun state(): DatabaseState = health.check()

    override suspend fun startOver(): RecoveryResult = mutex.withLock {
        done?.let { return@withLock it }
        val state = health.check()
        if (!state.needsRecovery) return@withLock RecoveryResult.Refused(OPENS_NORMALLY)
        try {
            settings.update(::resetForFreshStart)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock RecoveryResult.Refused(SETTINGS_UNWRITABLE)
        }
        val generations = withContext(io) { generationsOf() }
        val next = withContext(io) {
            try {
                generations.advance()
            } catch (_: IOException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        } ?: return@withLock RecoveryResult.Refused(STORAGE_UNWRITABLE)
        beforeSettingAside()
        withContext(io) {
            val files = filesOf()
            val folder = files.newSetAsideFolder(clock.now(), zone())
            val moved = files.setAside((KeyGenerations.FIRST until next).map(generations::namesOf), folder)
            files.writeNote(folder, clock.now(), zone(), moved)
            try {
                releaseFolderGrants()
            } catch (_: RuntimeException) {
                // The grants only name folders the old data shared; leaving them costs nothing but a list entry.
            }
            RecoveryResult.Done(folder.absolutePath).also { done = it }
        }
    }

    companion object {
        const val OPENS_NORMALLY: String = "Your data opens normally, so nothing was changed."
        const val SETTINGS_UNWRITABLE: String = "Your settings could not be changed, so nothing was set aside. Try again."
        const val STORAGE_UNWRITABLE: String = "The app could not write to the phone's storage, so nothing was set aside. Free some room and try again."

        /**
         * What a fresh start needs (rule 9): the starter team and starter knowledge are seeded again, no Director is
         * named, Getting started shows, and both screen-control switches are off. Every other setting is kept.
         */
        fun resetForFreshStart(settings: FrameworkSettings): FrameworkSettings = settings.copy(
            seedVersion = 0,
            memorySeedVersion = 0,
            directorAgentId = null,
            onboardingCompleted = false,
            operatorEnabled = false,
            accessibilityOperatorEnabled = false,
        )
    }
}

/**
 * Where the files of each key generation are, and how they are set aside (never deleted).
 *
 * @param databasesFolder Android's databases folder of the app.
 * @param noBackupFilesDir the app's no-backup folder (sealed keys, vault, set-aside folders).
 */
internal class RecoveryFiles(
    private val databasesFolder: File,
    private val noBackupFilesDir: File,
) {
    /** A new, empty `set-aside-<date>` folder (`-2`, `-3`… when one of that date exists). */
    fun newSetAsideFolder(now: Long, zone: ZoneId): File {
        val date = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(now).atZone(zone))
        var n = 1
        while (true) {
            val folder = File(noBackupFilesDir, if (n == 1) "$PREFIX$date" else "$PREFIX$date-$n")
            if (!folder.exists() && folder.mkdirs()) return folder
            n++
            check(n < 10_000) { "No room for another set-aside folder." }
        }
    }

    /**
     * Moves every file of [generations] that is still in place into [folder] (`databases/`, `database-keys/`, `vault/`),
     * keeping its name. Returns the generations whose files were found, each with the files moved. A file that cannot be
     * moved stays where it is, under its own generation's name, which the app never opens again.
     */
    fun setAside(generations: List<KeyGenerations.Names>, folder: File): List<Pair<KeyGenerations.Names, List<String>>> =
        generations.mapNotNull { names ->
            val moved = candidates(names).mapNotNull { (source, area) ->
                if (!source.isFile) return@mapNotNull null
                val target = File(File(folder, area), source.name)
                if (move(source, target)) "$area/${source.name}" else null
            }
            if (moved.isEmpty()) null else names to moved
        }

    /** A plain note beside the set-aside files: when, and which keystore entries open them. No secret is in it. */
    fun writeNote(folder: File, now: Long, zone: ZoneId, moved: List<Pair<KeyGenerations.Names, List<String>>>) {
        val text = buildString {
            append("Set aside by \"Start over\" on ")
            append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(now).atZone(zone)))
            append(".\n")
            append("Nothing here was deleted. The keys that open these files stay in this phone's keystore.\n")
            if (moved.isEmpty()) append("No data file was found to set aside.\n")
            for ((names, list) in moved) {
                append("\nKey generation ").append(names.generation).append(": database key entry ")
                append(names.databaseKeyAlias).append(", vault key entry ").append(names.vaultKeyAlias).append('\n')
                list.forEach { append("  ").append(it).append('\n') }
            }
        }
        try {
            DurableFile(File(folder, NOTE)).replace(text.toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // The files are set aside either way; the note only explains them.
        }
    }

    /** Every file of one generation: the database and its journals, the sealed key, the vault and their side files. */
    private fun candidates(names: KeyGenerations.Names): List<Pair<File, String>> {
        val database = File(databasesFolder, names.databaseFile)
        val databaseFiles = DATABASE_SUFFIXES.map { File(databasesFolder, names.databaseFile + it) } + database
        val key = File(noBackupFilesDir, names.databaseKeyFile)
        val vault = File(noBackupFilesDir, names.vaultFile)
        val keyFiles = siblingsStartingWith(key)
        val vaultFiles = siblingsStartingWith(vault)
        return databaseFiles.map { it to DATABASES } + keyFiles.map { it to KEYS } + vaultFiles.map { it to VAULT }
    }

    /** [file] and its side files (`.tmp`, a damaged vault's `.corrupt-<time>`), which belong to the same generation. */
    private fun siblingsStartingWith(file: File): List<File> =
        file.parentFile?.listFiles()?.filter { it.isFile && (it.name == file.name || it.name.startsWith(file.name + ".")) }.orEmpty()

    private fun move(source: File, target: File): Boolean =
        try {
            target.parentFile?.mkdirs()
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }

    companion object {
        const val PREFIX: String = "set-aside-"
        const val NOTE: String = "about.txt"
        const val DATABASES: String = "databases"
        const val KEYS: String = "database-keys"
        const val VAULT: String = "vault"
        private val DATABASE_SUFFIXES = listOf("-wal", "-shm", "-journal")
    }
}

/** Android's persisted folder grants (the shared folders the owner picked), released at Start over. */
internal object FolderGrants {
    /** Releases every persisted grant of the app; returns how many. One that cannot be released is skipped. */
    fun releaseAll(context: Context): Int {
        val resolver = context.contentResolver
        var released = 0
        for (permission in resolver.persistedUriPermissions) {
            val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            try {
                resolver.releasePersistableUriPermission(permission.uri, flags)
                released++
            } catch (_: SecurityException) {
                // Already gone.
            }
        }
        return released
    }
}
