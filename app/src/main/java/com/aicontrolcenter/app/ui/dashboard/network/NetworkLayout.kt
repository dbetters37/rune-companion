package com.aicontrolcenter.app.ui.dashboard.network

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** A point in the layout's unit space: the centre is (0, 0) and both axes run from -1 to 1. */
data class UnitPoint(val x: Float, val y: Float)

/**
 * Places the nodes of an [AgentNetwork] in unit space. The hub sits at the centre and the
 * other agents on a ring around it, starting at the top and going clockwise in registry order,
 * so an agent keeps its place when unrelated agents change. A crowded team spills into a
 * second, inner ring rather than shrinking every node.
 */
object NetworkLayout {
    /** Most satellites a single ring holds before a second ring is used. */
    const val MAX_SINGLE_RING = 10

    private const val OUTER_RADIUS = 1f
    private const val INNER_RADIUS = 0.56f

    fun arrange(network: AgentNetwork): Map<String, UnitPoint> {
        val hub = network.hub
        val satellites = network.nodes.filter { it.id != hub?.id }
        val positions = LinkedHashMap<String, UnitPoint>()
        if (hub != null) positions[hub.id] = UnitPoint(0f, 0f)
        if (satellites.isEmpty()) return positions
        if (hub == null && satellites.size == 1) {
            positions[satellites.single().id] = UnitPoint(0f, 0f)
            return positions
        }
        if (satellites.size <= MAX_SINGLE_RING) {
            placeRing(satellites, OUTER_RADIUS, startAngle = -PI / 2, into = positions)
        } else {
            // The outer ring has the longer circumference, so it takes the larger share.
            val outerCount = ceil(satellites.size * 0.62).toInt()
            val outer = satellites.take(outerCount)
            val inner = satellites.drop(outerCount)
            placeRing(outer, OUTER_RADIUS, startAngle = -PI / 2, into = positions)
            // Offset by half a step so inner nodes sit between outer ones, not in front of them.
            placeRing(inner, INNER_RADIUS, startAngle = -PI / 2 + PI / inner.size, into = positions)
        }
        return positions
    }

    private fun placeRing(nodes: List<NetworkNode>, radius: Float, startAngle: Double, into: MutableMap<String, UnitPoint>) {
        val step = 2 * PI / nodes.size
        nodes.forEachIndexed { index, node ->
            val angle = startAngle + index * step
            into[node.id] = UnitPoint((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
        }
    }
}

/**
 * Zoom and pan of the network view, in pixels. [scale] is applied about the centre of the view
 * and the offset is applied afterwards; both are kept within limits so the content can never
 * be lost off-screen.
 */
data class Viewport(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    val isZoomed: Boolean get() = scale > 1f + EPSILON
    val canZoomIn: Boolean get() = scale < MAX_SCALE - EPSILON
    val canZoomOut: Boolean get() = isZoomed

    /**
     * Applies a pinch ([zoomChange], a multiplier) and a drag ([panX], [panY]) inside a view
     * of [width] by [height] pixels.
     */
    fun transformedBy(zoomChange: Float, panX: Float, panY: Float, width: Float, height: Float): Viewport {
        val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        val maxX = (newScale - 1f) * width / 2f
        val maxY = (newScale - 1f) * height / 2f
        return Viewport(
            scale = newScale,
            offsetX = (offsetX + panX).coerceIn(-maxX, maxX),
            offsetY = (offsetY + panY).coerceIn(-maxY, maxY),
        )
    }

    fun zoomedIn(width: Float, height: Float): Viewport = transformedBy(ZOOM_STEP, 0f, 0f, width, height)

    fun zoomedOut(width: Float, height: Float): Viewport = transformedBy(1f / ZOOM_STEP, 0f, 0f, width, height)

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 2.5f
        const val ZOOM_STEP = 1.35f
        private const val EPSILON = 0.001f
    }
}
