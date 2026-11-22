package com.aicontrolcenter.data.db

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.data.repository.RoomAgentRepository
import com.aicontrolcenter.data.security.DatabaseKeyException
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The wrapper that lets the encrypted open helper be chosen when the database is opened
 * instead of when it is built. The platform's SQLite stands in for SQLCipher here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DeferredOpenHelperFactoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val platform = FrameworkSQLiteOpenHelperFactory()

    private fun journalMode(database: AiccDatabase): String =
        database.openHelper.writableDatabase.query(SimpleSQLiteQuery("PRAGMA journal_mode")).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    @Test
    fun `the real factory is asked once, at the first use of the database and on the query thread`() = runTest {
        val io = Executors.newSingleThreadExecutor { task -> Thread(task, "data-io") }.asCoroutineDispatcher()
        val askedOn = Collections.synchronizedList(mutableListOf<String>())
        val deferred = DeferredOpenHelperFactory {
            askedOn += Thread.currentThread().name.substringBefore(" @")
            platform
        }
        val database = AiccDatabase.create(context, "deferred.db", deferred, io)
        try {
            val agents = RoomAgentRepository(database, io)
            assertEquals(emptyList(), askedOn, "building the database must not open it")

            agents.upsert(Samples.agent())
            assertEquals(listOf(Samples.agent()), agents.getAll())
            assertEquals(listOf("data-io"), askedOn)
            assertEquals("deferred.db", database.openHelper.databaseName)
        } finally {
            database.close()
            io.close()
        }
    }

    @Test
    fun `the database behaves as with the real factory - same journal mode, data kept across reopening`() = runTest {
        val direct = AiccDatabase.create(context, "direct.db", platform)
        val expectedJournalMode = try {
            journalMode(direct)
        } finally {
            direct.close()
        }

        val first = AiccDatabase.create(context, "deferred-journal.db", DeferredOpenHelperFactory { platform })
        try {
            RoomAgentRepository(first).upsert(Samples.agent())
            // Room picks the journal mode before the first open; the wrapper has to pass it on.
            assertEquals(expectedJournalMode, journalMode(first))
        } finally {
            first.close()
        }

        val second = AiccDatabase.create(context, "deferred-journal.db", DeferredOpenHelperFactory { platform })
        try {
            assertEquals(listOf(Samples.agent()), RoomAgentRepository(second).getAll())
        } finally {
            second.close()
        }
    }

    @Test
    fun `a failure to provide the factory fails the query, and the next query tries again`() = runTest {
        var attempts = 0
        val deferred = DeferredOpenHelperFactory {
            if (++attempts == 1) throw DatabaseKeyException("keystore busy")
            platform
        }
        val database = AiccDatabase.create(context, "deferred-retry.db", deferred)
        try {
            val agents = RoomAgentRepository(database)
            val failure = assertFailsWith<DatabaseKeyException> { agents.getAll() }
            assertEquals("keystore busy", failure.message)

            agents.upsert(Samples.agent())
            assertEquals(listOf(Samples.agent()), agents.getAll())
            assertEquals(2, attempts)
        } finally {
            database.close()
        }
    }

    @Test
    fun `closing a database that was never opened does not ask for the factory`() {
        var asked = false
        val factory: SupportSQLiteOpenHelper.Factory = DeferredOpenHelperFactory {
            asked = true
            platform
        }
        AiccDatabase.create(context, "deferred-unused.db", factory).close()
        assertEquals(false, asked)
    }
}
