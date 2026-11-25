package com.aicontrolcenter.data.recovery

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.data.security.DatabaseKeyException
import com.aicontrolcenter.data.security.KeyProblem
import com.aicontrolcenter.data.security.SoftwareVaultCipher
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The database check behind the gate and the upkeep job (Phase 6, R5): every way the data can fail to open, told apart. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DatabaseHealthTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val open = mutableListOf<DataLayer>()

    private fun plain(): DataLayer =
        DataLayer(context, databaseEncryption = DatabaseEncryption.None, vaultCipher = SoftwareVaultCipher()).also(open::add)

    @After
    fun tearDown() = runBlocking { open.forEach { it.close() } }

    private fun stateOf(failure: Exception): DatabaseState = runBlocking { DatabaseHealth { throw failure }.check() }

    @Test
    fun `each key problem has its own state, also when wrapped`() {
        assertEquals(DatabaseState.KEY_MISSING, stateOf(DatabaseKeyException("x", problem = KeyProblem.MISSING)))
        assertEquals(DatabaseState.KEY_DAMAGED, stateOf(DatabaseKeyException("x", problem = KeyProblem.DAMAGED)))
        assertEquals(DatabaseState.KEYSTORE_UNAVAILABLE, stateOf(DatabaseKeyException("x", problem = KeyProblem.KEYSTORE_UNAVAILABLE)))
        assertEquals(DatabaseState.OTHER, stateOf(DatabaseKeyException("x", problem = KeyProblem.OTHER)))
        assertEquals(DatabaseState.KEY_DAMAGED, stateOf(RuntimeException("query failed", DatabaseKeyException("x", problem = KeyProblem.DAMAGED))))
    }

    @Test
    fun `SQLite's and Room's own failures are sorted by what they say`() {
        assertEquals(DatabaseState.NOT_A_DATABASE, stateOf(SQLiteException("file is not a database (code 26 SQLITE_NOTADB)")))
        assertEquals(DatabaseState.NOT_A_DATABASE, stateOf(SQLiteException("database disk image is malformed (code 11 SQLITE_CORRUPT)")))
        assertEquals(DatabaseState.MIGRATION_FAILED, stateOf(IllegalStateException("A migration from 7 to 6 was required but not found.")))
        val inMigration = SQLiteException("no such table: tasks").apply {
            stackTrace = arrayOf(StackTraceElement("com.aicontrolcenter.data.db.Migration5To6", "migrate", "AiccMigrations.kt", 1))
        }
        assertEquals(DatabaseState.MIGRATION_FAILED, stateOf(inMigration))
        assertEquals(DatabaseState.OTHER, stateOf(IOException("disk went away")))
        assertEquals(DatabaseState.OK, runBlocking { DatabaseHealth { }.check() })
    }

    @Test
    fun `a cancelled check is cancelled, not a broken database`() = runTest {
        assertFailsWith<CancellationException> { DatabaseHealth { throw CancellationException("gone") }.check() }
    }

    @Test
    fun `a healthy database is OK and stays usable after the check`() = runTest {
        val data = plain()
        assertEquals(DatabaseState.OK, data.databaseHealth.check())
        data.agents.upsert(Samples.agent())
        assertEquals(1, data.agents.getAll().size)
    }

    @Test
    fun `a file that is not a database is reported as damaged, and nothing replaces it`() = runTest {
        val file = context.getDatabasePath("aicc.db").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8192) { (it % 251).toByte() }) }
        val before = file.readBytes()
        assertEquals(DatabaseState.NOT_A_DATABASE, plain().databaseHealth.check())
        assertEquals(before.toList(), file.readBytes().toList(), "the check changes nothing")
    }

    @Test
    fun `data a newer version wrote, and an update that fails half-way, are migration failures`() = runTest {
        val file = context.getDatabasePath("aicc.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE notes (id TEXT)")
            db.version = 99
        }
        assertEquals(DatabaseState.MIGRATION_FAILED, plain().databaseHealth.check())
        tearDown()
        open.clear()
        file.delete()
        // A version-5 file without its tables: Migration5To6's first ALTER TABLE fails.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE notes (id TEXT)")
            db.version = 5
        }
        assertEquals(DatabaseState.MIGRATION_FAILED, plain().databaseHealth.check())
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(5, db.version, "a failed update leaves the stored version as it was")
        }
    }
}
