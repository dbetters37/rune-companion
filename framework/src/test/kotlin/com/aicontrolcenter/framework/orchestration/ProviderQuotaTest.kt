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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review of wave 2a, INT-1: a provider whose quota is used up (a free tier's daily quota answers "slow down" all day)
 * must not keep an objective sending requests until its time limit, nor use up the owner's model answers for the day.
 * Waits after failed calls are bounded, a "slow down" answer holds the provider's other work instead of each step
 * probing it, a provider that asks again after its wait is not waited for any more, and such calls are not answers.
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
}
