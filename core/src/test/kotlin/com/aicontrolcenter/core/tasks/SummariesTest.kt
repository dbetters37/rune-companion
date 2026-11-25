package com.aicontrolcenter.core.tasks

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityCursor
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.activityPageOf
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 6, K1, K2, K10: summaries, headers and pages built from whole records. */
class SummariesTest {
    private fun task(id: String, parent: String? = "tsk_root", kind: TaskKind = TaskKind.WORK, status: TaskStatus = TaskStatus.QUEUED, at: Long = 10, assignee: String? = "agt_a") = TaskRecord(
        id = TaskId(id), rootId = TaskId("tsk_root"), parentId = parent?.let(::TaskId), kind = kind, title = "T $id", instructions = "Do it",
        assignee = assignee?.let(::AgentId), requestedBy = TaskRequester.Owner, status = status, createdAt = at, updatedAt = at,
    )

    private val objective = task("tsk_root", parent = null, kind = TaskKind.OBJECTIVE, status = TaskStatus.RUNNING, at = 1, assignee = "agt_director")

    @Test
    fun `text is cut and counted like SQLite, by code point`() {
        assertNull(SummaryText.cut(null, 3))
        assertEquals("abc", SummaryText.cut("abcdef", 3))
        assertEquals("ab", SummaryText.cut("ab", 3))
        assertEquals("a😀b", SummaryText.cut("a😀bc", 3), "an emoji is one character")
        assertEquals("😀😀", SummaryText.cut("😀😀😀", 2))
        assertEquals(3, SummaryText.length("a😀b"))
        assertEquals(0, SummaryText.length(null))
    }

    @Test
    fun `a summary carries everything a list needs and nothing else`() {
        val full = task("tsk_1", status = TaskStatus.FAILED).copy(
            statusDetail = null, error = "x".repeat(300), result = "Résumé 📝", order = 3, attempt = 2, revisionRound = 1, needsReview = true,
            startedAt = 11, finishedAt = 12, retryAt = 99, dependsOn = listOf(TaskId("tsk_0")),
        )
        val summary = TaskSummary.of(full)
        assertEquals("x".repeat(TaskSummary.DETAIL_MAX_CHARS), summary.statusDetailShort, "the error when there is no detail, cut")
        assertEquals(8, summary.resultChars)
        assertEquals(listOf(3, 2, 1), listOf(summary.sortOrder, summary.attempt, summary.revisionRound))
        assertEquals(99, summary.retryAt)
        assertTrue(summary.isPlanStep)
        assertEquals("Waiting", TaskSummary.of(full.copy(statusDetail = "Waiting")).statusDetailShort)
        assertEquals(0, TaskSummary.of(full.copy(result = null)).resultChars)
    }

    @Test
    fun `plan steps are the work and the final result directly under the objective`() {
        assertTrue(PlanSteps.isStep(task("tsk_w")))
        assertTrue(PlanSteps.isStep(task("tsk_s", kind = TaskKind.SYNTHESIS)))
        assertFalse(PlanSteps.isStep(task("tsk_r", kind = TaskKind.REVIEW)))
        assertFalse(PlanSteps.isStep(task("tsk_d", parent = "tsk_w")), "delegated work belongs to its step")
        assertFalse(PlanSteps.isStep(objective))
    }

    @Test
    fun `an objective row and its progress count the plan's steps`() {
        val tree = listOf(
            objective.copy(statusDetail = "Working"),
            task("tsk_a", status = TaskStatus.COMPLETED, at = 5, assignee = "agt_b"),
            task("tsk_b", status = TaskStatus.RUNNING, at = 6, assignee = "agt_a"),
            task("tsk_c", status = TaskStatus.WAITING_FOR_OWNER, at = 7, assignee = "agt_b"),
            task("tsk_d", status = TaskStatus.TIMED_OUT, at = 8, assignee = null),
            task("tsk_e", kind = TaskKind.REVIEW, status = TaskStatus.IN_REVIEW, at = 9, assignee = "agt_c"),
            task("tsk_f", kind = TaskKind.SYNTHESIS, status = TaskStatus.QUEUED, at = 10, assignee = "agt_director"),
        )
        val row = ObjectiveRow.of(tree.shuffled(java.util.Random(1)))!!
        assertEquals(5, row.planned)
        assertEquals(1, row.completed)
        assertEquals("Working", row.statusDetailShort)
        assertEquals(listOf("agt_director", "agt_b", "agt_a", "agt_c").map(::AgentId), row.participants, "the Director first, then by creation")
        assertEquals(PlanProgress(TaskId("tsk_root"), total = 5, done = 1, running = 1, waiting = 1, failed = 1), PlanProgress.of(TaskId("tsk_root"), tree))
        assertNull(ObjectiveRow.of(tree.drop(1)), "no objective, no row")
        assertEquals(0, ObjectiveRow.of(listOf(objective))!!.planned, "planning")
    }

    @Test
    fun `cursors follow the list's order`() {
        val cursor = ObjectiveCursor(createdAt = 100, id = TaskId("tsk_m"))
        assertTrue(cursor.precedes(99, TaskId("tsk_a")))
        assertTrue(cursor.precedes(100, TaskId("tsk_z")))
        assertFalse(cursor.precedes(100, TaskId("tsk_m")))
        assertFalse(cursor.precedes(100, TaskId("tsk_a")))
        assertFalse(cursor.precedes(101, TaskId("tsk_z")))
    }

    @Test
    fun `a header previews the body and keeps everything else`() {
        val message = AgentMessage(
            id = MessageId("msg_1"), rootId = TaskId("tsk_root"), taskId = null, from = Participant.Owner, to = Participant.Space(SpaceId("spc_1")),
            type = MessageType.GUIDANCE, body = "🔎".repeat(200), replyTo = MessageId("msg_0"), hop = 2, at = 5,
        )
        val header = MessageHeader.of(message)
        assertEquals("🔎".repeat(MessageHeader.PREVIEW_MAX_CHARS), header.preview)
        assertEquals(listOf<Any?>(message.id, message.rootId, null, message.from, message.to, message.type, 5L, 2, message.replyTo), listOf(header.id, header.rootId, header.taskId, header.from, header.to, header.type, header.at, header.hop, header.replyTo))
        // A space is stored by its own kind, and GUIDANCE is the last type.
        assertEquals("""{"type":"space","spaceId":"spc_1"}""", Json.encodeToString(Participant.serializer(), Participant.Space(SpaceId("spc_1"))))
        assertEquals(MessageType.GUIDANCE, MessageType.entries.last())
    }

    @Test
    fun `log pages follow the cursor, and the text filter ignores case and means itself`() {
        fun event(id: String, at: Long, title: String = "Event $id", detail: String = "") =
            ActivityEvent(EventId(id), at, ActivityType.SYSTEM, title = title, detail = detail)
        val newestFirst = listOf(event("e5", 5), event("e4", 4), event("e3b", 3), event("e3a", 3), event("e1", 1))
        assertEquals(listOf("e5", "e4"), activityPageOf(newestFirst, null, 2).map { it.id.value })
        assertEquals(listOf("e3b", "e3a"), activityPageOf(newestFirst, ActivityCursor(4, EventId("e4")), 2).map { it.id.value })
        assertEquals(listOf("e3a", "e1"), activityPageOf(newestFirst, ActivityCursor(3, EventId("e3b")), 5).map { it.id.value })
        assertEquals(listOf("e1"), activityPageOf(newestFirst, ActivityCursor(3, EventId("gone")), 5).map { it.id.value }, "a pruned cursor event: older ones")
        assertEquals(emptyList(), activityPageOf(newestFirst, null, 0))
        val filter = ActivityFilter(text = "50% off_")
        assertTrue(filter.matches(event("x", 1, title = "Got 50% OFF_ today")))
        assertFalse(filter.matches(event("y", 1, title = "Got 50 percent off")))
        assertTrue(ActivityFilter(text = "report").matches(event("z", 1, detail = "The REPORT is ready")))
        assertTrue(ActivityFilter(text = "  ").matches(event("w", 1)), "a blank text filters nothing")
    }
}
