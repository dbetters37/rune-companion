package com.aicontrolcenter.memory

import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import com.aicontrolcenter.memory.MemoryWorld.Companion.shared
import com.aicontrolcenter.memory.MemoryWorld.Companion.task
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecallTest {
    private val world = MemoryWorld()
    private val service = world.service
    private val day = MemoryConfig.DAY_MS

    private fun RecallQuery.browsing() = copy(recordAccess = false)

    @Test
    fun `meaning ranks the related memory first and leaves the unrelated out`() = runTest {
        val agent = world.agent("agt_a")
        world.remember(agent, private(agent), "Car maintenance", "The car needs new winter tyres before December.")
        val tea = world.remember(agent, private(agent), "Morning drink", "The owner prefers green tea in the morning.")
        world.remember(agent, private(agent), "Dentist", "The dentist appointment is on Tuesday at nine.")
        val hits = service.recall(agent, RecallQuery("what does the owner like drinking in the mornings").browsing())
        assertEquals(listOf(tea.id), hits.map { it.record.id })
        assertTrue(hits.single().semantic > 0.3, "semantic ${hits.single().semantic}")
    }

    @Test
    fun `semantic similarity finds a misspelt query that shares no exact word`() = runTest {
        val agent = world.agent("agt_a")
        val booking = world.remember(agent, private(agent), "Restaurant reservation", "Restaurant reservation for Friday evening.")
        world.remember(agent, private(agent), "Garden", "Water the tomatoes twice a week.")
        val hits = service.recall(agent, RecallQuery("resturant resevation").browsing())
        assertEquals(listOf(booking.id), hits.map { it.record.id })
        assertEquals(0.0, hits.single().keyword)
        assertTrue(hits.single().semantic >= 0.2)
    }

    @Test
    fun `without a usable embedder recall falls back to keywords with renormalised weights`() = runTest {
        val remote = RecordingEmbedder(Locality.CLOUD)
        val w = MemoryWorld(remote, localOnly = true)
        val agent = w.agent("agt_a")
        val tea = w.remember(agent, private(agent), "Morning drink", "The owner prefers green tea.", importance = 0.8)
        w.remember(agent, private(agent), "Restaurant reservation", "Friday evening.")
        val hit = w.service.recall(agent, RecallQuery("green tea").browsing()).single()
        assertEquals(tea.id, hit.record.id)
        assertEquals(0.0, hit.semantic)
        assertEquals(RecallScorer.BODY_MATCH, hit.keyword)
        assertEquals(1.0, hit.recency)
        val expected = (0.25 * RecallScorer.BODY_MATCH + 0.10 * 1.0 + 0.10 * 0.8) / 0.45
        assertTrue(abs(expected - hit.score) < 1e-9, "score ${hit.score} expected $expected")
        // No meaning without vectors: the misspelling finds nothing, and nothing was sent anywhere.
        assertEquals(emptyList(), w.service.recall(agent, RecallQuery("resturant resevation")))
        assertEquals(emptyList(), remote.calls)
    }

    @Test
    fun `more recent and more important memories rank higher`() = runTest {
        val agent = world.agent("agt_a")
        val old = world.remember(agent, private(agent), "Project Falcon deadline", "Falcon ships on Friday.")
        world.clock.advance(60 * day)
        val fresh = world.remember(agent, private(agent), "Project Falcon deadline", "Falcon ships on Friday.")
        val hits = service.recall(agent, RecallQuery("falcon deadline").browsing())
        assertEquals(listOf(fresh.id, old.id), hits.map { it.record.id })
        assertTrue(abs(hits[1].recency - 0.25) < 1e-9, "two half-lives: ${hits[1].recency}")

        val minor = world.remember(agent, private(agent), "Budget review", "Quarterly budget review.", importance = 0.1)
        val major = world.remember(agent, private(agent), "Budget review", "Quarterly budget review.", importance = 0.9)
        assertEquals(listOf(major.id, minor.id), service.recall(agent, RecallQuery("budget review").browsing()).map { it.record.id })
    }

    @Test
    fun `a match in the title outranks the same match in the content`() = runTest {
        val agent = world.agent("agt_a")
        val inBody = world.remember(agent, private(agent), "Notes from Monday", "Discussed the invoice.")
        val inTitle = world.remember(agent, private(agent), "Invoice", "Discussed on Monday.")
        val hits = service.recall(agent, RecallQuery("invoice").browsing())
        assertEquals(listOf(inTitle.id, inBody.id), hits.map { it.record.id })
        assertEquals(1.0, hits[0].keyword)
        assertEquals(RecallScorer.BODY_MATCH, hits[1].keyword)
    }

    @Test
    fun `minScore and limit are applied and the limit is capped`() = runTest {
        val w = MemoryWorld(config = MemoryConfig(maxRecallLimit = 3))
        val agent = w.agent("agt_a")
        repeat(6) { w.remember(agent, private(agent), "Recipe $it", "A pasta recipe, variant $it.") }
        assertEquals(2, w.service.recall(agent, RecallQuery("pasta recipe", limit = 2)).size)
        assertEquals(3, w.service.recall(agent, RecallQuery("pasta recipe", limit = 100)).size)
        assertEquals(emptyList(), w.service.recall(agent, RecallQuery("pasta recipe", minScore = 0.99)))
        assertEquals(emptyList(), w.service.recall(agent, RecallQuery("pasta recipe", limit = 0)))
        assertEquals(emptyList(), w.service.recall(agent, RecallQuery("   ")))
        // A minimum of zero still never returns irrelevant memories.
        assertEquals(emptyList(), w.service.recall(agent, RecallQuery("carburettor", minScore = 0.0)))
    }

    @Test
    fun `expired memories are never recalled, even before maintenance deletes them`() = runTest {
        val agent = world.agent("agt_a")
        world.service.remember(agent, world.draft(private(agent), "Gate code", "The gate code is 4321.", tier = MemoryTier.SHORT_TERM, ttlMs = 1_000))
        assertEquals(1, service.recall(agent, RecallQuery("gate code")).size)
        world.clock.advance(1_000)
        assertEquals(emptyList(), service.recall(agent, RecallQuery("gate code")))
        assertEquals(emptyList(), service.recall(world.owner, RecallQuery("gate code")))
    }

    @Test
    fun `nothing the actor may not read is ever returned`() = runTest {
        val readable = world.space("Readable")
        val hidden = world.space("Hidden")
        val writeOnly = world.space("Write only")
        val agent = world.agent("agt_a", read = setOf(readable.id), write = setOf(writeOnly.id))
        val other = world.agent("agt_b", read = setOf(hidden.id))
        val text = "Solar panel installation quote"
        val scopes = listOf(
            private(agent), private(other), shared(readable), shared(hidden), shared(writeOnly), task(agent, "tsk_1"), task(other, "tsk_2"),
        )
        for (scope in scopes) world.remember(world.owner, scope, text, "$text for ${scope.key}")
        val hits = service.recall(agent, RecallQuery("solar panel quote", limit = 50).browsing())
        assertEquals(setOf(private(agent), shared(readable), task(agent, "tsk_1")), hits.map { it.record.scope }.toSet())
        // Asking for unreadable scopes explicitly changes nothing.
        val explicit = service.recall(agent, RecallQuery("solar panel quote", scopes = scopes.toSet()).browsing())
        assertEquals(setOf(private(agent), shared(readable), task(agent, "tsk_1")), explicit.map { it.record.scope }.toSet())
        assertEquals(7, service.recall(world.owner, RecallQuery("solar panel quote", limit = 50)).size)
    }

    @Test
    fun `scope and kind filters narrow recall`() = runTest {
        val team = world.space("Team")
        val agent = world.agent("agt_a", read = setOf(team.id))
        world.remember(agent, private(agent), "Travel plan", "Train to Lyon.", kind = MemoryKind.DECISION)
        world.remember(world.owner, shared(team), "Travel policy", "Trains preferred over flights.", kind = MemoryKind.PROCEDURE)
        val onlyTeam = service.recall(agent, RecallQuery("travel", scopes = setOf(shared(team))).browsing())
        assertEquals(listOf("Travel policy"), onlyTeam.map { it.record.title })
        val onlyDecisions = service.recall(agent, RecallQuery("travel", kinds = setOf(MemoryKind.DECISION)).browsing())
        assertEquals(listOf("Travel plan"), onlyDecisions.map { it.record.title })
        assertEquals(emptyList(), service.recall(agent, RecallQuery("travel", scopes = emptySet())))
    }

    @Test
    fun `ties are broken deterministically by id`() = runTest {
        val agent = world.agent("agt_a")
        val first = world.remember(agent, private(agent), "Umbrella", "Keep an umbrella in the car.")
        val second = world.remember(agent, private(agent), "Umbrella", "Keep an umbrella in the car.")
        repeat(3) {
            assertEquals(listOf(first.id, second.id), service.recall(agent, RecallQuery("umbrella").browsing()).map { it.record.id })
        }
    }

    @Test
    fun `recall used for work counts as an access, browsing does not`() = runTest {
        val agent = world.agent("agt_a")
        val record = world.remember(agent, private(agent), "Bike lock", "The bike lock code is 1984.")
        service.recall(agent, RecallQuery("bike lock").browsing())
        assertEquals(0, world.repository.get(record.id)!!.accessCount)
        world.clock.advance(5)
        val hit = service.recall(agent, RecallQuery("bike lock")).single()
        assertEquals(1, hit.record.accessCount)
        assertEquals(world.clock.current, hit.record.lastAccessedAt)
        assertEquals(hit.record, world.repository.get(record.id))
    }

    @Test
    fun `old memories beyond the candidate bound are still found by their exact words`() = runTest {
        val w = MemoryWorld(config = MemoryConfig(candidateLimit = 3))
        val agent = w.agent("agt_a")
        val old = w.remember(agent, private(agent), "Zebra crossing", "The zebra crossing near school was repainted.")
        repeat(5) {
            w.clock.advance(1)
            w.remember(agent, private(agent), "Filler $it", "Unrelated filler number $it.")
        }
        assertEquals(listOf(old.id), w.service.recall(agent, RecallQuery("Where is the zebra crossing?")).map { it.record.id })
    }
}
