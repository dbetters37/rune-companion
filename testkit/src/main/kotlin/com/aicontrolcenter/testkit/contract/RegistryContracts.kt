package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.registry.AiAppRepository
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Behaviour every implementation of a registry port must have. Subclass in the implementing
 * module and provide a fresh, empty repository per test.
 */

abstract class AgentRepositoryContract {
    protected abstract fun createRepository(): AgentRepository

    @Test
    fun `stores and returns every field of an agent`() = runTest {
        val repo = createRepository()
        val agent = Samples.agent()
        repo.upsert(agent)
        assertEquals(agent, repo.get(agent.id))
        assertEquals(listOf(agent), repo.getAll())
        assertEquals(listOf(agent), repo.observeAll().first())
        assertEquals(agent, repo.observe(agent.id).first())
    }

    @Test
    fun `stores an agent without a model and with empty collections`() = runTest {
        val repo = createRepository()
        val bare = Samples.agent(modelId = null).copy(
            fallbackModelIds = emptyList(), responsibilities = emptyList(), skills = emptySet(),
            toolIds = emptySet(), templateId = null,
        )
        repo.upsert(bare)
        assertEquals(bare, repo.get(bare.id))
    }

    @Test
    fun `upsert replaces an existing agent`() = runTest {
        val repo = createRepository()
        val agent = Samples.agent()
        repo.upsert(agent)
        val renamed = agent.copy(name = "Renamed", enabled = false, updatedAt = 9_999)
        repo.upsert(renamed)
        assertEquals(listOf(renamed), repo.getAll())
    }

    @Test
    fun `lists agents oldest first`() = runTest {
        val repo = createRepository()
        repo.upsert(Samples.agent(id = "agt_c", name = "C", createdAt = 300))
        repo.upsert(Samples.agent(id = "agt_a", name = "A", createdAt = 100))
        repo.upsert(Samples.agent(id = "agt_b", name = "B", createdAt = 200))
        assertEquals(listOf("A", "B", "C"), repo.getAll().map { it.name })
        assertEquals(listOf("A", "B", "C"), repo.observeAll().first().map { it.name })
    }

    @Test
    fun `missing agent is null`() = runTest {
        val repo = createRepository()
        assertNull(repo.get(AgentId("agt_missing")))
        assertNull(repo.observe(AgentId("agt_missing")).first())
        assertTrue(repo.getAll().isEmpty())
    }

    @Test
    fun `delete removes the agent and its stats`() = runTest {
        val repo = createRepository()
        val agent = Samples.agent()
        repo.upsert(agent)
        repo.updateStats(agent.id) { it.copy(tasksCompleted = 4) }
        repo.delete(agent.id)
        assertNull(repo.get(agent.id))
        assertEquals(AgentStats(), repo.getStats(agent.id))
        assertTrue(agent.id !in repo.observeStats().first())
        repo.delete(agent.id) // deleting again is a no-op
    }

    @Test
    fun `stats start at zero and accumulate`() = runTest {
        val repo = createRepository()
        val agent = Samples.agent()
        repo.upsert(agent)
        assertEquals(AgentStats(), repo.getStats(agent.id))
        repo.updateStats(agent.id) { it.copy(tasksAssigned = it.tasksAssigned + 1, inputTokens = it.inputTokens + 50) }
        repo.updateStats(agent.id) {
            it.copy(
                tasksAssigned = it.tasksAssigned + 1, tasksCompleted = 1, tasksFailed = 2, tasksTimedOut = 3,
                tasksCancelled = 4, reviewsPassed = 5, revisionsRequested = 6, reviewsRejected = 7, modelCalls = 8,
                modelErrors = 9, toolCalls = 10, toolErrors = 11, inputTokens = it.inputTokens + 50,
                outputTokens = 6_000_000_000, totalTaskDurationMs = 123_456, lastActiveAt = 777,
            )
        }
        val expected = AgentStats(
            tasksAssigned = 2, tasksCompleted = 1, tasksFailed = 2, tasksTimedOut = 3, tasksCancelled = 4,
            reviewsPassed = 5, revisionsRequested = 6, reviewsRejected = 7, modelCalls = 8, modelErrors = 9,
            toolCalls = 10, toolErrors = 11, inputTokens = 100, outputTokens = 6_000_000_000,
            totalTaskDurationMs = 123_456, lastActiveAt = 777,
        )
        assertEquals(expected, repo.getStats(agent.id))
        assertEquals(expected, repo.observeStats().first()[agent.id])
    }
}

abstract class ProviderRepositoryContract {
    protected abstract fun createRepository(): ProviderRepository

    @Test
    fun `stores and returns every field of a provider`() = runTest {
        val repo = createRepository()
        val provider = Samples.provider()
        repo.upsert(provider)
        assertEquals(provider, repo.get(provider.id))
        assertEquals(listOf(provider), repo.observeAll().first())
    }

    @Test
    fun `stores a provider with no url, key, headers or check`() = runTest {
        val repo = createRepository()
        val bare = Samples.provider().copy(
            baseUrl = null, apiKeyAlias = null, extraHeaders = emptyMap(), statusMessage = null, lastCheckedAt = null,
        )
        repo.upsert(bare)
        assertEquals(bare, repo.get(bare.id))
    }

    @Test
    fun `upsert replaces, delete removes, order is oldest first`() = runTest {
        val repo = createRepository()
        repo.upsert(Samples.provider(id = "prv_b", name = "B", createdAt = 200))
        repo.upsert(Samples.provider(id = "prv_a", name = "A", createdAt = 100))
        repo.upsert(Samples.provider(id = "prv_b", name = "B2", createdAt = 200))
        assertEquals(listOf("A", "B2"), repo.getAll().map { it.name })
        repo.delete(ProviderId("prv_a"))
        assertEquals(listOf("B2"), repo.observeAll().first().map { it.name })
        assertNull(repo.get(ProviderId("prv_a")))
        repo.delete(ProviderId("prv_a"))
    }
}

abstract class ModelRepositoryContract {
    protected abstract fun createRepository(): ModelRepository

    @Test
    fun `stores and returns every field of a model`() = runTest {
        val repo = createRepository()
        val model = Samples.model()
        repo.upsert(model)
        assertEquals(model, repo.get(model.id))
        assertEquals(listOf(model), repo.observeAll().first())
    }

    @Test
    fun `stores a model with no optional values`() = runTest {
        val repo = createRepository()
        val bare = Samples.model().let {
            it.copy(
                localPath = null, sizeBytes = null,
                capabilities = it.capabilities.copy(contextWindow = null, maxOutputTokens = null),
            )
        }
        repo.upsert(bare)
        assertEquals(bare, repo.get(bare.id))
    }

    @Test
    fun `lists and deletes by provider`() = runTest {
        val repo = createRepository()
        repo.upsertAll(
            listOf(
                Samples.model(id = "mdl_1", providerId = "prv_a", createdAt = 100),
                Samples.model(id = "mdl_2", providerId = "prv_b", createdAt = 200),
                Samples.model(id = "mdl_3", providerId = "prv_a", createdAt = 300),
            ),
        )
        assertEquals(listOf("mdl_1", "mdl_3"), repo.getByProvider(ProviderId("prv_a")).map { it.id.value })
        repo.deleteByProvider(ProviderId("prv_a"))
        assertEquals(listOf("mdl_2"), repo.getAll().map { it.id.value })
        assertTrue(repo.getByProvider(ProviderId("prv_a")).isEmpty())
    }

    @Test
    fun `upsertAll replaces existing models, delete removes one`() = runTest {
        val repo = createRepository()
        repo.upsert(Samples.model(id = "mdl_1", modelName = "old"))
        repo.upsertAll(listOf(Samples.model(id = "mdl_1", modelName = "new"), Samples.model(id = "mdl_2", createdAt = 2_000)))
        assertEquals(listOf("new", "gemma3:4b"), repo.getAll().map { it.modelName })
        repo.delete(ModelId("mdl_1"))
        assertEquals(listOf("mdl_2"), repo.observeAll().first().map { it.id.value })
        assertNull(repo.get(ModelId("mdl_1")))
    }
}

abstract class AiAppRepositoryContract {
    protected abstract fun createRepository(): AiAppRepository

    @Test
    fun `stores and returns every field of an app`() = runTest {
        val repo = createRepository()
        val app = Samples.aiApp()
        repo.upsert(app)
        assertEquals(app, repo.get(app.id))
        assertEquals(app, repo.getByPackage(app.packageName))
        assertEquals(listOf(app), repo.observeAll().first())
    }

    @Test
    fun `stores an app with no optional values`() = runTest {
        val repo = createRepository()
        val bare = Samples.aiApp().copy(
            vendor = null, versionName = null, capabilities = emptySet(), linkedProviderId = null,
            promptDeepLink = null, notes = "", status = AiAppStatus.NOT_INSTALLED, accessibilityAuthorized = false,
        )
        repo.upsert(bare)
        assertEquals(bare, repo.get(bare.id))
    }

    @Test
    fun `upsert replaces, delete removes, lookup by unknown package is null`() = runTest {
        val repo = createRepository()
        repo.upsert(Samples.aiApp(id = "app_b", packageName = "b.pkg", firstSeenAt = 200))
        repo.upsert(Samples.aiApp(id = "app_a", packageName = "a.pkg", firstSeenAt = 100))
        repo.upsert(Samples.aiApp(id = "app_b", packageName = "b.pkg", firstSeenAt = 200).copy(label = "B2"))
        assertEquals(listOf("a.pkg", "b.pkg"), repo.getAll().map { it.packageName })
        assertEquals("B2", repo.getByPackage("b.pkg")?.label)
        assertNull(repo.getByPackage("missing.pkg"))
        repo.delete(AiAppId("app_a"))
        assertEquals(listOf("b.pkg"), repo.observeAll().first().map { it.packageName })
        repo.delete(AiAppId("app_a"))
    }
}
