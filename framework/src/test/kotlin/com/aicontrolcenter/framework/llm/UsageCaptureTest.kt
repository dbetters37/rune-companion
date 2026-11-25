package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.llm.CallContext
import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.core.usage.WorkKind
import com.aicontrolcenter.framework.StreamingClient
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WP4 item 1: every gateway call is timed on the gateway's time source and recorded as usage, with the numbers the
 * Usage tab and the daily limits read, and nothing else (rule 7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageCaptureTest {
    private val ask = ChatRequest(messages = listOf(ChatMessage.user("What is the weather like?")))
    private val task = CallContext(agentId = AgentId("agt_1"), taskId = TaskId("tsk_2"), rootTaskId = TaskId("tsk_1"))

    private fun answer(text: String, usage: TokenUsage? = TokenUsage(100, 20, 40), finish: FinishReason = FinishReason.STOP, model: String = "scripted-1") =
        ChatResponse(ChatMessage.assistant(text), finish, usage, model)

    @Test
    fun `a streamed answer records its time to the first words and to the whole answer, and its tokens for the task and the objective`() = runTest {
        val metered = MeteredWorld(this)
        val client = StreamingClient { _, _ ->
            flow {
                delay(300)
                emit(ChatChunk.Text("Sun"))
                delay(200)
                emit(ChatChunk.Text("ny"))
                delay(100)
                emit(ChatChunk.Completed(answer("Sunny")))
            }
        }
        val model = metered.cloud(client)

        val chunks = metered.gateway.chatStream(model.id, ask, task).toList()

        assertEquals("Sunny", (chunks.last() as ChatChunk.Completed).response.message.text)
        val sample = metered.today()
        assertEquals(1, sample.calls)
        assertEquals(1, sample.ok)
        assertEquals(300, sample.ttftMsSum)
        assertEquals(1, sample.ttftCount)
        assertEquals(600, sample.answerMsSum)
        assertEquals(100, sample.inputTokens)
        assertEquals(20, sample.outputTokens)
        assertEquals(40, sample.cachedInputTokens)
        assertTrue(sample.bytesSent >= "What is the weather like?".length)
        val event = metered.modelCalls().single().data!!
        assertEquals(300, event.getValue("ttftMs").jsonPrimitive.long)
        assertEquals(300, event.getValue("firstTextMs").jsonPrimitive.long)
        assertEquals(600, event.getValue("answerMs").jsonPrimitive.long)
        // One work row for the task, one for the objective, with the model's tokens.
        for ((kind, id) in listOf(WorkKind.TASK to "tsk_2", WorkKind.OBJECTIVE to "tsk_1")) {
            val row = metered.usage.workUsage(kind, id).single()
            assertEquals(model.id, row.modelId)
            assertEquals(1, row.calls)
            assertEquals(100, row.inputTokens)
            assertEquals(40, row.cachedInputTokens)
        }
        val byModel = metered.usage.byModel(UsageRange.ALL).single()
        assertEquals("Test model", byModel.modelLabel)
        assertEquals(com.aicontrolcenter.core.registry.Locality.CLOUD, byModel.locality)
        assertEquals(listOf(AgentId("agt_1")), metered.usage.byAgent(UsageRange.ALL).map { it.agentId })
        assertEquals(setOf(UsageSource.OBJECTIVE), metered.usage.hourRows.keys.map { it[3] }.toSet())
    }

    @Test
    fun `a retried call records the wait, the retry and the provider's status, and times only the attempt that answered`() = runTest {
        val metered = MeteredWorld(this)
        val client = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Server("Busy", 503)), ScriptStep.Reply("ok", TokenUsage(7, 3)))
        val model = metered.cloud(client)

        metered.gateway.chat(model.id, ask)

        assertEquals(1_000, testScheduler.currentTime)
        val totals = metered.today()
        assertEquals(1, totals.calls)
        assertEquals(1, totals.retries)
        assertEquals(0, totals.answerMsSum, "the answering attempt itself took no time")
        assertEquals(0, totals.ttftCount, "a call that is not streamed has no time to its first chunk")
        val event = metered.modelCalls().single().data!!
        assertEquals(1_000, event.getValue("retryWaitMs").jsonPrimitive.long)
        assertEquals(503, event.getValue("httpStatus").jsonPrimitive.int)
        assertEquals(2, event.getValue("attempts").jsonPrimitive.int)
    }

    @Test
    fun `each kind of failure lands in its own column`() = runTest {
        val metered = MeteredWorld(this, retry = RetryPolicy.NONE)
        val failures = mapOf(
            LlmException.Authentication("no") to ErrorClass.AUTH,
            LlmException.RateLimited("slow down") to ErrorClass.RATE_LIMITED,
            LlmException.Overloaded("busy") to ErrorClass.OVERLOADED,
            LlmException.Timeout("late") to ErrorClass.TIMEOUT,
            LlmException.Network("gone") to ErrorClass.NETWORK,
            LlmException.Server("broken", 500) to ErrorClass.SERVER,
            LlmException.InvalidRequest("bad", 400) to ErrorClass.INVALID,
            LlmException.ModelNotFound("which") to ErrorClass.INVALID,
            LlmException.ContextTooLong("long") to ErrorClass.CONTEXT,
            LlmException.MalformedResponse("odd") to ErrorClass.OTHER,
        )
        for ((failure, _) in failures) {
            val model = metered.cloud(ScriptedLlmClient.script(ScriptStep.Fail(failure)))
            assertFailsWith<LlmException> { metered.gateway.chat(model.id, ask) }
        }

        val totals = metered.today()
        assertEquals(failures.size.toLong(), totals.failed)
        assertEquals(failures.values.groupingBy { it }.eachCount().mapValues { it.value.toLong() }, totals.errors)
        assertEquals(1, totals.rateLimitedWaits, "the 429 is a wait for the caller")
    }

    @Test
    fun `a chat's calls are counted for the chat, other calls for nothing, and an answer without token counts is noted`() = runTest {
        val metered = MeteredWorld(this)
        val model = metered.cloud(ScriptedLlmClient.replies("hi"))
        metered.gateway.chat(model.id, ask, CallContext(agentId = AgentId("agt_1"), conversationId = ConversationId("cnv_1")))
        // A provider that reports no usage: the answer is counted, its tokens are not known.
        val plain = metered.onPhone(object : com.aicontrolcenter.core.llm.LlmClient {
            override suspend fun chat(request: ChatRequest): ChatResponse = answer("plain", usage = null)
        })
        metered.gateway.chat(plain.id, ask)

        assertEquals(1, metered.usage.workUsage(WorkKind.CHAT, "cnv_1").single().calls)
        assertEquals(emptyList(), metered.usage.workUsage(WorkKind.TASK, "cnv_1"))
        val sources = metered.usage.hourRows.keys.map { it[3] }.toSet()
        assertEquals(setOf(UsageSource.CHAT, UsageSource.OTHER), sources)
        assertEquals(1, metered.today().noUsage)
    }

    @Test
    fun `a call refused by policy is recorded as blocked, and a stopped stream as cancelled`() = runTest {
        val metered = MeteredWorld(this)
        val cloud = metered.cloud(ScriptedLlmClient.replies("never"))
        metered.settings { it.copy(localOnlyMode = true) }
        assertFailsWith<LlmException.BlockedByPolicy> { metered.gateway.chat(cloud.id, ask) }
        metered.settings { it.copy(localOnlyMode = false) }

        val slow = metered.onPhone(
            StreamingClient { _, _ ->
                flow {
                    emit(ChatChunk.Text("one"))
                    delay(1_000)
                    emit(ChatChunk.Text("two"))
                    emit(ChatChunk.Completed(answer("onetwo")))
                }
            },
        )
        metered.gateway.chatStream(slow.id, ask).take(1).toList()

        val totals = metered.today()
        assertEquals(2, totals.calls)
        assertEquals(1, totals.blocked)
        assertEquals(1, totals.cancelled)
        assertEquals(0, totals.ok)
        assertEquals(0, totals.inputTokens)
    }

    @Test
    fun `a provider that keeps asking to slow down is waited for as it asks, then the failure stays a rate limit`() = runTest {
        val metered = MeteredWorld(this)
        val client = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.RateLimited("Slow down", retryAfterMs = 3_000)))
        val model = metered.cloud(client)

        val failure = assertFailsWith<LlmException.RateLimited> { metered.gateway.chat(model.id, ask) }

        assertEquals(3_000, failure.retryAfterMs, "the caller (WP7) waits as long as the provider asked")
        assertEquals(3, client.requests.size, "two retries, each after the wait the provider asked for")
        assertEquals(6_000, testScheduler.currentTime)
        val totals = metered.today()
        assertEquals(1, totals.failed)
        assertEquals(mapOf(ErrorClass.RATE_LIMITED to 1L), totals.errors)
        assertEquals(3, totals.rateLimitedWaits, "two waits in the gateway and one for the caller")
        val event = metered.modelCalls().single().data!!
        assertEquals(429, event.getValue("httpStatus").jsonPrimitive.int)
        assertEquals(3_000, event.getValue("retryAfterMs").jsonPrimitive.long)
        assertEquals(6_000, event.getValue("retryWaitMs").jsonPrimitive.long)
    }

    @Test
    fun `a provider asking for a longer pause than the gateway waits fails at once, still as a rate limit`() = runTest {
        val metered = MeteredWorld(this)
        val client = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.RateLimited("Slow down", retryAfterMs = 60_000)))
        val model = metered.cloud(client)

        val failure = assertFailsWith<LlmException.RateLimited> { metered.gateway.chat(model.id, ask) }

        assertEquals(60_000, failure.retryAfterMs)
        assertEquals(1, client.requests.size)
        assertEquals(0, testScheduler.currentTime)
        assertEquals(1, metered.today().rateLimitedWaits)
    }

    @Test
    fun `an answer cut off at the output limit is counted as cut off`() = runTest {
        val metered = MeteredWorld(this)
        val model = metered.cloud(object : com.aicontrolcenter.core.llm.LlmClient {
            override suspend fun chat(request: ChatRequest): ChatResponse = answer("cut", finish = FinishReason.LENGTH)
        })

        metered.gateway.chat(model.id, ask)

        assertEquals(1, metered.today().truncated)
    }

    @Test
    fun `a dated version of the requested model is not another model, an unrelated one is`() = runTest {
        val metered = MeteredWorld(this)
        val dated = metered.cloud(object : com.aicontrolcenter.core.llm.LlmClient {
            override suspend fun chat(request: ChatRequest): ChatResponse = answer("a", model = "scripted-1-0709")
        })
        val other = metered.cloud(object : com.aicontrolcenter.core.llm.LlmClient {
            override suspend fun chat(request: ChatRequest): ChatResponse = answer("b", model = "another-model")
        })
        metered.gateway.chat(dated.id, ask)
        metered.gateway.chat(other.id, ask)

        assertEquals(1, metered.today().substituted)
        val (first, second) = metered.modelCalls()
        assertTrue("answered by" !in first.detail)
        assertTrue("answered by another-model" in second.detail)
        assertNull(metered.usage.byModel(UsageRange.ALL).firstOrNull { it.totals.substituted > 0 && it.modelId == dated.id })
        assertNotNull(metered.usage.byModel(UsageRange.ALL).firstOrNull { it.totals.substituted > 0 && it.modelId == other.id })
    }

    @Test
    fun `day and month sums follow the owner's zone, cut at whole UTC hours in a half-hour zone`() = runTest {
        // Calls recorded by the gateway at 23:00 and 23:45 on 30 November and at 00:30 on 1 December, local time.
        suspend fun sums(zone: java.time.ZoneId): List<Long> {
            val metered = MeteredWorld(this, zone = zone, start = java.time.ZonedDateTime.of(2026, 11, 30, 23, 0, 0, 0, zone).toInstant().toEpochMilli())
            val model = metered.cloud(ScriptedLlmClient.replies("ok"))
            for (minutes in listOf(0L, 45L, 90L)) {
                metered.shift = minutes * 60_000L
                metered.gateway.chat(model.id, ask)
            }
            val november = metered.start
            val december = metered.start + 90 * 60_000L
            return listOf(
                metered.usage.totals(UsageRange.day(november, zone)).calls, metered.usage.totals(UsageRange.day(december, zone)).calls,
                metered.usage.totals(UsageRange.month(november, zone)).calls, metered.usage.totals(UsageRange.month(december, zone)).calls,
            )
        }

        // Paris (a whole hour from UTC): exactly the local day and month.
        assertEquals(listOf(2L, 1L, 2L, 1L), sums(java.time.ZoneId.of("Europe/Paris")))
        // Kolkata (UTC+5:30): the day and the month start with the UTC hour that holds local midnight, 23:30 local.
        assertEquals(listOf(1L, 2L, 1L, 2L), sums(java.time.ZoneId.of("Asia/Kolkata")))
    }
}
