package com.aicontrolcenter.memory

import com.aicontrolcenter.core.memory.GraphEdge
import com.aicontrolcenter.core.memory.GraphGenerator
import com.aicontrolcenter.core.memory.GraphNode
import com.aicontrolcenter.core.memory.MemoryGraphSnapshot
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import org.junit.Test
import java.util.Random
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ForceLayoutTest {
    private val provenance = Provenance(ProvenanceOrigin.SYSTEM, recordedAt = 0)

    private fun node(id: String, cluster: String) = GraphNode(
        id = id, kind = "memory", subkind = "fact", label = id, content = "", tags = emptyList(),
        clusterIds = listOf(cluster, "cl:kind:fact"), ownerAgentId = null, visibility = "shared", weight = 0.5,
        confidence = 1.0, activation = 0.5, createdAt = 0, updatedAt = 0, lastAccessedAt = null, accessCount = 0,
        expiresAt = null, provenance = provenance,
    )

    private fun edge(i: Int, a: String, b: String, weight: Double = 1.0) =
        GraphEdge("link:$i", a, b, "RELATES_TO", directed = false, weight = weight, createdAt = 0, provenance = provenance)

    private fun snapshot(nodes: List<GraphNode>, edges: List<GraphEdge>) =
        MemoryGraphSnapshot(generatedAt = 0, generator = GraphGenerator("test", "1", "test"), nodes = nodes, edges = edges, clusters = emptyList())

    /** 500 nodes in 5 clusters: a chain plus random extra edges within each cluster, and a few bridges. */
    private fun large(): MemoryGraphSnapshot {
        val random = Random(42)
        val nodes = (0 until 500).map { node("mem:n$it", "cl:agent:a${it % 5}") }
        val edges = ArrayList<GraphEdge>()
        val byCluster = nodes.groupBy { it.clusterIds.first() }.values.map { group -> group.map { it.id } }
        for (group in byCluster) {
            for (i in 1 until group.size) edges += edge(edges.size, group[i - 1], group[i])
            repeat(100) { edges += edge(edges.size, group[random.nextInt(group.size)], group[random.nextInt(group.size)], random.nextDouble()) }
        }
        repeat(10) { edges += edge(edges.size, nodes[random.nextInt(500)].id, nodes[random.nextInt(500)].id) }
        return snapshot(nodes, edges)
    }

    private fun distance(a: LayoutPoint, b: LayoutPoint) = hypot(a.x - b.x, a.y - b.y)

    @Test
    fun `lays out 500 nodes quickly, deterministically and inside the unit square`() {
        val graph = large()
        val layout = ForceLayout()
        layout.layout(graph) // warm up the JIT so the timing reflects the algorithm
        val started = System.nanoTime()
        val first = layout.layout(graph)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 1_500, "took $elapsedMs ms")
        assertEquals(500, first.size)
        assertTrue(first.values.all { it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0 })
        assertEquals(first, ForceLayout().layout(graph))
        // Nodes are spread out, not collapsed onto a few points.
        assertTrue(first.values.map { (it.x * 50).toInt() to (it.y * 50).toInt() }.toSet().size > 250)
    }

    /**
     * Edges that cut across clusters: groups of 10 consecutive nodes are chained together, while
     * cluster membership cycles through 5 clusters, so only edge attraction can bring them together.
     */
    private fun crossCluster(): MemoryGraphSnapshot {
        val nodes = (0 until 300).map { node("mem:n${it.toString().padStart(3, '0')}", "cl:agent:a${it % 5}") }
        val edges = nodes.chunked(10).flatMap { group -> group.zipWithNext() }.mapIndexed { i, (a, b) -> edge(i, a.id, b.id) }
        return snapshot(nodes, edges)
    }

    @Test
    fun `connected nodes end closer together than unconnected ones`() {
        for (graph in listOf(large(), crossCluster())) assertConnectedCloser(graph)
    }

    private fun assertConnectedCloser(graph: MemoryGraphSnapshot) {
        val positions = ForceLayout().layout(graph)
        val connected = graph.edges.filter { it.source != it.target }.map { distance(positions.getValue(it.source), positions.getValue(it.target)) }.average()
        val random = Random(7)
        val linked = graph.edges.map { setOf(it.source, it.target) }.toSet()
        val ids = graph.nodes.map { it.id }
        val unconnected = (0 until 2_000).mapNotNull {
            val a = ids[random.nextInt(ids.size)]
            val b = ids[random.nextInt(ids.size)]
            if (a == b || setOf(a, b) in linked) null else distance(positions.getValue(a), positions.getValue(b))
        }.average()
        assertTrue(connected < unconnected * 0.6, "connected $connected vs unconnected $unconnected")
    }

    @Test
    fun `small graphs, duplicates and dangling edges are handled`() {
        assertEquals(emptyMap(), ForceLayout().layout(snapshot(emptyList(), emptyList())))
        assertEquals(mapOf("mem:a" to LayoutPoint(0.5, 0.5)), ForceLayout().layout(snapshot(listOf(node("mem:a", "c")), emptyList())))
        val pair = ForceLayout().layout(
            snapshot(listOf(node("mem:a", "c"), node("mem:b", "c")), listOf(edge(0, "mem:a", "mem:b"), edge(1, "mem:a", "mem:ghost"), edge(2, "mem:a", "mem:a"))),
        )
        assertEquals(setOf("mem:a", "mem:b"), pair.keys)
        assertNotEquals(pair.getValue("mem:a"), pair.getValue("mem:b"))
        assertTrue(pair.values.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
    }

    @Test
    fun `starting from a previous layout keeps the picture recognisable`() {
        val graph = large()
        val before = ForceLayout().layout(graph)
        val after = ForceLayout().layout(graph, initial = before)
        val moved = graph.nodes.map { distance(before.getValue(it.id), after.getValue(it.id)) }.average()
        val scale = graph.nodes.map { distance(before.getValue(it.id), LayoutPoint(0.5, 0.5)) }.average()
        assertTrue(moved < scale * 0.5, "moved $moved of $scale")
    }

    @Test
    fun `invalid settings are refused`() {
        for (build in listOf({ ForceLayout(iterations = 0) }, { ForceLayout(clusterGravity = -1.0) }, { ForceLayout(clusterGravity = Double.NaN) })) {
            assertTrue(runCatching(build).exceptionOrNull() is IllegalArgumentException)
        }
    }
}
