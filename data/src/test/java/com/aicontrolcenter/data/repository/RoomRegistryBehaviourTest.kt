package com.aicontrolcenter.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.testing.InMemoryDatabaseRule
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Behaviour of the Room registry repositories beyond the shared contract suites. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomRegistryBehaviourTest {
    @get:Rule val db = InMemoryDatabaseRule()

    @Test
    fun `agent list re-emits on insert, change and delete`() = runTest {
        val repo = RoomAgentRepository(db.database)
        val first = Samples.agent(id = "agt_1", name = "One", createdAt = 100)
        val second = Samples.agent(id = "agt_2", name = "Two", createdAt = 200)
        repo.observeAll().test {
            assertEquals(emptyList(), awaitItem())
            repo.upsert(second)
            assertEquals(listOf(second), awaitItem())
            repo.upsert(first)
            assertEquals(listOf(first, second), awaitItem())
            val renamed = first.copy(name = "Uno")
            repo.upsert(renamed)
            assertEquals(listOf(renamed, second), awaitItem())
            repo.delete(second.id)
            assertEquals(listOf(renamed), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a single agent is observed through changes and removal, ignoring other agents`() = runTest {
        val repo = RoomAgentRepository(db.database)
        val watched = Samples.agent(id = "agt_watched")
        repo.observe(watched.id).test {
            assertNull(awaitItem())
            repo.upsert(watched)
            assertEquals(watched, awaitItem())
            // A write to another agent invalidates the table but must not produce a duplicate.
            repo.upsert(Samples.agent(id = "agt_other", name = "Other"))
            val disabled = watched.copy(enabled = false)
            repo.upsert(disabled)
            assertEquals(disabled, awaitItem())
            repo.delete(watched.id)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stats map re-emits on update and drops an agent when it is deleted`() = runTest {
        val repo = RoomAgentRepository(db.database)
        val a = AgentId("agt_a")
        val b = AgentId("agt_b")
        repo.upsert(Samples.agent(id = a.value))
        repo.upsert(Samples.agent(id = b.value))
        repo.observeStats().test {
            assertEquals(emptyMap(), awaitItem())
            repo.updateStats(a) { it.copy(tasksAssigned = 1) }
            assertEquals(mapOf(a to AgentStats(tasksAssigned = 1)), awaitItem())
            repo.updateStats(b) { it.copy(modelCalls = 3) }
            assertEquals(mapOf(a to AgentStats(tasksAssigned = 1), b to AgentStats(modelCalls = 3)), awaitItem())
            repo.delete(a)
            assertEquals(mapOf(b to AgentStats(modelCalls = 3)), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stats for an agent that does not exist are not written and the transform is not run`() = runTest {
        val repo = RoomAgentRepository(db.database)
        val ghost = AgentId("agt_ghost")
        var transformRuns = 0
        repo.observeStats().test {
            assertEquals(emptyMap(), awaitItem())
            repo.updateStats(ghost) { transformRuns++; it.copy(tasksCompleted = 1) }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, transformRuns)
        assertEquals(AgentStats(), repo.getStats(ghost))

        // Stats reported after an agent was deleted do not bring a row back either.
        val agent = Samples.agent()
        repo.upsert(agent)
        repo.updateStats(agent.id) { it.copy(tasksCompleted = 1) }
        repo.delete(agent.id)
        repo.updateStats(agent.id) { it.copy(tasksCompleted = it.tasksCompleted + 1) }
        assertEquals(emptyMap(), repo.observeStats().first())

        // An agent created again under the same id starts from zero.
        repo.upsert(agent)
        assertEquals(AgentStats(), repo.getStats(agent.id))
    }

    @Test
    fun `stats updates racing with the agent's deletion never leave stats behind`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = AiccDatabase.create(context, name = "stats-delete-race.db")
        try {
            val repo = RoomAgentRepository(database)
            repeat(20) { round ->
                val agent = Samples.agent(id = "agt_$round")
                repo.upsert(agent)
                coroutineScope {
                    repeat(4) {
                        launch(Dispatchers.Default) {
                            repeat(5) { repo.updateStats(agent.id) { it.copy(toolCalls = it.toolCalls + 1) } }
                        }
                    }
                    launch(Dispatchers.Default) { repo.delete(agent.id) }
                }
            }
            assertEquals(emptyMap(), repo.observeStats().first())
        } finally {
            database.close()
        }
    }

    @Test
    fun `deleting an agent leaves other agents and their stats alone`() = runTest {
        val repo = RoomAgentRepository(db.database)
        val kept = Samples.agent(id = "agt_kept", name = "Kept")
        val removed = Samples.agent(id = "agt_removed", name = "Removed")
        repo.upsert(kept)
        repo.upsert(removed)
        repo.updateStats(kept.id) { it.copy(tasksCompleted = 2) }
        repo.updateStats(removed.id) { it.copy(tasksCompleted = 9) }

        repo.delete(removed.id)

        assertEquals(listOf(kept), repo.getAll())
        assertEquals(AgentStats(tasksCompleted = 2), repo.getStats(kept.id))
        assertEquals(AgentStats(), repo.getStats(removed.id))
    }

    @Test
    fun `replacing a profile keeps the agent's stats`() = runTest {
        val repo = RoomAgentRepository(db.database)
        val agent = Samples.agent()
        repo.upsert(agent)
        repo.updateStats(agent.id) { it.copy(tasksCompleted = 5) }
        repo.upsert(agent.copy(name = "Renamed"))
        assertEquals(AgentStats(tasksCompleted = 5), repo.getStats(agent.id))
    }

    @Test
    fun `a failing stats transform changes nothing and reaches the caller`() = runTest {
        val repo = RoomAgentRepository(db.database)
        val id = AgentId("agt_a")
        repo.upsert(Samples.agent(id = id.value))
        repo.updateStats(id) { it.copy(toolCalls = 4) }
        val failure = assertFailsWith<IllegalStateException> {
            repo.updateStats(id) { error("transform failed") }
        }
        assertEquals("transform failed", failure.message)
        assertEquals(AgentStats(toolCalls = 4), repo.getStats(id))
        // The failed transaction did not leave the database locked.
        repo.updateStats(id) { it.copy(toolCalls = it.toolCalls + 1) }
        assertEquals(AgentStats(toolCalls = 5), repo.getStats(id))
    }

    @Test
    fun `concurrent stats updates are all applied`() = runTest {
        // File-backed, so that the test runs with write-ahead logging and several connections,
        // as the app does. An in-memory database has a single connection and would hide races.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = AiccDatabase.create(context, name = "concurrent-stats.db")
        try {
            val repo = RoomAgentRepository(database)
            val id = AgentId("agt_busy")
            repo.upsert(Samples.agent(id = id.value))
            val writers = 8
            val updatesPerWriter = 25
            coroutineScope {
                repeat(writers) {
                    launch(Dispatchers.Default) {
                        repeat(updatesPerWriter) {
                            repo.updateStats(id) { it.copy(modelCalls = it.modelCalls + 1, inputTokens = it.inputTokens + 10) }
                        }
                    }
                }
            }
            val total = writers * updatesPerWriter
            assertEquals(AgentStats(modelCalls = total, inputTokens = total * 10L), repo.getStats(id))
        } finally {
            database.close()
        }
    }

    @Test
    fun `provider list re-emits on change and delete`() = runTest {
        val repo = RoomProviderRepository(db.database)
        val provider = Samples.provider()
        repo.observeAll().test {
            assertEquals(emptyList(), awaitItem())
            repo.upsert(provider)
            assertEquals(listOf(provider), awaitItem())
            val offline = provider.copy(status = ConnectionStatus.OFFLINE, statusMessage = null)
            repo.upsert(offline)
            assertEquals(listOf(offline), awaitItem())
            repo.delete(provider.id)
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting a provider does not silently delete its models`() = runTest {
        // The port makes removing a provider's models an explicit second step.
        val providers = RoomProviderRepository(db.database)
        val models = RoomModelRepository(db.database)
        providers.upsert(Samples.provider(id = "prv_a"))
        models.upsert(Samples.model(id = "mdl_1", providerId = "prv_a"))
        providers.delete(ProviderId("prv_a"))
        assertEquals(listOf("mdl_1"), models.getAll().map { it.id.value })
        models.deleteByProvider(ProviderId("prv_a"))
        assertEquals(emptyList(), models.getAll())
    }

    @Test
    fun `model list re-emits once for a whole listing and on deletes`() = runTest {
        val repo = RoomModelRepository(db.database)
        val listing = listOf(
            Samples.model(id = "mdl_1", providerId = "prv_a", createdAt = 100),
            Samples.model(id = "mdl_2", providerId = "prv_a", createdAt = 200),
            Samples.model(id = "mdl_3", providerId = "prv_b", createdAt = 300),
        )
        repo.observeAll().test {
            assertEquals(emptyList(), awaitItem())
            repo.upsertAll(listing)
            assertEquals(listing, awaitItem())
            repo.delete(ModelId("mdl_2"))
            assertEquals(listOf(listing[0], listing[2]), awaitItem())
            repo.deleteByProvider(ProviderId("prv_a"))
            assertEquals(listOf(listing[2]), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `models with the same creation time are ordered by id`() = runTest {
        val repo = RoomModelRepository(db.database)
        repo.upsertAll(listOf(Samples.model(id = "mdl_b"), Samples.model(id = "mdl_c"), Samples.model(id = "mdl_a")))
        assertEquals(listOf("mdl_a", "mdl_b", "mdl_c"), repo.getAll().map { it.id.value })
        assertEquals(listOf("mdl_a", "mdl_b", "mdl_c"), repo.getByProvider(ProviderId("prv_sample")).map { it.id.value })
    }

    @Test
    fun `app list re-emits on change and delete`() = runTest {
        val repo = RoomAiAppRepository(db.database)
        val app = Samples.aiApp()
        repo.observeAll().test {
            assertEquals(emptyList(), awaitItem())
            repo.upsert(app)
            assertEquals(listOf(app), awaitItem())
            val revoked = app.copy(accessibilityAuthorized = false)
            repo.upsert(revoked)
            assertEquals(listOf(revoked), awaitItem())
            repo.delete(app.id)
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lookup by package returns the oldest entry when a package was stored twice`() = runTest {
        val repo = RoomAiAppRepository(db.database)
        repo.upsert(Samples.aiApp(id = "app_new", packageName = "same.pkg", firstSeenAt = 500))
        repo.upsert(Samples.aiApp(id = "app_old", packageName = "same.pkg", firstSeenAt = 100))
        assertEquals(AiAppId("app_old"), repo.getByPackage("same.pkg")?.id)
        repo.delete(AiAppId("app_old"))
        assertEquals(AiAppId("app_new"), repo.getByPackage("same.pkg")?.id)
    }
}
