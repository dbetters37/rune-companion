package com.aicontrolcenter.app.ui.memory.graph

import androidx.compose.ui.geometry.Offset
import com.aicontrolcenter.app.ui.memory.ScopeKind
import com.aicontrolcenter.app.ui.memory.ScopeUi
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.LinkRelation
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphModelTest {
    private val width = 400f
    private val height = 600f
    private val padding = 20f

    private fun node(id: String, x: Float, y: Float, weight: Double = 0.5) =
        GraphNodeUi("mem:$id", MemoryId(id), id, ScopeUi(ScopeKind.PRIVATE, "A", 0), null, weight, 0.0, x, y)

    private val render = GraphRender(
        nodes = listOf(node("a", 0f, 0f), node("b", 0.5f, 0.5f), node("c", 1f, 1f, weight = 1.0)),
        edges = listOf(GraphEdgeUi("link:1", 0, 1, LinkRelation.SUPPORTS, 1.0, true), GraphEdgeUi("link:2", 1, 2, LinkRelation.CONTRADICTS, 0.5, true)),
    )

    @Test
    fun `the unit square is fitted inside the padding and zoom keeps the centre fixed`() {
        val viewport = GraphViewport()
        assertEquals(Offset(padding, padding), viewport.toScreen(0f, 0f, width, height, padding))
        assertEquals(Offset(width - padding, height - padding), viewport.toScreen(1f, 1f, width, height, padding))

        val zoomed = viewport.zoomedBy(2f, width, height)
        assertEquals(Offset(width / 2, height / 2), zoomed.toScreen(0.5f, 0.5f, width, height, padding))
        val corner = zoomed.toScreen(0f, 0f, width, height, padding)
        assertTrue(corner.x < padding && corner.y < padding, "zooming spreads nodes apart")
    }

    @Test
    fun `a pinch keeps the point under the fingers in place`() {
        val centroid = Offset(100f, 150f)
        val zoomed = GraphViewport().transformed(2f, Offset.Zero, centroid, width, height)
        val before = GraphViewport().toScreen(0.2f, 0.25f, width, height, padding)
        val after = zoomed.toScreen(0.2f, 0.25f, width, height, padding)
        // The content point that was under the centroid is under it still.
        val ratio = 2f
        val expected = Offset(centroid.x + (before.x - centroid.x) * ratio, centroid.y + (before.y - centroid.y) * ratio)
        assertEquals(expected.x, after.x, 0.01f)
        assertEquals(expected.y, after.y, 0.01f)
    }

    @Test
    fun `panning is limited so the graph cannot leave the view`() {
        val unzoomed = GraphViewport().transformed(1f, Offset(500f, 500f), Offset(200f, 300f), width, height)
        assertEquals(0f, unzoomed.offsetX)
        assertEquals(0f, unzoomed.offsetY)
        val zoomed = GraphViewport(scale = 2f).transformed(1f, Offset(10_000f, -10_000f), Offset(200f, 300f), width, height)
        assertEquals(width / 2, zoomed.offsetX)
        assertEquals(-height / 2, zoomed.offsetY)
        assertEquals(GraphViewport.MAX_SCALE, GraphViewport().transformed(100f, Offset.Zero, Offset.Zero, width, height).scale)
    }

    @Test
    fun `a tap hits the nearest node within its radius and slop, and nothing elsewhere`() {
        val viewport = GraphViewport()
        val centre = viewport.toScreen(0.5f, 0.5f, width, height, padding)
        assertEquals(1, render.hitTest(centre + Offset(10f, 0f), viewport, width, height, padding, slop = 4f) { it.radiusDp })
        assertNull(render.hitTest(centre + Offset(40f, 0f), viewport, width, height, padding, slop = 4f) { it.radiusDp })
        assertEquals(2, render.hitTest(Offset(width - padding, height - padding), viewport, width, height, padding, slop = 0f) { it.radiusDp })
    }

    @Test
    fun `neighbours and relations come from the edges`() {
        assertEquals(setOf(0, 2), render.neighbours(1))
        assertEquals(setOf(1), render.neighbours(0))
        assertEquals(listOf(LinkRelation.SUPPORTS, LinkRelation.CONTRADICTS), render.relations)
        assertEquals(1, render.indexOf("mem:b"))
        assertNull(render.indexOf("mem:zz"))
        assertEquals(16f, render.nodes[2].radiusDp, "the most important node is the largest")
    }
}
