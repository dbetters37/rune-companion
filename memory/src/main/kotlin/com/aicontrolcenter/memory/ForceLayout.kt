package com.aicontrolcenter.memory

import com.aicontrolcenter.core.memory.MemoryGraphSnapshot
import kotlin.math.floor
import kotlin.math.sqrt

/** A node position in the unit square: x and y within 0..1, (0, 0) at the top left. */
data class LayoutPoint(val x: Double, val y: Double)

/**
 * A force-directed layout (Fruchterman-Reingold) for a [MemoryGraphSnapshot], for the app's
 * graph view. Pure and deterministic: the same snapshot always gives the same positions, on
 * every device, so the graph does not reshuffle each time it is opened.
 *
 * - Starting positions are seeded from each node id's [StableHash], around a centre per primary
 *   cluster (the first cluster that is not a kind cluster: the agent or space), so clusters
 *   start apart and nodes of one owner start together. Positions passed in `initial` (for
 *   example the user's last view) are used instead where given.
 * - Each of [iterations] steps applies repulsion between nearby nodes, attraction along edges
 *   (stronger for heavier edges), a pull of each node towards its cluster's centre
 *   ([clusterGravity]) and a weak pull towards the middle so separate parts stay in view.
 *   Moves are capped by a temperature that cools linearly.
 * - Repulsion only acts within two ideal edge lengths, found through a uniform grid, so a step
 *   costs about O(nodes + edges) instead of O(nodes²): 500 nodes lay out in well under a second
 *   on a phone.
 * - Trigonometry uses [StrictMath] and every loop runs in node-id order, so results are
 *   bit-for-bit reproducible.
 */
class ForceLayout(
    val iterations: Int = 250,
    val clusterGravity: Double = 0.5,
) {
    init {
        require(iterations in 1..10_000) { "Iterations must be within 1..10000." }
        require(clusterGravity.isFinite() && clusterGravity >= 0.0) { "Cluster gravity must be finite and not negative." }
    }

    /** Positions keyed by node id, for every node of [snapshot]. */
    fun layout(snapshot: MemoryGraphSnapshot, initial: Map<String, LayoutPoint> = emptyMap()): Map<String, LayoutPoint> {
        val byId = snapshot.nodes.associateBy { it.id }
        val ids = byId.keys.sorted()
        val n = ids.size
        if (n == 0) return emptyMap()
        if (n == 1) return mapOf(ids[0] to LayoutPoint(0.5, 0.5))
        val index = HashMap<String, Int>(n * 2).apply { ids.forEachIndexed { i, id -> put(id, i) } }

        val primary = ids.map { id -> byId.getValue(id).clusterIds.let { c -> c.firstOrNull { !it.startsWith("cl:kind:") } ?: c.firstOrNull() ?: "" } }
        val groups = primary.distinct().sorted()
        val groupOf = IntArray(n) { groups.binarySearch(primary[it]) }

        val x = DoubleArray(n)
        val y = DoubleArray(n)
        place(ids, groupOf, groups.size, initial, x, y)

        val edgeA = ArrayList<Int>()
        val edgeB = ArrayList<Int>()
        val edgeW = ArrayList<Double>()
        for (edge in snapshot.edges) {
            val a = index[edge.source] ?: continue
            val b = index[edge.target] ?: continue
            if (a == b) continue
            edgeA += a
            edgeB += b
            edgeW += 0.2 + 0.8 * (if (edge.weight.isFinite()) edge.weight.coerceIn(0.0, 1.0) else 1.0)
        }

        val k = IDEAL_SCALE / sqrt(n.toDouble())
        val dx = DoubleArray(n)
        val dy = DoubleArray(n)
        val cx = DoubleArray(groups.size)
        val cy = DoubleArray(groups.size)
        val count = IntArray(groups.size).also { c -> groupOf.forEach { c[it]++ } }
        for (step in 0 until iterations) {
            dx.fill(0.0)
            dy.fill(0.0)
            repel(x, y, k, dx, dy)
            for (e in edgeA.indices) {
                val a = edgeA[e]
                val b = edgeB[e]
                val ex = x[a] - x[b]
                val ey = y[a] - y[b]
                val d = sqrt(ex * ex + ey * ey)
                if (d == 0.0) continue
                val f = d / k * edgeW[e] // (d² / k) along the unit vector
                dx[a] -= ex * f
                dy[a] -= ey * f
                dx[b] += ex * f
                dy[b] += ey * f
            }
            cx.fill(0.0)
            cy.fill(0.0)
            for (i in 0 until n) {
                cx[groupOf[i]] += x[i]
                cy[groupOf[i]] += y[i]
            }
            for (i in 0 until n) {
                val g = groupOf[i]
                dx[i] += clusterGravity * (cx[g] / count[g] - x[i]) - CENTER_PULL * k * x[i]
                dy[i] += clusterGravity * (cy[g] / count[g] - y[i]) - CENTER_PULL * k * y[i]
            }
            val temperature = START_TEMPERATURE * (1.0 - step.toDouble() / iterations) + MIN_TEMPERATURE
            for (i in 0 until n) {
                val len = sqrt(dx[i] * dx[i] + dy[i] * dy[i])
                if (len == 0.0 || !len.isFinite()) continue
                val move = minOf(len, temperature) / len
                x[i] += dx[i] * move
                y[i] += dy[i] * move
            }
        }
        return normalise(ids, x, y)
    }

    private fun place(ids: List<String>, groupOf: IntArray, groupCount: Int, initial: Map<String, LayoutPoint>, x: DoubleArray, y: DoubleArray) {
        val spread = if (groupCount == 1) 0.4 else minOf(0.25, 0.7 / sqrt(groupCount.toDouble()))
        for (i in ids.indices) {
            val given = initial[ids[i]]
            if (given != null && given.x.isFinite() && given.y.isFinite()) {
                x[i] = given.x.coerceIn(0.0, 1.0) - 0.5
                y[i] = given.y.coerceIn(0.0, 1.0) - 0.5
                continue
            }
            val g = groupOf[i]
            val centreAngle = 2 * Math.PI * g / groupCount
            val centreX = if (groupCount == 1) 0.0 else GROUP_RING * StrictMath.cos(centreAngle)
            val centreY = if (groupCount == 1) 0.0 else GROUP_RING * StrictMath.sin(centreAngle)
            val angle = 2 * Math.PI * StableHash.unit(ids[i], 1)
            val radius = spread * sqrt(StableHash.unit(ids[i], 2))
            x[i] = centreX + radius * StrictMath.cos(angle)
            y[i] = centreY + radius * StrictMath.sin(angle)
        }
    }

    /** Fruchterman-Reingold repulsion k²/d between nodes closer than 2k, found through a grid of 2k cells. */
    private fun repel(x: DoubleArray, y: DoubleArray, k: Double, dx: DoubleArray, dy: DoubleArray) {
        val cell = 2 * k
        val grid = HashMap<Long, MutableList<Int>>()
        val cellX = IntArray(x.size)
        val cellY = IntArray(x.size)
        for (i in x.indices) {
            cellX[i] = floor(x[i] / cell).toInt()
            cellY[i] = floor(y[i] / cell).toInt()
            grid.getOrPut(key(cellX[i], cellY[i])) { ArrayList(4) }.add(i)
        }
        val k2 = k * k
        for (i in x.indices) {
            for (ox in -1..1) for (oy in -1..1) {
                val members = grid[key(cellX[i] + ox, cellY[i] + oy)] ?: continue
                for (j in members) {
                    if (j <= i) continue
                    var ex = x[i] - x[j]
                    var ey = y[i] - y[j]
                    var d2 = ex * ex + ey * ey
                    if (d2 < 1e-18) {
                        // Coincident nodes: separate them in a direction fixed by their indices.
                        ex = if ((i + j) % 2 == 0) 1e-6 else -1e-6
                        ey = 1e-6
                        d2 = ex * ex + ey * ey
                    }
                    if (d2 >= cell * cell) continue
                    val f = k2 / d2 // (k² / d) along the unit vector
                    dx[i] += ex * f
                    dy[i] += ey * f
                    dx[j] -= ex * f
                    dy[j] -= ey * f
                }
            }
        }
    }

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    /** Fits the layout into the unit square with a margin, keeping its aspect ratio and centring it. */
    private fun normalise(ids: List<String>, x: DoubleArray, y: DoubleArray): Map<String, LayoutPoint> {
        val minX = x.min()
        val minY = y.min()
        val width = x.max() - minX
        val height = y.max() - minY
        val size = maxOf(width, height)
        val out = LinkedHashMap<String, LayoutPoint>(ids.size * 2)
        if (size <= 1e-12 || !size.isFinite()) {
            ids.forEach { out[it] = LayoutPoint(0.5, 0.5) }
            return out
        }
        val usable = 1.0 - 2 * MARGIN
        val padX = (size - width) / 2
        val padY = (size - height) / 2
        for (i in ids.indices) {
            out[ids[i]] = LayoutPoint(
                (MARGIN + (x[i] - minX + padX) / size * usable).coerceIn(0.0, 1.0),
                (MARGIN + (y[i] - minY + padY) / size * usable).coerceIn(0.0, 1.0),
            )
        }
        return out
    }

    private companion object {
        /** k = IDEAL_SCALE * sqrt(area / n) with area 1. */
        const val IDEAL_SCALE = 0.8
        const val START_TEMPERATURE = 0.08
        const val MIN_TEMPERATURE = 0.001
        const val CENTER_PULL = 3.0
        const val GROUP_RING = 0.3
        const val MARGIN = 0.04
    }
}
