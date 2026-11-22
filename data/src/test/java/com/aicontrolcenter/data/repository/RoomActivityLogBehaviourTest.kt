package com.aicontrolcenter.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.data.testing.ActivityFixtures
import com.aicontrolcenter.data.testing.InMemoryDatabaseRule
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/** Behaviour of the Room activity log beyond the shared contract suite. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomActivityLogBehaviourTest {
    @get:Rule val db = InMemoryDatabaseRule()

    @Test
    fun `events with the same timestamp are returned latest-recorded first`() = runTest {
        val log = RoomActivityLog(db.database)
        // Ids deliberately not in recording order: the tie-break is insertion, not the id.
        listOf("evt_m", "evt_z", "evt_a").forEach { log.record(Samples.event(id = it, at = 500)) }
        log.record(Samples.event(id = "evt_older", at = 499))
        assertEquals(listOf("evt_a", "evt_z", "evt_m", "evt_older"), log.recent().map { it.id.value })
        assertEquals(listOf("evt_a", "evt_z"), log.recent(limit = 2).map { it.id.value })
    }

    @Test
    fun `the log is append-only - recording an existing id again changes nothing`() = runTest {
        val log = RoomActivityLog(db.database)
        val original = Samples.event(id = "evt_1", at = 100)
        log.record(original)
        log.record(original.copy(title = "Rewritten", at = 999, severity = Severity.ERROR))
        assertEquals(listOf(original), log.recent())
    }

    @Test
    fun `observers are told about new events, pruning and clearing`() = runTest {
        val log = RoomActivityLog(db.database)
        val old = Samples.event(id = "evt_old", at = 100)
        val new = Samples.event(id = "evt_new", at = 200)
        log.observe().test {
            assertEquals(emptyList(), awaitItem())
            log.record(old)
            assertEquals(listOf(old), awaitItem())
            log.record(new)
            assertEquals(listOf(new, old), awaitItem())
            assertEquals(1, log.prune(olderThan = 150))
            assertEquals(listOf(new), awaitItem())
            log.clear()
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a filtered observer only reports changes to its own result`() = runTest {
        val log = RoomActivityLog(db.database)
        val mine = Samples.event(id = "evt_mine", at = 100, agentId = "agt_a")
        val mineLater = Samples.event(id = "evt_mine_2", at = 300, agentId = "agt_a")
        log.observe(ActivityFilter(agentId = AgentId("agt_a"))).test {
            assertEquals(emptyList(), awaitItem())
            log.record(mine)
            assertEquals(listOf(mine), awaitItem())
            log.record(Samples.event(id = "evt_other", at = 200, agentId = "agt_b"))
            log.record(mineLater)
            // Had the other agent's event caused an emission, it would be a duplicate of [mine].
            assertEquals(listOf(mineLater, mine), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `limit zero is empty and a negative limit is an error`() = runTest {
        val log = RoomActivityLog(db.database)
        log.record(Samples.event())
        assertEquals(emptyList(), log.recent(limit = 0))
        assertEquals(emptyList(), log.observe(limit = 0).first())
        assertFailsWith<IllegalArgumentException> { log.recent(limit = -1) }
        assertFailsWith<IllegalArgumentException> { log.observe(limit = -1) }
    }

    @Test
    fun `pruning removes strictly older events across the whole log`() = runTest {
        val log = RoomActivityLog(db.database)
        val events = ActivityFixtures.events(300)
        events.forEach { log.record(it) }
        val cutoff = events.map { it.at }.sorted()[events.size / 2]
        val expectedRemoved = events.count { it.at < cutoff }

        assertEquals(expectedRemoved, log.prune(olderThan = cutoff))
        assertEquals(events.size - expectedRemoved, log.recent(limit = 10_000).size)
        assertTrue(log.recent(limit = 10_000).all { it.at >= cutoff })
    }

    /**
     * A guard against accidentally quadratic behaviour or a lost index, not a benchmark. The
     * bounds are far above what this takes on a development machine (about a second for the
     * inserts and a few milliseconds per query) so that a busy build server does not fail it.
     */
    @Test
    fun `five thousand events are stored and filtered quickly`() = runTest(timeout = 120.seconds) {
        val log = RoomActivityLog(db.database)
        val events = ActivityFixtures.events(5_000)

        val insertTime = measureTime { events.forEach { log.record(it) } }

        val filters = listOf(
            ActivityFilter(),
            ActivityFilter(taskId = ActivityFixtures.tasks[3]),
            ActivityFilter(agentId = ActivityFixtures.agents[1], types = setOf(ActivityType.MODEL_CALL, ActivityType.TOOL_CALL)),
            ActivityFilter(minSeverity = Severity.WARNING),
            ActivityFilter(rootTaskId = ActivityFixtures.rootTasks[0], minSeverity = Severity.ERROR, egressOnly = true),
            ActivityFilter(egressOnly = true),
        )
        filters.forEach { log.recent(it, limit = 1) } // open the statement cache before timing
        for (filter in filters) {
            val (result, queryTime) = measureTimedValue { log.recent(filter, limit = 200) }
            assertEquals(ActivityFixtures.expected(events, filter, 200), result, "result for $filter")
            assertTrue(result.isNotEmpty(), "the timed query should return rows for $filter")
            assertTrue(queryTime < 1_000.milliseconds, "query for $filter took $queryTime")
            println("activity query $filter: $queryTime for ${result.size} rows")
        }
        println("activity insert of ${events.size} events: $insertTime")
        assertTrue(insertTime < 30.seconds, "inserting ${events.size} events took $insertTime")
    }
}
