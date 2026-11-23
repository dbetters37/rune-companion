package com.aicontrolcenter.framework.chat

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.LiveTurn
import com.aicontrolcenter.core.chat.TurnPhase
import com.aicontrolcenter.core.chat.TurnResult
import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.LambdaTool
import com.aicontrolcenter.framework.StreamingClient
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.framework.tools.BuiltInTools
import com.aicontrolcenter.framework.tools.ClockTool
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Most tests hand the test's own scope to the framework, so turns run in virtual time and can
 * be paused mid-way; the simulator test uses the default world, whose turns run on a real
 * dispatcher as in the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentChatTest {
    private fun call(id: String, function: String = "clock_now") = ScriptedLlmClient.call(id, function)

    /** A new conversation with a new agent whose model is served by [client]. */
    private suspend fun TestWorld.conversation(
        client: LlmClient,
        tools: Set<ToolId> = setOf(ClockTool.ID),
        capabilities: ModelCapabilities = ModelCapabilities(toolCalling = true),
        agentName: String = "Scout",
    ): Conversation = chat.startConversation(agent(serve(client, capabilities), tools, name = agentName).id)

    private suspend fun TestWorld.entries(conversation: Conversation): List<ConversationEntry> = conversations.getEntries(conversation.id)

    private suspend fun TestWorld.texts(conversation: Conversation): List<String> = entries(conversation).map { it.message.text }

    /** A model that answers only when [gate] opens. */
    private fun gated(gate: CompletableDeferred<Unit>, reply: String = "Done.") = ScriptedLlmClient { _, _ ->
        gate.await()
        ScriptStep.Reply(reply)
    }

    /** A model whose first reply starts and never finishes; later replies are complete. */
    private fun stallingOnce(partial: String, later: String) = StreamingClient { _, index ->
        flow {
            if (index == 0) {
                emit(ChatChunk.Text(partial))
                awaitCancellation()
            }
            emit(ChatChunk.Text(later))
            emit(ChatChunk.Completed(ChatResponse(ChatMessage.assistant(later), FinishReason.STOP, TokenUsage(10, 5), "scripted-1")))
        }
    }

    // ---- persistence ----

    @Test
    fun `a turn stores the owner's message and everything the agent produced, in order`() = runTest {
        val world = TestWorld(scope = this)
        val client = ScriptedLlmClient.script(
            ScriptStep.Calls(listOf(call("call_1")), text = "Let me check.", usage = TokenUsage(100, 20)),
            ScriptStep.Reply("It is late.", TokenUsage(150, 30)),
        )
        val conversation = world.conversation(client)

        val result = world.chat.send(conversation.id, "  What time is it?  ")

        assertEquals(TurnResult.Completed("It is late.", TokenUsage(250, 50), modelCalls = 2, toolCalls = 1), result)
        val entries = world.entries(conversation)
        assertEquals(listOf(0, 1, 2, 3), entries.map { it.seq })
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT), entries.map { it.message.role })
        assertEquals(ChatMessage.user("What time is it?"), entries[0].message)
        assertEquals(ChatMessage.assistant("Let me check.", listOf(call("call_1"))), entries[1].message)
        assertEquals("scripted-1", entries[1].modelName)
        assertEquals(TokenUsage(100, 20), entries[1].usage)
        val toolResult = assertNotNull(entries[2].message.toolResult)
        assertEquals("call_1", toolResult.callId)
        assertTrue(toolResult.content.startsWith("It is "))
        assertNull(entries[2].modelName)
        assertEquals(ChatMessage.assistant("It is late."), entries[3].message)
        assertEquals(TokenUsage(150, 30), entries[3].usage)
        assertTrue(entries.all { it.error == null && it.conversationId == conversation.id })
        assertEquals(entries, world.chat.observeEntries(conversation.id).first())
        assertTrue(world.chat.liveTurns.value.isEmpty())
    }

    @Test
    fun `each turn sends the model the stored conversation, whole and unchanged`() = runTest {
        val world = TestWorld(scope = this)
        val client = ScriptedLlmClient.script(ScriptStep.Calls(listOf(call("call_1"))), ScriptStep.Reply("It is late."), ScriptStep.Reply("You are welcome."))
        val conversation = world.conversation(client)

        world.chat.send(conversation.id, "What time is it?")
        world.chat.send(conversation.id, "Thanks!")

        val (_, endOfFirstTurn, secondTurn) = client.requests
        val stored = world.entries(conversation).map { it.message }
        assertEquals(6, stored.size)
        assertEquals(ChatRole.SYSTEM, secondTurn.messages.first().role)
        assertEquals(stored.dropLast(1), secondTurn.messages.drop(1), "everything stored before the reply, exactly as stored")
        assertEquals(endOfFirstTurn.messages, secondTurn.messages.take(endOfFirstTurn.messages.size), "what a provider already saw never changes")
    }

    @Test
    fun `a stored tool call without a result is answered when the history is rebuilt, and failure notes are left out`() {
        fun entry(seq: Int, message: ChatMessage, error: String? = null) =
            ConversationEntry(ConversationId("cnv_1"), seq, message, at = seq.toLong(), error = error)
        val entries = listOf(
            entry(0, ChatMessage.user("Go")),
            entry(1, ChatMessage.assistant("", listOf(call("a"), call("b")))),
            entry(2, ChatMessage.toolResult("a", "clock_now", "noon")),
            entry(3, ChatMessage.system("The app was interrupted."), error = "The app was interrupted."),
            entry(4, ChatMessage.user("Again")),
        )

        val messages = ConversationHistory.toMessages(entries)

        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.TOOL, ChatRole.USER), messages.map { it.role })
        assertEquals(listOf("a", "b"), messages.mapNotNull { it.toolResult?.callId })
        assertEquals(listOf(false, true), messages.mapNotNull { it.toolResult?.isError })
        assertEquals(messages, ConversationHistory.toMessages(entries), "the repair is the same every time, so the history stays stable")
    }

    // ---- conversations ----

    @Test
    fun `the first message names the conversation, unless the owner already did`() = runTest {
        val world = TestWorld(scope = this)
        val conversation = world.conversation(ScriptedLlmClient.replies("ok"))
        assertEquals(DefaultAgentChat.DEFAULT_TITLE, conversation.title)

        world.chat.send(conversation.id, "\n  Plan a three-day trip to Lisbon in May, with museums, food and one day at the beach\nand keep it cheap")
        val titled = world.conversations.getConversation(conversation.id)!!
        assertTrue(titled.title.startsWith("Plan a three-day trip to Lisbon"), titled.title)
        assertTrue(titled.title.endsWith("…") && titled.title.length <= 48 && '\n' !in titled.title, titled.title)
        assertTrue(titled.updatedAt > conversation.updatedAt)

        world.chat.send(conversation.id, "Something else entirely")
        assertEquals(titled.title, world.conversations.getConversation(conversation.id)!!.title, "only the first message names it")

        val named = world.chat.startConversation(conversation.agentId, "  Holiday \n plans ")
        assertEquals("Holiday plans", named.title)
        world.chat.send(named.id, "Where should we go?")
        assertEquals("Holiday plans", world.conversations.getConversation(named.id)!!.title)

        world.chat.rename(named.id, " Summer ")
        assertEquals("Summer", world.conversations.getConversation(named.id)!!.title)
        assertFailsWith<IllegalArgumentException> { world.chat.rename(named.id, "   ") }
        assertFailsWith<IllegalArgumentException> { world.chat.rename(ConversationId("cnv_missing"), "Title") }
    }

    @Test
    fun `conversations are listed per agent, the most recently used first`() = runTest {
        val world = TestWorld(scope = this)
        val older = world.conversation(ScriptedLlmClient.replies("ok"))
        val newer = world.chat.startConversation(older.agentId)
        val elsewhere = world.conversation(ScriptedLlmClient.replies("ok"), agentName = "Other")

        assertEquals(listOf(newer.id, older.id), world.chat.observeConversations(older.agentId).first().map { it.id })
        world.chat.send(older.id, "Hello")
        assertEquals(listOf(older.id, newer.id), world.chat.observeConversations(older.agentId).first().map { it.id })
        assertEquals(setOf(older.id, newer.id, elsewhere.id), world.chat.observeConversations().first().map { it.id }.toSet())

        assertFailsWith<IllegalArgumentException> { world.chat.startConversation(AgentId("agt_missing")) }
        assertFailsWith<IllegalArgumentException> { world.chat.send(older.id, "   ") }
        assertFailsWith<IllegalArgumentException> { world.chat.send(ConversationId("cnv_missing"), "Hello") }
    }

    // ---- turns in progress ----

    @Test
    fun `a second turn in the same conversation is refused while one is running`() = runTest {
        val world = TestWorld(scope = this)
        val gate = CompletableDeferred<Unit>()
        val conversation = world.conversation(gated(gate))
        val sibling = world.chat.startConversation(conversation.agentId)

        val first = async { world.chat.send(conversation.id, "One") }
        runCurrent()
        assertEquals(TurnPhase.WAITING_FOR_MODEL, world.chat.liveTurns.value.getValue(conversation.id).phase)

        val refused = assertFailsWith<IllegalStateException> { world.chat.send(conversation.id, "Two") }
        assertTrue("still answering" in refused.message.orEmpty())

        val elsewhere = async { world.chat.send(sibling.id, "Elsewhere") }
        runCurrent()
        assertEquals(setOf(conversation.id, sibling.id), world.chat.liveTurns.value.keys, "another conversation of the same agent is free")

        gate.complete(Unit)
        assertIs<TurnResult.Completed>(first.await())
        assertIs<TurnResult.Completed>(elsewhere.await())
        assertEquals(listOf("One", "Done."), world.texts(conversation), "the refused message was not stored")
        assertTrue(world.chat.liveTurns.value.isEmpty())
        assertIs<TurnResult.Completed>(world.chat.send(conversation.id, "Two"))
    }

    @Test
    fun `a turn in progress shows its phase, model, destination and the tool being run`() = runTest {
        val world = TestWorld(scope = this)
        val seen = ArrayList<LiveTurn>()
        lateinit var conversation: Conversation
        world.register(
            LambdaTool("peek") {
                seen += world.chat.liveTurns.value.getValue(conversation.id)
                ToolOutcome.success("peeked")
            },
        )
        val client = ScriptedLlmClient.script(ScriptStep.Calls(listOf(call("call_1", "test_peek")), text = "Looking."), ScriptStep.Reply("Seen."))
        conversation = world.conversation(client, tools = setOf(ToolId("test.peek")))

        world.chat.send(conversation.id, "Look")

        val live = seen.single()
        assertEquals(conversation.id, live.conversationId)
        assertEquals(TurnPhase.RUNNING_TOOL, live.phase)
        assertEquals(call("call_1", "test_peek"), live.activeTool)
        assertEquals("Test model", live.modelName)
        assertEquals(Locality.ON_DEVICE, live.locality)
        assertEquals(1, live.modelCalls)
        assertEquals(1, live.toolCalls)
        assertEquals("", live.partialText, "a reply that has been stored is no longer partial")
    }

    @Test
    fun `cancel stops the turn, keeps what was produced, and the conversation goes on from there`() = runTest {
        val world = TestWorld(scope = this)
        val client = stallingOnce(partial = "So far ", later = "Short version.")
        val conversation = world.conversation(client)

        val turn = async { world.chat.send(conversation.id, "Tell me a long story") }
        runCurrent()
        val live = world.chat.liveTurns.value.getValue(conversation.id)
        assertEquals(TurnPhase.RECEIVING, live.phase)
        assertEquals("So far ", live.partialText)

        world.chat.cancel(conversation.id)

        assertEquals(TurnResult.Cancelled, turn.await())
        val kept = world.entries(conversation)
        assertEquals(listOf(ChatMessage.user("Tell me a long story"), ChatMessage.assistant("So far ")), kept.map { it.message })
        assertTrue(kept.none { it.error != null }, "stopping a turn is not a failure")
        assertTrue(world.chat.liveTurns.value.isEmpty())
        world.chat.cancel(conversation.id)

        assertIs<TurnResult.Completed>(world.chat.send(conversation.id, "Shorter, please"))
        assertEquals(kept.map { it.message } + ChatMessage.user("Shorter, please"), client.requests[1].messages.drop(1))
    }

    @Test
    fun `a turn outlives the caller that started it`() = runTest {
        val world = TestWorld(scope = this)
        val gate = CompletableDeferred<Unit>()
        val conversation = world.conversation(gated(gate))

        val caller = launch { world.chat.send(conversation.id, "One") }
        runCurrent()
        caller.cancelAndJoin()
        assertTrue(conversation.id in world.chat.liveTurns.value, "leaving the screen does not stop the agent")

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("One", "Done."), world.texts(conversation))
        assertTrue(world.chat.liveTurns.value.isEmpty())
    }

    // ---- failures ----

    @Test
    fun `a very long reply streams in time proportional to its length, and its complete text is published`() = runTest {
        val world = TestWorld(scope = this)
        val full = buildString { repeat(200_000) { append('a' + it % 26) } }
        val client = StreamingClient { _, _ ->
            flow {
                // One character at a time: a reasoning model's long output, at its most demanding.
                for (c in full) emit(ChatChunk.Text(c.toString()))
                emit(ChatChunk.Completed(ChatResponse(ChatMessage.assistant(full), FinishReason.STOP, TokenUsage(10, 5), "scripted-1")))
            }
        }
        val conversation = world.conversation(client)
        // An eager collector sees every value liveTurns takes, not only the latest.
        var snapshots = 0
        var lastText = ""
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            world.chat.liveTurns.collect { turns ->
                turns[conversation.id]?.partialText?.takeIf { it.isNotEmpty() }?.let {
                    snapshots++
                    lastText = it
                }
            }
        }

        val started = System.nanoTime()
        val result = world.chat.send(conversation.id, "Think hard.")
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(full, assertIs<TurnResult.Completed>(result).text)
        assertTrue(snapshots < 1_000, "snapshots are published at a bounded rate, not once per delta: $snapshots in $tookMs ms")
        assertEquals(full, lastText, "the last text published is the whole reply")
        assertTrue(tookMs < 5_000, "200,000 one-character deltas took $tookMs ms")
        assertTrue(world.chat.liveTurns.value.isEmpty())
    }

    @Test
    fun `an agent switched off during its turn is stopped, and the conversation says so`() = runTest {
        val world = TestWorld(scope = this)
        var agentId = AgentId("")
        val switchOff = world.register(LambdaTool("switch_off") {
            world.agentManager.setEnabled(agentId, false)
            ToolOutcome.success("done")
        })
        val client = ScriptedLlmClient.script(ScriptStep.Calls(listOf(call("call_1", "test_switch_off"))), ScriptStep.Reply("never asked"))
        val conversation = world.conversation(client, tools = setOf(switchOff.spec.id))
        agentId = conversation.agentId

        val result = world.chat.send(conversation.id, "Go.")

        assertEquals(TurnResult.Cancelled, result)
        assertEquals(1, client.requests.size)
        val note = world.entries(conversation).last()
        assertEquals("Stopped: Scout was switched off while it was working.", note.error)
        val event = world.events(ActivityType.TASK).single { it.title == "Agent turn stopped by the owner" }
        assertEquals(agentId, event.agentId)
        assertTrue(world.events(ActivityType.ERROR).none { it.agentId == agentId }, "the owner's decision is not logged as an error")
    }

    @Test
    fun `a failed turn leaves a visible note that is never sent to a model`() = runTest {
        val world = TestWorld(scope = this)
        val client = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.InvalidRequest("The request was rejected.", 400)), ScriptStep.Reply("Hello again."))
        val conversation = world.conversation(client)

        val failed = world.chat.send(conversation.id, "Hello")

        assertEquals(TurnResult.Failed("Scout could not answer. The request was rejected.", retryable = false), failed)
        val (question, note) = world.entries(conversation)
        assertEquals(ChatMessage.user("Hello"), question.message)
        assertEquals("Scout could not answer. The request was rejected.", note.error)
        assertEquals(ChatRole.SYSTEM, note.message.role)
        assertTrue(world.events(ActivityType.ERROR).isEmpty(), "the gateway already logged the failed call")
        assertEquals("Model call failed", world.events(ActivityType.MODEL_CALL).single().title)

        assertIs<TurnResult.Completed>(world.chat.send(conversation.id, "Are you there?"))
        assertEquals(listOf(ChatMessage.user("Hello"), ChatMessage.user("Are you there?")), client.requests[1].messages.drop(1))
        assertEquals(4, world.entries(conversation).size, "the note stays in the conversation for the owner")
    }

    @Test
    fun `after a rejected key the agent is unavailable until its provider has been checked again`() = runTest {
        val world = TestWorld(scope = this)
        val client = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Authentication("The API key was rejected.")), ScriptStep.Reply("Back."))
        val conversation = world.conversation(client)

        val rejected = assertIs<TurnResult.Failed>(world.chat.send(conversation.id, "Hello"))
        val blocked = assertIs<TurnResult.Failed>(world.chat.send(conversation.id, "Hello?"))

        assertEquals("Scout could not answer. The API key was rejected.", rejected.message)
        assertTrue("rejected its credentials" in blocked.message, blocked.message)
        assertEquals(1, client.requests.size, "the key is not sent again while it is known to be wrong")

        world.providerManager.checkProvider(world.providers.getAll().single().id)
        assertIs<TurnResult.Completed>(world.chat.send(conversation.id, "And now?"))
    }

    @Test
    fun `a failure that may pass is reported as retryable`() = runTest {
        val world = TestWorld(scope = this)
        val client = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Network("Connection reset.")))
        val conversation = world.conversation(client)

        val failed = assertIs<TurnResult.Failed>(world.chat.send(conversation.id, "Hello"))

        assertTrue(failed.retryable)
        assertTrue("Connection reset." in failed.message)
        assertEquals(3, client.requests.size, "the gateway had already retried")
    }

    @Test
    fun `an agent that cannot run says why, and nothing is sent anywhere`() = runTest {
        val world = TestWorld(scope = this)
        val noModel = world.agent(model = null)
        val first = world.chat.startConversation(noModel.id)

        val result = world.chat.send(first.id, "Hello")

        assertEquals(TurnResult.Failed("Scout cannot answer right now. No model is assigned to this agent.", retryable = false), result)
        assertEquals(listOf("Hello", "Scout cannot answer right now. No model is assigned to this agent."), world.texts(first))
        val logged = world.events(ActivityType.ERROR).single()
        assertEquals("Agent turn stopped", logged.title)
        assertEquals(noModel.id, logged.agentId)

        world.agents.upsert(noModel.copy(enabled = false))
        assertTrue("switched off" in assertIs<TurnResult.Failed>(world.chat.send(first.id, "Hello?")).message)

        val cloudClient = ScriptedLlmClient.replies("never")
        val cloudModel = world.model(world.provider(FakeProviderAdapter(client = cloudClient), baseUrl = "https://api.example.com/v1"))
        val second = world.chat.startConversation(world.agent(cloudModel, name = "Cloudy").id)
        world.settings.update { it.copy(localOnlyMode = true) }
        assertTrue("Local-only mode" in assertIs<TurnResult.Failed>(world.chat.send(second.id, "Hello")).message)
        assertTrue(cloudClient.requests.isEmpty())
        assertTrue(world.events(ActivityType.MODEL_CALL).isEmpty())
    }

    @Test
    fun `a turn stopped by a limit fails visibly and leaves a history the next turn can use`() = runTest {
        val world = TestWorld(scope = this)
        world.settings.update { it.copy(maxModelCallsPerTask = 2) }
        val client = ScriptedLlmClient { _, index -> if (index < 2) ScriptStep.Calls(listOf(call("call_$index"))) else ScriptStep.Reply("Now I answer.") }
        val conversation = world.conversation(client)

        val stopped = assertIs<TurnResult.Failed>(world.chat.send(conversation.id, "Keep checking the time"))

        assertEquals(TurnResult.Failed("Stopped after 2 model calls, the limit for one task.", retryable = false), stopped)
        val entries = world.entries(conversation)
        assertEquals(
            listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.SYSTEM),
            entries.map { it.message.role },
        )
        assertEquals(stopped.message, entries.last().error)

        assertEquals(TurnResult.Completed("Now I answer.", TokenUsage(10, 5), modelCalls = 1, toolCalls = 0), world.chat.send(conversation.id, "And?"))
        val resumed = client.requests[2].messages.drop(1)
        assertEquals(entries.dropLast(1).map { it.message } + ChatMessage.user("And?"), resumed)
    }

    @Test
    fun `a reply cut off at the output limit is kept and flagged`() = runTest {
        val world = TestWorld(scope = this)
        val client = StreamingClient { _, _ ->
            flow {
                emit(ChatChunk.Text("Half an ans"))
                emit(ChatChunk.Completed(ChatResponse(ChatMessage.assistant("Half an ans"), FinishReason.LENGTH, TokenUsage(10, 5), "scripted-1")))
            }
        }
        val conversation = world.conversation(client)

        val result = world.chat.send(conversation.id, "Explain everything")

        assertEquals("Half an ans", assertIs<TurnResult.Completed>(result).text)
        val (_, reply, flag) = world.entries(conversation)
        assertEquals(ChatMessage.assistant("Half an ans"), reply.message)
        assertNull(reply.error)
        assertTrue("cut off" in flag.error.orEmpty())
    }

    @Test
    fun `a conversation too long for the model's context window fails with an explanation instead of losing messages`() = runTest {
        val world = TestWorld(scope = this)
        val long = "word ".repeat(700)
        val small = ScriptedLlmClient.replies("ok")
        val conversation = world.conversation(small, tools = emptySet(), capabilities = ModelCapabilities(toolCalling = true, contextWindow = 1_000))
        assertIs<TurnResult.Completed>(world.chat.send(conversation.id, "Hello"))

        val failed = assertIs<TurnResult.Failed>(world.chat.send(conversation.id, long))

        assertTrue("too long for Test model" in failed.message && "1000" in failed.message && "Nothing was sent" in failed.message, failed.message)
        assertFalse(failed.retryable)
        assertEquals(1, small.requests.size)
        val entries = world.entries(conversation)
        assertEquals(listOf("Hello", "ok", long.trim()), entries.take(3).map { it.message.text }, "every message is still there")
        assertEquals(failed.message, entries[3].error)

        val unknownWindow = ScriptedLlmClient.replies("ok")
        val other = world.conversation(unknownWindow, agentName = "Roomy")
        assertIs<TurnResult.Completed>(world.chat.send(other.id, long))
        assertEquals(long.trim(), unknownWindow.requests.single().messages.last().text, "with no known window the request is sent as it is")
    }

    // ---- deletion ----

    @Test
    fun `deleting a conversation stops its turn and removes everything stored for it`() = runTest {
        val world = TestWorld(scope = this)
        val conversation = world.conversation(stallingOnce(partial = "So far ", later = "unused"))
        val turn = async { world.chat.send(conversation.id, "Go") }
        runCurrent()

        world.chat.delete(conversation.id)

        assertEquals(TurnResult.Cancelled, turn.await())
        assertNull(world.conversations.getConversation(conversation.id))
        assertTrue(world.entries(conversation).isEmpty())
        assertTrue(world.chat.liveTurns.value.isEmpty())
        assertFailsWith<IllegalArgumentException> { world.chat.send(conversation.id, "Anyone?") }
    }

    @Test
    fun `deleting an agent stops its turns and deletes its conversations, and nobody else's`() = runTest {
        val world = TestWorld(scope = this)
        val busy = world.conversation(stallingOnce(partial = "Still ", later = "unused"))
        val idle = world.chat.startConversation(busy.agentId, "Idle")
        val bystander = world.conversation(ScriptedLlmClient.replies("ok"), agentName = "Bystander")
        world.chat.send(bystander.id, "Hello")
        val turn = async { world.chat.send(busy.id, "Go") }
        runCurrent()

        world.agentManager.delete(busy.agentId)

        assertEquals(TurnResult.Cancelled, turn.await())
        assertNull(world.conversations.getConversation(busy.id))
        assertNull(world.conversations.getConversation(idle.id))
        assertTrue(world.entries(busy).isEmpty())
        assertTrue(world.chat.liveTurns.value.isEmpty())
        assertNotNull(world.conversations.getConversation(bystander.id))
        assertEquals(listOf("Hello", "ok"), world.texts(bystander))
    }

    // ---- the simulator, end to end ----

    @Test
    fun `the simulator runs the whole pipeline offline and never passes as a real model`() = runTest {
        val world = TestWorld()
        world.providerManager.ensureBuiltIns()
        world.settings.update { it.copy(localOnlyMode = true) }
        val agent = world.agent(world.models.get(Simulator.MODEL_ID), tools = BuiltInTools.defaultToolIds)
        val conversation = world.chat.startConversation(agent.id)

        val answered = assertIs<TurnResult.Completed>(world.chat.send(conversation.id, "What time is it, and who is on the team?"))

        assertEquals(2, answered.modelCalls)
        assertEquals(2, answered.toolCalls)
        assertTrue(answered.text.startsWith(Simulator.DISCLOSURE))
        assertTrue("The tool \"clock_now\" returned:" in answered.text && "The tool \"team_list_agents\" returned:" in answered.text, answered.text)
        assertTrue("- Scout (Specialist, this is you)" in answered.text, "the answer is built from what the tools returned")
        val entries = world.entries(conversation)
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.TOOL, ChatRole.ASSISTANT), entries.map { it.message.role })
        val replies = entries.filter { it.message.role == ChatRole.ASSISTANT }
        assertTrue(replies.all { it.message.text.startsWith(Simulator.DISCLOSURE) && it.modelName == Simulator.MODEL_NAME })
        assertEquals(2, world.events(ActivityType.MODEL_CALL).size)
        assertEquals(2, world.events(ActivityType.TOOL_CALL).size)
        assertTrue(world.log.all.none { it.egress != null }, "nothing left the device")

        val plain = assertIs<TurnResult.Completed>(world.chat.send(conversation.id, "Thanks, that is all."))
        assertEquals(1, plain.modelCalls)
        assertEquals(0, plain.toolCalls)
        assertTrue(plain.text.startsWith(Simulator.DISCLOSURE) && "Your request, restated: \"Thanks, that is all.\"" in plain.text, plain.text)
    }
}
