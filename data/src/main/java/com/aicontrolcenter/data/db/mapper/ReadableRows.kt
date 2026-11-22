package com.aicontrolcenter.data.db.mapper

import android.util.Log
import com.aicontrolcenter.data.StoredDataException
import java.util.concurrent.ConcurrentHashMap

/*
 * How the repositories read rows: a row that cannot be turned into a domain object (a JSON
 * column that no longer parses, an enum name this build does not know) is treated as absent
 * and reported in the log, instead of failing the read.
 *
 * Without this, one damaged record would make every list of its table throw, on every launch,
 * and nothing in the app could show or repair anything. Treated as absent, the rest of the
 * table stays usable, and the damaged row stays in the database untouched until something is
 * saved or deleted under its id.
 */

/** The readable rows, in order. */
internal inline fun <E, D : Any> List<E>.mapReadable(toDomain: (E) -> D): List<D> =
    mapNotNull { row -> row.readableOrNull(toDomain) }

/** The row as a domain object, or null when it cannot be read. */
internal inline fun <E, D : Any> E.readableOrNull(toDomain: (E) -> D): D? =
    try {
        toDomain(this)
    } catch (unreadable: StoredDataException) {
        UnreadableRows.report(unreadable)
        null
    }

/** Logs each unreadable value once per process; lists are re-read on every change of their table. */
internal object UnreadableRows {
    private const val TAG = "AiccDatabase"
    private const val MAX_REMEMBERED = 200
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** The message of a [StoredDataException] names table, column and row, not the stored content. */
    fun report(unreadable: StoredDataException) {
        val message = unreadable.message ?: return
        if (reported.size < MAX_REMEMBERED && reported.add(message)) {
            Log.w(TAG, "Skipping a stored row that cannot be read. $message")
        }
    }
}
