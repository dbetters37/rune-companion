package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.MessageHeader
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveCursor
import com.aicontrolcenter.core.tasks.ObjectiveRow
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.PlanProgress
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6, K1 (rule 16): what every [TaskRepository] must return for lists. The summaries,
 * rows and progress equal what [TaskSummary.of], [ObjectiveRow.of] and [PlanProgress.of] build
 * from the whole records, field by field, so a Room projection that reads less gives the same.
 */
abstract class TaskSummaryContract {
    protected abstract fun createRepository(): TaskRepository

    private val root = TaskId("tsk_root")

    /** An objective with a plan in every state, delegated work, a review and a long detail. */
    private fun tree(): List<TaskRecord> = listOf(
        TaskSamples.objective().copy(statusDetail = "Working on it, " + "very ".repeat(60) + "carefully 🙂", assignee = AgentId("agt_director")),
        TaskSamples.work("tsk_a", at = 2_000, status = TaskStatus.COMPLETED).copy(assignee = AgentId("agt_b"), result = "Résumé 📝 of the work", finishedAt = 2_500),
        TaskSamples.work("tsk_b", at = 2_100, status = TaskStatus.RUNNING).copy(assignee = AgentId("agt_a"), result = null, retryAt = 9_999),
        TaskSamples.work("tsk_c", at = 2_200, status = TaskStatus.WAITING_FOR_OWNER).copy(assignee = AgentId("agt_b"), statusDetail = null, error = "e".repeat(300)),
        TaskSamples.work("tsk_d", at = 2_300, status = TaskStatus.FAILED).copy(assignee = null, error = null),
        TaskSamples.work("tsk_e", at = 2_400, status = TaskStatus.IN_REVIEW, kind = TaskKind.REVIEW).copy(assignee = AgentId("agt_c")),
        TaskSamples.work("tsk_f", at = 2_500, status = TaskStatus.RUNNING).copy(parentId = TaskId("tsk_b"), assignee = AgentId("agt_d")),
        TaskSamples.work("tsk_g", at = 2_600, status = TaskStatus.QUEUED, kind = TaskKind.SYNTHESIS).copy(assignee = AgentId("agt_director")),
    )

    @Test
    fun `summaries equal those built from the records, in the order of the tree`() = runTest {
        val repo = createRepository()
        val tree = tree()
        repo.upsertAll(tree.shuffled(java.util.Random(7)))
        val expected = tree.sortedWith(compareBy({ it.createdAt }, { it.id.value })).map(TaskSummary::of)
        assertEquals(expected, repo.observeSummaries(root).first())
        assertEquals(TaskSummary.DETAIL_MAX_CHARS, com.aicontrolcenter.core.tasks.SummaryText.length(expected.first().statusDetailShort))
        assertEquals("e".repeat(TaskSummary.DETAIL_MAX_CHARS), expected.single { it.id.value == "tsk_c" }.statusDetailShort, "the error stands in for a missing detail")
        assertEquals(20, expected.single { it.id.value == "tsk_a" }.resultChars, "result length in characters, an emoji is one")
        assertEquals(9_999, expected.single { it.id.value == "tsk_b" }.retryAt)
        assertEquals(emptyList(), repo.observeSummaries(TaskId("tsk_unknown")).first())
    }

    @Test
    fun `objective rows count the plan, name the participants and page by created time`() = runTest {
        val repo = createRepository()
        repo.upsertAll(tree())
        val others = listOf(
            TaskSamples.objective("tsk_o1", at = 500, status = TaskStatus.COMPLETED).copy(finishedAt = 900, statusDetail = null, error = "Stopped"),
            TaskSamples.objective("tsk_o2", at = 1_500, status = TaskStatus.PAUSED),
            TaskSamples.objective("tsk_o3a", at = 800, status = TaskStatus.RUNNING),
            TaskSamples.objective("tsk_o3b", at = 800, status = TaskStatus.RUNNING),
        )
        repo.upsertAll(others)
        val all = repo.observeObjectiveRows(limit = 10).first()
        assertEquals(listOf("tsk_o2", "tsk_root", "tsk_o3a", "tsk_o3b", "tsk_o1"), all.map { it.rootId.value }, "newest first; at the same time by id")
        val row = all.single { it.rootId == root }
        assertEquals(ObjectiveRow.of(tree()), row)
        assertEquals(5, row.planned)
        assertEquals(1, row.completed)
        assertEquals(listOf("agt_director", "agt_b", "agt_a", "agt_c", "agt_d").map(::AgentId), row.participants)
        assertEquals("Stopped", all.last().statusDetailShort)
        assertEquals(0, all.last().planned, "an objective without a plan")
        // Paging.
        val first = repo.observeObjectiveRows(limit = 2).first()
        assertEquals(listOf("tsk_o2", "tsk_root"), first.map { it.rootId.value })
        val second = repo.observeObjectiveRows(limit = 2, olderThan = ObjectiveCursor.after(first.last())).first()
        assertEquals(listOf("tsk_o3a", "tsk_o3b"), second.map { it.rootId.value })
        val third = repo.observeObjectiveRows(limit = 2, olderThan = ObjectiveCursor.after(second.last())).first()
        assertEquals(listOf("tsk_o1"), third.map { it.rootId.value })
        assertEquals(emptyList(), repo.observeObjectiveRows(limit = 2, olderThan = ObjectiveCursor.after(third.last())).first())
        assertEquals(emptyList(), repo.observeObjectiveRows(limit = 0).first())
    }

    @Test
    fun `an agent's recent tasks are the most recently updated, across objectives`() = runTest {
        val repo = createRepository()
        repo.upsertAll(tree())
        repo.upsertAll(listOf(
            TaskSamples.objective("tsk_other", at = 100),
            TaskSamples.work("tsk_x", root = "tsk_other", at = 3_000).copy(assignee = AgentId("agt_b"), updatedAt = 3_000),
            TaskSamples.work("tsk_y", root = "tsk_other", at = 3_000).copy(assignee = AgentId("agt_b"), updatedAt = 2_210),
        ))
        // agt_b: tsk_a (updated 2_010), tsk_c (2_210), tsk_x (3_000), tsk_y (2_210): newest first, ties by id.
        assertEquals(listOf("tsk_x", "tsk_c", "tsk_y", "tsk_a"), repo.observeRecentByAgent(AgentId("agt_b"), 10).first().map { it.id.value })
        assertEquals(listOf("tsk_x", "tsk_c"), repo.observeRecentByAgent(AgentId("agt_b"), 2).first().map { it.id.value })
        val director = repo.observeRecentByAgent(AgentId("agt_director"), 10).first()
        assertEquals(setOf("tsk_root", "tsk_g", "tsk_other"), director.map { it.id.value }.toSet(), "objectives count as the Director's tasks")
        assertEquals(TaskSummary.of(repo.get(TaskId("tsk_x"))!!), repo.observeRecentByAgent(AgentId("agt_b"), 1).first().single())
        assertEquals(emptyList(), repo.observeRecentByAgent(AgentId("agt_nobody"), 10).first())
    }

    @Test
    fun `progress is given per objective, for the objectives that exist`() = runTest {
        val repo = createRepository()
        repo.upsertAll(tree())
        repo.upsert(TaskSamples.objective("tsk_new", at = 3_000))
        val progress = repo.observeProgress(setOf(root, TaskId("tsk_new"), TaskId("tsk_unknown"))).first()
        assertEquals(setOf(root, TaskId("tsk_new")), progress.keys)
        assertEquals(PlanProgress(root, total = 5, done = 1, running = 1, waiting = 1, failed = 1), progress[root])
        assertEquals(PlanProgress(TaskId("tsk_new"), 0, 0, 0, 0, 0), progress[TaskId("tsk_new")])
        assertEquals(emptyMap(), repo.observeProgress(emptySet()).first())
    }

    @Test
    fun `open observers of summaries, rows, recent tasks and progress see later changes`() = runTest {
        val repo = createRepository()
        val summaries = collectIn(backgroundScope, repo.observeSummaries(root))
        val rows = collectIn(backgroundScope, repo.observeObjectiveRows(limit = 5))
        val recent = collectIn(backgroundScope, repo.observeRecentByAgent(AgentId("agt_b"), 5))
        val progress = collectIn(backgroundScope, repo.observeProgress(setOf(root)))
        awaitUntil { summaries.isNotEmpty() && rows.isNotEmpty() && recent.isNotEmpty() && progress.isNotEmpty() }
        repo.upsertAll(tree())
        awaitUntil { summaries.last().size == 8 && rows.last().size == 1 && recent.last().size == 2 && progress.last()[root]?.total == 5 }
        repo.update(TaskId("tsk_b")) { it.copy(status = TaskStatus.COMPLETED, updatedAt = 5_000, assignee = AgentId("agt_b")) }
        awaitUntil {
            summaries.last().single { it.id.value == "tsk_b" }.status == TaskStatus.COMPLETED &&
                rows.last().single().completed == 2 && recent.last().first().id.value == "tsk_b" && progress.last()[root]?.done == 2
        }
        repo.deleteTree(root)
        awaitUntil { summaries.last().isEmpty() && rows.last().isEmpty() && recent.last().isEmpty() && progress.last().isEmpty() }
    }
}

/** Phase 6, K2 (rule 16): message headers for lists, equal to [MessageHeader.of] of the whole messages. */
abstract class MessageHeaderContract {
    protected abstract fun createRepository(): MessageRepository

    @Test
    fun `headers keep every field but the body, and preview its first characters`() = runTest {
        val repo = createRepository()
        val long = TaskSamples.message("msg_long", at = 300).copy(body = "Findings: " + "🔎 data ".repeat(40))
        val toSpace = TaskSamples.message("msg_space", at = 200, type = MessageType.MEMORY_SHARE).copy(to = Participant.Space(SpaceId("spc_1")), replyTo = null)
        val note = TaskSamples.message("msg_note", at = 250, type = MessageType.GUIDANCE).copy(from = Participant.Owner, to = Participant.Agent(AgentId("agt_director")), taskId = null)
        val tie = TaskSamples.message("msg_tie", at = 300).copy(body = "short")
        val other = TaskSamples.message("msg_other", root = "tsk_other", at = 400)
        listOf(long, other, toSpace, tie, note).forEach { repo.append(it) }
        assertEquals(listOf(toSpace, note, long, tie).map(MessageHeader::of), repo.observeHeaders(TaskId("tsk_root"), 10).first())
        assertEquals(listOf(long, tie).map(MessageHeader::of), repo.observeHeaders(TaskId("tsk_root"), 2).first(), "the newest, in sending order")
        assertEquals(MessageHeader.PREVIEW_MAX_CHARS, MessageHeader.of(long).preview.codePointCount(0, MessageHeader.of(long).preview.length))
        assertEquals(listOf(other, tie, long).map(MessageHeader::of), repo.observeRecentHeaders(3).first(), "newest first across objectives")
        assertEquals(emptyList(), repo.observeHeaders(TaskId("tsk_unknown"), 10).first())
    }

    @Test
    fun `an open observer of headers sees new messages`() = runTest {
        val repo = createRepository()
        val headers = collectIn(backgroundScope, repo.observeHeaders(TaskId("tsk_root"), 2))
        val recent = collectIn(backgroundScope, repo.observeRecentHeaders(1))
        awaitUntil { headers.isNotEmpty() && recent.isNotEmpty() }
        repo.append(TaskSamples.message("msg_1", at = 1))
        repo.append(TaskSamples.message("msg_2", at = 2))
        repo.append(TaskSamples.message("msg_3", at = 3))
        awaitUntil { headers.last().map { it.id } == listOf(MessageId("msg_2"), MessageId("msg_3")) && recent.last().singleOrNull()?.id == MessageId("msg_3") }
        repo.deleteFor(TaskId("tsk_root"))
        awaitUntil { headers.last().isEmpty() && recent.last().isEmpty() }
        assertTrue(headers.isNotEmpty())
    }
}
