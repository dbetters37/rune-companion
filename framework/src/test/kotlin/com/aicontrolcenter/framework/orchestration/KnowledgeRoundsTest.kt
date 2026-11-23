package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryChange
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.memory.FakeMemoryService
import com.aicontrolcenter.framework.orchestration.TeamFixture.Companion.MINUTE_MS
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.ScriptStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saving what an objective's team learned, once for each final result (a round): a task run
 * again saves what is new; a finish with a result already saved does nothing new; the status
 * says what is stored; and the objective's ending is announced exactly once, wherever the app
 * dies. The memory is [FakeMemoryService], which promotes as the memory store does (promoting
 * again keeps nothing twice), so promoting twice would show as duplicate copies.
 */
class KnowledgeRoundsTest {

    // The team at work: each completed task left task memory, and the app may die at any point.

    /** Each WORK task, once it completed, has left task memory: two notes worth keeping and one not, as an agent saves while working. */
    private class WorkingNotes(private val inner: TaskRepository, private val memory: FakeMemoryService) : TaskRepository by inner {
        private val noted = ConcurrentHashMap.newKeySet<TaskId>()

        private suspend fun note(tasks: List<TaskRecord>) = tasks
            .filter { it.kind == TaskKind.WORK && it.status == TaskStatus.COMPLETED && it.assignee != null && noted.add(it.id) }
            .forEach { t ->
                listOf(0.9, 0.8, 0.2).forEachIndexed { i, importance ->
                    memory.seed(MemoryScope.Task(t.id, t.assignee!!), "Note $i of ${t.title}", "Fact $i about ${t.title}", importance = importance)
                }
            }

        override suspend fun upsert(task: TaskRecord) = inner.upsert(task).also { note(listOf(task)) }

        override suspend fun upsertAll(tasks: List<TaskRecord>) = inner.upsertAll(tasks).also { note(tasks) }

        override suspend fun update(id: TaskId, transform: (TaskRecord) -> TaskRecord): TaskRecord? = inner.update(id, transform)?.also { note(listOf(it)) }
    }

    private enum class Point {
        AFTER_SAVING_MARK, AFTER_REMEMBER, AFTER_UPDATE, BEFORE_DONE, ANNOUNCING, AFTER_ANNOUNCED, FINAL_WRITE, AFTER_FINAL_WRITE, CONCLUDE_WRITE,
        AFTER_REOPEN, AFTER_RUN_AGAIN, SYNTHESIS_RUNNING,
    }

    private enum class Hang { NONE, REMEMBER, SECOND_PROMOTION }

    /** One run of the app: once it died, it stores nothing more, and what it was doing hangs until the app is cancelled. */
    private class Life(private val number: Int, private val dies: (life: Int, at: Point) -> Boolean) {
        @Volatile private var dead = false
        val promotions = AtomicInteger()

        fun check() {
            if (dead) throw CancellationException("The app died.")
        }

        suspend fun at(point: Point) {
            if (dies(number, point)) {
                dead = true
                awaitCancellation()
            }
        }
    }

    private class DyingTasks(private val inner: TaskRepository, private val life: Life) : TaskRepository by inner {
        override suspend fun upsert(task: TaskRecord) {
            life.check()
            val ending = task.kind == TaskKind.OBJECTIVE && task.status.isFinished
            if (ending) life.at(Point.FINAL_WRITE)
            inner.upsert(task)
            if (ending) life.at(Point.AFTER_FINAL_WRITE)
            if (task.kind == TaskKind.SYNTHESIS && task.status == TaskStatus.RUNNING) life.at(Point.SYNTHESIS_RUNNING)
        }

        override suspend fun upsertAll(tasks: List<TaskRecord>) {
            life.check()
            // Ending the objective early writes its final status with its stopped tasks.
            if (tasks.any { it.kind == TaskKind.OBJECTIVE && it.status.isFinished }) life.at(Point.CONCLUDE_WRITE)
            inner.upsertAll(tasks)
            if (tasks.any { it.kind == TaskKind.OBJECTIVE && it.statusDetail == "Reopened by the owner." }) life.at(Point.AFTER_REOPEN)
        }

        override suspend fun update(id: TaskId, transform: (TaskRecord) -> TaskRecord): TaskRecord? {
            life.check()
            return inner.update(id, transform)
        }
    }

    private class DyingMessages(private val inner: MessageRepository, private val life: Life) : MessageRepository by inner {
        override suspend fun append(message: AgentMessage) {
            life.check()
            val record = message.from == Participant.System && message.type == MessageType.STATUS
            if (record && message.body.startsWith(ObjectiveDriver.KNOWLEDGE_DONE)) life.at(Point.BEFORE_DONE)
            if (isEnd(message)) life.at(Point.ANNOUNCING)
            inner.append(message)
            if (record && message.body.startsWith(ObjectiveDriver.KNOWLEDGE_SAVING)) life.at(Point.AFTER_SAVING_MARK)
            if (isEnd(message)) life.at(Point.AFTER_ANNOUNCED)
            if (ObjectiveDriver.isRunAgain(message)) life.at(Point.AFTER_RUN_AGAIN)
        }
    }

    private class DyingMemory(private val inner: MemoryService, private val life: Life, private val hang: Hang) : MemoryService by inner {
        override suspend fun remember(actor: MemoryActor, draft: MemoryDraft): MemoryRecord {
            life.check()
            if (draft.kind == MemoryKind.SUMMARY && hang == Hang.REMEMBER) awaitCancellation()
            return inner.remember(actor, draft).also { if (draft.kind == MemoryKind.SUMMARY) life.at(Point.AFTER_REMEMBER) }
        }

        override suspend fun update(actor: MemoryActor, id: com.aicontrolcenter.core.MemoryId, change: MemoryChange): MemoryRecord {
            life.check()
            return inner.update(actor, id, change).also { life.at(Point.AFTER_UPDATE) }
        }

        override suspend fun promoteTaskMemory(actor: MemoryActor, taskId: TaskId, agentId: AgentId, minImportance: Double): List<MemoryRecord> {
            life.check()
            if (life.promotions.incrementAndGet() == 2 && hang == Hang.SECOND_PROMOTION) awaitCancellation()
            return inner.promoteTaskMemory(actor, taskId, agentId, minImportance)
        }
    }

    private fun TestScope.app() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    /** The app's start-up recovery, run by the app: a life that dies during it (ending an objective early, say) hangs there, not in the test. */
    private fun restart(app: CoroutineScope, orchestrator: DefaultOrchestrator) {
        app.launch { orchestrator.recoverInterrupted() }
    }

    /** The app runs for [minutes]: the wall clock and virtual time move together. */
    private fun TeamFixture.alive(minutes: Int) = repeat(minutes) {
        world.clock.advance(MINUTE_MS)
        settle(MINUTE_MS)
    }

    private fun TeamFixture.orchestrator(
        scope: CoroutineScope,
        tasks: TaskRepository = this.tasks,
        messages: MessageRepository = this.messages,
        memory: MemoryService = world.memory,
        clock: Clock = world.clock,
    ) = DefaultOrchestrator(tasks, messages, world.conversations, world.agents, world.directory, world.settings, world.runner, world.toolRegistry, world.monitor, world.activity, world.ids, clock, scope, memory)

    /**
     * An objective of two tasks that saves knowledge (unless [save] is off), its work leaving task
     * memory (unless [notes] is off). The app lives [minutes] at a time and [dies] says where it
     * dies; it is restarted (recovering, and resuming on its own) until the objective ends, then
     * once more, which must change nothing. Returns the objective and the restarts.
     */
    private suspend fun TestScope.lives(
        team: TeamFixture,
        minutes: Int = 1,
        hang: Hang = Hang.NONE,
        notes: Boolean = true,
        save: Boolean = true,
        dies: (life: Int, at: Point) -> Boolean,
    ): Pair<TaskId, Int> {
        val tasks = if (notes) WorkingNotes(team.tasks, team.world.memory) else team.tasks
        var lives = 0
        fun orchestrator(scope: CoroutineScope): DefaultOrchestrator {
            val life = Life(++lives, dies)
            return team.orchestrator(scope, DyingTasks(tasks, life), DyingMessages(team.messages, life), DyingMemory(team.world.memory, life, hang))
        }
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("a", "Find apps", listOf("research")), task("b", "Compare apps", listOf("analysis"))))) }
        var app = app()
        val objective = orchestrator(app).startObjective("Research note apps", ObjectiveOptions(saveKnowledge = save))
        team.alive(minutes)
        var restarts = 0
        while (!team.objective(objective.id).status.isFinished && restarts < 10) {
            app.cancel()
            team.world.clock.advance(30 * MINUTE_MS)
            team.settle(MINUTE_MS)
            app = app()
            restart(app, orchestrator(app))
            team.alive(minutes)
            restarts++
        }
        val ended = team.objective(objective.id)
        app.cancel()
        app = app()
        restart(app, orchestrator(app))
        team.settle(MINUTE_MS)
        assertEquals(ended, team.objective(objective.id), "a finished objective does not change when the app starts again")
        app.cancel()
        return objective.id to restarts
    }

    private suspend fun TeamFixture.summaries() = world.memory.repository.list().filter { it.kind == MemoryKind.SUMMARY }

    private suspend fun TeamFixture.summaryId() = summaries().single().id.value

    /** The Director's messages about the summary. */
    private suspend fun TeamFixture.notices(id: TaskId) = messagesOf(id, MessageType.STATUS)
        .filter { it.from is Participant.Agent && (it.body.startsWith(KnowledgeKeeper.SAVED_SUMMARY) || it.body.startsWith(KnowledgeKeeper.UPDATED_SUMMARY)) }
        .map { it.body }

    /** The "Final result ready" messages about the objective. */
    private suspend fun TeamFixture.ends(id: TaskId) = messages(id).count { isEnd(it) }

    private suspend fun TeamFixture.records(id: TaskId, opener: String) =
        messagesOf(id, MessageType.STATUS).filter { it.from == Participant.System && it.body.startsWith(opener) }.map { it.body }

    private fun TeamFixture.logged(id: TaskId, title: String) = world.log.all.count { it.title.startsWith(title) && it.rootTaskId == id }

    /** The working notes kept long-term: copies in an agent's own memory (a copy keeps its note's title). */
    private suspend fun TeamFixture.promoted() = world.memory.repository.list().filter { it.scope is MemoryScope.Agent && it.title.startsWith("Note ") }

    /** Task memory of the objective's work that is worth keeping and was not kept. */
    private suspend fun TeamFixture.unkept(id: TaskId): List<MemoryRecord> {
        val work = work(id).map { it.id }.toSet()
        return world.memory.repository.list().filter { (it.scope as? MemoryScope.Task)?.taskId in work && !it.isExpired(world.clock.current) && it.importance >= 0.6 }
    }

    private fun TeamFixture.writerFailsFirst(): () -> Unit {
        var refuse = true
        val written = AtomicInteger()
        script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("a", "Find apps", listOf("research")), task("b", "Write notes", listOf("writing"))))) }
        script.work = { s -> if (s.agent == "Writer" && refuse) ScriptStep.Fail(LlmException.InvalidRequest("refused")) else ScriptStep.Reply("Found Alpha") }
        script.synthesis = { ScriptStep.Reply("# Final v${written.incrementAndGet()}") }
        return { refuse = false }
    }

    private companion object {
        fun isEnd(message: AgentMessage) = message.taskId == message.rootId && message.type == MessageType.COMPLETION && message.body.startsWith("Final result ready")

        /** The status note on a summary [id] in the Director's own memory that holds the owner's version. */
        fun ownersVersion(id: String) = "${ObjectiveDriver.SAVED}: the summary in the Director's own memory (memory $id) holds the owner's version."

        /** The status note on knowledge saved in part: summary [id] in the Director's own memory, task memories perhaps not kept. */
        fun partly(id: String, why: String?) =
            "${ObjectiveDriver.PARTLY_SAVED}: the summary was saved to the Director's own memory (memory $id), but the team's task memories may not all have been kept${why?.let { ": $it." } ?: "."}"
    }

    // 1. A task run again saves what is new (probes R1, R9, R2).

    @Test
    fun `a task run again on a finished objective updates the summary in place, keeps the new task's memory once, and announces the new end`() = runTest {
        val team = TeamFixture(this).setUp()
        val allow = team.writerFailsFirst()
        val orchestrator = team.orchestrator(backgroundScope, tasks = WorkingNotes(team.tasks, team.world.memory))
        val objective = orchestrator.startObjective("Research note apps", ObjectiveOptions(saveKnowledge = true))
        team.settle()
        assertEquals("Completed: 1 of 2 tasks produced results.", team.objective(objective.id).statusDetail)
        val first = team.summaries().single()
        assertTrue(first.content.endsWith("# Final v1"), first.content)
        assertEquals(2, team.promoted().size)

        allow()
        orchestrator.retryTask(team.byTitle(objective.id, "Write notes").id)
        team.settle()
        val done = team.objective(objective.id)
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals("# Final v2", done.result)
        assertEquals("Completed: 2 of 2 tasks produced results.", done.statusDetail)
        // The same memory, updated in place to the new result.
        val summary = team.summaries().single()
        assertEquals(first.id, summary.id)
        assertTrue(summary.content.endsWith("# Final v2"), summary.content)
        assertEquals(2, summary.revision)
        assertEquals(
            listOf(
                "Saved a summary of this objective to its own memory (memory ${first.id.value}).",
                "Updated the summary of this objective in its own memory to final result 2 (memory ${first.id.value}).",
            ),
            team.notices(objective.id),
        )
        // Both tasks' memory kept, each note once.
        val promoted = team.promoted()
        assertEquals(4, promoted.size)
        assertEquals(4, promoted.map { it.title }.distinct().size)
        assertTrue(team.unkept(objective.id).isEmpty())
        assertEquals(
            listOf("Done saving what the team learned from final result 1.", "Done saving what the team learned from final result 2."),
            team.records(objective.id, ObjectiveDriver.KNOWLEDGE_DONE),
        )
        assertEquals(2, team.ends(objective.id), "each end announced")
        assertEquals(2, team.logged(objective.id, "Objective completed"))
    }

    /** Saving the summary takes a while; records how the summary was looked up. */
    private class SlowMemory(private val inner: MemoryService) : MemoryService by inner {
        val remembers = AtomicInteger()
        val lookups = CopyOnWriteArrayList<MemoryFilter>()

        override suspend fun remember(actor: MemoryActor, draft: MemoryDraft): MemoryRecord {
            remembers.incrementAndGet()
            delay(30_000)
            return inner.remember(actor, draft)
        }

        override fun observe(actor: MemoryActor, filter: MemoryFilter): Flow<List<MemoryRecord>> {
            if (MemoryKind.SUMMARY in filter.kinds) lookups += filter
            return inner.observe(actor, filter)
        }
    }

    @Test
    fun `a task run again while the objective is finishing has what is new saved, the summary updated rather than duplicated`() = runTest {
        val team = TeamFixture(this).setUp()
        val allow = team.writerFailsFirst()
        val memory = SlowMemory(team.world.memory)
        val orchestrator = team.orchestrator(backgroundScope, tasks = WorkingNotes(team.tasks, team.world.memory), memory = memory)
        val objective = orchestrator.startObjective("Research note apps", ObjectiveOptions(saveKnowledge = true))
        team.settle(10_000)
        assertEquals(TaskStatus.RUNNING, team.objective(objective.id).status, "the knowledge is being saved")
        allow()
        orchestrator.retryTask(team.byTitle(objective.id, "Write notes").id)
        team.settle()
        val done = team.objective(objective.id)
        assertEquals("Completed: 2 of 2 tasks produced results.", done.statusDetail)
        assertEquals("# Final v2", done.result)
        val summary = team.summaries().single()
        assertTrue(summary.content.endsWith("# Final v2"), summary.content)
        assertEquals(1, memory.remembers.get())
        assertEquals(listOf(KnowledgeKeeper.SAVED_SUMMARY, KnowledgeKeeper.UPDATED_SUMMARY), team.notices(objective.id).map { it.substringBefore(" its own") })
        assertEquals(4, team.promoted().map { it.title }.distinct().size)
        assertEquals(4, team.promoted().size, "no note kept twice")
        assertTrue(team.unkept(objective.id).isEmpty())
        assertEquals(1, team.ends(objective.id), "the first finish never ended")
        // The summary is looked up where the Director writes it: its own memory and the shared spaces (none here).
        assertTrue(memory.lookups.isNotEmpty())
        assertTrue(memory.lookups.all { it.scopes == setOf<MemoryScope>(MemoryScope.Agent(team.director.id)) }, memory.lookups.toString())
    }

    @Test
    fun `a task run again with the same final result keeps the memory of the task run again, and leaves a summary the owner edited as it is, saying so`() = runTest {
        val team = TeamFixture(this).setUp()
        val allow = team.writerFailsFirst()
        team.script.synthesis = { ScriptStep.Reply("# Final\nThe same each time.") }
        val orchestrator = team.orchestrator(backgroundScope, tasks = WorkingNotes(team.tasks, team.world.memory))
        val objective = orchestrator.startObjective("Research note apps", ObjectiveOptions(saveKnowledge = true))
        team.settle()
        val summary = team.summaries().single()
        team.world.memory.update(MemoryActor.Owner, summary.id, MemoryChange(content = "The owner's own words."))
        allow()
        orchestrator.retryTask(team.byTitle(objective.id, "Write notes").id)
        team.settle()
        assertEquals("Completed: 2 of 2 tasks produced results. ${ownersVersion(summary.id.value)}", team.objective(objective.id).statusDetail)
        assertEquals("The owner's own words.", team.summaries().single().content)
        assertEquals(1, team.notices(objective.id).size)
        assertEquals(4, team.promoted().size)
        assertTrue(team.unkept(objective.id).isEmpty())
    }

    /** Changing a stored memory never answers. */
    private class HangingUpdate(private val inner: MemoryService) : MemoryService by inner {
        override suspend fun update(actor: MemoryActor, id: com.aicontrolcenter.core.MemoryId, change: MemoryChange): MemoryRecord = awaitCancellation()
    }

    @Test
    fun `a summary a task run again could not update is said to hold an earlier result`() = runTest {
        val team = TeamFixture(this) { copy(taskTimeoutMinutes = 15) }.setUp()
        val allow = team.writerFailsFirst()
        val orchestrator = team.orchestrator(backgroundScope, tasks = WorkingNotes(team.tasks, team.world.memory), memory = HangingUpdate(team.world.memory))
        val objective = orchestrator.startObjective("Research note apps", ObjectiveOptions(saveKnowledge = true))
        team.alive(1)
        assertEquals("Completed: 1 of 2 tasks produced results.", team.objective(objective.id).statusDetail)
        allow()
        orchestrator.retryTask(team.byTitle(objective.id, "Write notes").id)
        team.alive(20)
        assertEquals(
            "Completed: 2 of 2 tasks produced results. ${ObjectiveDriver.PARTLY_SAVED}: the summary in the Director's own memory (memory ${team.summaryId()}) " +
                "was not updated to this result, and the team's task memories may not all have been kept: saving it reached its time limit.",
            team.objective(objective.id).statusDetail,
        )
        assertTrue(team.summaries().single().content.endsWith("# Final v1"))
    }

    /** Saving the summary never answers. */
    private class Hanging(private val inner: MemoryService) : MemoryService by inner {
        val calls = AtomicInteger()

        override suspend fun remember(actor: MemoryActor, draft: MemoryDraft): MemoryRecord {
            calls.incrementAndGet()
            awaitCancellation()
        }
    }

    @Test
    fun `a save that reached its time limit is said again after a task is run again, which tries again`() = runTest {
        // One attempt per result: the new result gets its own.
        val team = TeamFixture(this) { copy(taskTimeoutMinutes = 15, maxTaskAttempts = 1) }.setUp()
        val allow = team.writerFailsFirst()
        val memory = Hanging(team.world.memory)
        val orchestrator = team.orchestrator(backgroundScope, memory = memory)
        val objective = orchestrator.startObjective("Research note apps", ObjectiveOptions(saveKnowledge = true))
        team.alive(20)
        assertEquals("Completed: 1 of 2 tasks produced results. Knowledge was not saved: saving it reached its time limit.", team.objective(objective.id).statusDetail)
        allow()
        orchestrator.retryTask(team.byTitle(objective.id, "Write notes").id)
        team.alive(20)
        assertEquals("Completed: 2 of 2 tasks produced results. Knowledge was not saved: saving it reached its time limit.", team.objective(objective.id).statusDetail)
        assertEquals(2, memory.calls.get(), "the new result was tried")
        assertEquals(
            listOf(
                "Done saving what the team learned from final result 1: Knowledge was not saved: saving it reached its time limit.",
                "Done saving what the team learned from final result 2: Knowledge was not saved: saving it reached its time limit.",
            ),
            team.records(objective.id, ObjectiveDriver.KNOWLEDGE_DONE),
        )
    }

    // 2. The status matches what is stored (crash rows of probe verify4c).

    @Test
    fun `the note a save ended with is kept when the app dies before the objective's final write`() = runTest {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true, maxTaskAttempts = 2) }.setUp()
        val (id, restarts) = lives(team, minutes = 20, hang = Hang.REMEMBER) { life, at -> life == 1 && at == Point.FINAL_WRITE }
        assertEquals(1, restarts)
        assertEquals("Completed: 2 of 2 tasks produced results. Knowledge was not saved: saving it reached its time limit.", team.objective(id).statusDetail)
        assertEquals(1, team.records(id, ObjectiveDriver.KNOWLEDGE_SAVING).size, "the same result is not saved again")
        assertEquals(1, team.logged(id, "Knowledge not saved"))
        assertEquals(1, team.ends(id))
    }

    @Test
    fun `the note a save ended with is kept when the app keeps dying before the final write and the objective is ended early`() = runTest {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true, maxTaskAttempts = 10) }.setUp()
        val (id, restarts) = lives(team, minutes = 20, hang = Hang.SECOND_PROMOTION) { _, at -> at == Point.FINAL_WRITE }
        assertEquals(4, restarts)
        assertEquals(
            "Completed: 2 of 2 tasks produced results. Ended early: the objective was interrupted 4 times in a row without progress. " +
                partly(team.summaryId(), "saving it reached its time limit"),
            team.objective(id).statusDetail,
        )
        assertEquals(2, team.promoted().size, "one task's memory was kept before the save ran out of time")
        assertEquals(1, team.records(id, ObjectiveDriver.KNOWLEDGE_SAVING).size)
        assertEquals(1, team.logged(id, "Knowledge saved in part"), "logged when the save ended; ending early repeats nothing")
        assertEquals(1, team.ends(id))
    }

    @Test
    fun `a summary stored by an attempt the app stopped is named in the status when no attempt is left`() = runTest {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true, maxTaskAttempts = 1) }.setUp()
        val (id, restarts) = lives(team) { life, at -> life == 1 && at == Point.AFTER_REMEMBER }
        assertEquals(1, restarts)
        assertEquals(emptyList<String>(), team.notices(id), "the Director never said where it is")
        assertEquals(
            "Completed: 2 of 2 tasks produced results. ${partly(team.summaryId(), "saving it was interrupted 1 time(s), and no attempts are left")}",
            team.objective(id).statusDetail,
        )
        assertEquals(1, team.logged(id, "Knowledge saved in part"))
    }

    @Test
    fun `a summary stored with nothing else to keep is named in the status when the owner was never told of it`() = runTest {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true, maxTaskAttempts = 1) }.setUp()
        val (id, _) = lives(team, notes = false) { life, at -> life == 1 && at == Point.AFTER_REMEMBER }
        assertEquals(emptyList<String>(), team.notices(id))
        assertEquals(
            "Completed: 2 of 2 tasks produced results. ${ObjectiveDriver.SAVED}: the summary is in the Director's own memory (memory ${team.summaryId()}).",
            team.objective(id).statusDetail,
        )
        assertEquals(0, team.logged(id, "Knowledge saved in part") + team.logged(id, "Knowledge not saved"))
    }

    @Test
    fun `an objective ended early names the stored summary and logs what its status says`() = runTest {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true, maxTaskAttempts = 10) }.setUp()
        // The first life stores the summary and dies before saying so; every later one dies as soon as it starts saving.
        val (id, restarts) = lives(team) { life, at -> (life == 1 && at == Point.AFTER_REMEMBER) || (life >= 2 && at == Point.AFTER_SAVING_MARK) }
        assertEquals(4, restarts)
        assertEquals(
            "Completed: 2 of 2 tasks produced results. Ended early: the objective was interrupted 4 times in a row without progress. ${partly(team.summaryId(), null)}",
            team.objective(id).statusDetail,
        )
        assertEquals(1, team.logged(id, "Knowledge saved in part"))
        assertEquals(1, team.ends(id))
    }

    @Test
    fun `when every task memory was kept, the status does not say it may not have been`() = runTest {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true, maxTaskAttempts = 2) }.setUp()
        // Every life saves everything, then dies before recording that the save ended.
        val (id, restarts) = lives(team) { _, at -> at == Point.BEFORE_DONE }
        assertEquals(2, restarts)
        assertEquals(4, team.promoted().size)
        assertTrue(team.unkept(id).isEmpty())
        assertEquals("Completed: 2 of 2 tasks produced results.", team.objective(id).statusDetail)
        assertEquals(1, team.notices(id).size)
        assertEquals(0, team.logged(id, "Knowledge saved in part") + team.logged(id, "Knowledge not saved"))
    }

    // 3. The end is announced exactly once.

    @Test
    fun `the announcement of the end is not progress for the cap on restarts`() = runTest {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true, maxTaskAttempts = 10) }.setUp()
        // The first life dies as it starts saving; every later one saves, announces the end, and dies before storing it.
        val (id, restarts) = lives(team) { life, at -> (life == 1 && at == Point.AFTER_SAVING_MARK) || (life >= 2 && at == Point.FINAL_WRITE) }
        assertEquals(4, restarts, "the announcement made in the second life does not reset the count")
        assertEquals("Completed: 2 of 2 tasks produced results. Ended early: the objective was interrupted 4 times in a row without progress.", team.objective(id).statusDetail)
        assertEquals(1, team.ends(id))
    }

    @Test
    fun `the end is announced exactly once wherever around it the app dies`() {
        for (save in listOf(true, false)) for (point in listOf(Point.ANNOUNCING, Point.AFTER_ANNOUNCED, Point.FINAL_WRITE, Point.AFTER_FINAL_WRITE)) runTest {
            val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true) }.setUp()
            val (id, _) = lives(team, save = save) { life, at -> life == 1 && at == point }
            val case = "$point, saving ${if (save) "on" else "off"}"
            assertEquals("Completed: 2 of 2 tasks produced results.", team.objective(id).statusDetail, case)
            assertEquals(1, team.ends(id), case)
            // The log entry is written before the message: a death between the two repeats it rather than losing it.
            assertTrue(team.logged(id, "Objective completed") in (if (point == Point.ANNOUNCING) 1..2 else 1..1), case)
        }
    }

    @Test
    fun `a task run again is announced again when it ends, even if the app died as the objective reopened`() = runTest {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true) }.setUp()
        val allow = team.writerFailsFirst()
        var lives = 0
        fun orchestrator(scope: CoroutineScope): DefaultOrchestrator {
            val life = Life(++lives) { number, at -> number == 1 && at == Point.AFTER_REOPEN }
            return team.orchestrator(scope, DyingTasks(team.tasks, life), DyingMessages(team.messages, life), DyingMemory(team.world.memory, life, Hang.NONE))
        }
        var app = app()
        val first = orchestrator(app)
        val objective = first.startObjective("Research note apps")
        team.settle()
        assertEquals("Completed: 1 of 2 tasks produced results.", team.objective(objective.id).statusDetail)
        assertEquals(1, team.ends(objective.id))
        allow()
        app.launch { first.retryTask(team.byTitle(objective.id, "Write notes").id) }
        team.settle(MINUTE_MS)
        assertEquals(TaskStatus.RUNNING, team.objective(objective.id).status, "reopened, and then the app died")
        app.cancel()
        team.world.clock.advance(30 * MINUTE_MS)
        team.settle(MINUTE_MS)
        app = app()
        restart(app, orchestrator(app))
        team.settle()
        assertEquals("Completed: 2 of 2 tasks produced results.", team.objective(objective.id).statusDetail)
        assertEquals(2, team.ends(objective.id))
        app.cancel()
    }

    // 4. Errors and clocks never cost an ending; each run is announced and saved once; recovery finishes bookkeeping.

    /** Fails the first time it is asked to store a message [failing] matches, as a full disk or a broken database would. */
    private class FailingMessages(private val inner: MessageRepository, private val failing: (AgentMessage) -> Boolean) : MessageRepository by inner {
        @Volatile var failed = 0

        override suspend fun append(message: AgentMessage) {
            if (failed == 0 && failing(message)) {
                failed++
                throw IllegalStateException("disk I/O error")
            }
            inner.append(message)
        }
    }

    @Test
    fun `an error storing the announcement of the end is logged, and the objective still ends with its result`() = runTest {
        val team = TeamFixture(this).setUp()
        val messages = FailingMessages(team.messages) { isEnd(it) }
        val objective = team.orchestrator(backgroundScope, messages = messages).startObjective("Research note apps", ObjectiveOptions(saveKnowledge = true))
        team.settle()
        val ended = team.objective(objective.id)
        assertEquals(1, messages.failed)
        assertEquals(TaskStatus.COMPLETED, ended.status)
        assertEquals("# Final\nAll done.", ended.result)
        assertEquals("Completed: 1 of 1 tasks produced results.", ended.statusDetail)
        assertEquals(1, team.logged(objective.id, "Ending not announced"))
        assertEquals(0, team.logged(objective.id, "Objective failed"))
    }

    /** Stores the owner's request to run a task again in the millisecond of the last announcement, with an id that sorts before it. */
    private class SameMillisecond(private val inner: MessageRepository) : MessageRepository by inner {
        override suspend fun append(message: AgentMessage) {
            val last = if (ObjectiveDriver.isRunAgain(message)) inner.list(message.rootId).lastOrNull { ObjectiveDriver.isAnnouncement(it) } else null
            inner.append(if (last != null) message.copy(at = last.at, id = MessageId("msg_0")) else message)
        }
    }

    @Test
    fun `a task run again is announced when it ends, even when the request is stored in the millisecond of the last announcement`() = runTest {
        val team = TeamFixture(this).setUp()
        val allow = team.writerFailsFirst()
        val orchestrator = team.orchestrator(backgroundScope, messages = SameMillisecond(team.messages))
        val objective = orchestrator.startObjective("Research note apps")
        team.settle()
        assertEquals(1, team.ends(objective.id))
        allow()
        orchestrator.retryTask(team.byTitle(objective.id, "Write notes").id)
        team.settle()
        assertEquals("# Final v2", team.objective(objective.id).result)
        assertEquals(2, team.ends(objective.id))
        assertEquals(2, team.logged(objective.id, "Objective completed"))
    }

    @Test
    fun `a task run again after the clock was set back is announced when it ends, and the summary's update is told`() = runTest {
        val team = TeamFixture(this).setUp()
        val allow = team.writerFailsFirst()
        val orchestrator = team.orchestrator(backgroundScope)
        val objective = orchestrator.startObjective("Research note apps", ObjectiveOptions(saveKnowledge = true))
        team.settle()
        team.world.clock.current -= 60 * MINUTE_MS // the device's clock was set back an hour
        allow()
        orchestrator.retryTask(team.byTitle(objective.id, "Write notes").id)
        team.settle()
        val id = team.summaryId()
        assertEquals("# Final v2", team.objective(objective.id).result)
        assertEquals(2, team.ends(objective.id))
        assertEquals(2, team.logged(objective.id, "Objective completed"))
        // Listed by time, the update comes first now.
        assertEquals(
            listOf(
                "Saved a summary of this objective to its own memory (memory $id).",
                "Updated the summary of this objective in its own memory to final result 2 (memory $id).",
            ),
            team.notices(objective.id).sorted(),
        )
    }

    /**
     * An objective whose writer fails at first ([writerFailsFirst]) ends in the app's first life;
     * the app starts again and the owner runs the failed task again, which then works. The app
     * dies where [dies] says, and is restarted (recovering) until the objective ends, then once
     * more, which must change nothing. Its work leaves task memory in [memory].
     */
    private suspend fun TestScope.runAgainInLives(
        team: TeamFixture,
        memory: FakeMemoryService = team.world.memory,
        clock: Clock = team.world.clock,
        dies: (life: Int, at: Point) -> Boolean,
    ): TaskId {
        val allow = team.writerFailsFirst()
        val tasks = WorkingNotes(team.tasks, memory)
        var lives = 0
        fun orchestrator(scope: CoroutineScope): DefaultOrchestrator {
            val life = Life(++lives, dies)
            return team.orchestrator(scope, DyingTasks(tasks, life), DyingMessages(team.messages, life), DyingMemory(memory, life, Hang.NONE), clock)
        }
        var app = app()
        val objective = orchestrator(app).startObjective("Research note apps", ObjectiveOptions(saveKnowledge = true))
        team.alive(1)
        assertEquals("Completed: 1 of 2 tasks produced results.", team.objective(objective.id).statusDetail)
        app.cancel()
        app = app()
        val second = orchestrator(app)
        allow()
        app.launch { second.retryTask(team.byTitle(objective.id, "Write notes").id) }
        team.alive(1)
        var restarts = 0
        while (!team.objective(objective.id).status.isFinished && restarts < 10) {
            app.cancel()
            team.world.clock.advance(30 * MINUTE_MS)
            team.settle(MINUTE_MS)
            app = app()
            restart(app, orchestrator(app))
            team.alive(1)
            restarts++
        }
        val ended = team.objective(objective.id)
        app.cancel()
        app = app()
        restart(app, orchestrator(app))
        team.settle(MINUTE_MS)
        assertEquals(ended, team.objective(objective.id), "a finished objective does not change when the app starts again")
        app.cancel()
        return objective.id
    }

    @Test
    fun `a request to run a task again that is in the conversation was acted on, even if the app died right after storing it`() = runTest {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true) }.setUp()
        val id = runAgainInLives(team) { life, at -> life == 2 && at == Point.AFTER_RUN_AGAIN }
        assertEquals(TaskStatus.COMPLETED, team.byTitle(id, "Write notes").status)
        assertEquals("# Final v2", team.objective(id).result)
        assertEquals(1, team.messages(id).count { ObjectiveDriver.isRunAgain(it) })
        assertEquals(2, team.ends(id))
    }

    @Test
    fun `an objective with only bookkeeping left is finished at start-up, even when interrupted work waits for the owner`() {
        for (point in listOf(Point.AFTER_SAVING_MARK, Point.ANNOUNCING, Point.AFTER_ANNOUNCED, Point.FINAL_WRITE)) runTest {
            val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = false) }.setUp()
            val (id, restarts) = lives(team) { life, at -> life == 1 && at == point }
            val ended = team.objective(id)
            assertEquals(1, restarts, "$point")
            assertEquals(TaskStatus.COMPLETED, ended.status, "$point")
            assertEquals("# Final\nAll done.", ended.result, "$point")
            assertEquals("Completed: 2 of 2 tasks produced results.", ended.statusDetail, "$point")
            assertEquals(1, team.ends(id), "$point")
            assertEquals(1, team.logged(id, ObjectiveDriver.FINISHED_AT_START_UP), "$point")
        }
    }

    @Test
    fun `an objective the app stopped while ending it early is ended at the next start`() {
        val combos = listOf<Pair<String, (Int, Point) -> Boolean>>(
            "dying before the final write, then while ending it" to { life, at -> (life <= 4 && at == Point.FINAL_WRITE) || (life == 5 && at == Point.CONCLUDE_WRITE) },
            "dying as it announces, then after announcing while ending it" to { life, at -> (life <= 4 && at == Point.ANNOUNCING) || (life == 5 && at == Point.AFTER_ANNOUNCED) },
        )
        for (attempts in listOf(2, 10)) for ((name, dies) in combos) runTest {
            val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true, maxTaskAttempts = attempts) }.setUp()
            val (id, restarts) = lives(team, dies = dies)
            val case = "$name, $attempts attempts"
            assertEquals(5, restarts, case)
            assertEquals(
                "Completed: 2 of 2 tasks produced results. Ended early: the objective was interrupted 5 times in a row without progress.",
                team.objective(id).statusDetail, case,
            )
            assertEquals(1, team.ends(id), case)
            assertEquals(1, team.logged(id, ObjectiveDriver.FINISHED_AT_START_UP), case)
        }
    }

    /** Changing a stored memory never answers while [hold] is set. */
    private class HeldUpdate(private val inner: MemoryService) : MemoryService by inner {
        @Volatile var hold = false

        override suspend fun update(actor: MemoryActor, id: com.aicontrolcenter.core.MemoryId, change: MemoryChange): MemoryRecord =
            if (hold) awaitCancellation() else inner.update(actor, id, change)
    }

    @Test
    fun `a summary an update could not bring up to date is updated when the same result comes again`() = runTest {
        val team = TeamFixture(this) { copy(taskTimeoutMinutes = 15) }.setUp()
        val allow = team.writerFailsFirst()
        val documents = listOf("# Final v1", "# Final v2", "# Final v2")
        val written = AtomicInteger()
        team.script.synthesis = { ScriptStep.Reply(documents[minOf(written.getAndIncrement(), documents.size - 1)]) }
        val memory = HeldUpdate(team.world.memory)
        val orchestrator = team.orchestrator(backgroundScope, tasks = WorkingNotes(team.tasks, team.world.memory), memory = memory)
        val objective = orchestrator.startObjective("Research note apps", ObjectiveOptions(saveKnowledge = true))
        team.alive(1)
        // The writer fails again; the new result cannot be put into the summary.
        memory.hold = true
        orchestrator.retryTask(team.byTitle(objective.id, "Write notes").id)
        team.alive(20)
        val id = team.summaryId()
        assertEquals(
            "Completed: 1 of 2 tasks produced results. ${ObjectiveDriver.PARTLY_SAVED}: the team's task memories were kept, but the summary in the Director's own memory " +
                "(memory $id) was not updated to this result: saving it reached its time limit.",
            team.objective(objective.id).statusDetail,
        )
        memory.hold = false
        allow()
        orchestrator.retryTask(team.byTitle(objective.id, "Write notes").id)
        team.alive(20)
        assertEquals("# Final v2", team.objective(objective.id).result)
        assertEquals("Completed: 2 of 2 tasks produced results.", team.objective(objective.id).statusDetail)
        val summary = team.summaries().single()
        assertTrue(summary.content.endsWith("# Final v2"), summary.content)
        assertEquals(
            listOf(
                "Saved a summary of this objective to its own memory (memory $id).",
                "Updated the summary of this objective in its own memory to final result 3 (memory $id).",
            ),
            team.notices(objective.id),
        )
    }

    @Test
    fun `a summary the owner edited is left as it is when a task run again gives another result, and the status says it holds the owner's version`() = runTest {
        val team = TeamFixture(this).setUp()
        val allow = team.writerFailsFirst()
        val orchestrator = team.orchestrator(backgroundScope, tasks = WorkingNotes(team.tasks, team.world.memory))
        val objective = orchestrator.startObjective("Research note apps", ObjectiveOptions(saveKnowledge = true))
        team.settle()
        val summary = team.summaries().single()
        team.world.memory.update(MemoryActor.Owner, summary.id, MemoryChange(content = "The owner's own words."))
        allow()
        orchestrator.retryTask(team.byTitle(objective.id, "Write notes").id)
        team.settle()
        assertEquals("# Final v2", team.objective(objective.id).result)
        assertEquals("Completed: 2 of 2 tasks produced results. ${ownersVersion(summary.id.value)}", team.objective(objective.id).statusDetail)
        assertEquals("The owner's own words.", team.summaries().single().content)
        assertEquals(1, team.notices(objective.id).size)
        assertEquals(4, team.promoted().size)
    }

    @Test
    fun `the synthesis of a task run again has the attempts of a run, so one interruption does not cost the new result`() = runTest {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true, maxTaskAttempts = 2) }.setUp()
        val id = runAgainInLives(team) { life, at -> life == 2 && at == Point.SYNTHESIS_RUNNING }
        val done = team.objective(id)
        assertEquals("# Final v2", done.result)
        assertEquals("Completed: 2 of 2 tasks produced results.", done.statusDetail)
        assertTrue(team.summaries().single().content.endsWith("# Final v2"))
        assertEquals(4, team.promoted().size)
        assertTrue(team.unkept(id).isEmpty())
        assertEquals(2, team.ends(id))
    }

    @Test
    fun `a task run again whose final document the Director cannot write has its memory kept, and the status says the summary holds an earlier result`() = runTest {
        val team = TeamFixture(this).setUp()
        val allow = team.writerFailsFirst()
        val written = AtomicInteger()
        team.script.synthesis = { if (written.incrementAndGet() == 1) ScriptStep.Reply("# Final v1") else ScriptStep.Fail(LlmException.InvalidRequest("refused")) }
        val orchestrator = team.orchestrator(backgroundScope, tasks = WorkingNotes(team.tasks, team.world.memory))
        val objective = orchestrator.startObjective("Research note apps", ObjectiveOptions(saveKnowledge = true))
        team.settle()
        allow()
        orchestrator.retryTask(team.byTitle(objective.id, "Write notes").id)
        team.settle()
        val done = team.objective(objective.id)
        assertTrue(done.result!!.contains("The Director could not write the final document"), done.result)
        assertEquals(
            "Completed: 2 of 2 tasks produced results. ${ObjectiveDriver.PARTLY_SAVED}: the team's task memories were kept, but the summary in the Director's own memory " +
                "(memory ${team.summaryId()}) was not updated to this result: ${ObjectiveDriver.NOT_WRITTEN}.",
            done.statusDetail,
        )
        assertTrue(team.summaries().single().content.endsWith("# Final v1"))
        assertEquals(4, team.promoted().size)
        assertTrue(team.unkept(objective.id).isEmpty())
        assertEquals(1, team.logged(objective.id, "Knowledge saved in part"))
    }

    /** Times at [resolutionMs]: several writes of one step share a time. */
    private class CoarseClock(private val base: Clock, private val resolutionMs: Long) : Clock {
        override fun now(): Long = base.now() / resolutionMs * resolutionMs
    }

    @Test
    fun `the summary's update is told after the app died right after it, even when the clock is coarse`() = runTest {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true) }.setUp()
        val clock = CoarseClock(team.world.clock, 1_000)
        val memory = FakeMemoryService(team.world.agents, clock, team.world.ids)
        val id = runAgainInLives(team, memory, clock) { life, at -> life == 2 && at == Point.AFTER_UPDATE }
        val summary = memory.repository.list().single { it.kind == MemoryKind.SUMMARY }
        assertTrue(summary.content.endsWith("# Final v2"), summary.content)
        assertEquals(2, summary.revision)
        assertEquals("Completed: 2 of 2 tasks produced results.", team.objective(id).statusDetail)
        assertEquals(
            listOf(
                "Saved a summary of this objective to its own memory (memory ${summary.id.value}).",
                "Updated the summary of this objective in its own memory to final result 2 (memory ${summary.id.value}).",
            ),
            team.notices(id),
        )
    }
}
