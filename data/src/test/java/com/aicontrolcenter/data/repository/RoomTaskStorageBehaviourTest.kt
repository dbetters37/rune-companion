package com.aicontrolcenter.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.data.db.dao.AgentMessageDao
import com.aicontrolcenter.data.db.dao.TaskDao
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.data.testing.InMemoryDatabaseRule
import com.aicontrolcenter.testkit.contract.TaskSamples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the task and message storage does beyond the shared contract: rows it cannot read,
 * updates that fail half-way, rows too large to read back, and the indexes behind the lists.
 * Damaged rows are written straight through the DAOs, the way a later build, a downgrade or
 * disk damage would leave them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomTaskStorageBehaviourTest {
    @get:Rule val db = InMemoryDatabaseRule()

    private val tasks by lazy { RoomTaskRepository(db.database) }
    private val messages by lazy { RoomMessageRepository(db.database) }
    private val root = TaskSamples.objective()

    @Test
    fun `an unreadable task is absent from every read, is not updated, and goes with its tree`() = runTest {
        val good = TaskSamples.work("tsk_good", at = 2_000)
        val damaged = TaskSamples.work("tsk_damaged", at = 3_000, status = TaskStatus.RUNNING)
        tasks.upsertAll(listOf(root, good))
        db.database.taskDao().upsert(damaged.toEntity().copy(status = "SNOOZED"))
        db.database.taskDao().upsert(TaskSamples.work("tsk_json", at = 4_000, status = TaskStatus.RUNNING).toEntity().copy(reviewsJson = "[{\"reviewer\":"))

        assertEquals(listOf(root, good), tasks.getTree(root.id))
        assertEquals(listOf(root, good), tasks.observeTree(root.id).first())
        assertEquals(listOf(root), tasks.observeObjectives().first())
        assertNull(tasks.get(damaged.id))
        assertEquals(listOf(root), tasks.withStatus(setOf(TaskStatus.RUNNING)))

        var transformCalled = false
        assertNull(tasks.update(damaged.id) { transformCalled = true; it.copy(status = TaskStatus.FAILED) })
        assertFalse(transformCalled, "an unreadable task must not be handed to the transform")
        assertEquals("SNOOZED", db.database.taskDao().get(damaged.id.value)?.status, "the damaged row stays as it was")

        // Saving the task again repairs it; deleting the objective removes the damaged rows with it.
        tasks.upsert(damaged)
        assertEquals(damaged, tasks.get(damaged.id))
        tasks.deleteTree(root.id)
        assertNull(db.database.taskDao().get("tsk_json"))
        assertTrue(db.database.taskDao().tree(root.id.value).isEmpty())
    }

    @Test
    fun `an unreadable message is left out of the lists but still counts toward the objective's limit`() = runTest {
        val good = TaskSamples.message("msg_good", at = 100)
        messages.append(good)
        val dao = db.database.agentMessageDao()
        // An agent without its id, and a message type this build does not know.
        dao.insertIfAbsent(TaskSamples.message("msg_anonymous", at = 200).toEntity().copy(fromAgentId = null))
        dao.insertIfAbsent(TaskSamples.message("msg_shout", at = 300).toEntity().copy(type = "SHOUT"))

        assertEquals(listOf(good), messages.list(root.id))
        assertEquals(listOf(good), messages.observe(root.id).first())
        assertEquals(listOf(good), messages.observeRecent(10).first())
        assertEquals(3, messages.count(root.id))
        assertFailsWith<IllegalArgumentException> { messages.observeRecent(-1) }
    }

    @Test
    fun `an update that throws or changes the id writes nothing`() = runTest {
        val task = TaskSamples.work("tsk_1", status = TaskStatus.RUNNING)
        tasks.upsert(task)

        assertFailsWith<IllegalStateException> {
            tasks.update(task.id) { error("the orchestrator changed its mind") }
        }
        assertFailsWith<IllegalArgumentException> {
            tasks.update(task.id) { it.copy(id = TaskId("tsk_other"), status = TaskStatus.COMPLETED) }
        }
        assertEquals(task, tasks.get(task.id))
        assertNull(tasks.get(TaskId("tsk_other")))

        val done = tasks.update(task.id) { it.copy(status = TaskStatus.COMPLETED, result = "Done", finishedAt = 50) }
        assertEquals(done, tasks.get(task.id))
        assertEquals(TaskStatus.COMPLETED, done?.status)
    }

    @Test
    fun `text is limited in bytes so that every stored row can be read back`() = runTest {
        // Two-byte characters: what counts is the size SQLite stores, not the number of characters.
        val large = "ä".repeat(450_000)
        val tooLarge = "ä".repeat(600_000)
        val allowed = TaskSamples.work("tsk_large").copy(result = large)
        tasks.upsert(allowed)
        assertEquals(allowed, tasks.get(allowed.id))

        val plan = listOf(TaskSamples.work("tsk_a"), TaskSamples.work("tsk_b").copy(instructions = tooLarge))
        assertFailsWith<IllegalArgumentException> { tasks.upsertAll(plan) }
        assertNull(tasks.get(TaskId("tsk_a")), "a plan is stored whole or not at all")
        assertFailsWith<IllegalArgumentException> { tasks.upsert(TaskSamples.work("tsk_c").copy(result = tooLarge)) }
        assertFailsWith<IllegalArgumentException> { tasks.update(allowed.id) { it.copy(result = large + large) } }
        assertEquals(allowed, tasks.get(allowed.id))

        val message = TaskSamples.message("msg_large").copy(body = tooLarge)
        val failure = assertFailsWith<IllegalArgumentException> { messages.append(message) }
        assertFalse(tooLarge.take(20) in failure.message.orEmpty(), "the error must not quote the content")
        assertEquals(0, messages.count(root.id))
        messages.append(message.copy(body = large))
        assertEquals(large, messages.list(root.id).single().body)
    }

    @Test
    fun `participants are stored in typed columns`() = runTest {
        val toOwner = TaskSamples.message("msg_1").copy(from = Participant.Agent(AgentId("agt_writer")), to = Participant.Owner)
        val fromSystem = TaskSamples.message("msg_2", at = 2_000, type = MessageType.STATUS).copy(from = Participant.System, to = Participant.Agent(AgentId("agt_a")))
        messages.append(toOwner)
        messages.append(fromSystem)
        val rows = db.database.agentMessageDao().list(root.id.value)
        assertEquals(
            listOf(listOf("AGENT", "agt_writer", "OWNER", null), listOf("SYSTEM", null, "AGENT", "agt_a")),
            rows.map { listOf(it.fromKind, it.fromAgentId, it.toKind, it.toAgentId) },
        )
        assertEquals(listOf(toOwner, fromSystem), messages.list(root.id))
    }

    @Test
    fun `the lists are read from their indexes, in their own order where one index gives it`() = runTest {
        tasks.upsertAll(listOf(root, TaskSamples.work("tsk_1"), TaskSamples.work("tsk_2", status = TaskStatus.RUNNING)))
        messages.append(TaskSamples.message("msg_1"))

        val objectives = plan(TaskDao.OBJECTIVES)
        assertTrue("index_tasks_kind_created_at_id" in objectives && "TEMP B-TREE" !in objectives, objectives)
        val tree = plan(TaskDao.TREE, "tsk_root")
        assertTrue("index_tasks_root_id_created_at_id" in tree && "TEMP B-TREE" !in tree, tree)
        // Several statuses are looked up one after another; their few rows are then sorted.
        val byStatus = plan(TaskDao.WITH_STATUS.replace(":statuses", "?, ?"), "RUNNING", "PAUSED")
        assertTrue("index_tasks_status_created_at_id" in byStatus, byStatus)
        val conversation = plan(AgentMessageDao.BY_ROOT, "tsk_root")
        assertTrue("index_agent_messages_root_id_at_id" in conversation && "TEMP B-TREE" !in conversation, conversation)
        val recent = plan(AgentMessageDao.RECENT, 20L)
        assertTrue("index_agent_messages_at_id" in recent && "TEMP B-TREE" !in recent, recent)
    }

    /** SQLite's query plan for [sql], with the DAO's named parameters bound in order. */
    private fun plan(sql: String, vararg args: Any): String {
        val bound = sql.replace(Regex(":[A-Za-z]+"), "?")
        return db.database.openHelper.writableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $bound", args)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }.joinToString(" | ")
    }
}
