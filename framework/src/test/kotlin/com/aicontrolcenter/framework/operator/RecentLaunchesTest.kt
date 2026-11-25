package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.AgentId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 6 (WP1c, D5): the app each agent opened last, for a bounded time. */
class RecentLaunchesTest {
    private val a = AgentId("agt_a")
    private val b = AgentId("agt_b")

    @Test
    fun `an agent's latest launch is known for the given time, and only to that agent`() {
        val launches = RecentLaunches()
        launches.record(a, "com.example.mail", at = 1_000)
        assertEquals(RecentLaunches.Launch("com.example.mail", 1_000), launches.latest(a, withinMs = 15_000, now = 1_000))
        assertEquals(RecentLaunches.Launch("com.example.mail", 1_000), launches.latest(a, withinMs = 15_000, now = 15_999))
        assertNull(launches.latest(a, withinMs = 15_000, now = 16_000), "15 s later it is no longer recent")
        assertNull(launches.latest(a, withinMs = 15_000, now = 999), "a launch after now does not count")
        assertNull(launches.latest(b, withinMs = 15_000, now = 1_000), "another agent did not launch it")
        assertTrue(launches.launchedWithin(a, "com.example.mail", 15_000, now = 5_000))
        assertFalse(launches.launchedWithin(a, "com.example.maps", 15_000, now = 5_000))
        assertFalse(launches.launchedWithin(b, "com.example.mail", 15_000, now = 5_000))
    }

    @Test
    fun `a newer launch replaces the older one, and a link records no package`() {
        val launches = RecentLaunches()
        launches.record(a, "com.example.mail", at = 1_000)
        launches.record(a, null, at = 2_000)
        assertEquals(RecentLaunches.Launch(null, 2_000), launches.latest(a, 15_000, now = 3_000))
        assertTrue(launches.launchedWithin(a, null, 15_000, now = 3_000))
        assertFalse(launches.launchedWithin(a, "com.example.mail", 15_000, now = 3_000))
    }

    @Test
    fun `it keeps a bounded number of agents, dropping the one that launched least recently`() {
        val launches = RecentLaunches(maxAgents = 2)
        launches.record(a, "one", at = 1)
        launches.record(b, "two", at = 2)
        launches.record(a, "three", at = 3)
        val c = AgentId("agt_c")
        launches.record(c, "four", at = 4)
        assertNull(launches.latest(b, 15_000, now = 5), "b launched least recently")
        assertEquals("three", launches.latest(a, 15_000, now = 5)?.packageName)
        assertEquals("four", launches.latest(c, 15_000, now = 5)?.packageName)
    }

    @Test
    fun `the operator dependencies and limits carry the launches and the waits of D5`() {
        val limits = OperatorLimits()
        assertEquals(10_000, limits.screenOpenWaitMs)
        assertEquals(15_000, limits.launchRecentMs)
        assertEquals(250, limits.screenPollMs)
        val d = OperatorFixture().dependencies
        assertNull(d.launches.latest(a, limits.launchRecentMs, now = 0), "nothing is recorded yet: no tool records launches in this wave")
    }
}
