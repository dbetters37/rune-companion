package com.aicontrolcenter.framework.chat

import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.chat.TurnResult
import com.aicontrolcenter.core.health.TimedWaits
import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.llm.ProviderRechecks
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.framework.StreamingClient
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.tools.ClockTool
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * WP7 item 10 (R3, D28, rule 6) and item 11: a chat reply the app stopped in the middle of is said to be cut off at the
 * next start, and "Send again" answers the owner's message once more without storing it twice; an agent whose provider
 * is not answering has it checked again (through the agent-side re-checks, never the provider manager) before the turn
 * is refused; a turn stopped by today's limit says the limit's sentence; no turn starts while a backup is restored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRecoveryTest {

    /** The conversation store as a killed process leaves it: once [dead], nothing more is written. */
    private class Dying(private val inner: ConversationRepository) : ConversationRepository by inner {
        @Volatile var dead = false

        override suspend fun appendEntry(entry: ConversationEntry): ConversationEntry = if (dead) entry else inner.appendEntry(entry)

        override suspend fun upsertConversation(conversation: Conversation) {
            if (!dead) inner.upsertConversation(conversation)
        }

        override suspend fun markTurnEnded(id: ConversationId) {
            if (!dead) inner.markTurnEnded(id)
        }
    }

    private class Rechecks : ProviderRechecks {
        val calls = CopyOnWriteArrayList<Pair<ProviderId, Long>>()
        var answer: suspend (ProviderId) -> ConnectionStatus? = { null }

        override suspend fun recheckIfStale(providerId: ProviderId, olderThanMs: Long): ConnectionStatus? {
            calls += providerId to olderThanMs
            return answer(providerId)
        }

        override val timedWaits: TimedWaits get() = TimedWaits.None
    }

    private fun TestWorld.chatOn(
        scope: CoroutineScope,
        store: ConversationRepository = conversations,
        rechecks: ProviderRechecks = ProviderRechecks.None,
        restoring: MutableStateFlow<Boolean> = MutableStateFlow(false),
    ) = DefaultAgentChat(store, agents, models, providers, settings, runner, activity, ids, clock, scope, rechecks, restoring)

    /** A model whose first reply starts and never finishes; later replies are complete. */
    private fun stallingOnce(later: String) = StreamingClient { _, index ->
        flow {
            if (index == 0) {
                emit(ChatChunk.Text("Let me think"))
                awaitCancellation()
            }
            emit(ChatChunk.Text(later))
            emit(ChatChunk.Completed(ChatResponse(ChatMessage.assistant(later), FinishReason.STOP, TokenUsage(10, 5), "scripted-1")))
        }
    }

    /** Sends "Hello" in an "app" that is killed while the agent answers; returns the conversation. */
    private suspend fun TestScope.cutOff(world: TestWorld, client: StreamingClient): Conversation {
        val app = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val store = Dying(world.conversations)
        val chat = world.chatOn(app, store)
        val conversation = chat.startConversation(world.agent(world.serve(client), setOf(ClockTool.ID)).id)
        backgroundScope.async { chat.send(conversation.id, "Hello") }
        runCurrent()
        assertEquals(listOf(conversation.id), world.conversations.interruptedTurns().map { it.conversationId }, "the turn is marked while it runs")
        store.dead = true
        app.cancel() // the app is killed in the middle of the reply: nothing more is written
        runCurrent()
        return conversation
    }

    @Test
    fun `a reply the app stopped in the middle of is said to be cut off at the next start, once`() = runTest {
        val world = TestWorld(scope = this)
        val conversation = cutOff(world, stallingOnce("Hello back."))
        assertEquals(1, world.conversations.interruptedTurns().size, "the mark is still there, as after a real kill")

        val next = world.chatOn(backgroundScope)
        assertEquals(1, next.recoverInterruptedTurns())
        val last = world.conversations.getEntries(conversation.id).last()
        assertEquals("The app stopped before Scout finished answering. Send your message again to try again.", last.error)
        assertEquals(ChatRole.SYSTEM, last.message.role)
        assertTrue(world.conversations.interruptedTurns().isEmpty())
        assertEquals(0, next.recoverInterruptedTurns(), "said once")
        assertTrue(world.log.all.any { it.title == "Chat reply cut off" })
    }

    @Test
    fun `Send again answers the owner's last message without storing it a second time`() = runTest {
        val world = TestWorld(scope = this)
        val client = stallingOnce("Hello back.")
        val conversation = cutOff(world, client)
        val next = world.chatOn(backgroundScope)
        next.recoverInterruptedTurns()

        val again = assertIs<TurnResult.Completed>(next.sendAgain(conversation.id))
        assertEquals("Hello back.", again.text)
        val entries = world.conversations.getEntries(conversation.id)
        assertEquals(1, entries.count { it.message.role == ChatRole.USER && it.message.text == "Hello" }, "the owner's message is stored once")
        assertEquals("Hello back.", entries.last().message.text)
        // The model is sent the conversation without the note, ending with the owner's message.
        val sent = client.requests.last().messages.drop(1)
        assertEquals(listOf(ChatMessage.user("Hello")), sent)
        assertTrue(world.conversations.interruptedTurns().isEmpty(), "the turn's mark is gone once it ended")
    }

    @Test
    fun `Send again after a failed turn runs it again, and refuses when there is nothing of the owner's to send`() = runTest {
        val world = TestWorld(scope = this)
        val client = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.InvalidRequest("The request was rejected.", 400)), ScriptStep.Reply("Hello back."))
        val chat = world.chatOn(this)
        val conversation = chat.startConversation(world.agent(world.serve(client), setOf(ClockTool.ID)).id)
        assertEquals(TurnResult.Failed(DefaultAgentChat.NOTHING_TO_SEND_AGAIN, retryable = false), chat.sendAgain(conversation.id))

        assertIs<TurnResult.Failed>(chat.send(conversation.id, "Hello"))
        assertIs<TurnResult.Completed>(chat.sendAgain(conversation.id))
        val entries = world.conversations.getEntries(conversation.id)
        assertEquals(listOf("Hello"), entries.filter { it.message.role == ChatRole.USER }.map { it.message.text })
        assertEquals(listOf(ChatMessage.user("Hello")), client.requests[1].messages.drop(1), "the failure note is never sent")
        assertTrue(world.conversations.interruptedTurns().isEmpty())
    }

    @Test
    fun `a turn stopped by today's limit says the limit's sentence, nothing else`() = runTest {
        val world = TestWorld(scope = this)
        val sentence = "Today's limit of 200 model answers is reached. It starts again at midnight, or you can raise it in Settings › Daily limits."
        val client = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.DailyLimitReached(sentence)))
        val chat = world.chatOn(this)
        val conversation = chat.startConversation(world.agent(world.serve(client)).id)
        assertEquals(TurnResult.Failed(sentence, retryable = false), chat.send(conversation.id, "Hello"))
        assertEquals(sentence, world.conversations.getEntries(conversation.id).last().error)
        assertEquals(1, client.requests.size, "not tried again")
    }

    @Test
    fun `an agent whose provider is not answering has it checked again first, through the agent-side re-checks only`() = runTest {
        val world = TestWorld(scope = this)
        val client = ScriptedLlmClient.replies("Back again.")
        val rechecks = Rechecks()
        val chat = world.chatOn(this, rechecks = rechecks)
        val model = world.serve(client)
        val conversation = chat.startConversation(world.agent(model).id)
        val provider = world.providers.get(model.providerId)!!
        world.providers.upsert(provider.copy(status = ConnectionStatus.OFFLINE, statusMessage = "Not answering"))

        val refused = assertIs<TurnResult.Failed>(chat.send(conversation.id, "Hello"))
        assertEquals("Scout cannot answer right now. Provider Test server is offline.", refused.message)
        assertTrue(refused.retryable, "it may pass by itself")
        assertEquals(listOf(provider.id to DefaultAgentChat.RECHECK_OLDER_THAN_MS), rechecks.calls)
        assertEquals(0, client.requests.size)

        // The next re-check finds it answering: the turn goes on.
        rechecks.answer = { id -> world.providers.upsert(world.providers.get(id)!!.copy(status = ConnectionStatus.ONLINE)); ConnectionStatus.ONLINE }
        assertIs<TurnResult.Completed>(chat.send(conversation.id, "Hello?"))
        assertEquals(1, client.requests.size)

        // The chat holds no provider manager: only the re-checks.
        assertTrue(DefaultAgentChat::class.java.declaredFields.none { ProviderManager::class.java.isAssignableFrom(it.type) })
        assertTrue(DefaultAgentChat::class.java.declaredFields.any { it.type == ProviderRechecks::class.java })
    }

    @Test
    fun `no turn starts while a backup is restored, and nothing is stored`() = runTest {
        val world = TestWorld(scope = this)
        val client = ScriptedLlmClient.replies("Hello back.")
        val restoring = MutableStateFlow(true)
        val chat = world.chatOn(this, restoring = restoring)
        val conversation = chat.startConversation(world.agent(world.serve(client)).id)
        assertEquals(TurnResult.Failed(MaintenanceGate.RESTORING, retryable = true), chat.send(conversation.id, "Hello"))
        assertEquals(TurnResult.Failed(MaintenanceGate.RESTORING, retryable = true), chat.sendAgain(conversation.id))
        assertTrue(world.conversations.getEntries(conversation.id).isEmpty())
        assertEquals(0, client.requests.size)
        restoring.value = false
        assertIs<TurnResult.Completed>(chat.send(conversation.id, "Hello"))
    }

    @Test
    fun `a turn that ends, or that the owner stops, leaves no mark`() = runTest {
        val world = TestWorld(scope = this)
        val chat = world.chatOn(backgroundScope)
        val conversation = chat.startConversation(world.agent(world.serve(stallingOnce("Done."))).id)
        val turn = backgroundScope.async { chat.send(conversation.id, "Hello") }
        runCurrent()
        assertEquals(1, world.conversations.interruptedTurns().size)
        chat.cancel(conversation.id)
        assertEquals(TurnResult.Cancelled, turn.await())
        assertTrue(world.conversations.interruptedTurns().isEmpty(), "the owner's Stop is not an interruption")
        assertIs<TurnResult.Completed>(chat.send(conversation.id, "Again"))
        assertTrue(world.conversations.interruptedTurns().isEmpty())
        assertEquals(0, chat.recoverInterruptedTurns())
    }
}
