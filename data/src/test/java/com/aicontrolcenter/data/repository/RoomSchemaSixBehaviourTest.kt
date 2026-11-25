package com.aicontrolcenter.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.SummaryText
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.core.usage.WorkKind
import com.aicontrolcenter.data.db.ActivityQueryBuilder
import com.aicontrolcenter.data.testing.InMemoryDatabaseRule
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.contract.TaskSamples
import com.aicontrolcenter.testkit.contract.UsageSamples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the Room side of schema 6 does beyond the contract suites: cleaning up with the work it counts, and odd rows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomSchemaSixBehaviourTest {
    @get:Rule val db = InMemoryDatabaseRule()

    private val u = UsageSamples
    private val ledger by lazy { RoomUsageLedger(db.database) }
    private val reports by lazy { RoomUsageReports(db.database) }

    private fun workRows(): List<String> =
        db.database.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT work_kind || ':' || work_id FROM work_usage ORDER BY 1")).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    @Test
    fun `deleting a task tree deletes what its tasks and objective cost, and nothing else`() = runTest {
        val tasks = RoomTaskRepository(db.database)
        tasks.upsertAll(listOf(TaskSamples.objective(), TaskSamples.work("tsk_1"), TaskSamples.objective("tsk_other", at = 500), TaskSamples.work("tsk_9", root = "tsk_other")))
        ledger.record(u.call(u.at("2026-11-25T10:00"), task = "tsk_1", root = "tsk_root"))
        ledger.record(u.call(u.at("2026-11-25T10:01"), task = "tsk_9", root = "tsk_other"))
        ledger.record(u.call(u.at("2026-11-25T10:02"), task = null, root = null, conversation = "cnv_1", source = UsageSource.CHAT))
        tasks.deleteTree(TaskId("tsk_root"))
        assertEquals(listOf("CHAT:cnv_1", "OBJECTIVE:tsk_other", "TASK:tsk_9"), workRows())
        // The hourly usage counts calls, not tasks: it stays.
        assertEquals(3, reports.totals(UsageRange.ALL).calls)
    }

    @Test
    fun `deleting a chat, or an agent's chats, deletes what they cost`() = runTest {
        val conversations = RoomConversationRepository(db.database)
        conversations.upsertConversation(Conversation(ConversationId("cnv_1"), AgentId("agt_a"), "One", 1, 1))
        conversations.upsertConversation(Conversation(ConversationId("cnv_2"), AgentId("agt_b"), "Two", 1, 1))
        conversations.upsertConversation(Conversation(ConversationId("cnv_3"), AgentId("agt_b"), "Three", 1, 1))
        for (chat in listOf("cnv_1", "cnv_2", "cnv_3", "cnv_4")) {
            ledger.record(u.call(u.at("2026-11-25T10:00"), task = null, root = null, conversation = chat, source = UsageSource.CHAT))
        }
        conversations.deleteConversation(ConversationId("cnv_1"))
        assertEquals(listOf("CHAT:cnv_2", "CHAT:cnv_3", "CHAT:cnv_4"), workRows())
        conversations.deleteByAgent(AgentId("agt_b"))
        assertEquals(listOf("CHAT:cnv_4"), workRows())
    }

    @Test
    fun `cost rows of tasks and chats that are gone are pruned`() = runTest {
        RoomTaskRepository(db.database).upsertAll(listOf(TaskSamples.objective(), TaskSamples.work("tsk_1")))
        RoomConversationRepository(db.database).upsertConversation(Conversation(ConversationId("cnv_1"), AgentId("agt_a"), "One", 1, 1))
        ledger.record(u.call(u.at("2026-11-25T10:00"), task = "tsk_1", root = "tsk_root"))
        ledger.record(u.call(u.at("2026-11-25T10:00"), task = "tsk_gone", root = "tsk_gone_root"))
        ledger.record(u.call(u.at("2026-11-25T10:00"), task = null, root = null, conversation = "cnv_1", source = UsageSource.CHAT))
        ledger.record(u.call(u.at("2026-11-25T10:00"), task = null, root = null, conversation = "cnv_gone", source = UsageSource.CHAT))
        assertEquals(3, ledger.pruneOrphanedWork())
        assertEquals(listOf("CHAT:cnv_1", "OBJECTIVE:tsk_root", "TASK:tsk_1"), workRows())
        assertEquals(0, ledger.pruneOrphanedWork())
    }

    @Test
    fun `each kind of failure is counted in its own column`() = runTest {
        for ((n, error) in ErrorClass.entries.withIndex()) {
            ledger.record(u.call(u.at("2026-11-25T10:00") + n, outcome = CallOutcome.FAILED, error = error, input = null, output = null, cached = null))
        }
        val totals = reports.totals(UsageRange.ALL)
        assertEquals(ErrorClass.entries.associateWith { 1L }, totals.errors)
        assertEquals(ErrorClass.entries.size.toLong(), totals.failed)
    }

    @Test
    fun `a model's labels are those of its newest call, whatever order the calls were recorded in`() = runTest {
        ledger.record(u.call(u.at("2026-11-25T10:40"), label = "Newest", locality = Locality.ON_DEVICE, source = UsageSource.CHAT, task = null, root = null))
        ledger.record(u.call(u.at("2026-11-25T10:10"), label = "Older", locality = Locality.CLOUD))
        ledger.record(u.call(u.at("2026-11-25T09:59"), label = "Oldest", locality = Locality.CLOUD, agent = "agt_other"))
        val model = reports.byModel(UsageRange.ALL).single()
        assertEquals("Newest", model.modelLabel)
        assertEquals(Locality.ON_DEVICE, model.locality)
        assertEquals(3, model.totals.calls)
    }

    @Test
    fun `a usage row whose locality this build cannot read is left out of the lists and still counted`() = runTest {
        ledger.record(u.call(u.at("2026-11-25T10:00"), model = "mdl_a"))
        ledger.record(u.call(u.at("2026-11-25T10:00"), model = "mdl_b"))
        db.database.openHelper.writableDatabase.execSQL("UPDATE usage_hours SET locality = 'ON_THE_MOON' WHERE model_id = 'mdl_b'")
        assertEquals(listOf("mdl_a"), reports.byModel(UsageRange.ALL).map { it.modelId.value })
        assertEquals(2, reports.totals(UsageRange.ALL).calls)
    }

    @Test
    fun `a task's result length follows every write`() = runTest {
        val tasks = RoomTaskRepository(db.database)
        fun stored(id: String): Int = db.database.openHelper.readableDatabase
            .query(SimpleSQLiteQuery("SELECT result_chars FROM tasks WHERE id = ?", arrayOf(id))).use { it.moveToFirst(); it.getInt(0) }
        tasks.upsert(TaskSamples.work("tsk_1").copy(result = null))
        assertEquals(0, stored("tsk_1"))
        tasks.update(TaskId("tsk_1")) { it.copy(result = "Done 🙂") }
        assertEquals(6, stored("tsk_1"))
        tasks.upsertAll(listOf(TaskSamples.work("tsk_1").copy(result = "𝄞".repeat(10))))
        assertEquals(10, stored("tsk_1"))
        assertEquals(SummaryText.length("𝄞".repeat(10)), tasks.observeSummaries(TaskId("tsk_root")).first().single().resultChars)
        tasks.update(TaskId("tsk_1")) { it.copy(retryAt = 77) }
        assertEquals(77, tasks.get(TaskId("tsk_1"))!!.retryAt)
    }

    @Test
    fun `a memory space only receives, and a space row without its id is unreadable`() = runTest {
        val messages = RoomMessageRepository(db.database)
        val fromSpace = TaskSamples.message("msg_1").copy(from = Participant.Space(SpaceId("spc_1")))
        assertFailsWith<IllegalArgumentException> { messages.append(fromSpace) }
        messages.append(TaskSamples.message("msg_2").copy(to = Participant.Space(SpaceId("spc_1"))))
        messages.append(TaskSamples.message("msg_3", at = 2_000))
        db.database.openHelper.writableDatabase.execSQL("UPDATE agent_messages SET to_space_id = NULL WHERE id = 'msg_2'")
        assertEquals(listOf("msg_3"), messages.list(TaskId("tsk_root")).map { it.id.value })
        assertEquals(listOf("msg_3"), messages.observeHeaders(TaskId("tsk_root"), 10).first().map { it.id.value })
        assertEquals(2, messages.count(TaskId("tsk_root")), "the limit counts every stored message")
    }

    @Test
    fun `a text filter longer than a LIKE pattern may be still finds what contains it, ignoring ASCII case`() = runTest {
        val log = RoomActivityLog(db.database)
        val long = "Ab" + "c".repeat(ActivityQueryBuilder.MAX_LIKE_PATTERN_BYTES) + "%_"
        log.record(Samples.event(id = "evt_1", at = 1).copy(title = "x$long y", detail = ""))
        log.record(Samples.event(id = "evt_2", at = 2).copy(title = "other", detail = "z${long.uppercase()}"))
        log.record(Samples.event(id = "evt_3", at = 3).copy(title = "no", detail = "no"))
        assertEquals(listOf("evt_2", "evt_1"), log.recent(ActivityFilter(text = long)).map { it.id.value })
        assertEquals(listOf("evt_2", "evt_1"), log.observePage(ActivityFilter(text = long.lowercase()), limit = 5).first().map { it.id.value })
    }

    @Test
    fun `a conversation's mark is set and cleared only by its turn`() = runTest {
        val conversations = RoomConversationRepository(db.database)
        conversations.markTurnStarted(ConversationId("cnv_1"), 5)
        assertTrue(conversations.interruptedTurns().isEmpty(), "an unknown conversation is never marked, nor created")
        assertNull(conversations.getConversation(ConversationId("cnv_1")))
        assertEquals(null, conversations.observeLatestSeq(ConversationId("cnv_1")).first())
        assertEquals(emptyList(), RoomUsageReports(db.database).workUsage(WorkKind.CHAT, "cnv_1"))
    }
}
