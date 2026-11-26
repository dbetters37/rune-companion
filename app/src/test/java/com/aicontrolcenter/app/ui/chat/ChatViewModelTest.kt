package com.aicontrolcenter.app.ui.chat

import com.aicontrolcenter.app.testing.GatedLlmClient
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.serveModel
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.usage.CostEstimate
import com.aicontrolcenter.core.usage.DailyLimitKind
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.WorkKind
import com.aicontrolcenter.framework.chat.DefaultAgentChat
import com.aicontrolcenter.core.agents.AgentDraft
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.framework.tools.ClockTool
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Counts how many entries the chat reads, to show that a long chat is read in parts (P5). */
private class CountingPages(private val pages: ConversationPages) : ConversationPages by pages {
    var entriesRead = 0

    override suspend fun entriesAfter(id: ConversationId, seq: Int) = pages.entriesAfter(id, seq).also { entriesRead += it.size }

    override suspend fun entriesBefore(id: ConversationId, seq: Int, limit: Int) = pages.entriesBefore(id, seq, limit).also { entriesRead += it.size }
}

class ChatViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private suspend fun TestApp.agentOn(model: ModelDescriptor?, name: String = "Scout"): AgentId =
        graph.agentManager.create(
            AgentDraft(name = name, kind = AgentKind.SPECIALIST, modelId = model?.id, toolIds = setOf(ClockTool.ID), systemInstructions = "Answer briefly."),
        ).id

    private suspend fun TestScope.chatWith(app: TestApp, agent: AgentId): ChatViewModel {
        val conversation = app.graph.agentChat.startConversation(agent)
        return ChatViewModel(conversation.id, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry, app.graph.providerManager)
            .also { it.uiState.activeIn(backgroundScope) }
    }

    /**
     * Lets the gateway's retry pauses pass. They run in the app scope, which is the test's
     * background scope, and `advanceUntilIdle` leaves background work alone.
     */
    private fun TestScope.letRetriesPass() {
        advanceTimeBy(RETRY_WINDOW_MS)
        runCurrent()
    }

    private fun ChatViewModel.say(text: String) {
        setDraft(text)
        send()
    }

    @Test
    fun `a turn streams its reply, shows each tool call with its outcome, and ends with model and usage`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val client = ScriptedLlmClient.script(
            ScriptStep.Calls(listOf(ScriptedLlmClient.call("c1", "clock_now", buildJsonObject { put("timezone", JsonPrimitive("Europe/Paris")) }))),
            ScriptStep.Reply("It is noon in Paris.", TokenUsage(100, 20)),
        )
        val model = app.serveModel(client)
        val chat = chatWith(app, app.agentOn(model))

        val before = chat.uiState.value
        assertEquals(DestinationUi("Scripted model", "Test server", Locality.ON_DEVICE, isSimulator = false, usingFallback = false), before.destination)
        assertNull(before.blockedReason)
        assertFalse(before.canSend, "nothing to send yet")

        chat.say("What time is it in Paris?")
        val state = chat.uiState.value
        assertEquals("", state.draft, "the input is cleared as the message goes")
        assertNull(state.live, "the scripted turn has finished")
        assertEquals("What time is it in Paris?", state.title, "the first message names the conversation")

        val items = state.items
        assertEquals(TranscriptItem.Owner(items[0].key, "What time is it in Paris?"), items[0])
        val lookup = assertIs<TranscriptItem.Agent>(items[1])
        val call = lookup.toolCalls.single()
        assertEquals("Current time", call.title)
        assertEquals("timezone: Europe/Paris", call.argumentsSummary)
        val outcome = assertIs<ToolOutcomeUi.Succeeded>(call.outcome)
        assertTrue("Europe/Paris" in outcome.content)
        assertNull(lookup.footer, "only the turn's final reply carries the footer")

        val reply = assertIs<TranscriptItem.Agent>(items[2])
        assertEquals("It is noon in Paris.", reply.text)
        val footer = assertNotNull(reply.footer)
        assertEquals("scripted-1", footer.answeredBy)
        assertFalse(footer.differs)
        assertEquals("110 tokens in · 25 out", footer.usage, "both model calls of the turn are counted")
        assertEquals(3, items.size)
    }

    @Test
    fun `partial text and the current phase are shown while the reply streams, and a turn outlives the screen`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val client = GatedLlmClient(listOf("Hel", "lo there."), reasoning = "The owner greets me.")
        val model = app.serveModel(client)
        val agent = app.agentOn(model)
        val chat = chatWith(app, agent)

        chat.say("Hello")
        runCurrent()
        val live = assertNotNull(chat.uiState.value.live)
        assertEquals("Writing the reply", live.phase)
        assertEquals("Hel", live.partialText)
        assertTrue(chat.uiState.value.isRunning)
        assertFalse(chat.uiState.value.canSend, "one turn at a time")
        assertEquals("Writing a reply", app.graph.activityMonitor.states.value[agent]?.detail, "the monitor sees the agent at work")

        // A second screen for the same conversation (the owner left and came back) sees the same turn.
        val returned = ChatViewModel(
            app.graph.agentChat.observeConversations(agent).first().single().id,
            app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry, app.graph.providerManager,
        ).also { it.uiState.activeIn(backgroundScope) }
        assertEquals("Hel", returned.uiState.value.live?.partialText)

        client.release()
        assertNull(chat.uiState.value.live)
        val reply = assertIs<TranscriptItem.Agent>(chat.uiState.value.items.last())
        assertEquals("Hello there.", reply.text)
        assertEquals("The owner greets me.", reply.reasoning)
        assertEquals("42 tokens in · 7 out", reply.footer?.usage)
        assertTrue(app.graph.activityMonitor.states.value.isEmpty(), "the agent is idle again")
    }

    @Test
    fun `stop ends the turn and keeps what was written so far`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val model = app.serveModel(GatedLlmClient(listOf("A long ans", "wer that never comes.")))
        val chat = chatWith(app, app.agentOn(model))

        chat.say("Tell me everything")
        assertNotNull(chat.uiState.value.live)
        chat.stop()
        advanceUntilIdle()

        val state = chat.uiState.value
        assertNull(state.live)
        val partial = assertIs<TranscriptItem.Agent>(state.items.last())
        assertEquals("A long ans", partial.text, "the reply is kept as far as it got")
        assertNull(state.blockedReason, "the agent can be asked again")
    }

    @Test
    fun `a failed turn is shown as such and offers a provider check when the key was rejected`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val model = app.serveModel(ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Authentication("The server rejected the key."))), apiKey = "sk-test-0123456789abcdef")
        val chat = chatWith(app, app.agentOn(model))

        chat.say("Hello")
        advanceUntilIdle()
        val notice = assertIs<TranscriptItem.Notice>(chat.uiState.value.items.last())
        assertEquals(NoticeKind.FAILURE, notice.kind)
        assertTrue("rejected the key" in notice.text, notice.text)

        // The gateway stored the rejection, so the agent now says why it cannot answer.
        val state = chat.uiState.value
        assertTrue(assertNotNull(state.blockedReason).contains("rejected its credentials"))
        assertEquals("Test server", state.checkProviderName)

        // The owner fixes the key outside; checking again unblocks the agent.
        chat.checkProvider()
        advanceUntilIdle()
        assertNull(chat.uiState.value.blockedReason)
        assertTrue(assertNotNull(chat.uiState.value.message).text.startsWith("Test server is online"))
        assertNull(chat.uiState.value.checkProviderId, "a working provider needs no check")
    }

    @Test
    fun `a connection failure offers a provider check without blocking the next message`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val model = app.serveModel(ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Network("Could not reach Test server."))))
        val chat = chatWith(app, app.agentOn(model))

        chat.say("Hello")
        letRetriesPass()
        val state = chat.uiState.value
        assertEquals(NoticeKind.FAILURE, assertIs<TranscriptItem.Notice>(state.items.last()).kind)
        assertNull(state.blockedReason, "a network failure does not mark the provider offline")
        assertNotNull(state.checkProviderId, "but it is worth checking")
    }

    @Test
    fun `a reply from another model than the assigned one is flagged`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val model = app.serveModel(ScriptedLlmClient.script(ScriptStep.Reply("Hi."), modelName = "router/other-model"))
        val chat = chatWith(app, app.agentOn(model))

        chat.say("Hello")
        val footer = assertNotNull(assertIs<TranscriptItem.Agent>(chat.uiState.value.items.last()).footer)
        assertEquals("router/other-model", footer.answeredBy)
        assertEquals("scripted-1", footer.assignedModel)
    }

    @Test
    fun `an alias answered by its dated version is not flagged, another model is (D29)`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val model = app.serveModel(ScriptedLlmClient.script(ScriptStep.Reply("Hi."), modelName = "grok-4-0709"), modelName = "grok-4-latest")
        val chat = chatWith(app, app.agentOn(model))
        chat.say("Hello")
        val footer = assertNotNull(assertIs<TranscriptItem.Agent>(chat.uiState.value.items.last()).footer)
        assertEquals("grok-4-0709", footer.answeredBy)
        assertFalse(footer.differs, "grok-4-latest resolved to grok-4-0709 is the same model")

        val other = app.serveModel(ScriptedLlmClient.script(ScriptStep.Reply("Hi."), modelName = "gpt-4o-mini"), name = "Other", baseUrl = "http://127.0.0.1:9101/v1", modelName = "gpt-4o")
        val second = chatWith(app, app.agentOn(other, name = "Second"))
        second.say("Hello")
        val flagged = assertNotNull(assertIs<TranscriptItem.Agent>(second.uiState.value.items.last()).footer)
        assertEquals("gpt-4o", flagged.assignedModel, "gpt-4o answered as gpt-4o-mini is another model")
    }

    @Test
    fun `local-only mode keeps a cloud model from being sent anything and says so`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val client = ScriptedLlmClient.replies("never")
        val model = app.serveModel(client, name = "Hosted", baseUrl = "https://api.example.com/v1", apiKey = "sk-test-0123456789abcdef")
        val chat = chatWith(app, app.agentOn(model))
        assertEquals(Locality.CLOUD, chat.uiState.value.destination?.locality)

        app.settings.update { it.copy(localOnlyMode = true) }
        chat.setDraft("Hello")
        val state = chat.uiState.value
        assertTrue(assertNotNull(state.blockedReason).contains("Local-only mode is on"), state.blockedReason)
        assertFalse(state.canSend)
        assertNull(state.checkProviderId, "checking would contact the provider, which the mode forbids")

        chat.send()
        assertEquals(0, client.requests.size, "nothing was sent")
        assertEquals(emptyList(), chat.uiState.value.items)
    }

    @Test
    fun `an agent without a model cannot be sent anything, and deleting the conversation leaves the screen`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val chat = chatWith(app, app.agentOn(null))
        val state = chat.uiState.value
        assertNull(state.destination)
        assertEquals("Scout cannot answer right now. No model is assigned to this agent.", state.blockedReason)

        chat.rename("Planning")
        assertEquals("Planning", chat.uiState.value.title)
        chat.delete()
        assertTrue(chat.uiState.value.deleted)
        assertTrue(chat.uiState.value.notFound)
    }

    @Test
    fun `the provider status alone can offer a check after a failure seen in an earlier session`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val model = app.serveModel(ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Server("Internal error", 500))))
        val agent = app.agentOn(model)
        val chat = chatWith(app, agent)
        chat.say("Hello")
        letRetriesPass()
        val conversation = app.graph.agentChat.observeConversations(agent).first().single().id
        app.providers.upsert(app.providers.get(model.providerId)!!.copy(status = ConnectionStatus.ERROR))

        val later = ChatViewModel(conversation, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry, app.graph.providerManager)
            .also { it.uiState.activeIn(backgroundScope) }
        assertEquals(model.providerId, later.uiState.value.checkProviderId)
    }

    // --- Phase 6: cut-off replies (R3), long chats (P5), the cost line (goal 1) and today's limit (rule 6) ---

    @Test
    fun `a reply the app stopped in the middle of says so and Send again runs the last message once more`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val client = ScriptedLlmClient.replies("Here is the whole answer.")
        val model = app.serveModel(client)
        val agent = app.agentOn(model)
        val conversation = app.graph.agentChat.startConversation(agent)
        // The app stopped during a turn: the owner's message is stored, the turn's mark is left.
        app.conversations.appendEntry(ConversationEntry(conversation.id, -1, ChatMessage.user("Tell me about tides"), app.clock.now()))
        app.conversations.markTurnStarted(conversation.id, app.clock.now())
        assertEquals(1, app.graph.agentChat.recoverInterruptedTurns())

        val chat = ChatViewModel(conversation.id, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry, app.graph.providerManager)
            .also { it.uiState.activeIn(backgroundScope) }
        val notice = assertIs<TranscriptItem.Notice>(chat.uiState.value.items.last())
        assertEquals(NoticeKind.APP_STOPPED, notice.kind)
        assertEquals(DefaultAgentChat.cutOff("Scout"), notice.text)
        assertTrue(chat.uiState.value.canSendAgain)

        chat.sendAgain()
        advanceUntilIdle()
        val items = chat.uiState.value.items
        assertEquals("Here is the whole answer.", assertIs<TranscriptItem.Agent>(items.last()).text)
        assertEquals(1, items.count { it is TranscriptItem.Owner }, "the message is not stored a second time")
        assertEquals("Tell me about tides", client.requests.single().messages.last { it.role == ChatRole.USER }.text)
        assertFalse(chat.uiState.value.canSendAgain, "a finished reply needs no second try")
    }

    @Test
    fun `Send again with nothing to send says so once and stores nothing`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val model = app.serveModel(ScriptedLlmClient.replies("never"))
        val conversation = app.graph.agentChat.startConversation(app.agentOn(model))
        app.conversations.appendEntry(ConversationEntry(conversation.id, -1, ChatMessage.system("Gone"), app.clock.now(), error = "Something failed."))
        val chat = ChatViewModel(conversation.id, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry, app.graph.providerManager)
            .also { it.uiState.activeIn(backgroundScope) }
        assertTrue(chat.uiState.value.canSendAgain)
        chat.sendAgain()
        advanceUntilIdle()
        assertEquals(DefaultAgentChat.NOTHING_TO_SEND_AGAIN, chat.uiState.value.message?.text)
        assertEquals(1, app.conversations.getEntries(conversation.id).size)
    }

    @Test
    fun `a long chat reads only its newest messages and Show earlier reads the part before them`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val model = app.serveModel(ScriptedLlmClient.replies("never"))
        val conversation = app.graph.agentChat.startConversation(app.agentOn(model))
        repeat(60) { turn ->
            app.conversations.appendEntry(ConversationEntry(conversation.id, -1, ChatMessage.user("Question $turn"), app.clock.now()))
            app.conversations.appendEntry(ConversationEntry(conversation.id, -1, ChatMessage.assistant("Answer $turn"), app.clock.now(), modelName = "scripted-1"))
        }
        val reads = CountingPages(ConversationPages.of(app.conversations))
        val chat = ChatViewModel(
            conversation.id, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry, app.graph.providerManager, pages = reads,
        ).also { it.uiState.activeIn(backgroundScope) }

        val first = chat.uiState.value
        assertEquals(50, first.items.size, "the newest 50 entries")
        assertEquals("Question 35", assertIs<TranscriptItem.Owner>(first.items.first()).text, "starting with the owner's message of the first turn shown")
        assertEquals("Answer 59", assertIs<TranscriptItem.Agent>(first.items.last()).text)
        assertTrue(first.hasEarlier)
        assertTrue(reads.entriesRead <= 51, "at most a page and the look-back of one entry were read, not all 120 (read ${reads.entriesRead})")

        chat.showEarlier()
        advanceUntilIdle()
        assertEquals(100, chat.uiState.value.items.size)
        assertEquals("Question 10", assertIs<TranscriptItem.Owner>(chat.uiState.value.items.first()).text)
        chat.showEarlier()
        advanceUntilIdle()
        val all = chat.uiState.value
        assertEquals(120, all.items.size)
        assertFalse(all.hasEarlier, "everything is shown")
        assertEquals("Question 0", assertIs<TranscriptItem.Owner>(all.items.first()).text)
    }

    @Test
    fun `the cost line shows the chat's tokens, and money once the model has a price`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        // In the cloud: a model on this phone is free whatever its price (D3A-10).
        val model = app.serveModel(
            ScriptedLlmClient.script(ScriptStep.Reply("Hi.", TokenUsage(100_000, 20_000))),
            baseUrl = com.aicontrolcenter.app.testing.CLOUD_MODEL_URL, apiKey = "test-key-not-real",
        )
        val agent = app.agentOn(model)
        val conversation = app.graph.agentChat.startConversation(agent)
        val chat = ChatViewModel(
            conversation.id, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry, app.graph.providerManager,
            usage = app.graph.usageReports.observeWorkUsage(WorkKind.CHAT, conversation.id.value),
            prices = app.graph.modelPrices.observeAll(),
        ).also { it.uiState.activeIn(backgroundScope) }
        assertNull(chat.uiState.value.cost, "nothing used yet")

        chat.say("Hello")
        advanceUntilIdle()
        val unpriced = assertNotNull(chat.uiState.value.cost)
        assertEquals("This chat so far: 120,000 tokens.", unpriced.line)
        assertFalse(unpriced.priced)
        assertTrue("Enter the model's prices in Models" in unpriced.explanation)
        assertTrue(TOKENS_EXPLAINED in unpriced.explanation, "tokens are explained where they are counted")

        // US$3 per million in, US$15 per million out: 0.30 + 0.30.
        app.graph.ownerModelPrices.set(ModelPrice(model.id, 3_000_000, 15_000_000))
        advanceUntilIdle()
        val priced = assertNotNull(chat.uiState.value.cost)
        assertEquals("This chat so far: 120,000 tokens, about US$0.60.", priced.line)
        assertTrue(CostEstimate.DISCLAIMER in priced.explanation, "rule 29: the cost is an estimate from the owner's prices")
    }

    @Test
    fun `today's limit is said in its own sentence, with the way to Daily limits`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope, FrameworkSettings(maxModelCallsPerDay = 1))
        val client = ScriptedLlmClient.replies("First answer.", "Never sent.")
        val model = app.serveModel(client, name = "Hosted", baseUrl = "https://api.example.com/v1", apiKey = "sk-test-0123456789abcdef")
        val agent = app.agentOn(model)
        val conversation = app.graph.agentChat.startConversation(agent)
        val chat = ChatViewModel(
            conversation.id, app.graph.registryDirectory, app.graph.agentChat, app.graph.toolRegistry, app.graph.providerManager,
            today = app.graph.todayUsage.observe(),
        ).also { it.uiState.activeIn(backgroundScope) }
        assertNull(chat.uiState.value.limitReached)

        chat.say("One")
        advanceUntilIdle()
        val sentence = DailyLimits(1, 500_000, 1_000_000).sentence(DailyLimitKind.CALLS)
        assertEquals(sentence, chat.uiState.value.limitReached, "the limit is reached and this chat's text leaves the phone")

        chat.say("Two")
        advanceUntilIdle()
        val notice = assertIs<TranscriptItem.Notice>(chat.uiState.value.items.last())
        assertEquals(NoticeKind.LIMIT, notice.kind)
        assertEquals(sentence, notice.text, "the limit's sentence alone")
        assertEquals(1, client.requests.size, "the second message was not sent")
    }

    private companion object {
        /** Longer than the gateway's default retries take (1 s, then 2 s). */
        const val RETRY_WINDOW_MS = 30_000L
    }
}
