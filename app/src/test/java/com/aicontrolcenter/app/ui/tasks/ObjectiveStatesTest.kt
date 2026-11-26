package com.aicontrolcenter.app.ui.tasks

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.work.WorkResult
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.OwnerConversation
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.CostEstimate
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.UsageSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The objective screen's Phase 6 states (WP11a): why an objective is not moving (today's limit, the phone almost full,
 * a provider wait, work that was waiting when it stopped), its cost so far, the routine that started it, the notes
 * composer, and that the 1-second clock alone never rebuilds the plan (P2).
 */
class ObjectiveStatesTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val root = TaskId("obj_states")

    private fun TestScope.screen(app: TestApp, ticker: kotlinx.coroutines.flow.Flow<Unit> = flowOf(Unit), routines: List<Routine> = emptyList()) =
        ObjectiveViewModel(
            root, app.graph.tasks, app.graph.messages, app.graph.registryDirectory, app.graph.orchestrator, app.graph.activityLog,
            app.graph.settingsView, app.clock, ZoneOffset.UTC, ticker = ticker,
            notes = app.graph.ownerConversation, usage = app.graph.usageReports, prices = app.graph.modelPrices,
            routines = flowOf(routines),
        ).also {
            it.uiState.activeIn(backgroundScope)
            it.elapsed.activeIn(backgroundScope)
        }

    private suspend fun TestApp.objective(
        status: TaskStatus,
        detail: String? = null,
        retryAt: Long? = null,
        options: ObjectiveOptions = ObjectiveOptions(),
        steps: List<TaskRecord> = emptyList(),
    ): AgentId {
        graph.start().join()
        val director = director().profile.id
        val now = clock.current
        tasks.upsert(
            TaskRecord(
                id = root, rootId = root, parentId = null, kind = TaskKind.OBJECTIVE, title = "Plan a weekend in Porto", instructions = "Plan a weekend in Porto.",
                assignee = director, requestedBy = TaskRequester.Owner, status = status, statusDetail = detail, retryAt = retryAt, options = options,
                createdAt = now, updatedAt = now, startedAt = now,
            ),
        )
        tasks.upsertAll(steps)
        return director
    }

    private fun TestApp.step(id: String, status: TaskStatus, detail: String? = null, retryAt: Long? = null, assignee: AgentId? = null, parent: TaskId = root, kind: TaskKind = TaskKind.WORK, order: Int = 0) =
        TaskRecord(
            id = TaskId(id), rootId = root, parentId = parent, kind = kind, title = "Step $id", instructions = "Do $id.", assignee = assignee,
            requestedBy = TaskRequester.Owner, status = status, statusDetail = detail, retryAt = retryAt, order = order,
            createdAt = clock.current, updatedAt = clock.current,
        )

    @Test
    fun `paused by today's limit says so, with the way to the limits`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective(TaskStatus.PAUSED, DailyLimits.PAUSED_TEXT)
        val header = assertNotNull(screen(app).uiState.value.header)
        assertEquals(PauseKind.DAILY_LIMIT, header.pause?.kind)
        assertEquals("Paused: today's limit is reached", header.pause?.title)
        assertEquals("Paused: today's limit", header.status.text)
        assertTrue(header.canResume)
    }

    @Test
    fun `paused because the phone is almost full says so`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective(TaskStatus.PAUSED, WorkResult.STORAGE_PAUSED_DETAIL)
        val header = assertNotNull(screen(app).uiState.value.header)
        assertEquals(PauseKind.STORAGE, header.pause?.kind)
        assertEquals("Paused: the phone is almost full", header.pause?.title)
        assertTrue(header.canResume)
    }

    @Test
    fun `the Director waiting for its provider while it plans says why and when`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective(TaskStatus.RUNNING, "Waiting: xAI is not answering; trying again at 10:45.", retryAt = app.clock.current + 60_000)
        val header = assertNotNull(screen(app).uiState.value.header)
        assertEquals(PauseKind.PROVIDER_WAIT, header.pause?.kind)
        assertEquals("XAI is not answering; trying again at 10:45. The objective goes on by itself.", header.pause?.text)
        assertEquals("Waiting to try again", header.status.text)
    }

    @Test
    fun `work waiting for its provider shows as waiting, and as stopped once the objective stopped`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val later = app.clock.current + 60_000
        app.objective(
            TaskStatus.PAUSED, "Paused by the owner.",
            steps = listOf(
                app.step("a", TaskStatus.QUEUED, "Waiting: Groq asked the app to slow down; trying again at 10:45.", retryAt = later),
                app.step("b", TaskStatus.QUEUED, "Was waiting: Groq is busy. Tries again when the objective resumes.", retryAt = later, order = 1),
                app.step("c", TaskStatus.QUEUED, order = 2),
            ),
        )
        val plan = screen(app).uiState.value.plan
        assertEquals("Waiting to try again", plan.single { it.id == TaskId("a") }.status.text)
        assertEquals("Stopped", plan.single { it.id == TaskId("b") }.status.text)
        assertEquals("Was waiting: Groq is busy. Tries again when the objective resumes.", plan.single { it.id == TaskId("b") }.statusDetail)
        assertEquals("Queued", plan.single { it.id == TaskId("c") }.status.text)
    }

    @Test
    fun `the plan is built from summaries, with the verdict of its reviews`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective(
            TaskStatus.RUNNING,
            steps = listOf(
                app.step("w", TaskStatus.COMPLETED, "Approved by Reviewer (same model as the author)."),
                app.step("r1", TaskStatus.COMPLETED, "Verdict: revise.", parent = TaskId("w"), kind = TaskKind.REVIEW),
                app.step("r2", TaskStatus.COMPLETED, "Verdict: approved.", parent = TaskId("w"), kind = TaskKind.REVIEW),
            ),
        )
        val plan = screen(app).uiState.value.plan
        val work = plan.first { it.id == TaskId("w") }
        assertEquals("Approved", work.verdict?.status?.text)
        assertEquals(2, work.verdict?.rounds)
        assertEquals("Approved by Reviewer (same model as the author).", work.statusDetail, "the reviewer line says when it shares the author's model")
        assertEquals(listOf(0, 1, 1), plan.map { it.depth })
    }

    @Test
    fun `the cost so far shows tokens, and money once a price is entered`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective(TaskStatus.RUNNING)
        val model = ModelId("mdl_grok")
        app.usage.record(
            ModelCallSample(
                at = app.clock.current, modelId = model, modelLabel = "Grok", providerId = ProviderId("prv_xai"), providerLabel = "xAI",
                locality = Locality.CLOUD, rootTaskId = root, taskId = TaskId("tsk_x"), source = UsageSource.OBJECTIVE, outcome = CallOutcome.OK,
                inputTokens = 10_000, outputTokens = 2_400, answerMs = 1_200,
            ),
        )
        val vm = screen(app)
        val tokensOnly = assertNotNull(vm.uiState.value.header?.cost)
        assertEquals("So far: 12,400 tokens · 1 model answer", tokensOnly.text)
        assertTrue(com.aicontrolcenter.app.ui.activity.TOKENS_IN_PLAIN_WORDS in tokensOnly.note)

        app.prices.set(ModelPrice(model, inputPerMTokMicros = 2_000_000, outputPerMTokMicros = 10_000_000))
        val priced = assertNotNull(vm.uiState.value.header?.cost)
        assertEquals("So far: about US$0.04 · 12,400 tokens · 1 model answer", priced.text)
        assertTrue(priced.note.startsWith(CostEstimate.DISCLAIMER))
    }

    private fun call(model: String, root: TaskId? = this.root, task: TaskId? = TaskId("tsk_x"), input: Long = 10_000, output: Long = 2_400, locality: Locality = Locality.CLOUD) =
        ModelCallSample(
            at = TestApp.START_TIME, modelId = ModelId(model), modelLabel = model, providerId = ProviderId("prv_$model"), providerLabel = model,
            locality = locality, rootTaskId = root, taskId = task, source = UsageSource.OBJECTIVE, outcome = CallOutcome.OK,
            inputTokens = input, outputTokens = output, answerMs = 1_200,
        )

    @Test
    fun `the privacy line never says nothing left the phone when a model off the phone answered (D3A-5)`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective(TaskStatus.RUNNING)
        val vm = screen(app)
        assertFalse(vm.uiState.value.egress.away)
        assertEquals("Nothing has left this phone for this objective.", egressWords(vm.uiState.value.egress))

        // Its log entries are gone (kept 30 days, newest 200 only); the usage rows still say a cloud model answered.
        app.usage.record(call("mdl_grok"))
        app.usage.record(call("mdl_grok"))
        val egress = vm.uiState.value.egress
        assertTrue(egress.away)
        assertEquals(0, egress.transfers)
        assertEquals(2, egress.offPhoneAnswers, "a model no longer registered counts as off the phone")
        assertEquals("2 model answers went to models off this phone; the details are no longer in the log.", egressWords(egress))
        assertEquals(
            "1 model call left this phone, to api.x.ai. See the Log. In all, 2 model answers came from models off this phone; the log no longer holds the rest.",
            egressWords(EgressUi(transfers = 1, destinations = listOf("api.x.ai"), offPhoneAnswers = 2)),
        )
    }

    @Test
    fun `a call refused before anything was sent is no proof that something left`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective(TaskStatus.RUNNING)
        app.usage.record(call("mdl_grok").copy(outcome = CallOutcome.BLOCKED, inputTokens = null, outputTokens = null, answerMs = null))
        assertFalse(screen(app).uiState.value.egress.away)
    }

    @Test
    fun `the cost line adds money per currency and leaves models on this phone free (D3A-8, D3A-10)`() {
        val rows = listOf(
            com.aicontrolcenter.core.usage.WorkUsage(com.aicontrolcenter.core.usage.WorkKind.OBJECTIVE, root.value, ModelId("usd"), 1, 1_000_000, 0, 0, 0),
            com.aicontrolcenter.core.usage.WorkUsage(com.aicontrolcenter.core.usage.WorkKind.OBJECTIVE, root.value, ModelId("eur"), 1, 1_000_000, 0, 0, 0),
            com.aicontrolcenter.core.usage.WorkUsage(com.aicontrolcenter.core.usage.WorkKind.OBJECTIVE, root.value, ModelId("phone"), 1, 1_000_000, 0, 0, 0),
        )
        val prices = mapOf(
            ModelId("usd") to ModelPrice(ModelId("usd"), inputPerMTokMicros = 1_000_000, outputPerMTokMicros = 0),
            ModelId("eur") to ModelPrice(ModelId("eur"), inputPerMTokMicros = 2_000_000, outputPerMTokMicros = 0, currencyLabel = "€"),
            ModelId("phone") to ModelPrice(ModelId("phone"), inputPerMTokMicros = 9_000_000, outputPerMTokMicros = 0),
        )
        val line = assertNotNull(costLineOf(rows, prices, onPhone = setOf(ModelId("phone"))))
        assertEquals("So far: about US$1.00 and €2.00 · 3,000,000 tokens · 3 model answers", line.text)
        assertEquals("So far: 1,000,000 tokens · 1 model answer · free on this phone", assertNotNull(costLineOf(rows.takeLast(1), prices, setOf(ModelId("phone")))).text)
    }

    @Test
    fun `the task screen shows its own cost so far, with what a token is (D3A-4)`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective(TaskStatus.RUNNING, steps = listOf(app.step("w", TaskStatus.RUNNING)))
        app.usage.record(call("mdl_grok", task = TaskId("w"), input = 2_400, output = 700))
        app.usage.record(call("mdl_grok", task = TaskId("other")))
        val vm = TaskDetailViewModel(
            TaskId("w"), app.graph.tasks, app.graph.messages, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry,
            app.graph.orchestrator, app.graph.activityMonitor, app.clock, ZoneOffset.UTC,
            workUsage = app.graph.usageReports.observeWorkUsage(com.aicontrolcenter.core.usage.WorkKind.TASK, "w"), prices = app.graph.modelPrices.observeAll(),
        ).also { it.uiState.activeIn(backgroundScope) }
        val tokensOnly = assertNotNull(vm.uiState.value.task?.cost)
        assertEquals("So far: 3,100 tokens · 1 model answer", tokensOnly.text, "this task's rows only")
        assertTrue(tokensOnly.note.startsWith(com.aicontrolcenter.app.ui.activity.TOKENS_IN_PLAIN_WORDS))

        app.prices.set(ModelPrice(ModelId("mdl_grok"), inputPerMTokMicros = 3_000_000, outputPerMTokMicros = 15_000_000))
        val priced = assertNotNull(vm.uiState.value.task?.cost)
        assertEquals("So far: about US$0.02 · 3,100 tokens · 1 model answer", priced.text)
        assertTrue(priced.note.startsWith(CostEstimate.DISCLAIMER))
    }

    @Test
    fun `an objective a routine started names it and says screens are not allowed`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val routine = RoutineId("rtn_morning")
        app.objective(TaskStatus.RUNNING, options = ObjectiveOptions(routineId = routine))
        val routines = listOf(
            Routine(
                id = routine, title = "Morning news", objectiveText = "Summarise the news.", schedule = RoutineSchedule.Daily(8 * 60),
                createdAt = 0, updatedAt = 0,
            ),
        )
        val header = assertNotNull(screen(app, routines = routines).uiState.value.header)
        assertEquals("Started by routine “Morning news”", header.routine)
        assertEquals(routine, header.routineId)
        assertTrue(header.screensNotAllowed)
    }

    @Test
    fun `notes go to the Director or to a task, and refusals are shown as they are`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val researcher = run {
            app.graph.start().join()
            app.agentNamed("Researcher").profile.id
        }
        app.objective(TaskStatus.RUNNING, steps = listOf(app.step("w", TaskStatus.RUNNING, assignee = researcher)))
        val vm = screen(app)
        val notes = vm.uiState.value.notes
        assertTrue(notes.canWrite)
        assertEquals(listOf(null, TaskId("w")), notes.recipients.map { it.taskId })

        vm.sendNote()
        assertEquals("Write the note first.", vm.uiState.value.notes.refusal)

        vm.setNoteRecipient(TaskId("w"))
        vm.note.setTextAndPlaceCursorAtEnd("Keep it under €300, please.")
        vm.sendNote()
        val stored = app.messages.list(root).single { it.type == MessageType.GUIDANCE }
        assertEquals("Keep it under €300, please.", stored.body)
        assertEquals(TaskId("w"), stored.taskId)
        assertEquals("", vm.note.text.toString())
        assertEquals(ObjectiveViewModel.NOTE_SENT, vm.uiState.value.message?.text)
        assertEquals(1, vm.uiState.value.notes.notesWritten)
    }

    @Test
    fun `a finished objective takes no notes`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective(TaskStatus.COMPLETED)
        assertFalse(screen(app).uiState.value.notes.canWrite)
    }

    @Test
    fun `notes switched off are refused in the app's words`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective(TaskStatus.RUNNING)
        val vm = ObjectiveViewModel(
            root, app.graph.tasks, app.graph.messages, app.graph.registryDirectory, app.graph.orchestrator, app.graph.activityLog,
            app.graph.settingsView, app.clock, ZoneOffset.UTC, ticker = flowOf(Unit), notes = OwnerConversation.Unavailable,
        ).also { it.uiState.activeIn(backgroundScope) }
        vm.note.setTextAndPlaceCursorAtEnd("Hello")
        vm.sendNote()
        assertEquals(OwnerConversation.UNAVAILABLE, vm.uiState.value.notes.refusal)
    }

    @Test
    fun `ten ticks of the clock move the elapsed time and build nothing else`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective(TaskStatus.RUNNING, steps = listOf(app.step("w", TaskStatus.RUNNING)))
        val ticks = MutableSharedFlow<Unit>(replay = 1).also { it.tryEmit(Unit) }
        app.graph.orchestrator.running.value.let { assertFalse(root in it, "not live in this test; the ticker is given straight") }
        val vm = ObjectiveViewModel(
            root, app.graph.tasks, app.graph.messages, app.graph.registryDirectory, app.graph.orchestrator, app.graph.activityLog,
            app.graph.settingsView, app.clock, ZoneOffset.UTC, ticker = ticks,
        ).also {
            it.uiState.activeIn(backgroundScope)
            it.elapsed.activeIn(backgroundScope)
        }
        val before = vm.builds.get()
        assertTrue(before > 0)
        repeat(10) { ticks.emit(Unit) }
        assertEquals(before, vm.builds.get(), "the clock never rebuilds the plan, the messages or the log")
        assertTrue(vm.elapsed.value.isNotEmpty())
    }

    @Test
    fun `the objectives list pages by fifty with Show older`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        val director = app.director().profile.id
        repeat(55) { index ->
            val id = TaskId("obj_%02d".format(index))
            app.tasks.upsert(
                TaskRecord(
                    id = id, rootId = id, parentId = null, kind = TaskKind.OBJECTIVE, title = "Objective $index", instructions = "Do $index.",
                    assignee = director, requestedBy = TaskRequester.Owner, status = TaskStatus.COMPLETED, createdAt = 1_000L + index, updatedAt = 1_000L + index,
                ),
            )
        }
        val list = ObjectivesViewModel(app.graph.tasks, app.graph.registryDirectory, app.graph.orchestrator, app.graph.notifications, app.clock, ZoneOffset.UTC, ObjectiveFilter.FINISHED)
            .also { it.uiState.activeIn(backgroundScope) }
        assertEquals(50, list.uiState.value.rows.size)
        assertTrue(list.uiState.value.hasOlder)
        assertEquals("Objective 54", list.uiState.value.rows.first().title, "newest first")
        list.showOlder()
        assertEquals(55, list.uiState.value.rows.size)
        assertFalse(list.uiState.value.hasOlder)
        assertNull(list.uiState.value.rows.firstOrNull { it.title == "Objective 99" })
    }

    /** Counts what the screens read of the task store (P1, rule 16: counts, never times). */
    private class CountingTasks(private val store: com.aicontrolcenter.core.tasks.TaskRepository) : com.aicontrolcenter.core.tasks.TaskRepository by store {
        val trees = java.util.concurrent.atomic.AtomicInteger()
        val objectiveLists = java.util.concurrent.atomic.AtomicInteger()

        override fun observeTree(rootId: TaskId) = store.observeTree(rootId).also { trees.incrementAndGet() }

        override fun observeObjectives() = store.observeObjectives().also { objectiveLists.incrementAndGet() }
    }

    @Test
    fun `the list, the objective and the task screens read summaries, never whole trees`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective(TaskStatus.RUNNING, steps = listOf(app.step("w", TaskStatus.RUNNING)))
        val counting = CountingTasks(app.graph.tasks)
        ObjectivesViewModel(counting, app.graph.registryDirectory, app.graph.orchestrator, app.graph.notifications, app.clock, ZoneOffset.UTC)
            .also { it.uiState.activeIn(backgroundScope) }
        val objective = ObjectiveViewModel(
            root, counting, app.graph.messages, app.graph.registryDirectory, app.graph.orchestrator, app.graph.activityLog,
            app.graph.settingsView, app.clock, ZoneOffset.UTC, ticker = flowOf(Unit),
        ).also { it.uiState.activeIn(backgroundScope) }
        val task = TaskDetailViewModel(
            TaskId("w"), counting, app.graph.messages, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry,
            app.graph.orchestrator, app.graph.activityMonitor, app.clock, ZoneOffset.UTC,
        ).also { it.uiState.activeIn(backgroundScope) }
        assertNotNull(objective.uiState.value.header)
        assertNotNull(task.uiState.value.task)
        // A write to another task: the screens follow it without reading any tree.
        app.tasks.update(TaskId("w")) { it.copy(statusDetail = "Reading reviews.") }
        assertEquals("Reading reviews.", objective.uiState.value.plan.single { it.id == TaskId("w") }.statusDetail)
        assertEquals(0, counting.trees.get(), "no whole tree is observed")
        assertEquals(0, counting.objectiveLists.get(), "no whole objective list is observed")
    }

    @Test
    fun `the framework's waiting words are the ones the screens recognise`() {
        assertEquals("Waiting:", WaitTexts.WAITING)
        assertEquals("XAI is not answering; trying again at 10:45.", WaitTexts.waitingFor("Waiting: xAI is not answering; trying again at 10:45."))
        assertNull(WaitTexts.waitingFor("Working on 2 task(s)."))
        assertEquals(com.aicontrolcenter.core.tasks.ReviewDecision.REVISE, verdictOf("Verdict: revise."))
        assertNull(verdictOf("Revising after the review (round 1)."))
    }
}
