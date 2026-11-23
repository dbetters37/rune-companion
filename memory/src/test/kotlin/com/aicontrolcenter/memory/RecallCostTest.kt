package com.aicontrolcenter.memory

import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What one recall may cost: bounded text, bounded analysis per memory, and a time-out that actually stops it. */
class RecallCostTest {
    private val scorer = RecallScorer(MemoryConfig())

    private fun record(id: String, scope: MemoryScope, at: Long, content: String) = MemoryRecord(
        id = MemoryId(id), scope = scope, kind = MemoryKind.NOTE, tier = MemoryTier.LONG_TERM, title = "Note $id", content = content,
        provenance = Provenance(ProvenanceOrigin.AGENT, recordedAt = at), createdAt = at, updatedAt = at,
    )

    @Test
    fun `keyword scoring reads the start of long content only`() {
        val filler = "lorem ipsum ".repeat(RecallScorer.KEYWORD_TEXT_CHARS / 12 + 10)
        val terms = scorer.queryTerms("zebra")
        val early = record("mem_early", MemoryScope.Agent(com.aicontrolcenter.core.AgentId("agt_a")), 1, "zebra $filler")
        val late = early.copy(id = MemoryId("mem_late"), content = "$filler zebra")
        assertEquals(RecallScorer.BODY_MATCH, scorer.keyword(terms, early))
        assertEquals(0.0, scorer.keyword(terms, late))
        // The cut never splits a word or a surrogate pair.
        val analysed = RecallScorer.analysedContent(filler)
        assertTrue(analysed.length <= RecallScorer.KEYWORD_TEXT_CHARS && filler[analysed.length] == ' ', analysed.takeLast(20))
        val emoji = "a".repeat(RecallScorer.KEYWORD_TEXT_CHARS - 1) + "🧠" + "b".repeat(50)
        assertTrue(!Character.isHighSurrogate(RecallScorer.analysedContent(emoji).last()))
    }

    @Test
    fun `an older memory the text search finds counts its whole text, however long`() = runTest {
        val world = MemoryWorld(config = MemoryConfig(candidateLimit = 3))
        val agent = world.agent("agt_a")
        val now = world.clock.current
        (1..10).forEach { world.repository.upsert(record("mem_new$it", private(agent), now - it, "Groceries for the week $it")) }
        val filler = "lorem ipsum ".repeat(RecallScorer.KEYWORD_TEXT_CHARS / 12 + 50)
        world.repository.upsert(record("mem_old", private(agent), now - 1_000, "$filler The zebra crossing is on Elm Street."))

        assertEquals(listOf("mem_old"), world.service.recall(agent, RecallQuery("zebra crossing")).map { it.record.id.value })
    }

    @Test
    fun `candidates are bounded by their total text as well as by their number`() = runTest {
        lateinit var spy: SpyRepository
        val config = MemoryConfig(candidateTextBudget = 50_000)
        val world = MemoryWorld(config = config, wrap = { SpyRepository(it).also { s -> spy = s } })
        val agent = world.agent("agt_a")
        val now = world.clock.current
        (1..2_000).forEach { world.repository.upsert(record("mem_$it", private(agent), now - it, "x".repeat(985))) }

        val reader = MemoryReader(spy, config)
        val who = AccessResolver(world.agents, spy).resolve(agent)
        val collected = reader.collect(who, MemoryFilter(), setOf(private(agent)), emptySet(), config.candidateLimit)
        assertTrue(collected.bounded)
        assertEquals((1..50).map { "mem_$it" }, collected.records.map { it.id.value }, "the newest that fit 50,000 characters")
        assertTrue(spy.loaded.all { it.second <= MemoryReader.FIRST_PAGE }, "no read loads more than one page: ${spy.loaded}")
    }

    @Test
    fun `a cancelled recall stops scoring instead of finishing the work`() = runTest {
        lateinit var spy: SpyRepository
        val world = MemoryWorld(wrap = { SpyRepository(it).also { s -> spy = s } })
        val agent = world.agent("agt_a")
        val now = world.clock.current
        (1..1_800).forEach { world.repository.upsert(record("mem_$it", private(agent), now - it, "Travel budget note $it")) }
        val started = CompletableDeferred<Unit>()
        val recall = async { world.service.recall(agent, RecallQuery("travel budget")) }
        // The caller gives up (as a time-out would) while the first chunk is being scored.
        spy.onEmbeddingsOf = {
            started.complete(Unit)
            recall.cancel()
        }
        assertFailsWith<CancellationException> { recall.await() }
        started.await()
        assertEquals(1, spy.calls.count { it.startsWith("embeddingsOf(") }, "${spy.calls}")
    }
}
