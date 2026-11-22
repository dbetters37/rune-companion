package com.aicontrolcenter.app.journeys

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.agents.AgentKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The journeys the owner takes most, driven through the real shell, navigation and view models. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class KeyJourneysTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var robot: AppRobot
    private val app: TestApp get() = robot.app

    @Before
    fun startApp() {
        robot = AppRobot(compose).given {
            installSampleApps()
            graph.start().join()
        }.launch()
    }

    @After
    fun stopApp() = robot.close()

    @Test
    fun `the dashboard explains the missing models once and lists no errors`() {
        robot.assertShown("Agents need a model to work. Connecting model providers is the next stage of this build.")
            .assertShown("0 of 6 agents ready to run")
            .assertShown("Nothing needs your attention.")
            .assertShown("Designated Director")
    }

    @Test
    fun `create an agent from a template and see it in the list`() {
        robot.openSection("Agents")
            .tapIcon("New agent")
            .assertShown("Choose a starting point.")
            .scrollListTo("Writes, explains and debugs")
            .tapItemContaining("Writes, explains and debugs")
            .assertShown("No models are registered yet")
            .type("Name", "Build Engineer")
            .tap("Create agent")

        // Saving leaves the creation flow and shows the new agent.
        robot.assertShown("Build Engineer").assertShown("No history yet")
        val created = runBlocking { app.agentNamed("Build Engineer") }
        assertEquals(AgentKind.SPECIALIST, created.profile.kind)

        robot.back()
            .scrollListTo("Build Engineer")
            .assertShown("Build Engineer")
            .assertShown("Search agents")
    }

    @Test
    fun `an invalid name is explained next to the field and nothing is created`() {
        robot.openSection("Agents")
            .tapIcon("New agent")
            .scrollListTo("Blank agent")
            .tapItemContaining("Blank agent")
            .type("Name", "Researcher")
            .assertShown("Another agent is already called \"Researcher\".")
            .tap("Create agent")
        assertEquals(6, runBlocking { app.agents.getAll() }.size)

        // Leaving with unsaved changes asks first.
        robot.back().assertShown("Discard changes?").tapInDialog("Discard").assertShown("Blank agent")
    }

    @Test
    fun `try to delete the Director and see why it is not possible`() {
        robot.openSection("Agents")
            .tapItemContaining("Turns objectives into plans")
            .assertShown("Designated Director")
            .tap("Delete agent")
            .assertShown("The Director cannot be deleted")
            .assertShown("Designate another Director first, then this one can be deleted.")
            .tapInDialog("OK")
        assertNotNull(runBlocking { app.director() })
        assertEquals(6, runBlocking { app.agents.getAll() }.size)
    }

    @Test
    fun `delete an ordinary agent after confirming`() {
        robot.openSection("Agents")
            .scrollListTo("Organises material")
            .tapItemContaining("Organises material")
            .tap("Delete agent")
            .assertShown("Delete Writer?")
            .tapInDialog("Delete")
            .assertShown("Search agents")
        assertTrue(runBlocking { app.agents.getAll() }.none { it.name == "Writer" })
    }

    @Test
    fun `scan for AI apps, open one and use what is permitted`() {
        robot.openSectionViaMore("Models")
            .assertShown("No providers yet")
            .tap("AI apps · 0")
            .assertShown("No AI apps registered")
            .tap("Scan device")
            .assertShown("AI apps · 3")
            .scrollListTo("AI Helper")
            .assertShown("Unverified")
            .scrollListTo("Example Chatbot")
            .tapItemContaining("com.example.chatbot")
            .assertShown("WHAT THIS FRAMEWORK MAY DO WITH IT")
            .assertShown("Nothing beyond this list is possible.")
            .tap("Open app")
        assertEquals(listOf("com.example.chatbot"), app.launcher.launched)

        robot.tap("Send text to app…")
            .assertShown("then leaves this app's control")
            .type("Text to hand over", "Hello there")
            .tap("Send")
            .assertShown("Handed to Example Chatbot. Its reply stays in that app.")
        assertEquals(listOf("com.example.chatbot" to "Hello there"), app.launcher.handedOff)
    }

    @Test
    fun `authorising accessibility needs an explicit confirmation that says what it means`() {
        robot.given { graph.aiAppRegistry.discover() }
            .openSectionViaMore("Models")
            .tap("AI apps · 3")
            .scrollListTo("Example Chatbot")
            .tapItemContaining("com.example.chatbot")
            .tapItemContaining("Allow accessibility operation")
            .assertShown("Allow operating Example Chatbot's screen?")
            .assertShown("the operator service, which is not built yet")
            .tapInDialog("Cancel")
        assertEquals(false, runBlocking { app.appNamed("Example Chatbot") }.accessibilityAuthorized)

        robot.tapItemContaining("Allow accessibility operation").tapInDialog("Authorise")
            .assertShown("Operate its screen: authorised, not active")
        assertEquals(true, runBlocking { app.appNamed("Example Chatbot") }.accessibilityAuthorized)
    }

    @Test
    fun `confirm an unverified app and remove another`() {
        robot.given { graph.aiAppRegistry.discover() }
            .openSectionViaMore("Models")
            .tap("AI apps · 3")
            .scrollListTo("Confirm it is an AI app")
            .tap("Confirm it is an AI app")
        assertTrue(runBlocking { app.appNamed("AI Helper") }.verifiedByUser)

        robot.scrollListTo("Sample Assistant")
            .tapItemContaining("com.sample.assistant")
            .tap("Remove from registry")
            .assertShown("A scan will not bring it back.")
            .tapInDialog("Remove")
            .assertShown("AI apps · 2")
    }

    @Test
    fun `change settings and see them stored`() {
        robot.openSectionViaMore("Settings")
            .tapItemContaining("Local-only mode")
            .tapIcon("Increase Tasks at once")
            .tap("90 days")
        val stored = runBlocking { app.settings.get() }
        assertTrue(stored.localOnlyMode)
        assertEquals(3, stored.maxConcurrentTasks)
        assertEquals(90, stored.activityRetentionDays)

        robot.tap("Clear activity log").assertShown("Clear the activity log?").tapInDialog("Clear log")
        assertEquals(listOf("Activity log cleared"), app.activityLog.all.map { it.title })
    }

    @Test
    fun `the activity log is reachable from the dashboard and opens an event`() {
        robot.scrollTo("LATEST ACTIVITY")
            .tap("See all")
            .assertShown("TODAY")
            .tap("Left the device")
            .assertShown("No recorded event matches these filters.")
            .tap("Clear filters")
            .tapItemContaining("Starter team created")
            .assertShown("Event id")
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp-xhdpi")
    fun `a wide screen reaches all eight sections from the rail, without a More item`() {
        robot.assertNotShown("More")
        for (section in listOf("Agents", "Models", "Tasks", "Memory", "Tools", "Activity", "Settings", "Dashboard")) {
            robot.openSection(section)
        }
        robot.assertShown("0 of 6 agents ready to run")
        robot.openSection("Models").assertShown("No providers yet")
    }

    @Test
    fun `a double tap opens a screen once`() {
        robot.openSection("Agents")
        // Both taps arrive before navigation has replaced the list, as two quick taps do on a device.
        val card = compose.onAllNodes(hasText("Turns objectives into plans", substring = true) and hasClickAction())
            .onFirst().fetchSemanticsNode()
        val click = checkNotNull(card.config[SemanticsActions.OnClick].action)
        compose.runOnUiThread {
            click()
            click()
        }
        compose.waitForIdle()
        robot.assertShown("Designated Director").back().assertShown("Search agents")
    }

    @Test
    fun `sections that are not built yet say so and offer nothing to tap`() {
        robot.openSection("Tasks").assertShown("built yet in this version")
        robot.openSectionViaMore("Memory").assertShown("built yet in this version")
        robot.openSectionViaMore("Tools").assertShown("built yet in this version")
    }
}
