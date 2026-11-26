package com.aicontrolcenter.app.ui.team

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.letTheTeamWork
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.sampleQuestion
import com.aicontrolcenter.app.testing.teamAtWork
import com.aicontrolcenter.app.testing.teamOnSimulator
import com.aicontrolcenter.app.ui.tasks.WaitTexts
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Team view over the real framework and in-memory stores (TestApp), while the team works. */
class TeamViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.team(app: TestApp, objective: TaskId? = null, routines: List<Routine> = emptyList()) = TeamViewModel(
        objectiveId = objective,
        tasks = app.graph.tasks,
        messages = app.graph.messages,
        directory = app.graph.registryDirectory,
        pendingRequests = app.graph.ownerRequests.pending,
        monitor = app.graph.activityMonitor,
        memory = app.graph.memoryService,
        clock = app.clock,
        zone = { ZoneOffset.UTC },
        routines = flowOf(routines),
        capabilityInfo = app.graph.capabilities::info,
    ).also {
        it.uiState.activeIn(backgroundScope)
        it.liveSummary.activeIn(backgroundScope)
    }

    private suspend fun TestApp.id(name: String): AgentId = agentNamed(name).profile.id

    @Test
    fun `while the team works, who works, who waits for earlier steps, and the question between two agents both ways`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val objective = app.teamAtWork()
        letTheTeamWork(1_000)
        val vm = team(app)
        val graph = vm.uiState.value.graph

        assertEquals(TeamFilter.AllCurrent, vm.uiState.value.filter)
        assertEquals(1, vm.uiState.value.currentCount)
        val researcher = app.id("Researcher").value
        val analyst = app.id("Analyst").value
        val writer = app.id("Writer").value
        assertEquals(NodeState.WORKING, graph.node(researcher)!!.state)
        assertEquals("Find offline-first note apps", graph.node(researcher)!!.taskTitle)
        assertEquals(NodeState.WORKING, graph.node(analyst)!!.state)
        assertEquals(NodeState.BLOCKED, graph.node(writer)!!.state, "the write-up waits for both")
        val asks = assertNotNull(graph.link(linkKeyOf(analyst, researcher)))
        assertEquals(LinkKind.ASKS, asks.kind)
        assertTrue(asks.aToB && asks.bToA, "the question and its answer")
        val director = graph.director!!
        assertEquals(LinkKind.ASSIGNS, graph.link(linkKeyOf(director.id, researcher))!!.kinds.first().kind)
        assertNotNull(graph.link(linkKeyOf(YOU_ID, director.id)), "you gave the Director the objective")
        assertEquals(NodeState.WAITING_FOR_TEAMMATE, director.state)
        assertTrue(vm.uiState.value.summary.startsWith("2 working"), vm.uiState.value.summary)

        // One objective: the same team, its participants only.
        vm.show(TeamFilter.One(objective.id))
        val one = vm.uiState.value
        assertEquals(TeamFilter.One(objective.id), one.filter)
        val card = assertNotNull(one.card)
        assertEquals(objective.title, card.title)
        assertNull(card.routine, "the owner started it")
        assertTrue(one.graph.ring.map { it.id }.containsAll(listOf(researcher, analyst, writer)))
    }

    @Test
    fun `a request for you puts the agent in waiting for you and is in your sheet until it is answered`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val objective = app.teamAtWork()
        letTheTeamWork(1_000)
        val researcher = app.id("Researcher")
        val request = app.raise(sampleApproval(researcher, "Researcher", createdAt = app.clock.current, taskId = null, rootId = objective.id))
        val question = app.raise(sampleQuestion(app.id("Analyst"), "Analyst", id = "req_q", createdAt = app.clock.current, taskId = null, rootId = objective.id))
        val vm = team(app, objective.id)

        val graph = vm.uiState.value.graph
        assertEquals(NodeState.WAITING_FOR_YOU, graph.node(researcher.value)!!.state)
        assertEquals(listOf(request.id, question.id), graph.owner!!.requestIds)
        vm.openNode(YOU_ID)
        val you = assertIs<TeamSheetUi.You>(vm.uiState.value.sheet)
        assertEquals(listOf(request.id, question.id), you.requests.map { it.id }, "oldest first")
        assertEquals("1 request and 1 question wait for you", graph.owner!!.stateText)

        app.graph.ownerRequests.deny(request.id)
        runCurrent()
        val after = vm.uiState.value
        assertEquals(listOf(question.id), after.graph.owner!!.requestIds)
        assertEquals(listOf(question.id), assertIs<TeamSheetUi.You>(after.sheet).requests.map { it.id })
        assertTrue(after.graph.node(researcher.value)!!.state != NodeState.WAITING_FOR_YOU)
    }

    @Test
    fun `opening sheets and links never rebuilds the picture, and a link reads its whole messages only when opened`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamAtWork()
        letTheTeamWork(1_000)
        val vm = team(app)
        val before = vm.builds.get()
        val researcher = app.id("Researcher").value
        val analyst = app.id("Analyst").value
        repeat(5) {
            vm.openNode(researcher)
            vm.openNode(YOU_ID)
            vm.closeSheet()
        }
        assertEquals(before, vm.builds.get(), "sheets are built from the picture, not the other way round")

        vm.openNode(researcher)
        val member = assertIs<TeamSheetUi.Member>(vm.uiState.value.sheet)
        assertTrue(member.links.any { it.otherName == "Analyst" && it.words.contains("asked by Analyst") }, member.links.toString())
        assertTrue(member.messages.size <= TeamViewModel.MEMBER_MESSAGES)

        val key = linkKeyOf(analyst, researcher)
        vm.openLink(key)
        runCurrent()
        val link = assertIs<TeamSheetUi.Link>(vm.uiState.value.sheet)
        assertTrue(link.title.startsWith("Questions between"), link.title)
        val messages = assertNotNull(link.messages, "read once the sheet is open")
        assertEquals(listOf(MessageType.ASSISTANCE_REQUEST, MessageType.ASSISTANCE_RESPONSE), messages.map { it.type })
        assertEquals("Which of the apps you found sync without an account?", messages.first().body, "the whole message, as stored")
    }

    @Test
    fun `the spoken summary changes at most every 10 seconds`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        val vm = team(app)
        runCurrent()
        val first = vm.liveSummary.value
        assertEquals("0 working, 0 waiting for you, 0 waiting for a teammate", first)

        app.teamAtWork()
        letTheTeamWork(1_000)
        assertEquals(first, vm.liveSummary.value, "the team started a moment ago: nothing announced yet")
        assertTrue(vm.uiState.value.summary != first, "the screen itself is up to date")
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(vm.uiState.value.summary, vm.liveSummary.value)
    }

    @Test
    fun `a paused objective says why, its waiting work is stopped, and a routine's objective names its routine`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnSimulator()
        val director = app.director().profile.id
        val analyst = app.id("Analyst")
        val root = TaskId("obj_news")
        val routine = RoutineId("rtn_morning")
        val now = app.clock.current
        fun task(id: String, kind: TaskKind, status: TaskStatus, assignee: AgentId, detail: String?, parent: TaskId? = root, retryAt: Long? = null, options: ObjectiveOptions? = null) =
            TaskRecord(
                id = TaskId(id), rootId = root, parentId = parent, kind = kind, title = if (kind == TaskKind.OBJECTIVE) "Morning news" else "Read the headlines",
                instructions = "Do it.", assignee = assignee, requestedBy = TaskRequester.Owner, status = status, statusDetail = detail,
                retryAt = retryAt, options = options, createdAt = now, updatedAt = now, startedAt = now,
            )
        app.tasks.upsert(task(root.value, TaskKind.OBJECTIVE, TaskStatus.PAUSED, director, DailyLimits.PAUSED_TEXT, parent = null, options = ObjectiveOptions(routineId = routine)))
        app.tasks.upsert(task("tsk_read", TaskKind.WORK, TaskStatus.QUEUED, analyst, "${WaitTexts.WAS_WAITING} xAI is not answering. Tries again when the objective resumes.", retryAt = now + 60_000))
        val routines = listOf(Routine(id = routine, title = "Morning news", objectiveText = "Summarise the news.", schedule = RoutineSchedule.Daily(8 * 60), createdAt = 0, updatedAt = 0))
        val vm = team(app, routines = routines)

        val notice = vm.uiState.value.notices.single()
        assertEquals("Paused: today's limit is reached", notice.title)
        assertTrue(notice.text.contains("Daily limits"))
        assertEquals(NodeState.STOPPED, vm.uiState.value.graph.node(analyst.value)!!.state, "never shown as waiting with a time")

        vm.show(TeamFilter.One(root))
        val card = assertNotNull(vm.uiState.value.card)
        assertEquals("Started by routine “Morning news”", card.routine)
        assertEquals("Paused: today's limit", card.status.text)
        assertEquals("Paused: today's limit is reached", card.notice?.title)
    }

    @Test
    fun `with nothing running the whole team is free and the screen says what it can still do`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        val state = team(app).uiState.value
        assertEquals(TeamWords.NO_OBJECTIVE, state.emptyNote)
        assertEquals(0, state.currentCount)
        assertTrue(state.graph.ring.isNotEmpty())
        assertTrue(state.graph.nodes.filter { it.isAgent }.all { it.state == NodeState.IDLE || it.state == NodeState.CANNOT_RUN })
        assertEquals(TeamWords.NOTHING_WAITS, state.graph.owner!!.stateText)
    }
}
