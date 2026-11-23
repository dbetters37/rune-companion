package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.memory.MaintenanceReport
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryAgentRepository
import com.aicontrolcenter.testkit.InMemoryMemoryRepository
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each step of maintenance runs on its own: one failing step never stops the others. */
class MaintenanceStepsTest {
    /** An agent registry whose listing fails, as an unreadable agents table would. */
    private class FailingAgents(val inner: InMemoryAgentRepository) : AgentRepository by inner {
        override suspend fun getAll(): List<AgentProfile> = throw IllegalStateException("agents table unreadable: secret row")
    }

    @Test
    fun `a failing removed-agent sweep does not stop expiry, vectors or the maintenance log`() = runTest {
        // The verifier's p10v case, with an expired memory added.
        val clock = FakeClock()
        val ids = SequentialIdGenerator()
        val repository = InMemoryMemoryRepository()
        val registry = InMemoryAgentRepository()
        val log = InMemoryActivityLog()
        val service = DefaultMemoryService(
            repository, FailingAgents(registry), InMemorySettingsRepository(FrameworkSettings()), HashingEmbedder(),
            ActivityRecorder(log, ids, clock), ids, clock,
        )
        val agent = AgentProfile(id = AgentId("agt_a"), name = "A", kind = AgentKind.SPECIALIST, createdAt = 1, updatedAt = 1)
        registry.upsert(agent)
        val now = clock.now()
        fun record(id: String, tier: MemoryTier, expiresAt: Long?) = MemoryRecord(
            MemoryId(id), MemoryScope.Agent(agent.id), MemoryKind.NOTE, tier, "Note $id", "needs a vector",
            provenance = Provenance(ProvenanceOrigin.AGENT, recordedAt = now), createdAt = now, updatedAt = now, expiresAt = expiresAt,
        )
        repository.upsert(record("mem_1", MemoryTier.LONG_TERM, null))
        repository.upsert(record("mem_old", MemoryTier.SHORT_TERM, now - 1))

        assertEquals(MaintenanceReport(expiredDeleted = 1, embeddingsComputed = 1), service.maintain())
        assertNull(repository.get(MemoryId("mem_old")))
        assertTrue(repository.embeddingsOf(HashingEmbedder.ID, listOf(MemoryId("mem_1"))).isNotEmpty())
        assertEquals(setOf(agent.id), repository.agentsWithMemory(), "nothing deleted by the failed sweep")

        val failure = log.all.single { it.title == "Memory maintenance incomplete" }
        assertEquals(Severity.WARNING, failure.severity)
        assertTrue("deleting memory of removed agents (IllegalStateException)" in failure.detail, failure.detail)
        assertTrue("secret" !in failure.detail, "the error's message is not logged")
        assertTrue(log.all.any { it.title == "Memory maintenance" })
    }
}
