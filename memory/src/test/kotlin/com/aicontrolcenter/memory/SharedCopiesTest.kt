package com.aicontrolcenter.memory

import com.aicontrolcenter.core.memory.MemoryChange
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.ProvenanceAction
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import com.aicontrolcenter.memory.MemoryWorld.Companion.shared
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A memory and its shared copies count once in recall, and sharing again never leaves an outdated copy behind. */
class SharedCopiesTest {
    private val world = MemoryWorld()
    private val service = world.service

    @Test
    fun `recall returns a memory once even when its shared copies are readable too`() = runTest {
        val team = world.space("Team")
        val other = world.space("Other")
        val b = world.agent("agt_b", read = setOf(team.id, other.id), write = setOf(team.id, other.id))
        val seat = world.remember(b, private(b), "Seat preference", "The owner prefers aisle seats on flights", kind = MemoryKind.PREFERENCE)
        val teamCopy = service.share(b, seat.id, team.id)
        service.share(b, teamCopy.id, other.id)
        world.remember(b, private(b), "Meal preference", "The owner wants vegetarian meals on flights", kind = MemoryKind.PREFERENCE)

        val hits = service.recall(b, RecallQuery("flight seat preference", limit = 2))
        assertEquals(listOf("Seat preference" to private(b), "Meal preference" to private(b)), hits.map { it.record.title to it.record.scope })

        // Asked for the spaces only, the agent gets one of the copies, not both.
        val inSpaces = service.recall(b, RecallQuery("flight seat preference", scopes = setOf(shared(team), shared(other))))
        assertEquals(listOf("Seat preference"), inSpaces.map { it.record.title })
    }

    @Test
    fun `sharing again after the original changed refreshes the copy`() = runTest {
        val team = world.space("Team")
        val a = world.agent("agt_a", write = setOf(team.id))
        val reader = world.agent("agt_r", read = setOf(team.id))
        val original = world.remember(a, private(a), "Office hours", "Open 9 to 5")
        val copy = service.share(a, original.id, team.id)
        world.clock.advance(100)
        service.update(a, original.id, MemoryChange(content = "Open 8 to 4"))

        val refreshed = service.share(a, original.id, team.id)
        assertEquals(copy.id, refreshed.id)
        assertEquals("Open 8 to 4", refreshed.content)
        assertEquals(2, refreshed.revision)
        assertEquals(world.clock.current, refreshed.updatedAt)
        assertEquals(
            listOf(ProvenanceAction.SHARED_FROM, ProvenanceAction.EDITED, ProvenanceAction.SHARED_FROM),
            refreshed.history.map { it.action },
        )
        assertEquals(refreshed, world.repository.get(copy.id))
        assertEquals(listOf("Open 8 to 4"), service.recall(reader, RecallQuery("office hours open", recordAccess = false)).map { it.record.content })
        // Nothing changed since: sharing again returns the copy as it is.
        assertEquals(refreshed, service.share(a, original.id, team.id))
        assertEquals(2, world.repository.list().size)
    }

    @Test
    fun `an expired copy is ignored and a new one made`() = runTest {
        val team = world.space("Team")
        val a = world.agent("agt_a", read = setOf(team.id), write = setOf(team.id))
        val original = world.remember(a, private(a), "Parking", "Level 2, spot 14")
        val copy = service.share(a, original.id, team.id)
        // Someone made the copy short-term; it has run out.
        service.update(world.owner, copy.id, MemoryChange(tier = MemoryTier.SHORT_TERM))
        world.clock.advance(4 * MemoryConfig.DAY_MS)

        val fresh = service.share(a, original.id, team.id)
        assertNotEquals(copy.id, fresh.id)
        assertTrue(!fresh.isExpired(world.clock.current))
        assertEquals(original.content, fresh.content)
    }
}
