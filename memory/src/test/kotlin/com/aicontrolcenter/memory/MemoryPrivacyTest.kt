package com.aicontrolcenter.memory

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.memory.GraphRequest
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryChange
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryValidationException
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import com.aicontrolcenter.memory.MemoryWorld.Companion.shared
import com.aicontrolcenter.memory.MemoryWorld.Companion.task
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryPrivacyTest {
    private val secret = "SECRET-7f3a diary entry"

    private fun ActivityEvent.text() = listOf(title, detail, data?.toString().orEmpty(), egress?.toString().orEmpty()).joinToString("\n")

    private fun assertNoContent(events: List<ActivityEvent>) {
        val leaks = events.filter { "SECRET-7f3a" in it.text() }
        assertTrue(leaks.isEmpty(), "content leaked into: ${leaks.map { it.title }}")
    }

    @Test
    fun `memory content never appears in activity events or exception messages`() = runTest {
        val embedder = RecordingEmbedder(Locality.ON_DEVICE)
        val world = MemoryWorld(embedder)
        val team = world.space("Team")
        val agent = world.agent("agt_a", read = setOf(team.id), write = setOf(team.id))
        val outsider = world.agent("agt_o")
        val note = world.remember(agent, private(agent), "Diary", "Today: $secret")
        val step = world.remember(agent, task(agent, "tsk_1"), "Step", "Found $secret", importance = 0.9)
        val s = world.service
        s.update(agent, note.id, MemoryChange(content = "Updated: $secret"))
        val copy = s.share(agent, note.id, team.id)
        s.link(agent, note.id, copy.id, LinkRelation.RELATES_TO)
        s.recall(agent, RecallQuery("diary entry"))
        s.graph(agent, GraphRequest(includeTaskMemory = true))
        s.promoteTaskMemory(agent, TaskId("tsk_1"), agent.profile.id)
        embedder.failing = true
        world.remember(agent, private(agent), "Broken embedder", "Also $secret")
        s.update(agent, note.id, MemoryChange(content = "Again: $secret"))
        embedder.failing = false
        s.maintain()
        s.forget(agent, step.id)
        s.forgetAgent(agent.profile.id)

        val permission = assertFailsWith<MemoryPermissionException> { s.update(outsider, copy.id, MemoryChange(content = "x")) }
        val tooLong = assertFailsWith<MemoryValidationException> { world.remember(world.owner, shared(team), "Long", secret.repeat(2_000)) }
        assertFalse("SECRET-7f3a" in permission.message.orEmpty() + tooLong.message.orEmpty())

        assertTrue(world.events.count { it.type == ActivityType.MEMORY } >= 10)
        assertTrue(world.events.any { it.title == "Embedding failed" })
        assertNoContent(world.events)
        // Every change names who acted, and every change to memory names where it happened.
        val memoryEvents = world.events.filter { it.type == ActivityType.MEMORY }
        assertTrue(memoryEvents.all { it.data?.get("actor") != null })
        assertTrue(memoryEvents.filter { it.title !in setOf("Memory maintenance", "Embedding failed") }.all { it.data?.get("scope") != null })
        // Titles are fine to show.
        assertTrue(world.events.any { it.title == "Memory saved: Diary" && "private memory of A" in it.detail })
    }

    @Test
    fun `local-only mode stops an off-device embedder from ever being called`() = runTest {
        for (locality in listOf(Locality.CLOUD, Locality.LOCAL_NETWORK)) {
            val remote = RecordingEmbedder(locality)
            val world = MemoryWorld(remote, localOnly = true)
            val agent = world.agent("agt_a")
            val record = world.remember(agent, private(agent), "Diary", "Today: $secret")
            world.service.update(agent, record.id, MemoryChange(content = "Changed: $secret"))
            assertEquals(1, world.service.recall(agent, RecallQuery("diary")).size, "keyword recall still works")
            world.service.maintain()
            assertEquals(emptyList(), remote.calls, "$locality embedder was called in local-only mode")
            assertTrue(world.events.none { it.type == ActivityType.DATA_EGRESS })

            // Once local-only mode is off, maintenance catches up, and the egress is logged first, without content.
            world.settings.update { it.copy(localOnlyMode = false) }
            assertEquals(1, world.service.maintain().embeddingsComputed)
            assertEquals(1, remote.calls.size)
            val egress = world.events.single { it.type == ActivityType.DATA_EGRESS }
            assertEquals(locality, egress.egress?.locality)
            assertTrue(egress.egress!!.approxBytes!! > 0)
            assertTrue(world.events.indexOf(egress) < world.events.indexOfLast { it.title == "Memory maintenance" })
            world.service.recall(agent, RecallQuery("diary"))
            assertEquals(2, world.events.count { it.type == ActivityType.DATA_EGRESS }, "the recall query left the device too")
            assertNoContent(world.events)
        }
    }

    @Test
    fun `the on-device embedder keeps working in local-only mode`() = runTest {
        val world = MemoryWorld(localOnly = true)
        val agent = world.agent("agt_a")
        val record = world.remember(agent, private(agent), "Typo test", "Restaurant reservation for Friday.")
        assertNotNull(world.repository.embeddings(HashingEmbedder.ID)[record.id])
        assertEquals(listOf(record.id), world.service.recall(agent, RecallQuery("resturant resevation")).map { it.record.id })
        assertTrue(world.events.none { it.type == ActivityType.DATA_EGRESS })
    }

    @Test
    fun `graph nodes carry only readable content`() = runTest {
        val world = MemoryWorld()
        val a = world.agent("agt_a")
        val b = world.agent("agt_b")
        world.remember(b, private(b), "B's diary", secret)
        world.remember(b, MemoryScope.Task(TaskId("tsk_9"), b.profile.id), "B's work", secret)
        world.remember(a, private(a), "A's note", "harmless")
        val graph = world.service.graph(a, GraphRequest(includeTaskMemory = true))
        assertEquals(listOf("A's note"), graph.nodes.map { it.label })
        assertFalse(secret in MemoryGraphJson.encode(graph))
    }
}
