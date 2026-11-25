package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.orchestration.TeamFixture.Companion.MINUTE_MS
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP7 item 2 (R4 part 1, D28, rule 25, waiting side): a provider that does not answer, or asks the app to slow down,
 * makes the work wait and go on by itself, never fail; an agent that cannot run only because its provider is not
 * answering waits for it (after asking for the provider to be checked again), never moving the work to a model farther
 * from the phone; the wait is published ([com.aicontrolcenter.core.tasks.Orchestrator.timedWaits]) and ends early on
 * [com.aicontrolcenter.core.health.TimedWaits.wake]; the objective's own time limit bounds it.
 */
class ProviderWaitTest {

    private fun clock(at: Long) = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC))

    @Test
    fun `a provider that does not answer makes the task wait 30 seconds, then 2 minutes, and the work goes on when it answers`() = runTest {
        val team = TeamFixture(this).setUp()
        var down = true
        team.script.work = { if (down) ScriptStep.Fail(LlmException.Server("Service unavailable", 503)) else ScriptStep.Reply("Apps found") }
        val objective = team.start()
        team.settle(10_000)

        val waiting = team.work(objective.id).single()
        assertEquals(TaskStatus.QUEUED, waiting.status)
        assertEquals(0, waiting.attempt, "a wait uses no attempt")
        val first = assertNotNull(waiting.retryAt)
        assertEquals("Waiting: Test server on this phone is not answering; trying again at ${clock(first)}.", waiting.statusDetail)
        assertEquals(first, team.orchestrator.timedWaits.nextDueAt.value, "the wait is published for the background service")
        val failedAt = team.world.clock.current
        assertTrue(first - failedAt in 29_000L..30_000L, "the first wait is 30 seconds")

        // Its time comes, the provider still does not answer: the next wait is two minutes.
        team.pass(30_000)
        val again = team.work(objective.id).single()
        assertEquals(TaskStatus.QUEUED, again.status)
        assertEquals(0, again.attempt)
        val second = assertNotNull(again.retryAt)
        assertTrue(second - team.world.clock.current in 119_000L..120_000L, "the next wait is two minutes")

        down = false
        team.pass(2 * MINUTE_MS)
        team.settle()
        val done = team.work(objective.id).single()
        assertEquals(TaskStatus.COMPLETED, done.status, done.statusDetail)
        assertEquals("Apps found", done.result)
        assertNull(done.retryAt)
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        assertNull(team.orchestrator.timedWaits.nextDueAt.value, "nothing waits any more")
    }

    @Test
    fun `a slow-down answer is waited out for as long as the provider asked, and the work goes on`() = runTest {
        val team = TeamFixture(this).setUp()
        var limited = true
        team.script.work = { if (limited) ScriptStep.Fail(LlmException.RateLimited("Too many requests", retryAfterMs = 5 * MINUTE_MS)) else ScriptStep.Reply("Apps found") }
        val objective = team.start()
        team.settle(10_000)
        val waiting = team.work(objective.id).single()
        val at = assertNotNull(waiting.retryAt)
        assertEquals("Waiting: Test server on this phone asked the app to slow down; trying again at ${clock(at)}.", waiting.statusDetail)
        assertTrue(at - team.world.clock.current in 4 * MINUTE_MS..5 * MINUTE_MS, "the provider's own time is honoured")

        limited = false
        team.pass(4 * MINUTE_MS)
        assertEquals(TaskStatus.QUEUED, team.work(objective.id).single().status, "not before its time")
        team.pass(MINUTE_MS)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        assertEquals(0, team.work(objective.id).single().attempt)
    }

    @Test
    fun `a wake makes the waiting work look at the clock at once`() = runTest {
        val team = TeamFixture(this).setUp()
        var down = true
        team.script.work = { if (down) ScriptStep.Fail(LlmException.Network("Connection reset")) else ScriptStep.Reply("Apps found") }
        val objective = team.start()
        team.settle(10_000)
        assertEquals(TaskStatus.QUEUED, team.work(objective.id).single().status)
        down = false

        // The phone slept: the clock moved on, the dispatcher's delay did not run yet. A wake looks again at once.
        team.world.clock.advance(31_000)
        val before = team.script.seenBy(OrchestrationProtocol.TASK).size
        team.orchestrator.timedWaits.wake()
        runCurrent()
        assertTrue(team.script.seenBy(OrchestrationProtocol.TASK).size > before, "tried again without waiting for the delay")
        assertEquals(TaskStatus.COMPLETED, team.work(objective.id).single().status)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)

        // A wake before the time has come starts nothing.
        down = true
        val next = team.start("Compare note apps")
        team.settle(10_000)
        val tries = team.script.seenBy(OrchestrationProtocol.TASK).size
        team.orchestrator.timedWaits.wake()
        runCurrent()
        assertEquals(tries, team.script.seenBy(OrchestrationProtocol.TASK).size)
        assertEquals(TaskStatus.QUEUED, team.work(next.id).single().status)
    }

    /** An agent on a provider of its own, at [baseUrl], with [fallback] as its fallback model; the plan gives it task "b" after "a". */
    private class Scout(val profile: AgentProfile, val provider: ModelProvider, val client: ScriptedLlmClient)

    private suspend fun TeamFixture.scout(baseUrl: String, fallback: ModelDescriptor? = null): Scout {
        val client = ScriptedLlmClient { _, _ -> ScriptStep.Reply("Scout result") }
        val provider = world.provider(FakeProviderAdapter(client = client), baseUrl = baseUrl, name = "Ollama")
        val model = world.model(provider, capabilities = ModelCapabilities(toolCalling = true))
        val profile = agent("Scout", AgentKind.SPECIALIST, setOf("scouting"), model = model).let { scout ->
            scout.copy(fallbackModelIds = listOfNotNull(fallback?.id)).also { world.agents.upsert(it) }
        }
        script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("a", "Find apps", skills = listOf("research")), task("b", "Scout prices", assignee = "Scout", dependsOn = listOf("a"))))) }
        // The Scout's provider stops answering while the first task runs.
        script.work = { s ->
            if (s.agent == "Researcher") world.providers.upsert(world.providers.get(provider.id)!!.copy(status = ConnectionStatus.OFFLINE, statusMessage = "Not answering"))
            ScriptStep.Reply("Result of ${s.agent}")
        }
        return Scout(profile, provider, client)
    }

    @Test
    fun `an agent whose provider is not answering waits for it, after asking for it to be checked again, and works when it is back`() = runTest {
        val team = TeamFixture(this).setUp()
        val scout = team.scout("http://127.0.0.1:11434/v1")
        val objective = team.start("Research note apps and scout their prices")
        team.settle(10_000)

        val waiting = team.byTitle(objective.id, "Scout prices")
        assertEquals(TaskStatus.QUEUED, waiting.status, waiting.statusDetail)
        assertEquals(scout.profile.id, waiting.assignee, "the task stays with its agent")
        assertTrue(waiting.statusDetail!!.startsWith("Waiting: Ollama on this phone is not answering; trying again at"), waiting.statusDetail)
        assertTrue(team.rechecks.calls.any { it.first == scout.provider.id && it.second == ProviderWaits.STALE_MS }, "the provider was asked to be checked again first")
        assertEquals(0, scout.client.requests.size)

        // The next re-check finds it answering again.
        team.rechecks.answer = { id ->
            team.world.providers.upsert(team.world.providers.get(id)!!.copy(status = ConnectionStatus.ONLINE, statusMessage = null))
            ConnectionStatus.ONLINE
        }
        team.pass(30_000)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.byTitle(objective.id, "Scout prices").status)
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        assertEquals(1, scout.client.requests.size)
    }

    @Test
    fun `rule 25 - work on a model on the owner's network never moves to a cloud fallback while that provider is not answering`() = runTest {
        val team = TeamFixture(this).setUp()
        val cloud = ScriptedLlmClient { _, _ -> ScriptStep.Reply("Cloud result") }
        val cloudProvider = team.world.provider(FakeProviderAdapter(client = cloud), baseUrl = "https://api.example.com/v1", name = "Cloud AI")
        val cloudModel = team.world.model(cloudProvider, capabilities = ModelCapabilities(toolCalling = true))
        val scout = team.scout("http://192.168.1.20:11434/v1", fallback = cloudModel)
        val objective = team.start("Research note apps and scout their prices")
        team.settle(10_000)
        repeat(4) { team.pass(2 * MINUTE_MS) }

        val waiting = team.byTitle(objective.id, "Scout prices")
        assertEquals(TaskStatus.QUEUED, waiting.status)
        assertTrue(waiting.statusDetail!!.startsWith("Waiting: Ollama on your network is not answering"), waiting.statusDetail)
        assertEquals(0, cloud.requests.size, "no request reached the cloud")
        assertEquals(0, scout.client.requests.size)

        team.world.providers.upsert(team.world.providers.get(scout.provider.id)!!.copy(status = ConnectionStatus.ONLINE))
        team.pass(2 * MINUTE_MS)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        assertEquals(0, cloud.requests.size)
        assertEquals(1, scout.client.requests.size)
    }

    @Test
    fun `the objective's own time limit ends a wait, and the task says what it was waiting for`() = runTest {
        val team = TeamFixture(this) { copy(objectiveTimeoutMinutes = 5) }.setUp()
        team.script.work = { ScriptStep.Fail(LlmException.Server("Service unavailable", 503)) }
        val objective = team.start()
        repeat(10) { team.pass(MINUTE_MS) }
        team.settle()

        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.CANCELLED, work.status)
        assertEquals(0, work.attempt)
        assertEquals("Stopped: the objective reached its time limit of 5 minutes. It was waiting: Test server on this phone is not answering.", work.error)
        val ended = team.objective(objective.id)
        assertTrue(ended.status.isFinished, ended.statusDetail)
        assertNull(team.orchestrator.timedWaits.nextDueAt.value)
    }

    @Test
    fun `the Director waits to plan while its provider is not answering, and plans when it answers`() = runTest {
        val team = TeamFixture(this).setUp()
        var down = true
        team.script.plan = { _, _ -> if (down) ScriptStep.Fail(LlmException.Timeout("No answer")) else ScriptStep.Reply(planJson(listOf(task("t1", "Research the subject", skills = listOf("research"))))) }
        val objective = team.start()
        team.settle(10_000)
        val waiting = team.objective(objective.id)
        assertEquals(TaskStatus.RUNNING, waiting.status)
        assertNotNull(waiting.retryAt)
        assertTrue(waiting.statusDetail!!.startsWith("Waiting: Test server on this phone is not answering"), waiting.statusDetail)
        assertEquals(waiting.retryAt, team.orchestrator.timedWaits.nextDueAt.value)
        assertTrue(team.tree(objective.id).none { it.kind == TaskKind.WORK })

        down = false
        team.pass(30_000)
        team.settle()
        val done = team.objective(objective.id)
        assertEquals(TaskStatus.COMPLETED, done.status, done.statusDetail)
        assertNull(done.retryAt)
        assertEquals(0, done.attempt)
    }

    @Test
    fun `the final result waits for the Director's provider while the objective has time`() = runTest {
        val team = TeamFixture(this).setUp()
        var down = true
        team.script.synthesis = { if (down) ScriptStep.Fail(LlmException.Overloaded("Busy")) else ScriptStep.Reply("# Final\nAll done.") }
        val objective = team.start()
        team.settle(10_000)
        val synthesis = team.tree(objective.id).single { it.kind == TaskKind.SYNTHESIS }
        assertEquals(TaskStatus.QUEUED, synthesis.status)
        assertTrue(synthesis.statusDetail!!.startsWith("Waiting: Test server on this phone is busy"), synthesis.statusDetail)
        assertEquals(synthesis.retryAt, team.orchestrator.timedWaits.nextDueAt.value)

        down = false
        team.pass(30_000)
        team.settle()
        val done = team.objective(objective.id)
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals("# Final\nAll done.", done.result)
    }

    @Test
    fun `pausing ends a wait, and resuming tries again at once`() = runTest {
        val team = TeamFixture(this).setUp()
        var down = true
        team.script.work = { if (down) ScriptStep.Fail(LlmException.Server("Service unavailable", 503)) else ScriptStep.Reply("Apps found") }
        val objective = team.start()
        team.settle(10_000)
        assertNotNull(team.orchestrator.timedWaits.nextDueAt.value)
        team.orchestrator.pause(objective.id)
        assertNull(team.orchestrator.timedWaits.nextDueAt.value, "a paused objective waits for nothing")

        down = false
        team.orchestrator.resume(objective.id)
        team.settle(1_000)
        assertEquals(TaskStatus.COMPLETED, team.work(objective.id).single().status, "tried again at once")
    }

    private val wasWaiting = "Was waiting: Test server on this phone is not answering. Tries again when the objective resumes."

    @Test
    fun `while the objective is paused, waiting work no longer names a time to try again`() = runTest {
        // Review of wave 2a, INT-3.
        val team = TeamFixture(this).setUp()
        var down = true
        team.script.work = { if (down) ScriptStep.Fail(LlmException.Server("Service unavailable", 503)) else ScriptStep.Reply("Apps found") }
        val objective = team.start()
        team.settle(10_000)
        team.orchestrator.pause(objective.id)
        val paused = team.work(objective.id).single()
        assertEquals(TaskStatus.QUEUED, paused.status)
        assertEquals(wasWaiting, paused.statusDetail)
        team.pass(5 * MINUTE_MS)
        assertEquals(wasWaiting, team.work(objective.id).single().statusDetail, "nothing tried again while paused")

        down = false
        team.orchestrator.resume(objective.id)
        team.settle(1_000)
        assertEquals(TaskStatus.COMPLETED, team.work(objective.id).single().status)
    }

    @Test
    fun `an objective paused by today's limit says the same of its waiting work`() = runTest {
        val team = TeamFixture(this) { copy(maxModelCallsPerDay = 3) }.setUp()
        val provider = team.world.provider(FakeProviderAdapter(client = team.script.client), baseUrl = "https://api.example.com/v1", name = "Cloud")
        val cloud = team.world.model(provider, capabilities = ModelCapabilities(toolCalling = true, jsonOutput = true))
        for (agent in listOf(team.director, team.researcher, team.analyst, team.writer, team.reviewer)) team.world.agents.upsert(team.world.agents.get(agent.id)!!.copy(modelId = cloud.id))
        // The plan, "Find apps" and the failed call of "Compare apps" are today's three answers; "Price apps" meets the limit.
        team.script.plan = { _, _ ->
            ScriptStep.Reply(planJson(listOf(task("a", "Find apps", listOf("research")), task("b", "Compare apps", listOf("analysis")), task("c", "Price apps", listOf("research")))))
        }
        team.script.work = { s ->
            when (TeamScript.titleOf(s.brief)) {
                "Compare apps" -> ScriptStep.Fail(LlmException.Server("Service unavailable", 503))
                "Find apps" -> {
                    // Answers after "Compare apps" failed and went to wait.
                    kotlinx.coroutines.delay(5_000)
                    ScriptStep.Reply("Found")
                }
                else -> ScriptStep.Reply("Priced")
            }
        }
        val objective = team.start()
        repeat(3) { team.pass(5_000) }
        assertEquals(TaskStatus.PAUSED, team.objective(objective.id).status, team.objective(objective.id).statusDetail)
        val compare = team.byTitle(objective.id, "Compare apps")
        assertEquals(TaskStatus.QUEUED, compare.status)
        assertEquals("Was waiting: Cloud is not answering. Tries again when the objective resumes.", compare.statusDetail)
    }

    @Test
    fun `an objective whose work waited when the app stopped says so, and it does not count as interrupted while idle`() = runTest {
        val team = TeamFixture(this).setUp()
        team.script.work = { ScriptStep.Fail(LlmException.Server("Service unavailable", 503)) }
        fun appScope() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        var app = appScope()
        val objective = team.newOrchestrator(app).startObjective("Research note apps", ObjectiveOptions())
        team.settle(10_000)
        // Four restarts in a row while its work waits: none counts as "interrupted while no task was running", which
        // would end it after the third.
        repeat(4) { round ->
            assertEquals(TaskStatus.QUEUED, team.work(objective.id).single().status, "round $round")
            app.cancel() // the app is killed
            team.settle(10_000)
            app = appScope()
            val orchestrator = team.newOrchestrator(app)
            orchestrator.recoverInterrupted()
            team.settle(1_000)
            val interrupted = team.objective(objective.id)
            assertEquals(TaskStatus.INTERRUPTED, interrupted.status, "round $round")
            assertEquals("Interrupted while its work waited: Test server on this phone is not answering. Resume it to continue.", interrupted.statusDetail)
            assertEquals(wasWaiting, team.work(objective.id).single().statusDetail)
            assertEquals(0, team.work(objective.id).single().attempt, "waiting used no attempt")
            orchestrator.resume(objective.id)
            team.settle(10_000)
        }
    }
}