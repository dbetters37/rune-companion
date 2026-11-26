package com.aicontrolcenter.app.ui.team

import org.junit.Test
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the Team view puts everyone: no two 48 dp targets touch, for 1 to 30 agents, on a small phone and a large one. */
class TeamLayoutTest {
    private fun ring(n: Int) = (1..n).map { "agt_$it" }

    private val sizes = listOf(360f to 640f, 600f to 960f, 360f to 420f, 900f to 520f)

    @Test
    fun `no two touch targets overlap and everything stays inside, for 1 to 30 agents`() {
        for ((width, height) in sizes) for (n in 0..30) for (spaces in listOf(0, 3, 6)) {
            val placed = TeamLayout.arrange(true, "agt_director", ring(n), (1..spaces).map { "space:$it" }, width, height)
            val points = placed.positions.values.toList()
            assertEquals(n + 2 + spaces, points.size, "$n agents at ${width}x$height")
            for (i in points.indices) for (j in i + 1 until points.size) {
                val gap = hypot(points[i].x - points[j].x, points[i].y - points[j].y)
                assertTrue(gap >= TeamLayout.TOUCH, "$n agents, $spaces spaces at ${width}x$height: two targets $gap dp apart")
            }
            assertTrue(points.all { it.x in 0f..placed.width && it.y in 0f..placed.height })
            assertTrue(placed.height >= height, "the picture never shrinks below the screen")
            assertEquals(width, placed.width)
        }
    }

    @Test
    fun `you at the top, the Director in the centre, spaces along the bottom`() {
        val placed = TeamLayout.arrange(true, "agt_director", ring(5), listOf("space:a", "space:b"), 360f, 640f)
        val you = placed.positions.getValue(YOU_ID)
        val director = placed.positions.getValue("agt_director")
        assertEquals(180f, you.x)
        assertEquals(180f, director.x)
        assertTrue(placed.positions.values.all { it.y >= you.y }, "nobody above You")
        val spaces = listOf("space:a", "space:b").map { placed.positions.getValue(it) }
        assertTrue(spaces.all { s -> placed.positions.filterKeys { !it.startsWith("space:") }.values.all { it.y < s.y } }, "spaces at the bottom")
        assertTrue(placed.showLabels, "five agents on a phone have room for their names")
        // Nobody sits on the line from You to the Director.
        assertTrue(ring(5).map { placed.positions.getValue(it) }.none { kotlin.math.abs(it.x - 180f) < 1f && it.y < director.y })
    }

    @Test
    fun `positions depend only on who is in the picture, and a big team on a small screen gets a taller picture`() {
        val a = TeamLayout.arrange(true, "d", ring(7), emptyList(), 360f, 640f)
        val b = TeamLayout.arrange(true, "d", ring(7), emptyList(), 360f, 640f)
        assertEquals(a, b)
        val crowd = TeamLayout.arrange(true, "d", ring(30), listOf("space:1"), 360f, 420f)
        assertTrue(crowd.height > 420f, "30 agents need more than 420 dp: the picture pans")
        assertTrue(!crowd.showLabels, "names would touch: TalkBack, the sheet and the list name everyone")
        assertEquals(TeamLayout.SMALL_NODE_SIZE, crowd.nodeSize)
    }

    @Test
    fun `without a Director a lone agent is centred and without You the ring starts at the top`() {
        val lone = TeamLayout.arrange(false, null, ring(1), emptyList(), 360f, 640f)
        assertEquals(lone.centre, lone.positions.getValue("agt_1"))
        val ringOnly = TeamLayout.arrange(false, "d", ring(4), emptyList(), 360f, 640f)
        val first = ringOnly.positions.getValue("agt_1")
        assertEquals(180f, first.x, 0.01f)
        assertTrue(first.y < ringOnly.centre.y)
    }

    @Test
    fun `the signs of lines sit on no member and on no other sign`() {
        for (n in listOf(3, 8, 20, 30)) {
            val ring = ring(n)
            val placed = TeamLayout.arrange(true, "d", ring, listOf("space:1"), 360f, 640f)
            val lines = ring.map { id ->
                val link = TeamLink(linkKeyOf("d", id), "d", id, LinkKind.ASSIGNS, emptyList(), true, false, 1, 0, false, emptyList())
                link.key to LinkLine.of(placed, link) { if (it == "d") TeamNodeKind.DIRECTOR else TeamNodeKind.AGENT }!!
            }
            val signs = LinkSigns.place(placed, lines, 12)
            assertTrue(signs.isNotEmpty(), "$n agents: some line has room for its sign")
            val spots = signs.values.toList()
            for (s in spots) for (p in placed.positions.values) assertTrue(hypot(s.x - p.x, s.y - p.y) >= TeamLayout.TOUCH, "$n agents: a sign on a member")
            for (i in spots.indices) for (j in i + 1 until spots.size) {
                assertTrue(hypot(spots[i].x - spots[j].x, spots[i].y - spots[j].y) >= TeamLayout.TOUCH, "$n agents: two signs touch")
            }
            assertTrue(signs.size <= 12)
        }
    }
}
