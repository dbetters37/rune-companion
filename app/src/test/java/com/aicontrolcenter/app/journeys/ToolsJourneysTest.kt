package com.aicontrolcenter.app.journeys

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.PickerResults
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.TestSystemScreens
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.samplePermission
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.tools.CapabilityConstraint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 5 journeys through the Tools section, one per tab, and the workspace file manager: what
 * the owner sees and does, and what that changes in the stores the permission broker and the
 * screen operator read.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ToolsJourneysTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null
    private val pickers = PickerResults()
    private val application: Application get() = ApplicationProvider.getApplicationContext()

    private fun app(setUp: suspend TestApp.() -> Unit = {}): AppRobot =
        AppRobot(compose).given { graph.start().join(); setUp() }.launch(results = pickers).also { robot = it }

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun AppRobot.grants() = runBlocking { app.permissions.observeAll().first() }

    /** Waits until a control screen readers know as [description] is on screen (file work runs off the main thread). */
    private fun AppRobot.waitForIcon(description: String) = waitUntil { compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty() }

    /** The next activity the app asked Android to start, once it has. */
    private fun AppRobot.startedActivity(): Intent {
        var started: Intent? = null
        waitUntil { shadowOf(application).nextStartedActivity?.also { started = it } != null }
        return assertNotNull(started)
    }

    @Test
    fun `the catalogue says what each tool does and leads to the agents that have it and to their permissions`() {
        val robot = app().openSectionViaMore("Tools")
            .assertShown("tools agents can be given")
            .assertShown("Phone operator is off in Settings")
            .tap("Workspace")
            .assertShown("Creates a text file in the workspace, or replaces one.")
            .assertShown("Permission: Change the workspace · Needs your permission")
        robot.tapIcon("Open Operator")
            .assertShown("Carries out approved actions")
            .scrollTo("Workspace")
            .tap("Workspace")
            .waitUntilShown("The agents' own folder inside this app")
            .back()
            .tap("Permissions")
            // Tools › Allowed, narrowed to the Operator, who has been given nothing yet.
            .assertShown("Nothing beyond what every agent may")
            .back()
            .assertShown("Carries out approved actions")
    }

    @Test
    fun `the owner removes one permission, then all of an agent's, in the Allowed tab`() {
        val robot = app {
            val operator = agentNamed("Operator").profile.id
            graph.ownerGrants.grant(operator, OperatorCapabilities.WEB_READ)
            graph.ownerGrants.grant(operator, OperatorCapabilities.APPS_OPEN)
            graph.ownerGrants.grant(operator, OperatorCapabilities.SCRIPTS_JS)
        }.openSectionViaMore("Tools").tap("Allowed")
            .assertShown("Read web pages")
            .assertShown("3 permissions")
            .tapIcon("Remove Read web pages for Operator")
            .waitUntilShown("Removed. The agent asks you again")
        assertEquals(setOf(OperatorCapabilities.APPS_OPEN, OperatorCapabilities.SCRIPTS_JS), robot.grants().map { it.capability }.toSet())

        robot.tap("Remove all").tapInDialog("Remove all").waitUntilShown("2 permissions removed")
        assertEquals(emptyList(), robot.grants())
        robot.assertShown("No permissions given")
    }

    @Test
    fun `the owner shares a folder through the picker, chooses who may use it, makes it read-only and removes it`() {
        pickers.next = Uri.parse("content://tree/documents")
        val robot = app { folderAccess.pickable["content://tree/documents"] = "Documents" }
            .openSectionViaMore("Tools").tap("Folders")
            .assertShown("No folders shared")
            .tap("Add a folder")
            .waitUntilShown("“Documents” is shared")
        assertEquals(listOf<Any?>(null), pickers.launched, "Android's folder picker was asked, with no starting place")
        val folder = runBlocking { robot.app.sharedFolders.getAll().single() }

        robot.press("Read and change, for Operator").waitUntilShown("May now read and change this folder")
        val given = robot.grants()
        assertEquals(setOf(OperatorCapabilities.FOLDERS_READ, OperatorCapabilities.FOLDERS_WRITE), given.map { it.capability }.toSet())
        assertTrue(given.all { it.constraint == CapabilityConstraint.Folder(folder.id) && it.scope == GrantScope.ALWAYS })

        robot.tap("Read-only").waitUntil { runBlocking { robot.app.sharedFolders.get(folder.id)?.readOnly } == true }
        robot.tapIcon("Remove Documents").assertShown("The folder and its files stay on the phone").tapInDialog("Remove")
            .waitUntilShown("Folder removed")
        assertEquals(emptyList(), robot.grants(), "its permissions were revoked first")
        assertTrue("releaseAccess content://tree/documents" in robot.app.folderAccess.calls)
    }

    @Test
    fun `the owner follows the steps to switch the service on, authorises an app, chooses its agents, stops and resumes`() {
        val robot = app {
            screen.setState(ScreenServiceState.NOT_ENABLED)
            installSampleApps()
            certificates["org.example.notes"] = "12".repeat(32)
        }.openSectionViaMore("Tools").tap("Screen")
            .assertShown("The service is off in Android's accessibility settings")
            .assertShown("Find “AI Control Center operator”")
            .tap("Open accessibility settings")
        assertEquals(TestSystemScreens.ACCESSIBILITY_SETTINGS, robot.startedActivity().action)

        // The owner switched it on in Android's settings and came back.
        robot.app.screen.setState(ScreenServiceState.READY)
        robot.waitUntilShown("The service is on")
            .tap("Authorise an app")
            .waitUntilShown("Never operated: it can change the phone's security")
            // Picking an app only chooses it; the next step names it and says what authorising allows.
            .tapInDialog("Notes")
            .assertShown("Authorise Notes?")
            .assertShown("Take care with banking, payment, messaging and terminal apps")
        assertEquals(null, runBlocking { robot.app.operatorApps.get("org.example.notes") }, "nothing is authorised before the owner confirms")
        robot.tapInDialog("Authorise")
            .waitUntil { runBlocking { robot.app.operatorApps.get("org.example.notes") } != null }
            .scrollListTo("Checked: this exact app")
            .assertNotShown("12:12:12:12 … 12:12")
            .tapIcon("Details of Notes")
            .assertShown("Signing certificate fingerprint: 12:12:12:12 … 12:12")
        assertEquals("12".repeat(32), runBlocking { robot.app.operatorApps.get("org.example.notes") }?.signingCertSha256)

        robot.scrollListTo("See and use").press("See and use, for Operator").waitUntilShown("May now see and use this app's screen")
        assertEquals(setOf(OperatorCapabilities.SCREEN_READ, OperatorCapabilities.SCREEN_OPERATE), robot.grants().map { it.capability }.toSet())

        robot.scrollListTo("Stop screen control").tap("Stop screen control").waitUntilShown("Resume screen control")
        assertTrue(robot.app.screen.stopped.value)
        robot.tap("Resume screen control").waitUntilShown("Stop screen control")
        assertFalse(robot.app.screen.stopped.value)

        robot.scrollListTo("Checked: this exact app").tapIcon("Remove Notes").tapInDialog("Remove")
            .waitUntil { runBlocking { robot.app.operatorApps.get("org.example.notes") } == null }
            .scrollListTo("No apps authorised")
        assertEquals(emptyList(), robot.grants())
    }

    @Test
    fun `screen control sends the owner to Settings for the two switches, and the restricted-settings steps to App info`() {
        val robot = app { screen.setState(ScreenServiceState.RESTRICTED) }
            .openSectionViaMore("Tools").tap("Screen")
            .assertShown("Blocked by restricted settings")
            .tap("Open App info")
        assertEquals(TestSystemScreens.APP_INFO, robot.startedActivity().action)

        robot.tap("Open Settings")
            .scrollTo("Phone operator")
            .tap("Phone operator")
            .waitUntil { runBlocking { robot.app.settings.get() }.operatorEnabled }
            .tap("Screen control")
        robot.waitUntil { runBlocking { robot.app.settings.get() }.accessibilityOperatorEnabled }
        assertTrue(runBlocking { robot.app.settings.get() }.operatorEnabled)
    }

    @Test
    fun `authorising an AI app from its detail puts it on the screen operator's list`() {
        val robot = app {
            installSampleApps()
            graph.aiAppRegistry.discover()
        }.openSectionViaMore("Models")
            .tap("AI apps · 3")
            .scrollListTo("com.example.chatbot")
            .tapItemContaining("com.example.chatbot")
            .scrollTo("Allow accessibility operation")
            .tapItemContaining("Allow accessibility operation")
            .tapInDialog("Authorise")
            .waitUntilShown("Authorised and checked")
        assertEquals("ab".repeat(32), runBlocking { robot.app.operatorApps.get("com.example.chatbot") }?.signingCertSha256)

        robot.back().openSectionViaMore("Tools").tap("Screen").scrollListTo("Example Chatbot").assertShown("Checked: this exact app")
    }

    @Test
    fun `the request history filters by outcome and opens a request`() {
        val robot = app {
            val operator = agentNamed("Operator").profile.id
            raise(sampleApproval(operator, id = "req_denied", createdAt = clock.current, minutes = 240))
            raise(samplePermission(agentNamed("Researcher").profile.id, id = "req_waiting", createdAt = clock.current + 1, minutes = 240))
            graph.ownerRequests.deny(RequestId("req_denied"), "Not these files.")
        }.openSectionViaMore("Tools").tap("History")
            .assertShown("2 requests · newest first")
            .assertShown("Delete 3 files")
            .assertShown("Read the web page")
            .tap("Denied")
            .assertShown("1 request of 2 match")
            .assertNotShown("Read the web page")
            .tapItemContaining("Delete 3 files")
            .assertShown("Not these files.")
            .back()
            .tap("Clear filters")
            .assertShown("Read the web page")
    }

    @Test
    fun `the owner browses the workspace, previews and shares a file, renames, imports, trashes, restores and empties the trash`() {
        val robot = app {
            graph.workspace.write(RelativePath.of("notes.md"), "# Plan\nBuy milk".toByteArray(), overwrite = false)
            graph.workspace.write(RelativePath.of("reports/q1.csv"), "month,total".toByteArray(), overwrite = false)
        }.openSectionViaMore("Tools").scrollListTo("Open the workspace").tap("Open the workspace")
            .waitUntilShown("notes.md")
            .assertShown("reports")
            .tapItemContaining("reports")
            .waitUntilShown("q1.csv")
            .tap("Workspace")
            .waitUntilShown("notes.md")
            .tapItemContaining("notes.md")
            .waitUntilShown("Buy milk")
            .tap("Share")
        val shared = robot.startedActivity()
        assertEquals(File(robot.app.workspaceRoot.directory(), "notes.md").path, shared.getStringExtra(TestSystemScreens.SHARED_FILE))
        assertEquals("text/markdown", shared.type)

        robot.back()
            .tapIcon("More for notes.md").tap("Rename")
            .type("New name", "plan.md").tap("Rename")
            .waitUntilShown("Renamed to “plan.md”")

        // Import: Android's document picker returns one document, copied in under its own name.
        val document = File(application.cacheDir, "picked/trip.txt").apply { parentFile?.mkdirs(); writeText("Train at 9") }
        pickers.next = Uri.fromFile(document)
        robot.tap("Import a file").waitUntilShown("Imported as “trip.txt”")
        assertEquals("Train at 9", File(robot.app.workspaceRoot.directory(), "trip.txt").readText())

        robot.tapIcon("More for plan.md").tap("Move to trash").waitUntilShown("“plan.md” moved to the trash")
        robot.waitForIcon("Trash, 1 item").tapIcon("Trash, 1 item").waitUntilShown("From the workspace").tap("Restore").waitUntilShown("Restored to plan.md")
        robot.back().waitUntilShown("plan.md").tapIcon("More for trip.txt").tap("Move to trash").waitUntilShown("moved to the trash")
        robot.waitForIcon("Trash, 1 item").tapIcon("Trash, 1 item").tap("Empty the trash").assertShown("deleted for good. This cannot be undone").tapInDialog("Empty trash")
            .waitUntilShown("The trash is empty")
        assertFalse(File(robot.app.workspaceRoot.directory(), "trip.txt").exists())
        assertTrue(runBlocking { robot.app.graph.workspace.stat(RelativePath.of("plan.md")) } is DocResult.Ok)
    }

    @Test
    fun `the agent editor lists tools by family and links to the agent's permissions`() {
        app().openSection("Agents")
            .scrollListTo("Carries out approved actions")
            .tapItemContaining("Carries out approved actions")
            .tapIcon("Edit agent")
            .scrollTo("does not give the permission the tool needs")
            .assertShown("Switching a tool on makes it available to this agent")
            .scrollTo("Scripts")
            .assertShown("3 of 3 on")
            .tap("See what this agent is allowed in Tools › Allowed")
            .assertShown("Nothing beyond what every agent may")
            .back()
            // The editor is still open, with its draft.
            .assertShown("Switching a tool on makes it available to this agent")
    }
}
