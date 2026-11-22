package com.aicontrolcenter.app.ui.dashboard

import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.dashboard.network.AgentNetwork
import com.aicontrolcenter.app.ui.dashboard.network.NetworkLayout
import com.aicontrolcenter.app.ui.dashboard.network.NetworkNode
import com.aicontrolcenter.app.ui.dashboard.network.UnitPoint
import com.aicontrolcenter.app.ui.dashboard.network.Viewport
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.agents.AgentKind
import org.junit.Test
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkLayoutTest {
    private fun node(id: String, hub: Boolean = false) =
        NetworkNode(id, id, if (hub) AgentKind.DIRECTOR else AgentKind.SPECIALIST, 0, StatusLabel("Ready", StatusTone.READY), hub)

    private fun network(satellites: Int, withHub: Boolean = true) = AgentNetwork(
        nodes = listOfNotNull(node("hub", hub = true).takeIf { withHub }) + (1..satellites).map { node("n$it") },
    )

    @Test
    fun `the hub is centred and the first satellite sits straight above it`() {
        val positions = NetworkLayout.arrange(network(5))
        assertEquals(UnitPoint(0f, 0f), positions["hub"])
        val first = positions.getValue("n1")
        assertEquals(0f, first.x, 1e-5f)
        assertEquals(-1f, first.y, 1e-5f)
        assertTrue(positions.filterKeys { it != "hub" }.values.all { hypot(it.x, it.y) in 0.999f..1.001f })
    }

    @Test
    fun `every node gets its own place, also when a second ring is needed`() {
        for (count in listOf(1, 2, 7, NetworkLayout.MAX_SINGLE_RING, NetworkLayout.MAX_SINGLE_RING + 1, 30)) {
            val positions = NetworkLayout.arrange(network(count))
            assertEquals(count + 1, positions.size)
            val points = positions.values.toList()
            for (i in points.indices) for (j in i + 1 until points.size) {
                assertTrue(hypot(points[i].x - points[j].x, points[i].y - points[j].y) > 0.05f, "$count nodes: two overlap")
            }
            assertTrue(points.all { it.x in -1.001f..1.001f && it.y in -1.001f..1.001f })
        }
    }

    @Test
    fun `without a hub a single agent is centred and several form a ring`() {
        assertEquals(mapOf("n1" to UnitPoint(0f, 0f)), NetworkLayout.arrange(network(1, withHub = false)))
        assertEquals(3, NetworkLayout.arrange(network(3, withHub = false)).size)
        assertEquals(emptyMap(), NetworkLayout.arrange(AgentNetwork()))
    }

    @Test
    fun `the viewport never zooms out past the whole picture or pans it out of sight`() {
        val start = Viewport()
        assertFalse(start.isZoomed)
        assertFalse(start.canZoomOut)
        assertEquals(start, start.transformedBy(0.5f, 400f, 400f, 1000f, 600f), "an unzoomed view cannot be dragged away")

        var zoomed = start
        repeat(10) { zoomed = zoomed.zoomedIn(1000f, 600f) }
        assertEquals(Viewport.MAX_SCALE, zoomed.scale)
        assertFalse(zoomed.canZoomIn)

        val panned = zoomed.transformedBy(1f, 99_999f, -99_999f, 1000f, 600f)
        assertEquals((Viewport.MAX_SCALE - 1f) * 500f, panned.offsetX)
        assertEquals(-(Viewport.MAX_SCALE - 1f) * 300f, panned.offsetY)

        var back = panned
        repeat(10) { back = back.zoomedOut(1000f, 600f) }
        assertEquals(Viewport(), back, "zooming all the way out re-centres the view")
    }
}
