package com.aicontrolcenter.memory

import com.aicontrolcenter.core.memory.GraphRequest
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryGraphSnapshot
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import com.aicontrolcenter.memory.MemoryWorld.Companion.shared
import com.aicontrolcenter.memory.MemoryWorld.Companion.task
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryGraphTest {
    private val world = MemoryWorld()
    private val service = world.service

    private class Fixture(val world: MemoryWorld) {
        lateinit var ada: com.aicontrolcenter.core.memory.MemoryActor.Agent
        var teamId = com.aicontrolcenter.core.SpaceId("")
        val ids = mutableMapOf<String, String>()

        suspend fun build(): Fixture {
            val team = world.space("Team")
            val secret = world.space("Secret")
            teamId = team.id
            ada = world.agent("agt_ada", name = "Ada", read = setOf(team.id))
            val bob = world.agent("agt_bob", name = "Bob")
            suspend fun add(key: String, scope: com.aicontrolcenter.core.memory.MemoryScope, importance: Double) {
                ids[key] = world.remember(world.owner, scope, "Title $key", "Content $key", importance = importance).id.value
                world.clock.advance(10)
            }
            add("ada1", private(ada), 0.9)
            add("ada2", private(ada), 0.2)
            add("adaTask", task(ada, "tsk_1"), 0.5)
            add("bob1", private(bob), 0.5)
            add("team1", shared(team), 0.8)
            add("team2", shared(team), 0.1)
            add("secret1", shared(secret), 0.5)
            val o = world.owner
            world.service.link(o, id("ada1"), id("ada2"), LinkRelation.RELATES_TO, 0.5)
            world.service.link(o, id("ada1"), id("team1"), LinkRelation.SUPPORTS)
            world.service.link(o, id("ada1"), id("bob1"), LinkRelation.REFERENCES)
            return this
        }

        fun id(key: String) = com.aicontrolcenter.core.MemoryId(ids.getValue(key))
    }

    private fun MemoryGraphSnapshot.labels() = nodes.map { it.label.removePrefix("Title ") }.toSet()

    @Test
    fun `an agent's graph holds only what it may read, with edges among drawn nodes`() = runTest {
        val f = Fixture(world).build()
        val graph = service.graph(f.ada)
        assertEquals(setOf("ada1", "ada2", "team1", "team2"), graph.labels())
        assertTrue(graph.nodes.none { "bob" in it.content || "secret" in it.content || "Task" in it.content })
        assertEquals(setOf("RELATES_TO" to false, "SUPPORTS" to true), graph.edges.map { it.relation to it.directed }.toSet())
        assertTrue(graph.edges.all { e -> graph.nodes.any { it.id == e.source } && graph.nodes.any { it.id == e.target } })
        assertFalse(graph.truncated)
        assertEquals(MemoryGraphSnapshot.SCHEMA, graph.schema)
        assertEquals("ai-control-center", graph.generator.app)

        val ada1 = graph.nodes.single { it.label == "Title ada1" }
        assertEquals("mem:${f.ids["ada1"]}", ada1.id)
        assertEquals("private", ada1.visibility)
        assertEquals("agt_ada", ada1.ownerAgentId)
        assertEquals("fact", ada1.subkind)
        assertEquals(listOf("cl:agent:agt_ada", "cl:kind:fact"), ada1.clusterIds)
        val team1 = graph.nodes.single { it.label == "Title team1" }
        assertEquals("shared", team1.visibility)
        assertEquals(null, team1.ownerAgentId)
    }

    @Test
    fun `clusters are labelled per agent, space, task and kind and sized by drawn nodes`() = runTest {
        val f = Fixture(world).build()
        val graph = service.graph(world.owner, GraphRequest(includeTaskMemory = true))
        val clusters = graph.clusters.associate { it.id to (it.kind to it.label to it.size) }
        assertEquals("agent" to "Ada" to 3, clusters["cl:agent:agt_ada"])
        assertEquals("agent" to "Bob" to 1, clusters["cl:agent:agt_bob"])
        assertEquals("space" to "Team" to 2, clusters["cl:space:${f.teamId.value}"])
        assertEquals("task" to "Task tsk_1" to 1, clusters["cl:task:tsk_1"])
        assertEquals("kind" to "Facts" to 7, clusters["cl:kind:fact"])
        assertEquals(listOf("task"), graph.nodes.filter { "cl:task:tsk_1" in it.clusterIds }.map { it.visibility })
    }

    @Test
    fun `requests filter by agent, space, task memory and time`() = runTest {
        val f = Fixture(world).build()
        val o = world.owner
        assertEquals(setOf("ada1", "ada2", "bob1", "team1", "team2", "secret1"), service.graph(o).labels())
        assertEquals(setOf("ada1", "ada2"), service.graph(o, GraphRequest(agentIds = setOf(f.ada.profile.id), spaceIds = emptySet())).labels())
        assertEquals(
            setOf("ada1", "ada2", "adaTask"),
            service.graph(o, GraphRequest(agentIds = setOf(f.ada.profile.id), spaceIds = emptySet(), includeTaskMemory = true)).labels(),
        )
        assertEquals(setOf("team1", "team2"), service.graph(o, GraphRequest(agentIds = emptySet(), spaceIds = setOf(f.teamId))).labels())
        assertEquals(setOf("adaTask"), service.graph(f.ada, GraphRequest(includeTaskMemory = true, spaceIds = emptySet(), since = world.repository.get(f.id("adaTask"))!!.updatedAt)).labels())
        // An agent asking for another agent's memory gets nothing of it.
        assertEquals(emptySet(), service.graph(f.ada, GraphRequest(agentIds = setOf(com.aicontrolcenter.core.AgentId("agt_bob")), spaceIds = emptySet())).labels())
    }

    @Test
    fun `maxNodes keeps the most important and recently used and marks the graph truncated`() = runTest {
        val f = Fixture(world).build()
        val graph = service.graph(world.owner, GraphRequest(maxNodes = 2))
        assertTrue(graph.truncated)
        assertEquals(setOf("ada1", "team1"), graph.labels())
        assertEquals(listOf("SUPPORTS"), graph.edges.map { it.relation })
        assertEquals(setOf(1), graph.clusters.filter { it.kind != "kind" }.map { it.size }.toSet())

        // Using a memory raises its activation and lets it into a small graph.
        world.clock.advance(400 * MemoryConfig.DAY_MS)
        repeat(4) { world.repository.recordAccess(listOf(f.id("team2")), world.clock.current) }
        val used = service.graph(world.owner, GraphRequest(maxNodes = 1))
        assertEquals(setOf("team2"), used.labels())
        val activation = service.graph(world.owner).nodes.associate { it.label to it.activation }
        assertTrue(activation.values.all { it in 0.0..1.0 })
        assertTrue(activation.getValue("Title team2") > activation.getValue("Title team1"))
        assertEquals(f.ids.size - 1, service.graph(world.owner).nodes.size)
    }

    @Test
    fun `expired memories are not drawn`() = runTest {
        val agent = world.agent("agt_a")
        service.remember(agent, world.draft(private(agent), "Fleeting", tier = com.aicontrolcenter.core.memory.MemoryTier.SHORT_TERM, ttlMs = 5))
        assertEquals(1, service.graph(agent).nodes.size)
        world.clock.advance(5)
        assertEquals(0, service.graph(agent).nodes.size)
    }
}
