package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.llm.CallContext
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review of wave 2a, INT-1: a provider whose quota is used up (a free tier's daily quota answers "slow down" all day)
 * must not keep an objective sending requests until its time limit, nor use up the owner's model answers for the day.
 * Waits after failed calls are bounded, a "slow down" answer holds the provider's other work instead of each step
 * probing it, a provider that asks again after its wait is not waited for any more, and such calls are not answers.
 *
 * Wave-2a verification, NEW-2: a short per-minute limit (Groq, OpenRouter's free models at 20 requests a minute, Gemini's
 * free tier) must not be taken for a used-up quota. Without Retry-After it gets the plan's waits (R4: 30 seconds, then
 * 2 minutes) before any such verdict; a 429 that names a daily quota, or one that keeps coming after the second wait, is
 * a used-up quota; and the verdict ends as soon as a call to that provider is answered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderQuotaTest {
    /** Every agent on one cloud provider, "Gemini", answering through the team's scripted model. */
    private suspend fun TestScope.team(settings: com.aicontrolcenter.core.settings.FrameworkSettings.() -> com.aicontrolcenter.core.settings.FrameworkSettings = { this }): TeamFixture {
        val team = TeamFixture(this, settings).setUp()
        val provider = team.world.provider(FakeProviderAdapter(client = team.script.client), baseUrl = "https://api.example.com/v1", name = "Gemini")
        val cloud = team.world.model(provider, capabilities = ModelCapabilities(toolCalling = true, jsonOutput = true))
        for (agent in listOf(team.director, team.researcher, team.analyst, team.writer, team.reviewer)) {
            team.world.agents.upsert(team.world.agents.get(agent.id)!!.copy(modelId = cloud.id))
        }
        return team
    }

    /** Lets a minute pass at a time, by the clock and in virtual time, until the objective stops running; returns the minutes. */
    private suspend fun TeamFixture.runOut(objective: com.aicontrolcenter.core.TaskId, maxMinutes: Int = 120): Int {
        var minutes = 0
        while (objective(objective).status == TaskStatus.RUNNING && minutes < maxMinutes) {
            pass(60_000)
            minutes++
        }
        return minutes
    }

    @Test
    fun `five tasks on a used-up quota end within minutes, send few requests, and leave the day's answers to the other providers`() = runTest {
        // Low enough that the "slow down" calls alone would use it up if they counted.
        val team = team { copy(maxModelCallsPerDay = 20) }
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson((1..5).map { task("t$it", "Research part $it", skills = listOf("research")) })) }
        team.script.work = { ScriptStep.Fail(LlmException.RateLimited("You exceeded your current quota for today")) }
        val objective = team.start()
        val minutes = team.runOut(objective.id)

        val ended = team.objective(objective.id)
        assertTrue(ended.status != TaskStatus.RUNNING && ended.status != TaskStatus.PAUSED, "${ended.status}: ${ended.statusDetail}")
        assertTrue(minutes <= 10, "ended after $minutes minutes")
        assertTrue(team.work(objective.id).all { it.status == TaskStatus.FAILED }, team.work(objective.id).joinToString { "${it.status} ${it.statusDetail}" })
        val sent = team.script.seenBy(OrchestrationProtocol.TASK).size
        assertTrue(sent <= 40, "work requests sent: $sent")
        val calls = team.world.events(ActivityType.MODEL_CALL).size
        println("Used-up quota, five tasks: ${ended.status} after $minutes min; $sent work requests, $calls gateway calls")
        assertTrue(calls <= 15, "gateway calls: $calls")

        // The owner's other, healthy provider still answers: none of those calls was one of today's answers.
        val healthy = ScriptedLlmClient.replies("Hello!")
        val other = team.world.model(team.world.provider(FakeProviderAdapter(client = healthy), baseUrl = "https://api.other.example.com/v1", name = "xAI"))
        val answer = team.world.gateway.chat(other.id, ChatRequest(other.modelName, listOf(ChatMessage.user("Hi"))), CallContext())
        assertEquals("Hello!", answer.message.text)
    }

    @Test
    fun `a provider that asks for a pause longer than the app waits is not waited for`() = runTest {
        val team = team()
        team.script.work = { ScriptStep.Fail(LlmException.RateLimited("Quota exceeded for today", retryAfterMs = 6 * 3_600_000L)) }
        val objective = team.start()
        val minutes = team.runOut(objective.id)
        assertTrue(minutes <= 2, "ended after $minutes minutes")
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.FAILED, work.status, work.statusDetail)
        assertEquals(2, team.script.seenBy(OrchestrationProtocol.TASK).size, "one request per attempt, and no wait")
    }

    @Test
    fun `a slow-down answer holds the provider's other work, which waits with it instead of probing it, and then all goes on`() = runTest {
        val team = team { copy(maxConcurrentTasks = 1) }
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson((1..3).map { task("t$it", "Research part $it", skills = listOf("research")) })) }
        val calls = AtomicInteger()
        // The first work call is asked to wait a minute (longer than the gateway waits itself); the provider answers after that.
        team.script.work = { s ->
            if (calls.getAndIncrement() == 0) ScriptStep.Fail(LlmException.RateLimited("Slow down", retryAfterMs = 60_000))
            else ScriptStep.Reply("Result of ${s.agent}")
        }
        val objective = team.start()
        team.pass(10_000)
        // The task that was asked to slow down waits; the next one waits with it, without sending anything.
        val waiting = team.work(objective.id).filter { it.status == TaskStatus.QUEUED && it.retryAt != null }
        assertTrue(waiting.size >= 2, team.work(objective.id).joinToString { "${it.title} ${it.status} ${it.statusDetail}" })
        assertTrue(waiting.all { it.statusDetail.orEmpty().startsWith("Waiting: Gemini asked the app to slow down; trying again at") }, waiting.joinToString { it.statusDetail.orEmpty() })
        assertEquals(1, team.script.seenBy(OrchestrationProtocol.TASK).size)
        team.runOut(objective.id)
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status, team.objective(objective.id).statusDetail)
        assertEquals(4, team.script.seenBy(OrchestrationProtocol.TASK).size, "one refused call and three answers")
    }

    /** The clock times of the work requests [team]'s model received, one entry per model call (a call's retries within a second count once). */
    private fun callTimes(times: List<Long>): List<Long> = times.fold(mutableListOf()) { calls, at -> calls.apply { if (isEmpty() || at - last() > 1_000) add(at) } }

    @Test
    fun `a per-minute limit without Retry-After that lasts 90 seconds is waited out, 30 seconds and then 2 minutes, and the objective completes`() = runTest {
        val team = team()
        val start = team.world.clock.now()
        val times = CopyOnWriteArrayList<Long>()
        team.script.work = { s ->
            val now = team.world.clock.now()
            times += now
            if (now - start < 90_000) {
                ScriptStep.Fail(LlmException.RateLimited("Groq is limiting requests (HTTP 429): Rate limit reached in organization `o` on requests per minute (RPM): Limit 30, Used 30, Requested 1."))
            } else {
                ScriptStep.Reply("Result of ${s.agent}")
            }
        }
        val objective = team.start()
        var seconds = 0
        while (team.objective(objective.id).status == TaskStatus.RUNNING && seconds < 600) {
            team.pass(5_000)
            seconds += 5
        }

        val ended = team.objective(objective.id)
        assertEquals(TaskStatus.COMPLETED, ended.status, ended.statusDetail)
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.COMPLETED, work.status, work.statusDetail)
        assertEquals(0, work.attempt, "the waits used no attempt")
        val calls = callTimes(times)
        assertEquals(3, calls.size, "two refused calls and the answered one: $calls")
        val gaps = calls.zipWithNext { a, b -> b - a }
        assertTrue(gaps[0] in 30_000L..35_000L && gaps[1] in 120_000L..125_000L, "waits of 30 seconds, then 2 minutes: $gaps")
        assertTrue(seconds <= 200, "completed after $seconds seconds")
    }

    @Test
    fun `a 429 that says a daily quota is used up is not waited for, and its task fails within a minute`() = runTest {
        val team = team()
        val times = CopyOnWriteArrayList<Long>()
        team.script.work = {
            times += team.world.clock.now()
            ScriptStep.Fail(LlmException.RateLimited("OpenRouter is limiting requests (HTTP 429): Rate limit exceeded: free-models-per-day."))
        }
        val objective = team.start()
        var seconds = 0
        var waited = false
        while (team.objective(objective.id).status == TaskStatus.RUNNING && seconds < 600) {
            team.pass(5_000)
            seconds += 5
            if (team.work(objective.id).any { it.retryAt != null }) waited = true
        }

        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.FAILED, work.status, work.statusDetail)
        assertTrue(!waited && seconds <= 60, "ended after $seconds seconds; waited: $waited")
        assertEquals(1, callTimes(times).size, "no wait: both attempts at once, ${times.size} requests")
        assertEquals(2, times.size, "one request per attempt: a used-up daily quota is not tried again within the call either")
    }

    @Test
    fun `a per-minute limit that keeps coming after the second wait counts as a used-up quota, and three tasks end within minutes`() = runTest {
        val team = team()
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson((1..3).map { task("t$it", "Research part $it", skills = listOf("research")) })) }
        team.script.work = { ScriptStep.Fail(LlmException.RateLimited("Rate limit reached for requests per minute")) }
        val objective = team.start()
        val minutes = team.runOut(objective.id)

        assertTrue(team.work(objective.id).all { it.status == TaskStatus.FAILED }, team.work(objective.id).joinToString { "${it.status} ${it.statusDetail}" })
        assertTrue(minutes <= 5, "ended after $minutes minutes")
        val sent = team.script.seenBy(OrchestrationProtocol.TASK).size
        val calls = team.world.events(ActivityType.MODEL_CALL).size
        println("Per-minute limit all day, three tasks: ${team.objective(objective.id).status} after $minutes min; $sent work requests, $calls gateway calls")
        assertTrue(sent <= 36 && calls <= 14, "work requests $sent, gateway calls $calls")
    }

    @Test
    fun `the used-up verdict ends as soon as a call to that provider is answered`() = runTest {
        val team = team()
        var mode = "day"
        var minuteFrom = 0L
        team.script.work = { s ->
            when {
                mode == "day" -> ScriptStep.Fail(LlmException.RateLimited("Gemini is limiting requests (HTTP 429): Quota exceeded for requests per day."))
                mode == "minute" && team.world.clock.now() - minuteFrom < 20_000 -> ScriptStep.Fail(LlmException.RateLimited("Rate limit reached for requests per minute"))
                else -> ScriptStep.Reply("Result of ${s.agent}")
            }
        }
        // A daily quota used up: the provider's work is not waited for.
        val first = team.start("First objective")
        team.runOut(first.id)
        assertEquals(TaskStatus.FAILED, team.work(first.id).single().status)

        // The provider answers again (a new day, a paid plan): its calls are answered, which ends the verdict...
        mode = "answers"
        val second = team.start("Second objective")
        team.runOut(second.id)
        assertEquals(TaskStatus.COMPLETED, team.objective(second.id).status, team.objective(second.id).statusDetail)

        // ...so a short per-minute limit right after is waited out again instead of failing the task.
        mode = "minute"
        minuteFrom = team.world.clock.now()
        val third = team.start("Third objective")
        team.runOut(third.id)
        assertEquals(TaskStatus.COMPLETED, team.objective(third.id).status, team.work(third.id).joinToString { "${it.status} ${it.statusDetail} ${it.error}" })
    }

    @Test
    fun `a Director whose provider's daily quota is used up fails planning at once, saying so`() = runTest {
        val team = team()
        team.script.plan = { _, _ -> ScriptStep.Fail(LlmException.RateLimited("Gemini is limiting requests (HTTP 429): Quota exceeded for requests per day.")) }
        val objective = team.start()
        val minutes = team.runOut(objective.id)

        val ended = team.objective(objective.id)
        assertEquals(TaskStatus.FAILED, ended.status)
        assertTrue(minutes <= 1, "ended after $minutes minutes")
        assertEquals(1, team.script.seenBy(OrchestrationProtocol.PLAN).size, "one request, no wait and no repeat")
        assertEquals(
            "The Director could not plan: Gemini has used up its quota for today. Try again tomorrow, or give the Director a model of another provider.",
            ended.error,
        )
    }
}
