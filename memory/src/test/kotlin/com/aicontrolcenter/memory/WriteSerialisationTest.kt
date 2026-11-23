package com.aicontrolcenter.memory

import com.aicontrolcenter.core.memory.MemoryChange
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryValidationException
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import com.aicontrolcenter.memory.MemoryWorld.Companion.shared
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Writes and deletions never interleave: a forgotten memory never comes back, and a copy is
 * written with its link or not at all. Each test holds one operation open inside the
 * repository, starts a second one, and only then lets the first finish.
 */
class WriteSerialisationTest {
    private lateinit var spy: SpyRepository
    private val world = MemoryWorld(wrap = { inner -> SpyRepository(inner).also { spy = it } })
    private val service = world.service

    @Test
    fun `a memory forgotten while an update of it is under way stays forgotten`() = runTest {
        val agent = world.agent("agt_a")
        val memory = world.remember(world.owner, private(agent), "Health", "Diagnosis X")
        val gate = spy.holdNextGet(memory.id)
        val update = async { service.update(world.owner, memory.id, MemoryChange(importance = 0.9)) }
        runCurrent()
        val forget = async { service.forget(world.owner, memory.id) }
        runCurrent()
        gate.complete(Unit)
        update.await()
        forget.await()
        assertNull(world.repository.get(memory.id))
        assertTrue(world.repository.list().isEmpty())
    }

    @Test
    fun `an update of a memory forgotten meanwhile is refused instead of recreating it`() = runTest {
        val agent = world.agent("agt_a")
        val memory = world.remember(world.owner, private(agent), "Health", "Diagnosis X")
        val other = world.remember(world.owner, private(agent), "Other")
        // The owner's forget waits behind an update of another memory; the second update waits behind the forget.
        val gate = spy.holdNextGet(other.id)
        val first = async { service.update(world.owner, other.id, MemoryChange(importance = 0.1)) }
        runCurrent()
        val forget = async { service.forget(world.owner, memory.id) }
        runCurrent()
        val late = async { runCatching { service.update(world.owner, memory.id, MemoryChange(title = "Changed")) } }
        runCurrent()
        gate.complete(Unit)
        first.await()
        forget.await()
        assertTrue(late.await().exceptionOrNull() is MemoryValidationException)
        assertNull(world.repository.get(memory.id))
    }

    @Test
    fun `sharing a memory the owner forgets meanwhile completes before the forget and leaves nothing half-written`() = runTest {
        val team = world.space("Team")
        val agent = world.agent("agt_a", read = setOf(team.id), write = setOf(team.id))
        val secret = world.remember(agent, private(agent), "Private", "Owner's PIN hint")
        val gate = spy.holdNextGet(secret.id)
        val share = async { runCatching { service.share(agent, secret.id, team.id) } }
        runCurrent()
        val forget = async { service.forget(world.owner, secret.id) }
        runCurrent()
        gate.complete(Unit)
        val copy = share.await().getOrThrow()
        forget.await()

        assertNull(world.repository.get(secret.id))
        // The copy was complete when the forget came: its history still names the original.
        assertEquals(listOf(copy), world.repository.list(MemoryFilter(scopes = setOf(shared(team)))))
        assertEquals(secret.id, copy.history.single().relatedMemory)
    }

    @Test
    fun `a share whose original disappears before the copy is written is refused and stores nothing`() = runTest {
        val team = world.space("Team")
        val agent = world.agent("agt_a", read = setOf(team.id), write = setOf(team.id))
        val secret = world.remember(agent, private(agent), "Private", "Owner's PIN hint")
        // Something outside the engine deletes the original right after the share read it.
        spy.afterGet = { id -> world.repository.delete(id) }

        assertFailsWith<MemoryValidationException> { service.share(agent, secret.id, team.id) }
        assertTrue(world.repository.list().isEmpty(), "no copy without its original")
        assertTrue(world.repository.links().isEmpty())
    }

    @Test
    fun `a memory saved into a space while the owner deletes the space does not outlive it`() = runTest {
        val team = world.space("Team")
        val agent = world.agent("agt_a", read = setOf(team.id), write = setOf(team.id))
        // Held where the save checks that the space exists (the first read resolves the agent's access).
        val gate = spy.holdGetSpace(team.id, skip = 1)
        val remember = async { runCatching { world.remember(agent, shared(team), "Plan", "Meet on Monday") } }
        runCurrent()
        val delete = async { service.deleteSpace(world.owner, team.id) }
        runCurrent()
        gate.complete(Unit)
        remember.await()
        delete.await()
        assertNull(world.repository.getSpace(team.id))
        assertTrue(world.repository.list().isEmpty(), "${world.repository.list().map { it.title }}")
    }

    @Test
    fun `memory of an agent being forgotten is not recreated by an update in flight`() = runTest {
        val agent = world.agent("agt_a")
        val memory = world.remember(world.owner, private(agent), "Habit", "Runs at six")
        val gate = spy.holdNextGet(memory.id)
        val update = async { runCatching { service.update(world.owner, memory.id, MemoryChange(importance = 0.9)) } }
        runCurrent()
        world.agents.delete(agent.profile.id)
        val forget = async { service.forgetAgent(agent.profile.id) }
        runCurrent()
        gate.complete(Unit)
        update.await()
        forget.await()
        assertTrue(world.repository.list().isEmpty())
    }
}
