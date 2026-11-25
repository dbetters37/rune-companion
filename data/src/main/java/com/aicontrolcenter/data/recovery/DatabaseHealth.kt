package com.aicontrolcenter.data.recovery

import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.data.security.DatabaseKeyException
import com.aicontrolcenter.data.security.KeyProblem
import kotlinx.coroutines.CancellationException

/**
 * Whether the app's database opens, and if not, why (Phase 6, R5, F10): the check behind the app's gate (the Recovery
 * screen shows instead of a stuck app) and behind the upkeep job (which does nothing while it is not OK).
 *
 * [check] opens the database once through [open] and sorts any failure into a [DatabaseState]: a key that is missing,
 * damaged or whose keystore did not answer ([DatabaseKeyException.problem]); a file that is not a database or is
 * damaged; an update of the stored data (a Room migration) that failed; anything else. It never changes, creates or
 * deletes anything itself: opening is all it does, and opening never replaces a key (`DatabaseKeyStore`).
 *
 * @param open opens the database for writing, on a background thread (the data layer's own check), or reads one row
 *   through a store (the upkeep job's), which opens it the same way.
 */
class DatabaseHealth(private val open: suspend () -> Unit) {

    /** Opens the database once and says whether it worked. Cancellation is passed on; errors (not exceptions) too. */
    suspend fun check(): DatabaseState =
        try {
            open()
            DatabaseState.OK
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            classify(failure)
        }

    companion object {
        /** How deep a chain of causes is followed. */
        private const val MAX_CAUSES = 12

        /**
         * The state a failure to open the database stands for. Looks through the whole chain of causes, since Room and
         * coroutines may wrap the original exception. Only types, Room's and SQLite's own fixed messages and stack
         * frames are read, never stored or shown: the owner sees [DatabaseState.ownerText] alone.
         */
        fun classify(failure: Throwable): DatabaseState {
            val chain = generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).toList()
            chain.firstNotNullOfOrNull { it as? DatabaseKeyException }?.let { return keyState(it.problem) }
            if (chain.any(::isNotADatabase)) return DatabaseState.NOT_A_DATABASE
            if (chain.any(::isMigration)) return DatabaseState.MIGRATION_FAILED
            return DatabaseState.OTHER
        }

        private fun keyState(problem: KeyProblem): DatabaseState = when (problem) {
            KeyProblem.MISSING -> DatabaseState.KEY_MISSING
            KeyProblem.DAMAGED -> DatabaseState.KEY_DAMAGED
            KeyProblem.KEYSTORE_UNAVAILABLE -> DatabaseState.KEYSTORE_UNAVAILABLE
            KeyProblem.OTHER -> DatabaseState.OTHER
        }

        /**
         * SQLite's "file is not a database" (also what SQLCipher says for a file that does not open with its key) and
         * "database disk image is malformed": the file itself is damaged. Both the platform's and SQLCipher's classes
         * are named `SQLiteNotADatabaseException` / `SQLiteDatabaseCorruptException`.
         */
        private fun isNotADatabase(failure: Throwable): Boolean {
            val name = failure.javaClass.simpleName
            if (name == "SQLiteNotADatabaseException" || name == "SQLiteDatabaseCorruptException") return true
            val message = failure.message?.lowercase() ?: return false
            return "file is not a database" in message || "database disk image is malformed" in message ||
                "(code 26" in message || "sqlite_notadb" in message
        }

        /**
         * Room's own failures to update the stored data ("A migration from 5 to 6 was required but not found",
         * "Migration didn't properly handle …", a newer version's data), or any failure thrown while a migration ran.
         */
        private fun isMigration(failure: Throwable): Boolean {
            if (failure.message?.contains("migration", ignoreCase = true) == true) return true
            return failure.stackTrace.any { frame ->
                frame.methodName in MIGRATION_METHODS || (frame.methodName == "migrate" && frame.className.contains("Migration"))
            }
        }

        /** Where Room and SQLite run migrations, whatever wraps them. */
        private val MIGRATION_METHODS = setOf("onUpgrade", "onDowngrade", "onMigrate")
    }
}
