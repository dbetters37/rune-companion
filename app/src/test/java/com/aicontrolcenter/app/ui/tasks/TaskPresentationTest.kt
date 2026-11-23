package com.aicontrolcenter.app.ui.tasks

import com.aicontrolcenter.app.work.ObjectiveProgress
import com.aicontrolcenter.app.work.WorkNotice
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.ReviewVerdict
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.testkit.Samples
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskPresentationTest {
    private val director = AgentId("agt_director")
    private val researcher = AgentId("agt_researcher")
    private val reviewer = AgentId("agt_reviewer")
    private val root = TaskId("tsk_root")

    private val snapshot = RegistrySnapshot(
        agents = listOf(
            AgentEntry(Samples.agent(id = director.value, name = "Director"), AgentAvailability.Disabled, AgentStats(), isDesignatedDirector = false),
            AgentEntry(Samples.agent(id = researcher.value, name = "Researcher"), AgentAvailability.Disabled, AgentStats(), isDesignatedDirector = false),
            AgentEntry(Samples.agent(id = reviewer.value, name = "Reviewer"), AgentAvailability.Disabled, AgentStats(), isDesignatedDirector = false),
        ),
    )
    private val faces = AgentFaces(snapshot)

    private fun task(id: String, kind: TaskKind, parent: TaskId? = root, order: Int = 0, at: Long = 1, assignee: AgentId? = researcher, status: TaskStatus = TaskStatus.QUEUED, dependsOn: List<String> = emptyList(), chain: List<AgentId> = emptyList()) =
        TaskRecord(
            id = TaskId(id), rootId = root, parentId = parent, kind = kind, title = id, instructions = "", assignee = assignee,
            requestedBy = TaskRequester.Agent(director), status = status, dependsOn = dependsOn.map(::TaskId), order = order,
            delegationChain = chain, createdAt = at, updatedAt = at,
        )

    private val objective = TaskRecord(root, root, null, TaskKind.OBJECTIVE, "Objective", "Research note apps", assignee = director, requestedBy = TaskRequester.Owner, status = TaskStatus.RUNNING, createdAt = 0, updatedAt = 0)

    @Test
    fun `the plan lists the Director's steps in order with reviews and delegated work nested under them`() {
        val tree = listOf(
            objective,
            task("synthesis", TaskKind.SYNTHESIS, order = 2, at = 2, assignee = director),
            task("second", TaskKind.WORK, order = 1, at = 1, dependsOn = listOf("first"), status = TaskStatus.IN_REVIEW).copy(
                revisionRound = 1, attempt = 1, needsReview = true,
                reviews = listOf(ReviewVerdict(reviewer, ReviewDecision.REVISE, "Prices mixed", listOf("Use yearly prices"), at = 5)),
            ),
            task("first", TaskKind.WORK, order = 0, at = 1, status = TaskStatus.COMPLETED),
            task("review", TaskKind.REVIEW, parent = TaskId("second"), at = 6, assignee = reviewer),
            task("delegated", TaskKind.WORK, parent = TaskId("first"), at = 3, chain = listOf(researcher)),
        )
        val rows = planRows(tree, faces)
        assertEquals(listOf("first", "delegated", "second", "review", "synthesis"), rows.map { it.id.value })
        assertEquals(listOf(1, null, 2, null, null), rows.map { it.number })
        assertEquals(listOf(0, 1, 0, 1, 0), rows.map { it.depth })
        val second = rows[2]
        assertEquals(listOf(1), second.dependsOn)
        assertEquals(2, second.attempt)
        assertEquals("Revision asked", second.verdict?.status?.text)
        assertEquals("In review", second.status.text)
        assertEquals("Reviewer", rows[3].assignee?.name)
    }

    @Test
    fun `the interpretation comes from the final-result brief, or else the plan message`() {
        val synthesis = task("synthesis", TaskKind.SYNTHESIS).copy(instructions = "Interpretation: Find an offline app.\n\nWrite a page.")
        assertEquals("Find an offline app.", interpretationOf(listOf(objective, synthesis), emptyList()))
        val plan = message(Participant.Agent(director), Participant.Owner, MessageType.STATUS, "Plan: Compare three apps\n1. Research → Researcher")
        assertEquals("Compare three apps", interpretationOf(listOf(objective), listOf(plan)))
        assertNull(interpretationOf(listOf(objective), emptyList()))
    }

    @Test
    fun `limits the orchestrator reached are listed once each, in its words`() {
        val messages = listOf(
            message(Participant.System, Participant.Owner, MessageType.STATUS, "Limit reached: the objective reached its limit of 200 messages."),
            message(Participant.Agent(director), Participant.Owner, MessageType.STATUS, "Limit reached: not from the system"),
            message(Participant.System, Participant.Owner, MessageType.STATUS, "Limit reached: the objective reached its limit of 200 messages."),
        )
        assertEquals(listOf("The objective reached its limit of 200 messages."), limitsReached(messages))
    }

    @Test
    fun `messages name who sent them to whom, and the task they are about`() {
        val tree = listOf(objective, task("first", TaskKind.WORK))
        val rows = messagesUi(
            listOf(
                message(Participant.Owner, Participant.Agent(director), MessageType.ASSIGNMENT, "Research note apps"),
                message(Participant.Agent(director), Participant.Agent(AgentId("agt_gone")), MessageType.ASSIGNMENT, "Do it", task = TaskId("first")),
            ),
            tree, faces, now = 10, zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("You", "Director"), rows.map { it.from.name })
        assertEquals(listOf("Director", "Removed agent"), rows.map { it.to.name })
        assertEquals(listOf(null, "first"), rows.map { it.taskTitle })
        assertEquals(listOf("Director", "Researcher"), participantsOf(tree, faces).map { it.name })
    }

    @Test
    fun `the notification names the oldest running objective, its progress and the others`() {
        val second = objective.copy(id = TaskId("tsk_other"), rootId = TaskId("tsk_other"), title = "Plan a trip", createdAt = 5)
        val tree = listOf(objective, task("first", TaskKind.WORK, status = TaskStatus.COMPLETED), task("synthesis", TaskKind.SYNTHESIS))
        val notice = WorkNotice.of(setOf(root, second.id), listOf(listOf(second), tree))!!
        assertEquals(root, notice.objectiveId)
        assertEquals("Director is working on: Objective", notice.title)
        assertEquals("1 of 2 tasks done · 1 more objective running", notice.text)
        assertEquals(ObjectiveProgress(1, 2), notice.progress)
        assertEquals("Planning the work", WorkNotice.of(setOf(second.id), listOf(listOf(second)))!!.text)
        assertNull(WorkNotice.of(emptySet(), listOf(tree)))
    }

    @Test
    fun `a task waiting for the owner says so in the owner's words, in the plan too`() {
        val waiting = task("compare", TaskKind.WORK, status = TaskStatus.WAITING_FOR_OWNER)
            .copy(statusDetail = "Waiting for the owner's permission: Read the web page \"https://example.org\"")
        val row = planRows(listOf(objective, waiting), faces).single()
        assertEquals("Waiting for your permission: Read the web page \"https://example.org\"", row.statusDetail)
        assertEquals("Waiting for your approval: Delete \"a\"", ownerFacing("Waiting for the owner's approval: Delete \"a\""))
        assertEquals("Waiting for the owner's lawyer", ownerFacing("Waiting for the owner's lawyer"), "only the broker's own line is rephrased")
    }

    private var messageCount = 0

    private fun message(from: Participant, to: Participant, type: MessageType, body: String, task: TaskId? = null) =
        AgentMessage(MessageId("msg_${++messageCount}"), root, task, from, to, type, body, at = messageCount.toLong())
}
