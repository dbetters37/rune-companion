package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * D28, the gateway's side: three calls in five minutes that found a provider not answering store it as OFFLINE,
 * "Not answering since 10:42", so its agents wait and the re-checks look again; never over a rejected key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderOutageTest {
    private val ask = ChatRequest(messages = listOf(ChatMessage.user("Hello")))
    private val at1042 = ZonedDateTime.of(2026, 11, 25, 10, 42, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun TestScope.world() = MeteredWorld(this, start = at1042, retry = RetryPolicy.NONE)

    private suspend fun MeteredWorld.status(model: ModelDescriptor) = world.providers.get(model.providerId)!!

    private suspend fun TestScope.failOnce(metered: MeteredWorld, model: ModelDescriptor) {
        assertFailsWith<LlmException> { metered.gateway.chat(model.id, ask) }
        advanceTimeBy(60_000)
    }

    @Test
    fun `the third call in five minutes that finds a provider not answering marks it, since the first`() = runTest {
        val metered = world()
        val model = metered.cloud(ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Network("Could not reach it."))))

        repeat(2) { failOnce(metered, model) }
        assertEquals(ConnectionStatus.UNKNOWN, metered.status(model).status, "two failures are not an outage")
        failOnce(metered, model)

        val marked = metered.status(model)
        assertEquals(ConnectionStatus.OFFLINE, marked.status)
        assertEquals("Not answering since 10:42", marked.statusMessage)
    }

    @Test
    fun `time-outs, server errors and a busy provider count too, in the owner's zone`() = runTest {
        val metered = MeteredWorld(this, start = at1042, retry = RetryPolicy.NONE, zone = java.time.ZoneId.of("Europe/Paris"))
        val failures = listOf(LlmException.Timeout("late"), LlmException.Server("broken", 502), LlmException.Overloaded("busy"))
        val model = metered.cloud(ScriptedLlmClient { _, index -> ScriptStep.Fail(failures[index]) })

        repeat(3) { failOnce(metered, model) }

        assertEquals("Not answering since 11:42", metered.status(model).statusMessage)
    }

    @Test
    fun `failures spread over more than five minutes are not an outage`() = runTest {
        val metered = world()
        val model = metered.cloud(ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Network("gone"))))

        repeat(3) {
            assertFailsWith<LlmException> { metered.gateway.chat(model.id, ask) }
            advanceTimeBy(3 * 60_000)
        }

        assertEquals(ConnectionStatus.UNKNOWN, metered.status(model).status)
    }

    @Test
    fun `an answer ends the run, so only three failures in a row mark it`() = runTest {
        val metered = world()
        val script = listOf(false, false, true, false, false, false)
        val model = metered.cloud(
            ScriptedLlmClient { _, index -> if (script[index]) ScriptStep.Reply("fine") else ScriptStep.Fail(LlmException.Network("gone")) },
        )

        repeat(3) { runCatching { metered.gateway.chat(model.id, ask) }; advanceTimeBy(30_000) }
        assertEquals(ConnectionStatus.ONLINE, metered.status(model).status)
        repeat(2) { failOnce(metered, model) }
        assertEquals(ConnectionStatus.ONLINE, metered.status(model).status)
        failOnce(metered, model)

        assertEquals(ConnectionStatus.OFFLINE, metered.status(model).status)
        assertEquals("Not answering since 10:43", metered.status(model).statusMessage)
    }

    @Test
    fun `a rejected key stays shown, and "slow down" or a bad request is not an outage`() = runTest {
        val metered = world()
        val rejected = metered.cloud(ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Network("gone"))))
        metered.world.providers.upsert(metered.status(rejected).copy(status = ConnectionStatus.AUTH_ERROR, statusMessage = "The key was rejected."))
        val busy = metered.cloud(ScriptedLlmClient.script(ScriptStep.Fail(LlmException.RateLimited("slow down"))))
        val wrong = metered.cloud(ScriptedLlmClient.script(ScriptStep.Fail(LlmException.InvalidRequest("bad", 400))))

        repeat(3) {
            for (model in listOf(rejected, busy, wrong)) assertFailsWith<LlmException> { metered.gateway.chat(model.id, ask) }
            advanceTimeBy(30_000)
        }

        assertEquals(ConnectionStatus.AUTH_ERROR, metered.status(rejected).status)
        assertEquals("The key was rejected.", metered.status(rejected).statusMessage)
        assertEquals(ConnectionStatus.UNKNOWN, metered.status(busy).status)
        assertEquals(ConnectionStatus.UNKNOWN, metered.status(wrong).status)
    }

    @Test
    fun `a provider already marked keeps the time it stopped answering`() = runTest {
        val metered = world()
        val model = metered.cloud(ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Network("gone"))))
        repeat(3) { failOnce(metered, model) }
        repeat(3) { failOnce(metered, model) }

        assertEquals("Not answering since 10:42", metered.status(model).statusMessage)
    }
}
