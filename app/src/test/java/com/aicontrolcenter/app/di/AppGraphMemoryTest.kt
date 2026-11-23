package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.populateWithMemories
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.framework.memory.MemorySeeder
import com.aicontrolcenter.framework.memory.MemoryTools
import com.aicontrolcenter.memory.HashingEmbedder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The memory system as the composition root wires it: seeding, upkeep, tools, recall and agent deletion. */
class AppGraphMemoryTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test
    fun `starting creates the team space once and shares it with the starter team`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()

        val spaces = app.graph.memoryService.observeSpaces().first()
        val team = spaces.single { it.name == MemorySeeder.TEAM_SPACE_NAME }
        assertEquals(MemorySeeder.MEMORY_SEED_VERSION, app.settings.get().memorySeedVersion)
        assertTrue(app.agents.getAll().all { team.id in it.memory.readableSpaces })
        assertEquals(listOf(app.director().profile.id), app.agents.getAll().filter { team.id in it.memory.writableSpaces }.map { it.id })

        // The owner deletes it; the next process start must not bring it back.
        app.graph.memoryService.deleteSpace(MemoryActor.Owner, team.id)
        val restarted = AppGraph(app.ports, backgroundScope, app.factories.values.toList())
        restarted.start().join()
        assertTrue(restarted.memoryService.observeSpaces().first().isEmpty())
    }

    @Test
    fun `upkeep at start deletes expired short-term memory and fills in missing vectors`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val researcher = app.agentNamed("Researcher").profile
        val expired = app.graph.memoryService.remember(
            MemoryActor.Owner,
            MemoryDraft(
                MemoryScope.Agent(researcher.id), MemoryKind.NOTE, "Parking until noon", "The car is parked until noon.",
                tier = MemoryTier.SHORT_TERM, ttlMs = 60_000, provenance = Provenance(ProvenanceOrigin.OWNER, recordedAt = app.clock.now()),
            ),
        )
        app.clock.advance(10 * 60_000)
        // A memory written before the engine had a vector for it, as an older version or an interrupted save leaves it.
        val kept = app.memories.list().single().copy(id = com.aicontrolcenter.core.MemoryId("mem_legacy"), tier = MemoryTier.LONG_TERM, expiresAt = null, title = "Legacy")
        app.memories.upsert(kept)

        app.graph.start().join()
        mainRule.dispatcher.scheduler.advanceUntilIdle()

        assertNull(app.memories.get(expired.id))
        assertTrue(app.memories.missingEmbeddings(HashingEmbedder.ID).isEmpty(), "every memory has a search vector")
    }

    @Test
    fun `agents get the memory tools, and deleting an agent forgets its private memory but not what it shared`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val registered = app.graph.toolRegistry.all().map { it.spec.id }.toSet()
        assertTrue(MemoryTools.SEARCH_ID in registered && MemoryTools.SAVE_ID in registered && MemoryTools.SHARE_ID in registered)

        val researcher = app.agentNamed("Researcher").profile
        app.graph.agentManager.delete(researcher.id)

        assertNull(app.memories.get(sample.lisbonTrip))
        assertTrue(app.memories.list().none { (it.scope as? MemoryScope.Agent)?.agentId == researcher.id || (it.scope as? MemoryScope.Task)?.agentId == researcher.id })
        assertNotNull(app.memories.get(sample.bulletPointsShared), "the copy it shared stays in the space")
        assertNotNull(app.memories.list().firstOrNull { it.title == "Lisbon transport sources" }, "and so does what it wrote there")
    }
}
