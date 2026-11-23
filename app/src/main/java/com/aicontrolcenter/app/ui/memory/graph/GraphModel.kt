package com.aicontrolcenter.app.ui.memory.graph

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import com.aicontrolcenter.app.ui.memory.ScopeKind
import com.aicontrolcenter.app.ui.memory.ScopeUi
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryKind
import kotlin.math.sqrt

/** One memory as the graph draws it, at its laid-out position in the unit square. */
@Immutable
data class GraphNodeUi(
    /** The snapshot's node id, "mem:<memory id>". */
    val id: String,
    val memoryId: MemoryId,
    val label: String,
    val scope: ScopeUi,
    /** Null for a kind this version does not know (consumers must accept others). */
    val kind: MemoryKind?,
    /** Importance 0..1: decides the size. */
    val weight: Double,
    /** How recently and often it was used, 0..1: decides the glow. */
    val activation: Double,
    val x: Float,
    val y: Float,
) {
    val shape: ScopeKind get() = scope.kind

    /** Radius in dp: 6 for unimportant memories up to 16 for the most important. Square root, so area grows with weight. */
    val radiusDp: Float get() = 6f + 10f * sqrt(weight.coerceIn(0.0, 1.0)).toFloat()
}

/** One link, between two node indices. */
@Immutable
data class GraphEdgeUi(
    val id: String,
    val source: Int,
    val target: Int,
    /** Null for a relation this version does not know; drawn as a plain line. */
    val relation: LinkRelation?,
    val weight: Double,
    val directed: Boolean,
)

/** Everything the canvas draws. Immutable, so a frame never sees half an update. */
@Immutable
data class GraphRender(val nodes: List<GraphNodeUi> = emptyList(), val edges: List<GraphEdgeUi> = emptyList()) {
    private val neighbourSets: List<Set<Int>> by lazy {
        val sets = List(nodes.size) { HashSet<Int>() }
        edges.forEach { e ->
            sets[e.source] += e.target
            sets[e.target] += e.source
        }
        sets
    }

    fun indexOf(nodeId: String?): Int? = nodeId?.let { id -> nodes.indexOfFirst { it.id == id }.takeIf { it >= 0 } }

    fun neighbours(index: Int): Set<Int> = neighbourSets.getOrElse(index) { emptySet() }

    /** Relations that appear, in their declared order, for the legend. */
    val relations: List<LinkRelation> get() = edges.mapNotNull { it.relation }.distinct().sortedBy { it.ordinal }
}

/**
 * Pan and zoom of the graph view. The layout's unit square is first fitted into the canvas
 * minus [padding] on every side; [scale] then zooms around the canvas centre and the offsets
 * move the result. Node sizes do not scale: zooming spreads memories apart instead of making
 * them bigger, so a dense cluster can be opened up.
 */
@Immutable
data class GraphViewport(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    val isZoomed: Boolean get() = scale > MIN_SCALE + EPSILON
    val canZoomIn: Boolean get() = scale < MAX_SCALE - EPSILON

    /** Where the unit-square point ([x], [y]) is drawn in a canvas of [width] × [height] pixels. */
    fun toScreen(x: Float, y: Float, width: Float, height: Float, padding: Float): Offset {
        val baseX = padding + x * (width - 2 * padding).coerceAtLeast(0f)
        val baseY = padding + y * (height - 2 * padding).coerceAtLeast(0f)
        return Offset(width / 2 + (baseX - width / 2) * scale + offsetX, height / 2 + (baseY - height / 2) * scale + offsetY)
    }

    /**
     * Applies a pinch: zooms by [zoom] around [centroid] (so the point under the fingers stays
     * under them) and moves by [pan]. The result is clamped so the graph cannot be pushed out of view.
     */
    fun transformed(zoom: Float, pan: Offset, centroid: Offset, width: Float, height: Float): GraphViewport {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = newScale / scale
        val cx = width / 2
        val cy = height / 2
        val ox = centroid.x + pan.x - cx - (centroid.x - cx - offsetX) * ratio
        val oy = centroid.y + pan.y - cy - (centroid.y - cy - offsetY) * ratio
        return GraphViewport(newScale, ox, oy).clamped(width, height)
    }

    fun zoomedBy(factor: Float, width: Float, height: Float): GraphViewport =
        transformed(factor, Offset.Zero, Offset(width / 2, height / 2), width, height)

    /** At scale 1 the whole graph fits, so there is nothing to pan; zoomed, the edges may move to the centre and no further. */
    private fun clamped(width: Float, height: Float): GraphViewport {
        val maxX = (scale - 1f) * width / 2
        val maxY = (scale - 1f) * height / 2
        return copy(offsetX = offsetX.coerceIn(-maxX, maxX), offsetY = offsetY.coerceIn(-maxY, maxY))
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f
        const val ZOOM_STEP = 1.5f
        private const val EPSILON = 0.001f
    }
}

/**
 * The node under [position], if any: the nearest node whose drawn circle, widened by [slop]
 * so small nodes stay easy to hit, contains it. [radiusPx] converts a node's dp radius.
 */
fun GraphRender.hitTest(
    position: Offset,
    viewport: GraphViewport,
    width: Float,
    height: Float,
    padding: Float,
    slop: Float,
    radiusPx: (GraphNodeUi) -> Float,
): Int? {
    var best: Int? = null
    var bestDistance = Float.MAX_VALUE
    nodes.forEachIndexed { index, node ->
        val centre = viewport.toScreen(node.x, node.y, width, height, padding)
        val distance = (centre - position).getDistance()
        if (distance <= radiusPx(node) + slop && distance < bestDistance) {
            best = index
            bestDistance = distance
        }
    }
    return best
}
