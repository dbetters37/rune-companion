package com.aicontrolcenter.app.ui.chat

import com.aicontrolcenter.app.testing.GatedLlmClient
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.serveModel
import com.aicontrolcenter.core.AgentId
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
        assertTrue(sameModel("gpt-4o-2024-08-06", "gpt-4o"), "a dated version of the alias is the same model")
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

    private companion object {
        /** Longer than the gateway's default retries take (1 s, then 2 s). */
        const val RETRY_WINDOW_MS = 30_000L
    }
}
