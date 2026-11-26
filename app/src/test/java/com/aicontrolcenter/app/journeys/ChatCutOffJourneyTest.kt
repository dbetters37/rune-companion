package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.serveModel
import com.aicontrolcenter.core.agents.AgentDraft
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.framework.chat.DefaultAgentChat
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Phase 6 journeys of the chat: a reply the app stopped in the middle of (R3) says so after the next start and offers
 * "Send again", which answers the owner's message without storing it twice; and the cost line under the chat, which
 * shows money once the owner typed the model's price in its editor (goal 1, rule 29).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ChatCutOffJourneyTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    /** Goes back until a top-level screen shows (no Back button any more). */
    private fun AppRobot.backToTop(): AppRobot = apply {
        while (compose.onAllNodes(hasContentDescription("Back") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) back()
    }

    @Test
    fun `a reply cut off when the app stopped is said after the start, and Send again answers it`() {
        val client = ScriptedLlmClient.replies("High tide is at 14:20.")
        val robot = AppRobot(compose).given {
            val model = serveModel(client)
            val agent = graph.agentManager.create(AgentDraft(name = "Tidewatch", kind = AgentKind.SPECIALIST, modelId = model.id, description = "Knows the tides."))
            val conversation = graph.agentChat.startConversation(agent.id, "Tides")
            // The app was stopped while it answered: the owner's message is stored and the turn's mark was left behind.
            conversations.appendEntry(ConversationEntry(conversation.id, -1, ChatMessage.user("When is high tide?"), clock.now()))
            conversations.markTurnStarted(conversation.id, clock.now())
            graph.start().join()
        }.launch().also { robot = it }
        val app = robot.app

        robot.openSection("Agents").tapItemContaining("Knows the tides.").tap("Chat").tapItemContaining("Tides")
            .waitUntilShown("The app stopped")
            .assertShown(DefaultAgentChat.cutOff("Tidewatch"))
            .tap("Send again")
            .waitUntilShown("High tide is at 14:20.")
            .assertNotShown("Send again")

        val conversation = runBlocking { app.conversations.observeConversations(null).first() }.single()
        val owner = runBlocking { app.conversations.getEntries(conversation.id) }.filter { it.message.role == ChatRole.USER && it.error == null }
        assertEquals(listOf("When is high tide?"), owner.map { it.message.text }, "the message was not stored a second time")
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `a chat's cost line shows tokens, and money once the model's price is typed in`() {
        val robot = AppRobot(compose).given {
            val model = serveModel(ScriptedLlmClient.script(ScriptStep.Reply("Done.", TokenUsage(100_000, 20_000))), displayName = "Priced model")
            graph.agentManager.create(AgentDraft(name = "Counter", kind = AgentKind.SPECIALIST, modelId = model.id, description = "Counts things."))
            graph.start().join()
        }.launch().also { robot = it }
        val app = robot.app

        robot.openSection("Agents").tapItemContaining("Counts things.").tap("Chat").tap("New conversation")
            .type("Message Counter", "Count the apples")
            .tapIcon("Send")
            .waitUntilShown("This chat so far: 120,000 tokens.")
            .assertShown("Enter the model's prices in Models to see what this chat costs.")

        // The price, typed from the provider's page in the model's editor.
        robot.backToTop().openSectionViaMore("Models").tap("Models · 2").tapItemContaining("Priced model")
            .assertShown("You enter these from your provider's price page; the app never looks them up.")
            .type("Input, per million tokens", "3")
            .type("Output, per million tokens", "15")
            .scrollTo("Save price").tap("Save price")
            .waitUntilShown("Price saved.")
        val model = runBlocking { app.models.getAll() }.single { it.displayName == "Priced model" }
        assertEquals(ModelPrice(model.id, 3_000_000, 15_000_000, null, "US$", runBlocking { app.prices.get(model.id) }!!.updatedAt), runBlocking { app.prices.get(model.id) })

        robot.backToTop().openSection("Agents").tapItemContaining("Counts things.").tap("Chat").tapItemContaining("Count the apples")
            .waitUntilShown("This chat so far: 120,000 tokens, about US$0.60.")
            .assertShown("Estimated from the prices you entered; your provider's bill is what counts.")
    }
}
