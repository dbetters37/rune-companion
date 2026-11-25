package com.aicontrolcenter.data.repository

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.data.db.ActivityKeyset
import com.aicontrolcenter.data.db.ActivityQueryBuilder
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.MemoryQueryBuilder
import com.aicontrolcenter.data.db.SqlArg
import com.aicontrolcenter.data.db.dao.AgentMessageDao
import com.aicontrolcenter.data.db.dao.ConversationDao
import com.aicontrolcenter.data.db.dao.RoutineDao
import com.aicontrolcenter.data.db.dao.TaskDao
import com.aicontrolcenter.data.db.dao.UsageDao
import com.aicontrolcenter.data.testing.ActivityFixtures
import com.aicontrolcenter.testkit.contract.TaskSamples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rule 16, "lists read summaries", checked by counting, never by timing: what the list queries of
 * schema 6 select, that SQLite answers each from an index in the listing's own order (so a
 * `LIMIT` stops the read), and how many statements and rows building the objectives list costs.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SummaryQueriesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Every statement the database runs, as Room reports it. */
    private val statements = CopyOnWriteArrayList<String>()

    private val database: AiccDatabase = Room.inMemoryDatabaseBuilder(context, AiccDatabase::class.java)
        .setQueryCallback({ sql, _ -> statements += sql }, Runnable::run)
        .build()

    private val sqlite get() = database.openHelper.writableDatabase

    @After
    fun tearDown() = database.close()

    /** The `detail` column of SQLite's EXPLAIN QUERY PLAN for [sql], one entry per step. */
    private fun explain(sql: String, vararg args: Any?): List<String> =
        sqlite.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args)).use { cursor ->
            val detail = cursor.getColumnIndexOrThrow("detail")
            buildList { while (cursor.moveToNext()) add(cursor.getString(detail)) }
        }

    /** Every step that reads a table reads it through an index or the primary key, never by a full scan of the table. */
    private fun assertIndexed(plan: List<String>, what: String) {
        val reads = plan.filter { it.startsWith("SCAN") || it.startsWith("SEARCH") }
        assertTrue(reads.isNotEmpty(), "$what: no table read in $plan")
        for (step in reads) {
            assertTrue("USING" in step, "$what reads a table without an index: $step\n  plan: $plan")
        }
    }

    private fun assertNoSort(plan: List<String>, what: String) =
        assertFalse(plan.any { "TEMP B-TREE" in it }, "$what sorts outside the index: $plan")

    private fun rows(sql: String, vararg args: Any?): Int = sqlite.query(SimpleSQLiteQuery(sql, args)).use { it.count }

    @Test
    fun `the summary queries select no whole rows and no long text`() {
        val queries = mapOf(
            "summaries" to TaskDao.SUMMARIES, "objective rows" to TaskDao.OBJECTIVE_ROWS, "next objective rows" to TaskDao.OBJECTIVE_ROWS_AFTER,
            "participants" to TaskDao.PARTICIPANTS, "recent by agent" to TaskDao.RECENT_BY_AGENT, "progress" to TaskDao.PROGRESS,
            "headers" to AgentMessageDao.HEADERS, "recent headers" to AgentMessageDao.RECENT_HEADERS,
        )
        for ((what, sql) in queries) {
            assertFalse("SELECT *" in sql, "$what selects whole rows: $sql")
            for (column in listOf("instructions", "result,", "result ", "reviews_json", "usage_json", "criteria_json", "requested_by_json", "options_json")) {
                assertFalse(column in sql, "$what reads $column: $sql")
            }
        }
        // The body and the details are read only as their first characters.
        assertEquals(1, Regex("\\bbody\\b").findAll(AgentMessageDao.HEADERS).count())
        assertTrue("substr(body, 1, 120)" in AgentMessageDao.HEADERS)
        assertTrue("substr(COALESCE(status_detail, error), 1, 200)" in TaskDao.SUMMARIES)
    }

    @Test
    fun `each summary query is answered from an index in its listing order`() {
        val summaries = explain(TaskDao.SUMMARIES, "tsk_root")
        assertIndexed(summaries, "summaries")
        assertNoSort(summaries, "summaries")
        assertTrue(summaries.any { "index_tasks_root_id_created_at_id" in it }, "$summaries")

        for ((what, plan) in listOf(
            "objective rows" to explain(TaskDao.OBJECTIVE_ROWS, 20),
            "next objective rows" to explain(TaskDao.OBJECTIVE_ROWS_AFTER, 1_000L, "tsk_x", 20),
        )) {
            assertIndexed(plan, what)
            // The page comes from the listing index in its order, so LIMIT stops the read; the counts are index lookups per row.
            assertNoSort(plan, what)
            assertTrue(plan.any { "o USING" in it && "index_tasks_kind_created_at_id" in it }, "$what: $plan")
            assertTrue(plan.any { "s USING" in it && "index_tasks_root_id_created_at_id" in it }, "$what: $plan")
        }

        assertIndexed(explain(TaskDao.PARTICIPANTS, "tsk_root"), "participants")

        val recent = explain(TaskDao.RECENT_BY_AGENT, "agt_researcher", 20)
        assertIndexed(recent, "recent by agent")
        assertNoSort(recent, "recent by agent")
        assertTrue(recent.any { "index_tasks_assignee_id_updated_at_id" in it }, "$recent")

        val progress = explain(TaskDao.PROGRESS, "tsk_root")
        assertIndexed(progress, "progress")
        assertTrue(progress.any { "s USING" in it && "index_tasks_root_id_created_at_id" in it }, "$progress")

        val headers = explain(AgentMessageDao.HEADERS, "tsk_root", 20)
        assertIndexed(headers, "headers")
        assertNoSort(headers, "headers")
        assertTrue(headers.any { "index_agent_messages_root_id_at_id" in it }, "$headers")
        val recentHeaders = explain(AgentMessageDao.RECENT_HEADERS, 20)
        assertNoSort(recentHeaders, "recent headers")
        assertTrue(recentHeaders.any { "index_agent_messages_at_id" in it }, "$recentHeaders")

        for ((what, sql) in listOf("entries after" to ConversationDao.ENTRY_ROWS_AFTER, "entries before" to ConversationDao.ENTRY_ROWS_BEFORE)) {
            val plan = if (sql == ConversationDao.ENTRY_ROWS_AFTER) explain(sql, "cnv_1", 5) else explain(sql, "cnv_1", 5, 20)
            assertIndexed(plan, what)
            assertNoSort(plan, what)
        }

        val runs = explain(RoutineDao.RUNS, "rtn_a", 20)
        assertIndexed(runs, "routine runs")
        assertNoSort(runs, "routine runs")
    }

    @Test
    fun `an objective's messages of some types are read from its index in sending order`() {
        // Review of wave 2a, INT-4: the owner's notes and a task's answers, for every brief.
        val byType = AgentMessageDao.BY_ROOT_AND_TYPES.replace("(:types)", "(?, ?)").replace(":rootId", "?")
        val plan = explain(byType, "tsk_root", "GUIDANCE", "ASSISTANCE_RESPONSE")
        assertIndexed(plan, "messages by type")
        assertNoSort(plan, "messages by type")
        assertTrue(plan.any { "index_agent_messages_root_id_at_id" in it }, "$plan")
        val byTask = AgentMessageDao.BY_TASK_AND_TYPES.replace("(:types)", "(?)").replace(":rootId", "?").replace(":taskId", "?")
        val taskPlan = explain(byTask, "tsk_root", "tsk_a", "ASSISTANCE_RESPONSE")
        assertIndexed(taskPlan, "a task's messages by type")
        assertNoSort(taskPlan, "a task's messages by type")
    }

    @Test
    fun `usage sums read the hours of their range from the primary key`() {
        val plan = explain("SELECT ${UsageDao.SUMS} FROM usage_hours WHERE hour_start >= ? AND hour_start < ?", 0L, 3_600_000L)
        assertIndexed(plan, "usage sums")
        assertTrue(plan.single().startsWith("SEARCH usage_hours USING") && "hour_start>? AND hour_start<?" in plan.single(), "$plan")
    }

    @Test
    fun `a memory count reads no memory row it does not count, from the indexes`() {
        val query = MemoryQueryBuilder.count(MemoryFilter(scopes = setOf(com.aicontrolcenter.testkit.contract.MemorySamples.commons)))
        val args = query.args.map { if (it is SqlArg.Text) it.value else (it as SqlArg.Integer).value }.toTypedArray<Any?>()
        val plan = explain(query.sql, *args)
        assertIndexed(plan, "memory count")
        assertTrue(plan.any { "index_memories_scope_space_id" in it }, "$plan")
    }

    @Test
    fun `a page of the activity log with a text filter and a cursor is read from an index without sorting`() {
        for (filter in listOf(ActivityFilter(text = "report"), ActivityFilter(text = "report", types = setOf(ActivityType.TOOL_CALL)))) {
            val query = ActivityQueryBuilder.build(filter, limit = 50, after = ActivityKeyset(at = 5_000, seq = 42))
            val args = query.args.map { if (it is SqlArg.Text) it.value else (it as SqlArg.Integer).value }.toTypedArray<Any?>()
            val plan = explain(query.sql, *args)
            assertIndexed(plan, "$filter")
            assertNoSort(plan, "$filter")
            // The cursor bounds the index range, so a page far back costs no more than the first.
            assertTrue(plan.any { "at<?" in it }, "$filter: $plan")
        }
    }

    @Test
    fun `listing objective rows runs two projections that read only the page's objectives`() = runTest {
        val tasks = RoomTaskRepository(database)
        // Six objectives of 25 tasks each, every task with a long result.
        for (n in 1..6) {
            val root = "tsk_o$n"
            tasks.upsertAll(
                listOf(TaskSamples.objective(root, at = n * 10_000L)) +
                    (1..24).map { t ->
                        TaskSamples.work("tsk_o${n}_$t", root = root, at = n * 10_000L + t, status = TaskStatus.COMPLETED)
                            .copy(assignee = AgentId("agt_$t"), result = "r".repeat(50_000))
                    },
            )
        }
        statements.clear()
        val page = tasks.observeObjectiveRows(limit = 2).first()
        assertEquals(listOf("tsk_o6", "tsk_o5"), page.map { it.rootId.value })
        assertEquals(24, page.first().planned)
        assertEquals(25, page.first().participants.size, "the Director and 24 agents")

        // Distinct statements: Room may run a query again when it learns of the writes above.
        val reads = statements.filter { "FROM tasks" in it }.distinct()
        assertEquals(2, reads.size, "one page query and one participant query: $reads")
        assertTrue(reads.first().startsWith("SELECT o.id AS id"), "$reads")
        assertTrue(reads.none { "SELECT *" in it }, "$reads")
        // Rows: the page query returns the page, the participant query the assignees of the page's two objectives only.
        assertEquals(2, rows(TaskDao.OBJECTIVE_ROWS, 2))
        assertEquals(2 * 25, rows(TaskDao.PARTICIPANTS.replace("(:rootIds)", "(?, ?)"), "tsk_o6", "tsk_o5"))
        assertEquals(150, rows("SELECT id FROM tasks"), "the table holds far more than the page reads")
    }

    @Test
    fun `summaries, recent tasks and progress run projections only`() = runTest {
        val tasks = RoomTaskRepository(database)
        tasks.upsertAll(listOf(TaskSamples.objective(), TaskSamples.work("tsk_1"), TaskSamples.work("tsk_2", kind = TaskKind.SYNTHESIS)))
        statements.clear()
        tasks.observeSummaries(TaskId("tsk_root")).first()
        tasks.observeRecentByAgent(AgentId("agt_researcher"), 5).first()
        tasks.observeProgress(setOf(TaskId("tsk_root"))).first()
        RoomMessageRepository(database).observeHeaders(TaskId("tsk_root"), 5).first()
        RoomMessageRepository(database).observeRecentHeaders(5).first()
        val reads = statements.filter { "FROM tasks" in it || "FROM agent_messages" in it }.distinct()
        assertEquals(5, reads.size, "$reads")
        assertTrue(reads.none { "SELECT *" in it }, "$reads")
        // And the log's pages use the keyset query, never the whole log.
        val log = RoomActivityLog(database)
        ActivityFixtures.events(20).forEach { log.record(it) }
        statements.clear()
        log.observePage(ActivityFilter(text = "x"), com.aicontrolcenter.core.activity.ActivityCursor(1, EventId("evt_none")), 5).first()
        assertTrue(statements.any { "FROM activity_events" in it && "LIMIT" in it }, "$statements")
    }
}
