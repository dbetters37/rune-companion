package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.ReviewVerdict
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

object TaskSamples {
    fun objective(id: String = "tsk_root", at: Long = 1_000, status: TaskStatus = TaskStatus.RUNNING) = TaskRecord(
        id = TaskId(id), rootId = TaskId(id), kind = TaskKind.OBJECTIVE, title = "Compare note apps, \"quickly\"",
        instructions = "Research note apps,\ncompare them and write a plan 📝", requestedBy = TaskRequester.Owner,
        assignee = AgentId("agt_director"), status = status, statusDetail = "Planning", options = ObjectiveOptions(reviewResults = true, saveKnowledge = true, maxTasks = 9),
        createdAt = at, updatedAt = at + 1, startedAt = at + 2, deadlineAt = at + 90_000,
    )

    fun work(id: String, root: String = "tsk_root", at: Long = 2_000, status: TaskStatus = TaskStatus.QUEUED, kind: TaskKind = TaskKind.WORK) = TaskRecord(
        id = TaskId(id), rootId = TaskId(root), parentId = TaskId(root), kind = kind, title = "Task $id",
        instructions = "Do $id; with care, \"exactly\"", completionCriteria = listOf("Sources named", "", "Under 300 words"),
        assignee = AgentId("agt_researcher"), requestedBy = TaskRequester.Agent(AgentId("agt_director")), status = status,
        statusDetail = null, dependsOn = listOf(TaskId("tsk_z"), TaskId("tsk_a")), delegationChain = listOf(AgentId("agt_director")),
        order = 2, attempt = 1, revisionRound = 1, needsReview = true, result = "Found 3 apps", error = null,
        reviews = listOf(ReviewVerdict(AgentId("agt_reviewer"), ReviewDecision.REVISE, "Missing prices", listOf("Add prices", "Cite sources"), at + 5, TaskId("tsk_rev"))),
        usage = TaskUsage(3, 2, 1_200, 300, 4), conversationId = ConversationId("cnv_$id"),
        createdAt = at, updatedAt = at + 10, startedAt = at + 1, finishedAt = null, deadlineAt = at + 900_000,
    )

    fun message(id: String, root: String = "tsk_root", at: Long = 1_000, type: MessageType = MessageType.ASSIGNMENT) = AgentMessage(
        id = MessageId(id), rootId = TaskId(root), taskId = TaskId("tsk_1"), from = Participant.Agent(AgentId("agt_director")),
        to = Participant.Agent(AgentId("agt_researcher")), type = type, body = "Please research, \"carefully\"\nthen report 🔎",
        replyTo = MessageId("msg_prev"), hop = 1, at = at,
    )
}

abstract class TaskRepositoryContract {
    protected abstract fun createRepository(): TaskRepository

    @Test
    fun `stores every field and lists objectives newest first and trees in creation order`() = runTest {
        val repo = createRepository()
        val root = TaskSamples.objective()
        val older = TaskSamples.objective("tsk_old", at = 500, status = TaskStatus.COMPLETED).copy(options = null, statusDetail = null, deadlineAt = null)
        val w2 = TaskSamples.work("tsk_2", at = 3_000)
        val w1 = TaskSamples.work("tsk_1", at = 2_000, kind = TaskKind.REVIEW)
        val bare = TaskSamples.work("tsk_bare", at = 4_000).copy(
            parentId = null, completionCriteria = emptyList(), assignee = null, requestedBy = TaskRequester.Owner, dependsOn = emptyList(),
            delegationChain = emptyList(), result = null, reviews = emptyList(), conversationId = null, startedAt = null, deadlineAt = null,
        )
        repo.upsertAll(listOf(root, w2, older, w1, bare))
        listOf(root, older, w1, w2, bare).forEach { assertEquals(it, repo.get(it.id)) }
        assertNull(repo.get(TaskId("tsk_missing")))
        assertEquals(listOf(root, older), repo.observeObjectives().first())
        assertEquals(listOf(root, w1, w2, bare), repo.getTree(root.id))
        assertEquals(listOf(root, w1, w2, bare), repo.observeTree(root.id).first())
        assertEquals(listOf(older), repo.getTree(older.id))
    }

    @Test
    fun `update is atomic, returns the new task and ignores missing ones`() = runTest {
        val repo = createRepository()
        repo.upsert(TaskSamples.work("tsk_1"))
        withContext(Dispatchers.Default) {
            (1..100).map { launch { repo.update(TaskId("tsk_1")) { it.copy(usage = it.usage + TaskUsage(modelCalls = 1)) } } }.joinAll()
        }
        assertEquals(3 + 100, repo.get(TaskId("tsk_1"))!!.usage.modelCalls)
        val changed = repo.update(TaskId("tsk_1")) { it.copy(status = TaskStatus.COMPLETED, finishedAt = 9) }
        assertEquals(TaskStatus.COMPLETED, changed?.status)
        assertNull(repo.update(TaskId("tsk_missing")) { it })
    }

    @Test
    fun `tasks can be found by status across objectives`() = runTest {
        val repo = createRepository()
        repo.upsertAll(listOf(
            TaskSamples.objective("tsk_a", at = 1), TaskSamples.work("tsk_a1", root = "tsk_a", at = 3, status = TaskStatus.RUNNING),
            TaskSamples.objective("tsk_b", at = 2, status = TaskStatus.PAUSED), TaskSamples.work("tsk_b1", root = "tsk_b", at = 4, status = TaskStatus.IN_REVIEW),
            TaskSamples.work("tsk_b2", root = "tsk_b", at = 5, status = TaskStatus.COMPLETED),
        ))
        assertEquals(listOf("tsk_a", "tsk_a1", "tsk_b1"), repo.withStatus(setOf(TaskStatus.RUNNING, TaskStatus.IN_REVIEW)).map { it.id.value })
        assertTrue(repo.withStatus(emptySet()).isEmpty())
    }

    @Test
    fun `deleting a tree removes only that objective's tasks`() = runTest {
        val repo = createRepository()
        repo.upsertAll(listOf(TaskSamples.objective("tsk_a"), TaskSamples.work("tsk_a1", root = "tsk_a"), TaskSamples.objective("tsk_b"), TaskSamples.work("tsk_b1", root = "tsk_b")))
        repo.deleteTree(TaskId("tsk_a"))
        assertTrue(repo.getTree(TaskId("tsk_a")).isEmpty())
        assertEquals(listOf("tsk_b", "tsk_b1"), repo.getTree(TaskId("tsk_b")).map { it.id.value })
        repo.deleteTree(TaskId("tsk_a"))
    }

    @Test
    fun `open observers see later changes`() = runTest {
        val repo = createRepository()
        val objectives = collectIn(backgroundScope, repo.observeObjectives())
        val tree = collectIn(backgroundScope, repo.observeTree(TaskId("tsk_root")))
        awaitUntil { objectives.isNotEmpty() && tree.isNotEmpty() }
        repo.upsert(TaskSamples.objective())
        repo.upsert(TaskSamples.work("tsk_1"))
        awaitUntil { objectives.last().size == 1 && tree.last().size == 2 }
        repo.update(TaskId("tsk_1")) { it.copy(status = TaskStatus.RUNNING) }
        awaitUntil { tree.last().last().status == TaskStatus.RUNNING }
        repo.deleteTree(TaskId("tsk_root"))
        awaitUntil { objectives.last().isEmpty() && tree.last().isEmpty() }
    }
}

abstract class MessageRepositoryContract {
    protected abstract fun createRepository(): MessageRepository

    @Test
    fun `stores every field in sending order per objective and ignores a repeated id`() = runTest {
        val repo = createRepository()
        val b = TaskSamples.message("msg_b", at = 200)
        val a = TaskSamples.message("msg_a", at = 100, type = MessageType.FINDINGS).copy(taskId = null, replyTo = null, from = Participant.Owner, to = Participant.System, hop = 0)
        val tie = TaskSamples.message("msg_c", at = 200, type = MessageType.REVIEW_RESULT)
        val other = TaskSamples.message("msg_x", root = "tsk_other", at = 150)
        listOf(b, a, other, tie).forEach { repo.append(it) }
        repo.append(b.copy(body = "changed"))
        assertEquals(listOf(a, b, tie), repo.list(TaskId("tsk_root")))
        assertEquals(listOf(a, b, tie), repo.observe(TaskId("tsk_root")).first())
        assertEquals(3, repo.count(TaskId("tsk_root")))
        assertEquals(listOf(tie, b), repo.observeRecent(2).first())
        repo.deleteFor(TaskId("tsk_root"))
        assertTrue(repo.list(TaskId("tsk_root")).isEmpty())
        assertEquals(listOf(other), repo.list(TaskId("tsk_other")))
    }

    @Test
    fun `lists one objective's messages of some types, and of one task, in sending order`() = runTest {
        // Review of wave 2a, INT-4: the orchestrator reads the owner's notes and a task's answers this way.
        val repo = createRepository()
        val note = TaskSamples.message("msg_note", at = 300, type = MessageType.GUIDANCE).copy(taskId = null, from = Participant.Owner)
        val answer = TaskSamples.message("msg_ans", at = 200, type = MessageType.ASSISTANCE_RESPONSE).copy(taskId = TaskId("tsk_a"))
        val otherTask = TaskSamples.message("msg_ans2", at = 250, type = MessageType.ASSISTANCE_RESPONSE).copy(taskId = TaskId("tsk_b"))
        val tie = TaskSamples.message("msg_tie", at = 200, type = MessageType.GUIDANCE).copy(taskId = TaskId("tsk_a"), from = Participant.Owner)
        val assignment = TaskSamples.message("msg_assign", at = 100).copy(taskId = TaskId("tsk_a"))
        val elsewhere = TaskSamples.message("msg_far", root = "tsk_other", at = 150, type = MessageType.GUIDANCE)
        listOf(note, answer, otherTask, tie, assignment, elsewhere).forEach { repo.append(it) }
        val root = TaskId("tsk_root")
        assertEquals(listOf(tie, note), repo.listOfTypes(root, setOf(MessageType.GUIDANCE)))
        assertEquals(listOf(answer, tie, otherTask, note), repo.listOfTypes(root, setOf(MessageType.GUIDANCE, MessageType.ASSISTANCE_RESPONSE)))
        assertEquals(listOf(answer), repo.listOfTypes(root, setOf(MessageType.ASSISTANCE_RESPONSE), TaskId("tsk_a")))
        assertEquals(listOf(answer, tie), repo.listOfTypes(root, setOf(MessageType.ASSISTANCE_RESPONSE, MessageType.GUIDANCE), TaskId("tsk_a")))
        assertTrue(repo.listOfTypes(root, emptySet()).isEmpty())
        assertTrue(repo.listOfTypes(root, setOf(MessageType.QUESTION)).isEmpty())
        assertEquals(listOf(elsewhere), repo.listOfTypes(TaskId("tsk_other"), setOf(MessageType.GUIDANCE)))
    }

    @Test
    fun `concurrent appends are all kept and observers see them`() = runTest {
        val repo = createRepository()
        val seen = collectIn(backgroundScope, repo.observe(TaskId("tsk_root")))
        awaitUntil { seen.isNotEmpty() }
        withContext(Dispatchers.Default) {
            (1..60).map { n -> launch { repo.append(TaskSamples.message("msg_$n", at = n.toLong())) } }.joinAll()
        }
        assertEquals(60, repo.count(TaskId("tsk_root")))
        awaitUntil { seen.last().size == 60 }
    }
}
