package com.aicontrolcenter.data.db

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The SQL text and bind order produced for representative filters. */
class ActivityQueryBuilderTest {
    private val order = "ORDER BY at DESC, seq DESC LIMIT ?"

    private fun text(value: String) = SqlArg.Text(value)
    private fun int(value: Long) = SqlArg.Integer(value)

    @Test
    fun `no filter reads the time index`() {
        val query = ActivityQueryBuilder.build(ActivityFilter(), limit = 200)
        assertEquals("SELECT * FROM activity_events INDEXED BY index_activity_events_at $order", query.sql)
        assertEquals(listOf(int(200)), query.args)
    }

    @Test
    fun `a filter that excludes nothing adds no condition`() {
        val everything = ActivityFilter(types = ActivityType.entries.toSet(), minSeverity = Severity.INFO, egressOnly = false)
        assertEquals(ActivityQueryBuilder.build(ActivityFilter(), 10), ActivityQueryBuilder.build(everything, 10))
    }

    @Test
    fun `each single filter is answered from its own index`() {
        fun sql(filter: ActivityFilter) = ActivityQueryBuilder.build(filter, 5)

        assertEquals(
            ActivityQuery("SELECT * FROM activity_events INDEXED BY index_activity_events_task_id_at WHERE task_id = ? $order", listOf(text("tsk_1"), int(5))),
            sql(ActivityFilter(taskId = TaskId("tsk_1"))),
        )
        assertEquals(
            ActivityQuery("SELECT * FROM activity_events INDEXED BY index_activity_events_root_task_id_at WHERE root_task_id = ? $order", listOf(text("tsk_root"), int(5))),
            sql(ActivityFilter(rootTaskId = TaskId("tsk_root"))),
        )
        assertEquals(
            ActivityQuery("SELECT * FROM activity_events INDEXED BY index_activity_events_agent_id_at WHERE agent_id = ? $order", listOf(text("agt_1"), int(5))),
            sql(ActivityFilter(agentId = AgentId("agt_1"))),
        )
        assertEquals(
            ActivityQuery("SELECT * FROM activity_events INDEXED BY index_activity_events_type_at WHERE type = ? $order", listOf(text("TOOL_CALL"), int(5))),
            sql(ActivityFilter(types = setOf(ActivityType.TOOL_CALL))),
        )
        assertEquals(
            ActivityQuery("SELECT * FROM activity_events INDEXED BY index_activity_events_severity_at WHERE severity = ? $order", listOf(int(30), int(5))),
            sql(ActivityFilter(minSeverity = Severity.ERROR)),
        )
        assertEquals(
            ActivityQuery("SELECT * FROM activity_events INDEXED BY index_activity_events_has_egress_at WHERE has_egress = 1 $order", listOf(int(5))),
            sql(ActivityFilter(egressOnly = true)),
        )
    }

    @Test
    fun `the most selective filter drives and the rest are checked per row`() {
        val all = ActivityFilter(
            types = setOf(ActivityType.TOOL_CALL, ActivityType.MODEL_CALL), agentId = AgentId("agt_1"),
            taskId = TaskId("tsk_1"), rootTaskId = TaskId("tsk_root"), minSeverity = Severity.WARNING, egressOnly = true,
        )
        assertEquals(
            ActivityQuery(
                "SELECT * FROM activity_events INDEXED BY index_activity_events_task_id_at " +
                    "WHERE task_id = ? AND root_task_id = ? AND severity >= ? AND agent_id = ? AND type IN (?, ?) AND has_egress = 1 $order",
                listOf(text("tsk_1"), text("tsk_root"), int(20), text("agt_1"), text("MODEL_CALL"), text("TOOL_CALL"), int(9)),
            ),
            ActivityQueryBuilder.build(all, 9),
        )

        fun index(filter: ActivityFilter) = ActivityQueryBuilder.build(filter, 1).sql.substringAfter("INDEXED BY ").substringBefore(' ')
        assertEquals("index_activity_events_root_task_id_at", index(all.copy(taskId = null)))
        assertEquals("index_activity_events_severity_at", index(all.copy(taskId = null, rootTaskId = null)))
        assertEquals("index_activity_events_agent_id_at", index(all.copy(taskId = null, rootTaskId = null, minSeverity = Severity.INFO)))
        assertEquals("index_activity_events_type_at", index(ActivityFilter(types = all.types, egressOnly = true)))
        assertEquals("index_activity_events_has_egress_at", index(ActivityFilter(egressOnly = true)))
    }

    @Test
    fun `several event types are read as one bounded range each and merged`() {
        val query = ActivityQueryBuilder.build(
            ActivityFilter(types = setOf(ActivityType.TOOL_CALL, ActivityType.MODEL_CALL), egressOnly = true),
            limit = 7,
        )
        val range = "SELECT * FROM (SELECT * FROM activity_events INDEXED BY index_activity_events_type_at WHERE type = ? AND has_egress = 1 $order)"
        assertEquals("$range UNION ALL $range $order", query.sql)
        // Enum order, whatever order the set was built in, so equal filters give equal SQL.
        assertEquals(listOf(text("MODEL_CALL"), int(7), text("TOOL_CALL"), int(7), int(7)), query.args)
    }

    @Test
    fun `a minimum severity below the top is one range per level`() {
        val query = ActivityQueryBuilder.build(ActivityFilter(minSeverity = Severity.WARNING, agentId = AgentId("agt_1")), limit = 3)
        val range = "SELECT * FROM (SELECT * FROM activity_events INDEXED BY index_activity_events_severity_at WHERE severity = ? AND agent_id = ? $order)"
        assertEquals("$range UNION ALL $range $order", query.sql)
        assertEquals(listOf(int(20), text("agt_1"), int(3), int(30), text("agt_1"), int(3), int(3)), query.args)
    }

    @Test
    fun `every placeholder has exactly one argument`() {
        val filters = listOf(
            ActivityFilter(),
            ActivityFilter(types = setOf(ActivityType.ERROR, ActivityType.TASK, ActivityType.MEMORY)),
            ActivityFilter(minSeverity = Severity.WARNING, types = setOf(ActivityType.ERROR, ActivityType.TASK)),
            ActivityFilter(taskId = TaskId("t"), types = setOf(ActivityType.ERROR, ActivityType.TASK), minSeverity = Severity.ERROR),
        )
        for (filter in filters) {
            val query = ActivityQueryBuilder.build(filter, 1)
            assertEquals(query.sql.count { it == '?' }, query.args.size, query.sql)
        }
    }

    @Test
    fun `a negative limit is rejected instead of meaning no limit`() {
        // SQLite treats LIMIT -1 as unlimited, which would load the whole log.
        assertFailsWith<IllegalArgumentException> { ActivityQueryBuilder.build(ActivityFilter(), limit = -1) }
    }
}
