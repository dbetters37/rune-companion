package com.aicontrolcenter.app.ui.team

import com.aicontrolcenter.app.testing.TeamSamples
import com.aicontrolcenter.app.testing.TeamSamples.agent
import com.aicontrolcenter.app.testing.TeamSamples.analyst
import com.aicontrolcenter.app.testing.TeamSamples.commons
import com.aicontrolcenter.app.testing.TeamSamples.director
import com.aicontrolcenter.app.testing.TeamSamples.header
import com.aicontrolcenter.app.testing.TeamSamples.objective
import com.aicontrolcenter.app.testing.TeamSamples.researcher
import com.aicontrolcenter.app.testing.TeamSamples.reviewer
import com.aicontrolcenter.app.testing.TeamSamples.root
import com.aicontrolcenter.app.testing.TeamSamples.summary
import com.aicontrolcenter.app.testing.TeamSamples.writer
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.sampleQuestion
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.ui.tasks.WaitTexts
import com.aicontrolcenter.app.work.WorkResult
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentActivity
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentRuntimeState
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Team view's model from stored summaries and headers: who is in the picture, in which state, and the links. */
class TeamGraphBuilderTest {
    private val now = TestApp.START_TIME + 600_000
    private val snapshot = TeamSamples.snapshot()

    private fun build(
        summaries: List<com.aicontrolcenter.core.tasks.TaskSummary> = listOf(objective()),
        headers: List<com.aicontrolcenter.core.tasks.MessageHeader> = emptyList(),
        requests: List<com.aicontrolcenter.core.operator.OwnerRequest> = emptyList(),
        monitor: Map<AgentId, AgentRuntimeState> = emptyMap(),
        objectiveId: TaskId? = root,
        snapshot: com.aicontrolcenter.core.registry.RegistrySnapshot = this.snapshot,
    ) = TeamGraphBuilder.build(snapshot, summaries, headers, requests, monitor, now, TeamSamples.spaces, objectiveId)

    @Test
    fun `every message type becomes its link kind, one link per pair with both directions`() {
        val headers = listOf(
            header(Participant.Owner, agent(director), MessageType.ASSIGNMENT, 1),
            header(agent(director), agent(researcher), MessageType.ASSIGNMENT, 2),
            header(agent(analyst), agent(researcher), MessageType.ASSISTANCE_REQUEST, 3),
            header(agent(researcher), agent(analyst), MessageType.ASSISTANCE_RESPONSE, 4),
            header(agent(researcher), agent(writer), MessageType.DELEGATION, 5),
            header(agent(director), agent(reviewer), MessageType.REVIEW_REQUEST, 6),
            header(agent(reviewer), agent(researcher), MessageType.REVIEW_RESULT, 7),
            header(agent(researcher), agent(director), MessageType.COMPLETION, 8),
            header(agent(researcher), Participant.Space(commons), MessageType.MEMORY_SHARE, 9),
            header(agent(analyst), Participant.System, MessageType.MEMORY_SHARE, 10),
            header(agent(writer), Participant.Owner, MessageType.QUESTION, 11),
            header(Participant.Owner, agent(writer), MessageType.ANSWER, 12),
            header(Participant.Owner, agent(analyst), MessageType.GUIDANCE, 13),
            header(agent(director), Participant.Owner, MessageType.COMPLETION, 14),
            header(Participant.System, Participant.Owner, MessageType.STATUS, 15),
        )
        val graph = build(headers = headers)
        fun link(a: String, b: String) = assertNotNull(graph.link(linkKeyOf(a, b)), "$a–$b")

        assertEquals(LinkKind.TELLS_YOU, link(YOU_ID, director.value).kind, "the newest message decides the drawing")
        assertEquals(setOf(LinkKind.ASSIGNS, LinkKind.TELLS_YOU), link(YOU_ID, director.value).kinds.map { it.kind }.toSet())
        assertEquals(LinkKind.REPORTS, link(director.value, researcher.value).kind)
        with(link(analyst.value, researcher.value)) {
            assertEquals(LinkKind.ASKS, kind)
            assertTrue(aToB && bToA, "a question and its answer: both ways")
            assertEquals(2, count)
        }
        assertEquals(LinkKind.DELEGATES, link(researcher.value, writer.value).kind)
        assertEquals(LinkKind.REVIEWS, link(director.value, reviewer.value).kind)
        assertEquals(LinkKind.REVIEWS, link(reviewer.value, researcher.value).kind)
        assertEquals(LinkKind.SHARES, link(researcher.value, spaceNodeId(commons)).kind, "a share reaches its space")
        assertEquals(LinkKind.SHARES, link(analyst.value, SHARED_MEMORY_ID).kind, "an older share goes to Shared memory")
        assertEquals(LinkKind.ASKS_YOU, link(YOU_ID, writer.value).kind)
        assertEquals(LinkKind.NOTES, link(YOU_ID, analyst.value).kind, "a note from you")
        assertNull(graph.links.firstOrNull { it.touches("system") }, "the app itself is not in the picture")
        assertEquals("Team notes", graph.node(spaceNodeId(commons))?.name)
        assertEquals("Shared memory", graph.node(SHARED_MEMORY_ID)?.name)
        assertEquals(graph.links.size, graph.links.map { it.key }.distinct().size)
    }

    @Test
    fun `you are at the top with what waits, and the agent who asks waits for you`() {
        val question = sampleQuestion(researcher, createdAt = now - 60_000, taskId = TaskId("tsk_find"), rootId = root)
        val approval = sampleApproval(analyst, createdAt = now - 30_000, taskId = TaskId("tsk_compare"), rootId = root)
        val elsewhere = sampleApproval(writer, id = "req_chat", createdAt = now - 10_000, rootId = null)
        val summaries = listOf(
            objective(),
            summary("tsk_find", "Find note apps", TaskStatus.WAITING_FOR_OWNER, researcher, detail = "Waiting for the owner's answer: Which city?"),
            summary("tsk_compare", "Compare them", TaskStatus.WAITING_FOR_OWNER, analyst, detail = "Waiting for the owner's approval: Delete 3 files"),
        )
        val graph = build(summaries, requests = listOf(approval, question, elsewhere))

        val you = assertNotNull(graph.owner)
        assertEquals(NodeState.WAITING_FOR_YOU, you.state)
        assertEquals("1 request and 1 question wait for you", you.stateText)
        assertEquals(listOf(question.id, approval.id), you.requestIds, "oldest first, this objective's only")
        assertEquals(setOf(question.id), you.questionIds)
        with(assertNotNull(graph.node(researcher.value))) {
            assertEquals(NodeState.WAITING_FOR_YOU, state)
            assertEquals("Waiting for you", stateText)
            assertEquals("Asks you: “Which city should the report cover?”", detail, "the agent's question, quoted")
            assertEquals("Find note apps", taskTitle)
        }
        assertTrue(graph.node(analyst.value)!!.detail!!.startsWith("Asks you to approve: Delete 3 files"))
        val all = build(summaries, requests = listOf(approval, question, elsewhere), objectiveId = null)
        assertEquals(3, all.owner!!.requestIds.size, "the whole team: a chat's request waits for you too")
        val expired = sampleApproval(analyst, id = "req_old", createdAt = now - 3_600_000, minutes = 30, rootId = root)
        assertEquals(NodeState.IDLE, build(requests = listOf(expired)).owner!!.state, "a request past its time waits for nobody")
        assertEquals(TeamWords.NOTHING_WAITS, build().owner!!.stateText)
    }

    @Test
    fun `the state follows the most important thing an agent does, in words`() {
        val summaries = listOf(
            objective(),
            summary("t1", "Find note apps", TaskStatus.RUNNING, researcher),
            summary("t2", "Compare", TaskStatus.WAITING_FOR_AGENT, analyst, detail = "Waiting for Researcher's answer"),
            summary("t3", "Check the list", TaskStatus.RUNNING, reviewer, kind = TaskKind.REVIEW, parentId = TaskId("t1")),
            summary("t4", "Write it up", TaskStatus.QUEUED, writer, dependsOn = listOf("t1", "t2")),
            // An earlier, finished step of the Researcher does not hide that it works now.
            summary("t0", "Plan the search", TaskStatus.COMPLETED, researcher),
        )
        val monitor = mapOf(researcher to AgentRuntimeState(researcher, AgentActivity.WORKING, "Task: Find note apps · Reading a web page", TaskId("t1")))
        val graph = build(summaries, monitor = monitor)

        with(graph.node(researcher.value)!!) {
            assertEquals(NodeState.WORKING, state)
            assertEquals("Reading a web page", detail, "the step alone, not the title again")
            assertEquals("Find note apps", taskTitle)
        }
        assertEquals(NodeState.WAITING_FOR_TEAMMATE, graph.node(analyst.value)!!.state)
        assertEquals("Waiting for Researcher's answer", graph.node(analyst.value)!!.detail)
        assertEquals("Checking the work of Researcher", graph.node(reviewer.value)!!.detail)
        assertEquals(NodeState.BLOCKED, graph.node(writer.value)!!.state)
        assertEquals("Waiting for earlier steps", graph.node(writer.value)!!.stateText)
        with(graph.director!!) {
            assertEquals(TeamNodeKind.DIRECTOR, kind)
            assertEquals(NodeState.WAITING_FOR_TEAMMATE, state)
            assertEquals(TeamWords.WAITING_FOR_TEAM, detail)
        }
        assertEquals("Uses Gemma 3 4B (Home server) · its text leaves this device (local network)", graph.node(researcher.value)!!.modelLine)
        assertEquals(2, graph.count(NodeState.WORKING), "the Researcher and the Reviewer work")
    }

    @Test
    fun `an agent waiting for a teammate or for you is never shown as working (N3)`() {
        val monitor = mapOf(
            researcher to AgentRuntimeState(researcher, AgentActivity.WAITING_FOR_APPROVAL, "Waiting for your answer: Delete files", TaskId("t1")),
            analyst to AgentRuntimeState(analyst, AgentActivity.WAITING_FOR_AGENT, "Task: Compare · Waiting for Researcher's answer", TaskId("t2")),
        )
        val summaries = listOf(objective(), summary("t1", "Find", TaskStatus.RUNNING, researcher), summary("t2", "Compare", TaskStatus.RUNNING, analyst))
        val graph = build(summaries, monitor = monitor)
        assertEquals(NodeState.WAITING_FOR_YOU, graph.node(researcher.value)!!.state)
        assertEquals(NodeState.WAITING_FOR_TEAMMATE, graph.node(analyst.value)!!.state, "the live monitor wins over a task row that still says it runs")
        assertEquals("Waiting for Researcher's answer", graph.node(analyst.value)!!.detail)
        val waiting = build(listOf(objective(), summary("t2", "Compare", TaskStatus.WAITING_FOR_AGENT, analyst)))
        assertEquals(NodeState.WAITING_FOR_TEAMMATE, waiting.node(analyst.value)!!.state, "the task row alone says so too")
    }

    @Test
    fun `paused objectives say why, and waiting work after a pause or a stop is stopped, never waiting with a time`() {
        val paused = build(
            listOf(
                objective(TaskStatus.PAUSED, DailyLimits.PAUSED_TEXT),
                summary("t1", "Find", TaskStatus.PAUSED, researcher, detail = DailyLimits.PAUSED_TEXT),
                summary("t2", "Compare", TaskStatus.QUEUED, analyst, detail = "${WaitTexts.WAS_WAITING} xAI is not answering. Tries again when the objective resumes.", retryAt = now + 60_000),
                summary("t3", "Write", TaskStatus.INTERRUPTED, writer, detail = "Interrupted: Android closed the app to free memory."),
            ),
        )
        with(paused.node(researcher.value)!!) {
            assertEquals(NodeState.PAUSED, state)
            assertEquals("Paused: today's limit is reached", detail)
        }
        with(paused.node(analyst.value)!!) {
            assertEquals(NodeState.STOPPED, state, "INT-3: was waiting, now stopped")
            assertEquals("Stopped", stateText)
            assertTrue(detail!!.startsWith(WaitTexts.WAS_WAITING))
        }
        assertEquals("Interrupted: Android closed the app to free memory.", paused.node(writer.value)!!.detail)
        assertEquals("Paused: today's limit is reached", paused.director!!.detail)
        assertEquals("Paused: the phone is almost full", TeamWords.pauseReason(WorkResult.STORAGE_PAUSED_DETAIL))
        assertEquals("Paused by you", TeamWords.pauseReason(TeamWords.OWNER_PAUSED))

        val waiting = build(
            listOf(objective(), summary("t2", "Compare", TaskStatus.QUEUED, analyst, detail = "${WaitTexts.WAITING} xAI is not answering; trying again at 10:45.", retryAt = now + 60_000)),
        )
        with(waiting.node(analyst.value)!!) {
            assertEquals(NodeState.WAITING_TO_TRY_AGAIN, state)
            assertEquals("xAI is not answering; trying again at 10:45.", detail, "the framework's words, as the objective's page shows them")
        }
    }

    @Test
    fun `one objective places only its participants and counts the rest`() {
        val graph = build(
            listOf(objective(), summary("t1", "Find", TaskStatus.RUNNING, researcher)),
            headers = listOf(header(agent(analyst), agent(researcher), MessageType.ASSISTANCE_REQUEST, 1)),
        )
        assertEquals(listOf(researcher.value, analyst.value), graph.ring.map { it.id }, "the team's order, participants only")
        assertEquals(2, graph.agentsNotInObjective, "Reviewer and Writer")
        assertEquals(listOf(YOU_ID, director.value, researcher.value, analyst.value), graph.nodes.filter { it.isAgent || it.id == YOU_ID }.map { it.id })
        val whole = build(listOf(objective()), objectiveId = null)
        assertEquals(4, whole.ring.size, "the whole team without an objective")
        assertEquals(0, whole.agentsNotInObjective)
    }

    @Test
    fun `a deleted agent who took part is a former team member`() {
        val gone = AgentId("agt_gone")
        val graph = build(
            listOf(objective(), summary("t1", "Find", TaskStatus.COMPLETED, gone)),
            headers = listOf(header(agent(director), agent(gone), MessageType.ASSIGNMENT, 1)),
        )
        with(graph.node(gone.value)!!) {
            assertEquals(TeamNodeKind.FORMER_AGENT, kind)
            assertEquals(TeamWords.FORMER_MEMBER, name)
            assertEquals(NodeState.DONE, state)
            assertTrue(dimmed)
        }
        assertNotNull(graph.link(linkKeyOf(director.value, gone.value)))
    }

    @Test
    fun `agents that cannot run say why in their own words`() {
        val noModel = TeamSamples.entry(AgentId("agt_new"), "Translator", availability = AgentAvailability.Unavailable(UnavailableReason.NO_MODEL_ASSIGNED, "No model is assigned."))
        val off = TeamSamples.entry(AgentId("agt_off"), "Archivist", availability = AgentAvailability.Disabled)
        val graph = build(listOf(objective()), objectiveId = null, snapshot = TeamSamples.snapshot(noModel, off))
        with(graph.node("agt_new")!!) {
            assertEquals(NodeState.CANNOT_RUN, state)
            assertEquals("No model yet", stateText)
        }
        assertTrue(graph.node("agt_off")!!.dimmed)
        assertEquals(NodeState.IDLE, graph.node(writer.value)!!.state)
        assertEquals("Free", graph.node(writer.value)!!.stateText)
    }

    @Test
    fun `caps of 30 agents, 6 spaces and 120 links, the rest counted`() {
        val many = (1..31).map { TeamSamples.entry(AgentId("agt_x$it"), "Helper $it") }
        val crowd = TeamSamples.snapshot(*many.toTypedArray())
        val graph = build(listOf(objective()), objectiveId = null, snapshot = crowd)
        assertEquals(TeamGraphBuilder.MAX_AGENTS, graph.ring.size)
        assertEquals(4 + 31 - TeamGraphBuilder.MAX_AGENTS, graph.agentsLeftOut)

        val spaces = (1..8).map { com.aicontrolcenter.core.memory.KnowledgeSpace(com.aicontrolcenter.core.SpaceId("spc_$it"), "Space $it", createdAt = 1, updatedAt = 1) }
        val spaced = TeamGraphBuilder.build(snapshot, listOf(objective()), emptyList(), emptyList(), emptyMap(), now, spaces, objective = null)
        assertEquals(TeamGraphBuilder.MAX_SPACES, spaced.spaces.size)
        assertEquals(2, spaced.spacesLeftOut)

        val pairs = crowd.agents.flatMap { a -> crowd.agents.map { b -> a.profile.id to b.profile.id } }.filter { (a, b) -> a.value < b.value }
        val chatter = pairs.take(150).mapIndexed { index, (a, b) -> header(agent(a), agent(b), MessageType.ASSISTANCE_REQUEST, index.toLong()) }
        val busy = build(listOf(objective()), headers = chatter, objectiveId = null, snapshot = crowd)
        assertEquals(TeamGraphBuilder.MAX_LINKS, busy.links.size)
        assertTrue(busy.linksLeftOut > 0)
        assertTrue(busy.links.all { it.lastAt >= busy.links.minOf { l -> l.lastAt } }, "the newest links are kept")
    }

    @Test
    fun `recent links and the newest messages offered as dots`() {
        val headers = listOf(
            header(agent(director), agent(researcher), MessageType.ASSIGNMENT, now - 120_000),
            header(agent(analyst), agent(researcher), MessageType.ASSISTANCE_REQUEST, now - 5_000),
        )
        val graph = build(headers = headers)
        assertFalse(graph.link(linkKeyOf(director.value, researcher.value))!!.recent)
        assertTrue(graph.link(linkKeyOf(analyst.value, researcher.value))!!.recent)
        assertEquals(headers.map { it.id.value }, graph.pulses.map { it.key }, "in sending order")
        assertEquals(analyst.value, graph.pulses.last().fromId)
        assertEquals(LinkKind.ASKS, graph.pulses.last().kind)
        val many = (1..30).map { header(agent(director), agent(researcher), MessageType.ASSIGNMENT, it.toLong()) }
        assertEquals(TeamGraphBuilder.PULSE_CANDIDATES, build(headers = many).pulses.size)
    }

    @Test
    fun `a question that waits is not an approval, and an answered one is gone`() {
        val answered = sampleQuestion(researcher, createdAt = now - 60_000, rootId = root).copy(
            status = com.aicontrolcenter.core.operator.OwnerRequestStatus.APPROVED, decidedAt = now - 1_000, answer = "Berlin",
        )
        val graph = build(requests = listOf(answered))
        assertEquals(emptyList<RequestId>(), graph.owner!!.requestIds)
        assertEquals(NodeState.IDLE, graph.node(researcher.value)?.state ?: NodeState.IDLE)
    }

    @Test
    fun `the menu tool is named by its title, never tools_open`() {
        val monitor = mapOf(researcher to AgentRuntimeState(researcher, AgentActivity.WORKING, "Task: Find · Running tool: tools_open", TaskId("t1")))
        val graph = build(listOf(objective(), summary("t1", "Find", TaskStatus.RUNNING, researcher)), monitor = monitor)
        assertEquals("Running tool: Open more tools", graph.node(researcher.value)!!.detail)
        val chat = mapOf(analyst to AgentRuntimeState(analyst, AgentActivity.WORKING, "Running tool: tools_open"))
        assertEquals("Running tool: Open more tools", build(listOf(objective()), monitor = chat, objectiveId = null).node(analyst.value)!!.detail)
    }

    @Test
    fun `30 agents and 1,000 messages stay within the caps`() {
        val helpers = (1..25).map { TeamSamples.entry(AgentId("agt_h$it"), "Helper $it") }
        val crowd = TeamSamples.snapshot(*helpers.toTypedArray())
        val ids = crowd.agents.map { it.profile.id }
        val headers = (0 until 1_000).map { i ->
            val from = ids[i % ids.size]
            val to = ids[(i * 7 + 3) % ids.size].takeIf { it != from } ?: ids[(i + 1) % ids.size]
            header(agent(from), agent(to), if (i % 2 == 0) MessageType.ASSISTANCE_REQUEST else MessageType.ASSISTANCE_RESPONSE, now - 1_000_000 + i)
        }
        val summaries = listOf(objective()) + ids.drop(1).mapIndexed { i, id -> summary("w$i", "Part $i", TaskStatus.RUNNING, id) }
        val graph = build(summaries, headers = headers, snapshot = crowd)
        assertEquals(29, graph.ring.size, "every participant but the Director on the ring")
        assertTrue(graph.links.size <= TeamGraphBuilder.MAX_LINKS)
        assertEquals(TeamGraphBuilder.PULSE_CANDIDATES, graph.pulses.size)
        assertTrue(graph.links.all { it.messageIds.size <= TeamGraphBuilder.LINK_MESSAGES })
        assertEquals(1_000, graph.links.sumOf { it.count } + headers.count { h ->
            graph.link(linkKeyOf((h.from as Participant.Agent).agentId.value, (h.to as Participant.Agent).agentId.value)) == null
        }, "every message is on a kept link or on one left out")
    }
}
