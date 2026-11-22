package com.aicontrolcenter.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Puts off choosing the real open helper factory until the database is first opened.
 *
 * Room asks its factory for an open helper while the database object is being built, which the
 * composition root may well do on the main thread. The database itself is opened later, by the
 * first query, on a query thread. Providing the encrypted factory needs the keystore, a file
 * and a native library; with this wrapper all of that happens at open time, off the main
 * thread, and a failure reaches the query that needed the database instead of app start-up.
 *
 * [actual] is called again on the next open if it threw, so a temporary failure (a busy
 * keystore) does not disable the database for the rest of the process.
 */
internal class DeferredOpenHelperFactory(
    private val actual: () -> SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        DeferredOpenHelper(configuration, actual)
}

private class DeferredOpenHelper(
    private val configuration: SupportSQLiteOpenHelper.Configuration,
    private val actual: () -> SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper {
    private val lock = Any()
    private var opened: SupportSQLiteOpenHelper? = null

    /** Room chooses the journal mode before the first open; remembered until there is a helper to tell. */
    private var writeAheadLogging: Boolean? = null

    override val databaseName: String? get() = configuration.name

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        synchronized(lock) {
            writeAheadLogging = enabled
            opened?.setWriteAheadLoggingEnabled(enabled)
        }
    }

    override val writableDatabase: SupportSQLiteDatabase get() = helper().writableDatabase

    override val readableDatabase: SupportSQLiteDatabase get() = helper().readableDatabase

    override fun close() {
        synchronized(lock) { opened?.close() }
    }

    private fun helper(): SupportSQLiteOpenHelper = synchronized(lock) {
        opened ?: actual().create(configuration).also { helper ->
            writeAheadLogging?.let(helper::setWriteAheadLoggingEnabled)
            opened = helper
        }
    }
}
