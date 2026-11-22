package com.aicontrolcenter.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.data.testing.InMemoryDatabaseRule
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * One row that cannot be read must not take its whole table down. Damaged rows are written
 * straight through the DAOs here, the way a later build, a downgrade or disk damage would
 * leave them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UnreadableRowsTest {
    @get:Rule val db = InMemoryDatabaseRule()

    @Test
    fun `an unreadable agent is absent from every read while the others stay usable`() = runTest {
        val repo = RoomAgentRepository(db.database)
        val good = Samples.agent(id = "agt_good", createdAt = 100)
        val damaged = Samples.agent(id = "agt_damaged", createdAt = 200)
        repo.upsert(good)
        db.database.agentDao().upsert(damaged.toEntity().copy(kind = "OVERLORD"))
        db.database.agentDao().upsert(Samples.agent(id = "agt_json", createdAt = 300).toEntity().copy(memoryJson = "{\"privateMemoryEnabled\": tru"))

        assertEquals(listOf(good), repo.getAll())
        assertEquals(listOf(good), repo.observeAll().first())
        assertNull(repo.get(damaged.id))
        assertNull(repo.observe(damaged.id).first())
        assertEquals(good, repo.get(good.id))

        // The damaged row is still stored: its stats can be read, and saving the agent again repairs it.
        repo.updateStats(damaged.id) { it.copy(tasksCompleted = 1) }
        assertEquals(AgentStats(tasksCompleted = 1), repo.getStats(damaged.id))
        repo.upsert(damaged)
        assertEquals(listOf(good, damaged), repo.getAll())
        assertEquals(AgentStats(tasksCompleted = 1), repo.getStats(damaged.id))
    }

    @Test
    fun `unreadable providers and models are skipped and can be deleted`() = runTest {
        val providers = RoomProviderRepository(db.database)
        val models = RoomModelRepository(db.database)
        val provider = Samples.provider(id = "prv_good")
        val model = Samples.model(id = "mdl_good", providerId = "prv_good", createdAt = 100)
        providers.upsert(provider)
        models.upsert(model)
        db.database.providerDao().upsert(Samples.provider(id = "prv_damaged", createdAt = 2_000).toEntity().copy(locality = "MOON"))
        db.database.modelDao().upsert(
            Samples.model(id = "mdl_damaged", providerId = "prv_good", createdAt = 200).toEntity().copy(capabilitiesJson = "[]"),
        )

        assertEquals(listOf(provider), providers.getAll())
        assertEquals(listOf(provider), providers.observeAll().first())
        assertNull(providers.get(Samples.provider(id = "prv_damaged").id))
        assertEquals(listOf(model), models.getAll())
        assertEquals(listOf(model), models.observeAll().first())
        assertEquals(listOf(model), models.getByProvider(provider.id))
        assertNull(models.get(Samples.model(id = "mdl_damaged").id))

        providers.delete(Samples.provider(id = "prv_damaged").id)
        models.delete(Samples.model(id = "mdl_damaged").id)
        assertEquals(1, db.database.providerDao().getAll().size)
        assertEquals(1, db.database.modelDao().getAll().size)
    }

    @Test
    fun `an unreadable app is skipped, also when it is the oldest entry of its package`() = runTest {
        val repo = RoomAiAppRepository(db.database)
        val good = Samples.aiApp(id = "app_good", packageName = "com.example.chatbot", firstSeenAt = 200)
        repo.upsert(good)
        db.database.aiAppDao().upsert(
            Samples.aiApp(id = "app_damaged", packageName = "com.example.chatbot", firstSeenAt = 100)
                .toEntity().copy(capabilitiesJson = """["TELEPATHY"]"""),
        )

        assertEquals(listOf(good), repo.getAll())
        assertEquals(listOf(good), repo.observeAll().first())
        assertEquals(good, repo.getByPackage("com.example.chatbot"))
        assertNull(repo.get(Samples.aiApp(id = "app_damaged").id))
    }

    @Test
    fun `an unreadable event is left out of the log and the rest keeps its order`() = runTest {
        val log = RoomActivityLog(db.database)
        log.record(Samples.event(id = "evt_1", at = 100))
        db.database.activityEventDao().insert(Samples.event(id = "evt_damaged", at = 200).toEntity().copy(type = "TELEPORT"))
        db.database.activityEventDao().insert(Samples.event(id = "evt_level", at = 250).toEntity().copy(severity = 15))
        log.record(Samples.event(id = "evt_3", at = 300))

        assertEquals(listOf("evt_3", "evt_1"), log.recent().map { it.id.value })
        assertEquals(listOf("evt_3", "evt_1"), log.observe().first().map { it.id.value })
        assertEquals(3, log.prune(olderThan = 300))
        assertEquals(listOf("evt_3"), log.recent().map { it.id.value })
    }
}
