package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.operatorOn
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.chat.TurnResult
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 5 journeys: an agent's tool call needs a permission the owner has not given, the owner
 * is asked in the app, and the call runs only as the owner answers: once, refused, or not at
 * all in time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ApprovalJourneysTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var robot: AppRobot
    private val app: TestApp get() = robot.app
    private val turns = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** The Operator writes the owner's list into the workspace, then says what it did. */
    private val model = ScriptedLlmClient.script(
        ScriptStep.Calls(listOf(ScriptedLlmClient.call("call_1", "workspace_write", buildJsonObject { put("path", "notes/todo.md"); put("content", "Buy milk") }))),
        ScriptStep.Reply("Done: your list is in notes/todo.md."),
    )

    private fun written(): File = File(app.workspaceRoot.directory(), "notes/todo.md")

    @Before
    fun startApp() {
        robot = AppRobot(compose).given {
            operatorOn(model)
            // The test clock moves 37 seconds with every reading; give the owner time to read.
            settings.update { it.copy(approvalTimeoutMinutes = 240) }
        }
    }

    @After
    fun stopApp() {
        turns.cancel()
        robot.close()
    }

    @Test
    fun `the agent asks in the chat, the owner allows it once, and the call runs`() {
        robot.launch()
            // The master switch first: off, no agent can touch the phone and nobody is asked.
            .openSectionViaMore("Settings")
            .scrollTo("Phone operator")
            .tap("Phone operator")
        assertTrue(runBlocking { app.settings.get() }.operatorEnabled)

        robot.openSection("Agents")
            .scrollListTo("Carries out approved actions")
            .tapItemContaining("Carries out approved actions")
            .tap("Chat")
            .tap("New conversation")
            .type("Message Operator", "Save my list: buy milk")
            .tapIcon("Send")
            // The request appears in the chat where it was raised; the chat cannot answer it.
            .waitUntilShown("Permission needed")
            .assertShown("Waiting for your answer")
            .tap("Review")
            // Who asks, the tool, the exact action and the answers on offer.
            .assertShown("Operator")
            .assertShown("Needs a permission to use “Write a workspace file”")
            .assertShown("Create the workspace file \"notes/todo.md\"")
            .assertShown("Change the workspace")
            .assertShown("Stays on this phone")
            .assertShown("Allow just this once")
            .assertShown("Always allow…")
            .assertShown("Deny")
            .assertNotShown("Allow for the rest of this task")
            // Each answer says how far it reaches.
            .assertShown("Operator may change any file in the workspace in any task or chat")
        assertFalse(written().exists(), "nothing is written before the owner answers")

        robot.scrollTo("Allow just this once").tap("Allow just this once")
            .waitUntilShown("Allowed just this once. The action is checked again before it runs.")
            .back()
            .waitUntilShown("Done: your list is in notes/todo.md.")
            .assertShown("Allowed just once")

        assertEquals("Buy milk", written().readText())
        val request = runBlocking { app.ownerRequestStore.observeRecent(10).first() }.single()
        assertEquals(OwnerRequestKind.PERMISSION, request.kind)
        assertEquals(GrantScope.ONCE, request.decisionScope)
        // The ONCE grant was used by that call and by nothing else.
        assertNotNull(runBlocking { app.permissions.findByRequest(request.id) }?.consumedAt)
        val call = runBlocking { app.activityLog.recent() }.first { it.type == ActivityType.TOOL_CALL && "Write a workspace file" in it.title }
        assertEquals("Tool used: Write a workspace file", call.title)
        assertTrue(request.id.value in call.data.toString(), "the call's log entry names the request the owner answered")
    }

    @Test
    fun `the owner sees the request waiting from any section and refuses it with a note`() {
        robot.given {
            settings.update { it.copy(operatorEnabled = true) }
            val operator = agentNamed("Operator").profile
            val conversation = graph.agentChat.startConversation(operator.id, "My list")
            turns.launch { graph.agentChat.send(conversation.id, "Save my list: buy milk") }
        }.launch()
            .waitUntilShown("1 request waits for your answer")
            .tap("1 request waits for your answer")
            .assertShown("Waiting for you")
            .assertShown("Waiting for you · 1 request".uppercase())
            .tap("Review")
            .type("Note for the agent (optional)", "Not now, thanks.")
            .scrollTo("Deny")
            .tap("Deny")
            .waitUntilShown("Denied. The agent is told, with your note if you wrote one.")
            .back()
            .assertShown("Nothing is waiting for you")
            .back()
            .assertNotShown("1 request waits for your answer")

        robot.waitUntil { model.requests.size == 2 }
        assertFalse(written().exists(), "a refused call does nothing")
        val request = runBlocking { app.ownerRequestStore.observeRecent(10).first() }.single()
        assertEquals(OwnerRequestStatus.DENIED, request.status)
        assertEquals("Not now, thanks.", request.decisionNote)
        // The agent is told, with the owner's words as quoted data.
        val toolResult = model.requests[1].messages.last { it.role == ChatRole.TOOL }.toolResult!!
        assertTrue(toolResult.isError)
        assertTrue("did not give" in toolResult.content && "\"Not now, thanks.\"" in toolResult.content, toolResult.content)
        assertTrue(runBlocking { app.permissions.observeAll().first() }.isEmpty(), "no grant was stored")
    }
}

/** Expiry, in virtual time: nobody answers, the request expires and the call is refused. */
class ApprovalExpiryJourneyTest {
    @get:Rule val mainRule = MainDispatcherRule()

    /** Reading a web page needs a permission nobody gave; the page reader does no disk work, so virtual time is enough. */
    private val model = ScriptedLlmClient.script(
        ScriptStep.Calls(listOf(ScriptedLlmClient.call("call_1", "web_read_page", buildJsonObject { put("url", "https://example.org/news") }))),
        ScriptStep.Reply("I could not read it: you did not answer in time."),
    )

    @Test
    fun `a request nobody answers expires, the call is refused and the turn goes on`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope, FrameworkSettings(approvalTimeoutMinutes = 5, operatorEnabled = true))
        app.web.page("https://example.org/news", "<p>News</p>")
        val operator = app.operatorOn(model)
        val conversation = app.graph.agentChat.startConversation(operator.id, "Late")

        val turn = async { app.graph.agentChat.send(conversation.id, "Read the news") }
        runCurrent()
        // Read from the store: the test clock moves 37 seconds with every reading, so the owner's
        // list (which hides requests past their expiry by that clock) may already leave it out.
        val pending = app.ownerRequestStore.observePending().first()
        assertEquals(1, pending.size, "the call waits for the owner")
        assertEquals(OwnerRequestKind.PERMISSION, pending.single().kind)
        assertFalse(turn.isCompleted)

        advanceTimeBy(6 * 60_000L)
        runCurrent()

        assertIs<TurnResult.Completed>(turn.await())
        val request = assertNotNull(app.ownerRequestStore.get(pending.single().id))
        assertEquals(OwnerRequestStatus.EXPIRED, request.status)
        assertTrue(app.web.requests.isEmpty(), "the page was never requested")
        val toolResult = model.requests[1].messages.last { it.role == ChatRole.TOOL }.toolResult!!
        assertTrue(toolResult.isError)
        assertTrue("did not answer within" in toolResult.content, toolResult.content)
        assertTrue(app.ownerRequestStore.observePending().first().isEmpty())
        assertTrue(app.permissions.observeAll().first().isEmpty(), "nothing was granted")
    }
}
