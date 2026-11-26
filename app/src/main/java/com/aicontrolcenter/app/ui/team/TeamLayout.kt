package com.aicontrolcenter.app.ui.team

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A point in dp from the top-left of the picture. */
@Immutable
data class DpPoint(val x: Float, val y: Float)

/**
 * Where everything sits in the picture, in dp: "You" at the top, the Director in the centre, the agents on one or two
 * rings around it (clockwise from the top), spaces along the bottom. [height] can be taller than the screen when a big
 * team needs the room to keep every touch target 48 dp and apart; the picture then pans.
 */
@Immutable
data class TeamPlacement(
    val positions: Map<String, DpPoint>,
    val width: Float,
    val height: Float,
    /** The avatar of an agent on the ring. */
    val nodeSize: Float,
    /** Names under the avatars; off when they would touch (then TalkBack, the sheet and the list name everyone). */
    val showLabels: Boolean,
    /** The centre of the ring (the Director's place), for bowing curves away from it. */
    val centre: DpPoint,
) {
    fun sizeOf(kind: TeamNodeKind): Float = when (kind) {
        TeamNodeKind.OWNER -> TeamLayout.YOU_SIZE
        TeamNodeKind.DIRECTOR -> TeamLayout.HUB_SIZE
        TeamNodeKind.SPACE -> TeamLayout.SPACE_SIZE
        TeamNodeKind.AGENT, TeamNodeKind.FORMER_AGENT -> nodeSize
    }
}

/**
 * Places the Team view's nodes. Positions depend only on who is in the picture (ids and their order), never on what they
 * are doing, so an agent keeps its place while the team works. Every node's 48 dp touch target is kept clear of every
 * other one: when the screen is too small, the picture grows taller (it pans) instead of shrinking targets.
 */
object TeamLayout {
    const val TOUCH = 48f
    const val NODE_SIZE = 44f
    const val SMALL_NODE_SIZE = 40f
    const val HUB_SIZE = 56f
    const val YOU_SIZE = 48f
    const val SPACE_SIZE = 40f
    const val LABEL_WIDTH = 88f
    const val LABEL_HEIGHT = 18f
    const val MARGIN = 8f

    /** Centre to centre, so two 48 dp targets never touch. */
    const val MIN_GAP = 52f

    /** Most agents on one ring before a second, inner ring is used. */
    const val MAX_SINGLE_RING = 10
    private const val INNER = 0.56f

    fun arrange(
        hasOwner: Boolean,
        directorId: String?,
        ring: List<String>,
        spaces: List<String>,
        width: Float,
        height: Float,
    ): TeamPlacement {
        val labelled = tryHeights(hasOwner, directorId, ring, spaces, width, height, labels = true, maxGrowth = 1f)
        if (labelled != null) return labelled
        return tryHeights(hasOwner, directorId, ring, spaces, width, height, labels = false, maxGrowth = 4f)
            ?: place(hasOwner, directorId, ring, spaces, width, height * 4f, labels = false)
    }

    private fun tryHeights(
        hasOwner: Boolean,
        directorId: String?,
        ring: List<String>,
        spaces: List<String>,
        width: Float,
        height: Float,
        labels: Boolean,
        maxGrowth: Float,
    ): TeamPlacement? {
        var h = height
        while (h <= height * maxGrowth + 0.5f) {
            val placed = place(hasOwner, directorId, ring, spaces, width, h, labels)
            if (fits(placed, hasOwner, directorId, spaces)) return placed
            h += 24f
        }
        return null
    }

    private fun place(
        hasOwner: Boolean,
        directorId: String?,
        ring: List<String>,
        spaces: List<String>,
        width: Float,
        height: Float,
        labels: Boolean,
    ): TeamPlacement {
        val positions = LinkedHashMap<String, DpPoint>()
        val label = if (labels) LABEL_HEIGHT else 0f
        val nodeSize = if (ring.size > MAX_SINGLE_RING) SMALL_NODE_SIZE else NODE_SIZE
        val top = if (hasOwner) MARGIN + TOUCH + label + MARGIN else MARGIN
        val bottom = if (spaces.isNotEmpty()) height - (MARGIN + TOUCH + label + MARGIN) else height - MARGIN
        if (hasOwner) positions[YOU_ID] = DpPoint(width / 2, MARGIN + TOUCH / 2)
        val centre = DpPoint(width / 2, (top + bottom) / 2 - label / 2)
        directorId?.let { positions[it] = centre }
        val halfWidth = if (labels) LABEL_WIDTH / 2 else TOUCH / 2
        val rx = (width / 2 - MARGIN - halfWidth).coerceAtLeast(0f)
        val ry = ((bottom - top) / 2 - TOUCH / 2 - label / 2).coerceAtLeast(0f)
        when {
            ring.isEmpty() -> Unit
            directorId == null && ring.size == 1 -> positions[ring.single()] = centre
            ring.size <= MAX_SINGLE_RING -> placeRing(ring, centre, rx, ry, hasOwner, positions)
            else -> {
                // The outer ring is longer, so it takes the larger share.
                val outer = ring.take(ceil(ring.size * 0.62).toInt())
                val inner = ring.drop(outer.size)
                placeRing(outer, centre, rx, ry, hasOwner, positions)
                placeRing(inner, centre, rx * INNER, ry * INNER, hasOwner = true, positions, halfStep = true)
            }
        }
        if (spaces.isNotEmpty()) {
            val slot = width / spaces.size
            val y = height - MARGIN - label - TOUCH / 2
            spaces.forEachIndexed { index, id -> positions[id] = DpPoint(slot * index + slot / 2, y) }
        }
        return TeamPlacement(positions, width, height, nodeSize, labels, centre)
    }

    /**
     * One ring, clockwise from the top, the nodes spaced evenly along it (by length, not by angle, so a tall narrow ring
     * does not crowd its top and bottom). With "You" above it, the first place is half a step past the top, so nobody
     * sits on the line from "You" to the Director.
     */
    private fun placeRing(
        ids: List<String>,
        centre: DpPoint,
        rx: Float,
        ry: Float,
        hasOwner: Boolean,
        into: MutableMap<String, DpPoint>,
        halfStep: Boolean = false,
    ) {
        val samples = 720
        val start = -PI / 2
        val points = (0..samples).map { i ->
            val angle = start + 2 * PI * i / samples
            DpPoint((centre.x + rx * cos(angle)).toFloat(), (centre.y + ry * sin(angle)).toFloat())
        }
        val lengths = FloatArray(samples + 1)
        for (i in 1..samples) lengths[i] = lengths[i - 1] + hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        val total = lengths[samples]
        val step = total / ids.size
        val offset = if (hasOwner || halfStep) step / 2 else 0f
        var j = 0
        ids.forEachIndexed { index, id ->
            val target = offset + index * step
            while (j < samples && lengths[j + 1] < target) j++
            val span = (lengths[j + 1] - lengths[j]).takeIf { it > 0f } ?: 1f
            val f = ((target - lengths[j]) / span).coerceIn(0f, 1f)
            val a = points[j]
            val b = points[j + 1]
            into[id] = DpPoint(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
        }
    }

    /** Every pair of targets apart, names (when shown) not on top of each other, everything inside the picture. */
    private fun fits(placed: TeamPlacement, hasOwner: Boolean, directorId: String?, spaces: List<String>): Boolean {
        val points = placed.positions.entries.toList()
        val inside = points.all { (_, p) ->
            p.x >= TOUCH / 2 - 0.5f && p.x <= placed.width - TOUCH / 2 + 0.5f && p.y >= TOUCH / 2 - 0.5f && p.y <= placed.height - TOUCH / 2 + 0.5f
        }
        if (!inside) return false
        val spaceIds = spaces.toSet()
        for (i in points.indices) for (j in i + 1 until points.size) {
            val (a, pa) = points[i]
            val (b, pb) = points[j]
            if (hypot(pa.x - pb.x, pa.y - pb.y) < MIN_GAP) return false
            if (placed.showLabels && a !in spaceIds && b !in spaceIds && labelsTouch(pa, pb)) return false
        }
        // Spaces share the bottom band: their own slots keep them apart (checked above).
        return !(hasOwner && directorId != null && placed.positions[YOU_ID] == placed.positions[directorId])
    }

    /**
     * One node's name (a [LABEL_WIDTH] by [LABEL_HEIGHT] strip under its 48 dp target) runs into the other's target or
     * name. Targets alone are kept apart by [MIN_GAP].
     */
    private fun labelsTouch(a: DpPoint, b: DpPoint): Boolean {
        val dx = kotlin.math.abs(a.x - b.x)
        val dy = kotlin.math.abs(a.y - b.y)
        val nameIntoTarget = dx < (LABEL_WIDTH + TOUCH) / 2 && dy < TOUCH + LABEL_HEIGHT
        val nameIntoName = dx < LABEL_WIDTH && dy < LABEL_HEIGHT
        return nameIntoTarget || nameIntoName
    }
}

/**
 * A link's line in dp, from [TeamLink.aId]'s side to [TeamLink.bId]'s, trimmed short of both nodes: straight, or (between
 * two agents on the ring) a curve bowed away from the centre so it never runs under the Director. [mid] is where its
 * sign sits.
 */
@Immutable
data class LinkLine(val start: DpPoint, val control: DpPoint?, val end: DpPoint) {
    val mid: DpPoint get() = at(0.5f)

    /** The point [t] of the way from start to end. */
    fun at(t: Float): DpPoint {
        val c = control ?: return DpPoint(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
        val u = 1 - t
        return DpPoint(
            u * u * start.x + 2 * u * t * c.x + t * t * end.x,
            u * u * start.y + 2 * u * t * c.y + t * t * end.y,
        )
    }

    /** The direction of travel at [t], as a unit vector. */
    fun direction(t: Float): DpPoint {
        val (dx, dy) = if (control == null) {
            (end.x - start.x) to (end.y - start.y)
        } else {
            val u = 1 - t
            (2 * u * (control.x - start.x) + 2 * t * (end.x - control.x)) to (2 * u * (control.y - start.y) + 2 * t * (end.y - control.y))
        }
        val length = hypot(dx, dy).takeIf { it > 0f } ?: return DpPoint(1f, 0f)
        return DpPoint(dx / length, dy / length)
    }

    companion object {
        /** Space left between a line's end and the edge of its node. */
        private const val GAP = 6f

        /** The line of [link] in [placement], or null when one end is not placed (or both sit together). */
        fun of(placement: TeamPlacement, link: TeamLink, kindOf: (String) -> TeamNodeKind?): LinkLine? {
            val a = placement.positions[link.aId] ?: return null
            val b = placement.positions[link.bId] ?: return null
            val ka = kindOf(link.aId) ?: return null
            val kb = kindOf(link.bId) ?: return null
            val curved = ka.onRing && kb.onRing
            val control = if (curved) bow(a, b, placement.centre) else null
            val startInset = placement.sizeOf(ka) / 2 + GAP
            val endInset = placement.sizeOf(kb) / 2 + GAP
            val full = LinkLine(a, control, b)
            val length = if (control == null) hypot(b.x - a.x, b.y - a.y) else hypot(control.x - a.x, control.y - a.y) + hypot(b.x - control.x, b.y - control.y)
            if (length <= startInset + endInset + 4f) return null
            val t0 = startInset / length
            val t1 = 1 - endInset / length
            return if (control == null) {
                LinkLine(full.at(t0), null, full.at(t1))
            } else {
                // The trimmed piece of a quadratic curve is a quadratic curve with this control point.
                val c = DpPoint(
                    (1 - t0) * (1 - t1) * a.x + ((1 - t0) * t1 + t0 * (1 - t1)) * control.x + t0 * t1 * b.x,
                    (1 - t0) * (1 - t1) * a.y + ((1 - t0) * t1 + t0 * (1 - t1)) * control.y + t0 * t1 * b.y,
                )
                LinkLine(full.at(t0), c, full.at(t1))
            }
        }

        private val TeamNodeKind.onRing: Boolean get() = this == TeamNodeKind.AGENT || this == TeamNodeKind.FORMER_AGENT

        /** The midpoint pushed away from the centre (sideways for agents opposite each other). */
        private fun bow(a: DpPoint, b: DpPoint, centre: DpPoint): DpPoint {
            val mid = DpPoint((a.x + b.x) / 2, (a.y + b.y) / 2)
            var ax = mid.x - centre.x
            var ay = mid.y - centre.y
            if (hypot(ax, ay) < 1f) {
                ax = -(b.y - a.y)
                ay = b.x - a.x
            }
            val length = hypot(ax, ay).takeIf { it > 0f } ?: return mid
            val push = hypot(b.x - a.x, b.y - a.y) * 0.3f
            return DpPoint(mid.x + ax / length * push, mid.y + ay / length * push)
        }
    }
}

/** Where the signs at the middle of lines go, so that none sits on a member or on another sign. */
object LinkSigns {
    /** Places tried along a line, from its middle outwards. */
    private val SPOTS = floatArrayOf(0.5f, 0.4f, 0.6f, 0.3f, 0.7f, 0.22f, 0.78f)

    /** Centre to centre: a sign's 48 dp target clear of a member's and of another sign's. */
    private const val CLEAR = TeamLayout.TOUCH + 4f

    /**
     * A spot for each of [lines] (most important first), at most [max]: the first place along the line whose 48 dp target
     * touches no member and no sign already placed. A line with no free place gets no sign (its messages are still one tap
     * away in either member's sheet, and in TalkBack's actions).
     */
    fun place(placement: TeamPlacement, lines: List<Pair<String, LinkLine>>, max: Int): Map<String, DpPoint> {
        val members = placement.positions.values.toList()
        val placed = LinkedHashMap<String, DpPoint>()
        for ((key, line) in lines) {
            if (placed.size >= max) break
            val spot = SPOTS.asSequence().map { line.at(it) }.firstOrNull { p ->
                members.none { hypot(it.x - p.x, it.y - p.y) < CLEAR } && placed.values.none { hypot(it.x - p.x, it.y - p.y) < CLEAR }
            } ?: continue
            placed[key] = spot
        }
        return placed
    }
}
