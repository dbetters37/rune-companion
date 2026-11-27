package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.serveModel
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.agents.AgentDraft
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The owner's own complaint: a task ran for minutes and only at its end said "Phone operation is switched off in
 * Settings". Now the app says it before work starts: on New objective and in a chat with an agent that has phone tools,
 * a card with "Turn on" (the owner's Settings path, logged like the Settings switch). A phone tool refused for this
 * reason shows a line with the same "Turn on" where it was refused.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class OperatorOffJourneyTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private val card = "Phone operator is off. Agents can use their own workspace, but not your apps, folders or web pages."

    private fun turnOn() {
        val button = compose.onAllNodes(hasText("Turn on") and hasClickAction()).onFirst()
        try {
            button.performScrollTo()
        } catch (_: AssertionError) {
            // Not inside a scrolling list (the chat's card sits above it).
        }
        button.performClick()
        compose.waitForIdle()
    }

    private fun operatorOn(robot: AppRobot): Boolean = runBlocking { robot.app.settings.get().operatorEnabled }

    private fun loggedTurnOn(robot: AppRobot): Boolean =
        runBlocking { robot.app.activityLog.recent() }.any { it.type == ActivityType.USER_ACTION && it.title == "Phone operator turned on" }

    @Test
    fun `New objective says Phone operator is off before work starts, and Turn on switches it on`() {
        val robot = AppRobot(compose).given {
            graph.start().join()
            graph.agentManager.create(
                AgentDraft(name = "Reader", kind = AgentKind.SPECIALIST, description = "Reads pages.", toolIds = setOf(ToolId("web.read_page"))),
            )
        }.launch().also { this.robot = it }
        assertFalse(operatorOn(robot))

        robot.openSection("Tasks").tap("New objective").waitUntilShown(card)
        turnOn()
        robot.waitUntil { operatorOn(robot) }
        assertTrue(loggedTurnOn(robot), "recorded like the Settings switch")
        robot.waitUntil { compose.onAllNodesWithText(card).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `a chat with an agent that has phone tools says so, and a refused phone tool shows Turn on where it was refused`() {
        val client = ScriptedLlmClient.script(
            ScriptStep.Calls(listOf(ScriptedLlmClient.call("c1", "web_read_page", buildJsonObject { put("url", "https://example.org/news") }))),
            ScriptStep.Reply("I could not read the page."),
        )
        val robot = AppRobot(compose).given {
            val model = serveModel(client)
            graph.agentManager.create(
                AgentDraft(name = "Reader", kind = AgentKind.SPECIALIST, modelId = model.id, description = "Reads pages.", toolIds = setOf(ToolId("web.read_page"))),
            )
            graph.start().join()
        }.launch().also { this.robot = it }

        robot.openSection("Agents").tapItemContaining("Reads pages.").tap("Chat").tap("New conversation")
            .waitUntilShown(card)
            .type("Message Reader", "Read the news page")
            .tapIcon("Send")
            .waitUntilShown("I could not read the page.")
            .waitUntilShown("Refused because Phone operator is off. Turn it on, then ask again.")
        val conversation = runBlocking { robot.app.conversations.observeConversations(null).first() }.single()
        assertEquals(1, runBlocking { robot.app.conversations.getEntries(conversation.id) }.count { it.message.toolResult != null })

        turnOn()
        robot.waitUntil { operatorOn(robot) }
        assertTrue(loggedTurnOn(robot))
        robot.waitUntil { compose.onAllNodesWithText(card).fetchSemanticsNodes().isEmpty() }
        robot.waitUntil { compose.onAllNodesWithText("Refused because Phone operator is off. Turn it on, then ask again.").fetchSemanticsNodes().isEmpty() }
    }
}
