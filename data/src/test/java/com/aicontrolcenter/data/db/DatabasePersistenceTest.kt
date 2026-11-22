package com.aicontrolcenter.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.data.repository.RoomActivityLog
import com.aicontrolcenter.data.repository.RoomAgentRepository
import com.aicontrolcenter.data.repository.RoomAiAppRepository
import com.aicontrolcenter.data.repository.RoomModelRepository
import com.aicontrolcenter.data.repository.RoomProviderRepository
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A file-backed database keeps everything across close and reopen, as it must across app restarts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DatabasePersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `everything written is there after closing and reopening the database file`() = runTest {
        val agent = Samples.agent()
        val stats = AgentStats(tasksAssigned = 3, tasksCompleted = 2, outputTokens = 6_000_000_000, lastActiveAt = 42)
        val provider = Samples.provider()
        val model = Samples.model()
        val app = Samples.aiApp()
        val events = listOf(Samples.event(id = "evt_1", at = 100), Samples.event(id = "evt_2", at = 200, egress = false))

        val first = AiccDatabase.create(context, name = NAME)
        RoomAgentRepository(first).run {
            upsert(agent)
            updateStats(agent.id) { stats }
        }
        RoomProviderRepository(first).upsert(provider)
        RoomModelRepository(first).upsert(model)
        RoomAiAppRepository(first).upsert(app)
        RoomActivityLog(first).run { events.forEach { record(it) } }
        first.close()

        assertTrue(context.getDatabasePath(NAME).isFile, "the database must be a real file in app-private storage")

        val reopened = AiccDatabase.create(context, name = NAME)
        try {
            val agents = RoomAgentRepository(reopened)
            assertEquals(listOf(agent), agents.getAll())
            assertEquals(stats, agents.getStats(agent.id))
            assertEquals(listOf(provider), RoomProviderRepository(reopened).getAll())
            assertEquals(listOf(model), RoomModelRepository(reopened).getAll())
            assertEquals(listOf(app), RoomAiAppRepository(reopened).getAll())
            val log = RoomActivityLog(reopened)
            assertEquals(events.reversed(), log.recent())

            // The insertion sequence continues after a restart, so ordering of equal timestamps holds.
            log.record(Samples.event(id = "evt_3", at = 200))
            assertEquals(listOf("evt_3", "evt_2", "evt_1"), log.recent().map { it.id.value })
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `databases with different names are independent`() = runTest {
        val one = AiccDatabase.create(context, name = "one.db")
        val two = AiccDatabase.create(context, name = "two.db")
        try {
            RoomAgentRepository(one).upsert(Samples.agent())
            assertEquals(emptyList(), RoomAgentRepository(two).getAll())
        } finally {
            one.close()
            two.close()
        }
    }

    @Test
    fun `a supplied open helper factory opens the database`() = runTest {
        // The hook the encrypting SQLCipher helper is plugged into; here it only records the call.
        val opened = mutableListOf<String?>()
        val platform = FrameworkSQLiteOpenHelperFactory()
        val recording = SupportSQLiteOpenHelper.Factory { configuration ->
            opened += configuration.name
            platform.create(configuration)
        }

        val database = AiccDatabase.create(context, name = "custom-helper.db", openHelperFactory = recording)
        try {
            RoomAgentRepository(database).upsert(Samples.agent())
            assertEquals(listOf(Samples.agent()), RoomAgentRepository(database).getAll())
        } finally {
            database.close()
        }
        assertEquals(listOf<String?>("custom-helper.db"), opened)
    }

    @Test
    fun `an in-memory database starts empty every time`() = runTest {
        val first = AiccDatabase.createInMemory(context)
        RoomAgentRepository(first).upsert(Samples.agent())
        first.close()

        val second = AiccDatabase.createInMemory(context)
        try {
            assertEquals(emptyList(), RoomAgentRepository(second).getAll())
        } finally {
            second.close()
        }
    }

    private companion object {
        const val NAME = "persistence-test.db"
    }
}
