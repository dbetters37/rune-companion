package com.aicontrolcenter.data.recovery

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.security.SecretVaultException
import com.aicontrolcenter.data.security.DatabaseKeyException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/*
 * The data layer's side of key generations (Phase 6, WP2, rule 9): the database file and the vault of the generation in
 * use are chosen when they are first opened, off the main thread, never when the data layer is built (which may happen
 * on the main thread and must touch no file).
 */

/**
 * Set by Start over once the old generation's files are being set aside: from then on this process opens no database
 * through the data layer (the app restarts next, rule 30). Without it a late query could create an empty file under the
 * old generation's name.
 */
internal class SetAside {
    @Volatile
    var done: Boolean = false
        private set

    fun mark() {
        done = true
    }

    fun check() {
        if (done) throw IllegalStateException(SET_ASIDE)
    }

    companion object {
        const val SET_ASIDE: String = "The app's data was set aside; the app starts again with a fresh start."
    }
}

/**
 * Opens [fileName] (the database file of the generation in use) whatever name Room was built with, through [inner], and
 * refuses every open once [setAside] is marked.
 */
internal class GenerationOpenHelperFactory(
    private val fileName: String,
    private val inner: SupportSQLiteOpenHelper.Factory,
    private val setAside: SetAside,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val named = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(fileName)
            .callback(KeepOnCorruption(configuration.callback))
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
            .build()
        return GuardedOpenHelper(inner.create(named), setAside)
    }
}

/**
 * [inner], except that a damaged file is never deleted (rule 9: only the owner's Start over sets data aside). The
 * platform's helper calls [onCorruption], whose default deletes the file; SQLCipher's own handler already keeps a file it
 * cannot read. The open then fails, and the Recovery screen shows instead.
 */
private class KeepOnCorruption(private val inner: SupportSQLiteOpenHelper.Callback) : SupportSQLiteOpenHelper.Callback(inner.version) {
    override fun onConfigure(db: SupportSQLiteDatabase) = inner.onConfigure(db)

    override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = inner.onUpgrade(db, oldVersion, newVersion)

    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = inner.onDowngrade(db, oldVersion, newVersion)

    override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)

    override fun onCorruption(db: SupportSQLiteDatabase) {
        // Kept as it is: never deleted here.
    }
}

/** [inner], except that it opens nothing once the data was set aside. */
private class GuardedOpenHelper(private val inner: SupportSQLiteOpenHelper, private val setAside: SetAside) : SupportSQLiteOpenHelper by inner {
    override val writableDatabase: SupportSQLiteDatabase
        get() {
            setAside.check()
            return inner.writableDatabase
        }

    override val readableDatabase: SupportSQLiteDatabase
        get() {
            setAside.check()
            return inner.readableDatabase
        }
}

/**
 * The API-key vault of the generation in use, chosen on first use (on [io]): [open] reads the generation note and builds
 * the vault over that generation's file and keystore entry. A failure to choose (a damaged note) is a vault error on
 * that call, and the next call tries again. Holds only what [open] captures: the no-backup folder, the generation note
 * and the cipher maker, never the data layer.
 */
internal class GenerationSecretVault(
    private val io: CoroutineDispatcher,
    private val setAside: SetAside,
    private val open: () -> SecretVault,
) : SecretVault {
    @Volatile
    private var opened: SecretVault? = null
    private val lock = Any()

    private suspend fun vault(): SecretVault = opened ?: withContext(io) {
        synchronized(lock) {
            opened ?: run {
                if (setAside.done) throw SecretVaultException(SetAside.SET_ASIDE)
                try {
                    open()
                } catch (failure: DatabaseKeyException) {
                    throw SecretVaultException("The secret vault could not be opened.", failure)
                }
            }.also { opened = it }
        }
    }

    override suspend fun put(alias: String, secret: String) = vault().put(alias, secret)

    override suspend fun get(alias: String): String? = vault().get(alias)

    override suspend fun contains(alias: String): Boolean = vault().contains(alias)

    override suspend fun delete(alias: String) = vault().delete(alias)

    override suspend fun aliases(): Set<String> = vault().aliases()
}
