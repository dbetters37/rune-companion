package com.aicontrolcenter.app.journeys

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.DeviceAccessState
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerDecision
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
            .assertShown("Creates a text file in the workspace, or replaces one. A replaced file goes to the workspace trash.")
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
    fun `the tools list says what Android must allow for photos, installs and Termux, and opens the setting`() {
        val robot = app { systemScreens.access[DeviceAccess.MEDIA] = DeviceAccessState.SELECTED_ONLY }
            .openSectionViaMore("Tools")
            .scrollListTo("Allowed for the photos and videos you selected only.")
            .assertShown("Change selection")
            .scrollListTo("Install unknown apps (Android setting)")
            .assertShown("Off, so \"Install an app\" cannot ask Android to install anything.")
            .tap("Open Android's setting")
        val opened = robot.startedActivity()
        assertEquals(TestSystemScreens.ACCESS_SETTINGS, opened.action)
        assertEquals(DeviceAccess.INSTALL_UNKNOWN_APPS.name, opened.getStringExtra(TestSystemScreens.ACCESS))

        // Termux's permission is asked with Android's own dialog, here answered yes.
        robot.app.systemScreens.access[DeviceAccess.TERMUX_RUN_COMMAND] = DeviceAccessState.NOT_ALLOWED
        robot.scrollListTo("Run commands in Termux (Termux permission)")
            .assertShown("Not allowed yet, so Termux commands cannot run.")
        robot.app.systemScreens.access[DeviceAccess.TERMUX_RUN_COMMAND] = DeviceAccessState.ALLOWED
        pickers.next = mapOf("com.termux.permission.RUN_COMMAND" to true)
        robot.tap("Allow Termux commands").waitUntilShown("allow-external-apps=true")
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("com.termux.permission.RUN_COMMAND"), (pickers.launched.last() as Array<String>).toList())
        robot.assertNotShown("Allow Termux commands")
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
    fun `turning the phone operator on asks for notifications once, and Tools and Approvals say so while they are off`() {
        val robot = app {
            // Android 13 or later, where the app may not notify until the owner allows it. Nothing was asked yet for the
            // phone operator; an earlier answer about objectives' progress does not count for it.
            notifications.set(false)
            notifications.askableForOperator = true
        }
        robot.assertNotShown("Be told when an agent waits for you?")
            .openSectionViaMore("Settings")
            .scrollTo("Phone operator")
            .tap("Phone operator")
            .waitUntilShown("Be told when an agent waits for you?")
            .assertShown("Android asks for permission next")
        // The explanation first, then Android's own question, which the owner answers with no.
        pickers.next = false
        robot.tapInDialog("Continue")
        assertEquals("android.permission.POST_NOTIFICATIONS", pickers.launched.last())
        robot.waitUntil { robot.app.notifications.timesAskedForOperator == 1 }
            .assertNotShown("Be told when an agent waits for you?")

        // Tools says so, with the way to Android's notification settings for this app.
        robot.openSectionViaMore("Tools")
            .assertShown("Notifications are off")
            .assertShown("You are not told when an agent waits for your answer")
            .tap("Notification settings")
        val opened = robot.startedActivity()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(application.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))

        // So does the Approvals list, with a request waiting and with none.
        robot.given { raise(sampleApproval(agentNamed("Operator").profile.id, createdAt = clock.current, minutes = 240)) }
            .waitUntilShown("1 request waits for your answer")
            .tap("1 request waits for your answer")
            .assertShown("Waiting for you · 1 request".uppercase())
            .assertShown("Notifications are off")
            .tap("Review")
            .scrollTo("Deny")
            .tap("Deny")
            .waitUntilShown("Denied. The agent is told")
            .back()
            .assertShown("Nothing is waiting for you")
            .assertShown("Notifications are off")

        // Allowed later in Android's settings: the warning goes, and the owner is not asked again.
        robot.app.notifications.set(true)
        robot.waitUntil { runCatching { robot.assertNotShown("Notifications are off") }.isSuccess }
            .assertNotShown("Be told when an agent waits for you?")
        assertEquals(1, robot.app.notifications.timesAskedForOperator)
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
    fun `B2 - after a Deny the owner lets the agent ask again, from the history and from the request's own screen`() {
        val robot = app {
            val operator = agentNamed("Operator").profile.id
            val researcher = agentNamed("Researcher").profile.id
            raise(samplePermission(operator, agentName = "Operator", id = "req_operator", createdAt = clock.current, minutes = 100_000))
            raise(samplePermission(researcher, agentName = "Researcher", id = "req_researcher", createdAt = clock.current + 1, minutes = 100_000, taskId = null, rootId = null))
            // Refused "a day from now": this app's test clock jumps ahead on every read, and the ten minutes must not run out under the journey.
            val refusedAt = clock.current + 24 * 60 * 60_000L
            ownerRequestStore.decide(RequestId("req_operator"), OwnerDecision.Deny(), refusedAt, GrantId("grt_unused_1"))
            ownerRequestStore.decide(RequestId("req_researcher"), OwnerDecision.Deny(), refusedAt, GrantId("grt_unused_2"))
        }.openSectionViaMore("Tools").tap("History")
        fun refusalsHeld() = runBlocking {
            listOf("Operator", "Researcher").count { robot.app.ownerRequestStore.lastRefusal(robot.app.agentNamed(it).profile.id, OperatorCapabilities.WEB_READ, CapabilityConstraint.None, 0) != null }
        }
        assertEquals(2, refusalsHeld())

        // In the history: narrowed to the Operator's refusal, one tap ends its wait.
        robot.tap("Operator").assertShown("1 request of 2 match").tap("Let it ask again")
            .waitUntilShown("Operator can ask again. Its next call or request for this permission is put to you.")
            .assertNotShown("Let it ask again")
        assertEquals(1, refusalsHeld())

        // On the Researcher's request's own screen.
        robot.tap("Clear filters").tap("Researcher").tapItemContaining("Read the web page")
            .scrollTo("Changed your mind?")
            .assertShown("Changed your mind? Then its next call or request for this permission is put to you as usual. This allows nothing by itself.")
            .tap("Let it ask again")
            .waitUntilShown("Researcher can ask again. Its next call or request for this permission is put to you.")
            .assertNotShown("Let it ask again")
        assertEquals(0, refusalsHeld())
        assertTrue(robot.grants().isEmpty(), "letting an agent ask gives it nothing")
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
