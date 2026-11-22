package com.aicontrolcenter.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.data.db.ActivityQuery
import com.aicontrolcenter.data.db.ActivityQueryBuilder
import com.aicontrolcenter.data.db.SqlArg
import com.aicontrolcenter.data.testing.ActivityFixtures
import com.aicontrolcenter.data.testing.InMemoryDatabaseRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks every combination of activity filters against the real database: the rows returned
 * are exactly those of the reference implementation, and SQLite's own query plan shows each
 * statement being answered from the intended index.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomActivityLogQueryTest {
    @get:Rule val db = InMemoryDatabaseRule()

    @Test
    fun `every filter combination returns what the reference implementation returns`() = runTest {
        val log = RoomActivityLog(db.database)
        val events = ActivityFixtures.events(600)
        events.forEach { log.record(it) }

        for (filter in ActivityFixtures.allFilterCombinations) {
            for (limit in listOf(1, 25, 1_000)) {
                val expected = ActivityFixtures.expected(events, filter, limit).map { it.id.value }
                assertEquals(expected, log.recent(filter, limit).map { it.id.value }, "recent($filter, limit=$limit)")
            }
        }
        // The grid must actually exercise the filters, not compare empty lists throughout.
        val nonEmpty = ActivityFixtures.allFilterCombinations.count { ActivityFixtures.expected(events, it, 1).isNotEmpty() }
        assertTrue(nonEmpty > ActivityFixtures.allFilterCombinations.size / 2, "only $nonEmpty combinations matched anything")
    }

    @Test
    fun `observing returns the same rows as querying for every filter combination`() = runTest {
        val log = RoomActivityLog(db.database)
        val events = ActivityFixtures.events(200)
        events.forEach { log.record(it) }
        for (filter in ActivityFixtures.allFilterCombinations) {
            assertEquals(ActivityFixtures.expected(events, filter, 10), log.observe(filter, 10).first(), "observe($filter)")
        }
    }

    @Test
    fun `every filter combination is answered from an index in display order`() = runTest {
        val log = RoomActivityLog(db.database)
        ActivityFixtures.events(50).forEach { log.record(it) }

        for (filter in ActivityFixtures.allFilterCombinations) {
            val query = ActivityQueryBuilder.build(filter, limit = 50)
            val plan = explain(query)
            val description = "$filter\n  ${query.sql}\n  plan: $plan"
            val tableSteps = plan.filter { "activity_events" in it }
            val ranges = query.sql.split(" UNION ALL ").size

            assertEquals(ranges, tableSteps.size, "one index access per range expected for $description")
            for (step in tableSteps) {
                val index = step.substringAfter("USING INDEX ", missingDelimiterValue = "").substringBefore(' ')
                if (index.isEmpty()) fail("a step reads the table without an index for $description")
                assertTrue(index in query.sql, "the plan uses $index, not the pinned index, for $description")
                if (filter.isSelective()) {
                    assertTrue(step.startsWith("SEARCH"), "expected an index range lookup, not a walk, for $description")
                }
            }
            if (ranges == 1) {
                // The index already yields newest-first: no sorting step, so LIMIT stops the read early.
                assertFalse(plan.any { "TEMP B-TREE" in it }, "unexpected sort for $description")
            }
        }
    }

    @Test
    fun `the unfiltered query walks the time index newest-first without sorting`() = runTest {
        RoomActivityLog(db.database).record(ActivityFixtures.events(1).single())
        val plan = explain(ActivityQueryBuilder.build(ActivityFilter(), limit = 200))
        assertEquals(listOf("SCAN activity_events USING INDEX index_activity_events_at"), plan)
    }

    @Test
    fun `pruning by age uses the time index`() = runTest {
        RoomActivityLog(db.database).record(ActivityFixtures.events(1).single())
        val plan = explain(ActivityQuery("DELETE FROM activity_events WHERE at < ?", listOf(SqlArg.Integer(5))))
        assertEquals(1, plan.size)
        assertTrue(plan.single().startsWith("SEARCH activity_events USING") && "INDEX index_activity_events_at" in plan.single(), plan.toString())
    }

    private fun ActivityFilter.isSelective(): Boolean = this != ActivityFilter() &&
        ActivityQueryBuilder.build(this, 1) != ActivityQueryBuilder.build(ActivityFilter(), 1)

    /** The `detail` column of SQLite's EXPLAIN QUERY PLAN for [query], one entry per plan step. */
    private fun explain(query: ActivityQuery): List<String> {
        val args = query.args.map { arg ->
            when (arg) {
                is SqlArg.Text -> arg.value
                is SqlArg.Integer -> arg.value
            }
        }.toTypedArray<Any?>()
        val sqlite = db.database.openHelper.readableDatabase
        return sqlite.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN ${query.sql}", args)).use { cursor ->
            val detail = cursor.getColumnIndexOrThrow("detail")
            buildList { while (cursor.moveToNext()) add(cursor.getString(detail)) }
        }
    }
}
