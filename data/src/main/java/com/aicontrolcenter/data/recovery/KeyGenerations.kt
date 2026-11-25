package com.aicontrolcenter.data.recovery

import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.security.DatabaseKeyException
import com.aicontrolcenter.data.security.DurableFile
import com.aicontrolcenter.data.security.KeyProblem
import java.io.File
import java.io.IOException

/**
 * Which generation of the database, its sealed key and the API-key vault is in use (Phase 6, rule 9, F10).
 *
 * "Start over" never deletes anything and never re-uses a keystore entry that may be damaged: it sets the old files
 * aside and moves on to the next generation, whose files and keystore entries all have names of their own. The old
 * keystore entries stay, so the set-aside copy can still be opened on this phone.
 *
 * The current generation is a plain number in a small file beside the sealed keys (`noBackupFilesDir/database-keys/
 * generation`; no secret in it). No file means generation 1, which keeps the names 0.5.2 used, so data of 0.5.2 opens
 * unchanged: database `aicc.db`, sealed key `database-keys/aicc.db.key` under keystore entry `aicc.database-key.v1`,
 * vault `vault/secrets.json` under `aicc.secret-vault.v1`. Generation N > 1 uses `aicc.vN.db`,
 * `database-keys/aicc.vN.db.key`, `aicc.database-key.vN`, `vault/secrets.vN.json` and `aicc.secret-vault.vN`.
 *
 * The number is moved on *before* the old files are set aside: from that moment the app opens only the new
 * generation's files, so a Start over cut off half-way leaves the old files untouched under their own names, never a
 * mixture of the two.
 *
 * Not thread-safe on its own; [com.aicontrolcenter.data.recovery.FileOwnerRecovery] serialises Start over, and reading
 * is a single small file read.
 *
 * @param noBackupFilesDir the app's no-backup folder (sealed keys, vault, set-aside copies).
 * @param databaseName the database's base name: its generation file is `database-keys/generation` for the app's
 *   database and `database-keys/<name>.generation` for any other (tests), so two databases never share a number.
 */
class KeyGenerations(private val noBackupFilesDir: File, private val databaseName: String = AiccDatabase.DEFAULT_NAME) {
    private val file = File(noBackupFilesDir, fileFor(databaseName))
    private val durable = DurableFile(file)

    /**
     * The generation in use.
     *
     * @throws DatabaseKeyException when the note exists but cannot be read or is not a generation number: guessing a
     *   number could open (and create) the wrong files, so the database stays closed and the Recovery screen shows.
     */
    fun current(): Int {
        val bytes = try {
            durable.readOrNull()
        } catch (failure: IOException) {
            throw DatabaseKeyException("The note of which key is in use could not be read.", failure, KeyProblem.OTHER)
        } ?: return FIRST
        return parse(bytes) ?: throw DatabaseKeyException("The note of which key is in use is damaged.", problem = KeyProblem.DAMAGED)
    }

    /**
     * Moves on to the next generation and returns it, written durably before it returns. When the note itself is
     * damaged, the next generation is one above the highest any file in the no-backup folder shows, so a name is
     * never used twice.
     *
     * @throws IOException when the note cannot be written; the generation in use is then unchanged.
     */
    fun advance(): Int {
        val now = try {
            current()
        } catch (_: DatabaseKeyException) {
            highestSeen()
        }
        val next = maxOf(now, highestSeen()) + 1
        check(next <= MAX) { "Too many key generations." }
        durable.replace("$next\n".toByteArray(Charsets.US_ASCII))
        return next
    }

    /** The highest generation any file under the no-backup folder (sealed keys, vaults, set-aside copies) is named after; 1 if none. */
    fun highestSeen(): Int =
        noBackupFilesDir.walkTopDown().maxDepth(SCAN_DEPTH).filter { it.isFile }
            .mapNotNull { GENERATION_IN_NAME.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .filter { it in FIRST..MAX }
            .maxOrNull() ?: FIRST

    /** The names of generation [generation] of the database [databaseName]. */
    fun namesOf(generation: Int): Names = names(databaseName, generation)

    /** File names and keystore entries of one generation. Paths are relative to the databases folder or `noBackupFilesDir`. */
    data class Names(
        val generation: Int,
        /** The database file in Android's databases folder. */
        val databaseFile: String,
        /** The sealed database key, relative to `noBackupFilesDir`. */
        val databaseKeyFile: String,
        /** The keystore entry that seals the database key. */
        val databaseKeyAlias: String,
        /** The API-key vault, relative to `noBackupFilesDir`. */
        val vaultFile: String,
        /** The keystore entry that seals the vault's values. */
        val vaultKeyAlias: String,
    )

    companion object {
        /** No note: the names of 0.5.2. */
        const val FIRST: Int = 1

        /** Far more than any phone will see; a larger number in the note is damage. */
        const val MAX: Int = 10_000

        /** The note's name for the app's database. */
        const val FILE_NAME: String = "database-keys/generation"

        private const val SCAN_DEPTH = 4
        private val GENERATION_IN_NAME = Regex("""\.v(\d{1,5})\.(?:db|json)(?:\.|$|-)""")

        /** Where the note of [databaseName] lives, relative to `noBackupFilesDir`. */
        fun fileFor(databaseName: String): String =
            if (databaseName == AiccDatabase.DEFAULT_NAME) FILE_NAME else "database-keys/$databaseName.generation"

        /** The names of [generation] of the database [databaseName]; generation 1 keeps the names of 0.5.2. */
        fun names(databaseName: String, generation: Int): Names {
            require(generation in FIRST..MAX) { "No such key generation: $generation." }
            val database = if (generation == FIRST) {
                databaseName
            } else if (databaseName.endsWith(".db")) {
                databaseName.dropLast(3) + ".v$generation.db"
            } else {
                "$databaseName.v$generation"
            }
            return Names(
                generation = generation,
                databaseFile = database,
                databaseKeyFile = DataLayer.databaseKeyFile(database),
                databaseKeyAlias = "aicc.database-key.v$generation",
                vaultFile = if (generation == FIRST) DataLayer.VAULT_FILE else "vault/secrets.v$generation.json",
                vaultKeyAlias = "aicc.secret-vault.v$generation",
            )
        }

        private fun parse(bytes: ByteArray): Int? {
            if (bytes.size > 16) return null
            val text = String(bytes, Charsets.US_ASCII).trim()
            if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
            return text.toIntOrNull()?.takeIf { it in FIRST..MAX }
        }
    }
}
