package com.aicontrolcenter.app.screenshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.samplePermission
import com.aicontrolcenter.app.ui.tools.AccessLevel
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.app.ui.tools.FoldersViewModel
import com.aicontrolcenter.app.ui.tools.SETUP_ALL_SET
import com.aicontrolcenter.app.ui.tools.ScreenControlActions
import com.aicontrolcenter.app.ui.tools.ScreenControlTab
import com.aicontrolcenter.app.ui.tools.ScreenControlUiState
import com.aicontrolcenter.app.ui.tools.ScreenControlViewModel
import com.aicontrolcenter.app.ui.tools.applyAccess
import com.aicontrolcenter.app.ui.tools.serviceStatusOf
import com.aicontrolcenter.app.ui.tools.setupChecklistOf
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.DeviceAccessState
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the Tools section (its five tabs) and the workspace file manager (a folder, a file's
 * preview, the trash), at PHONE, WIDE and NARROW, plus the states only a phone picture shows:
 * the restricted-settings steps, Stop, the app picker, the Settings switches and the agent
 * editor's tool families; and Screen control's "Set up phone control" with none, some and every
 * step done, at PHONE and at 360 dp with font scale 2.0, dark and light. Recorded into
 * `build/outputs/roborazzi`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ToolsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    private fun app(dark: Boolean = true, setUp: suspend TestApp.() -> Unit = { atWork() }): AppRobot =
        AppRobot(compose).given { graph.start().join(); setUp() }.launch(dark).also { robot = it }

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    private fun shootScreen(name: String) = captureScreenRoboImage("build/outputs/roborazzi/$name.png")

    /**
     * The phone operator in use: both switches on, two shared folders, one authorised app, the
     * Operator and the Researcher with permissions, a history of requests and a workspace with
     * files, a folder and one item in the trash.
     */
    private suspend fun TestApp.atWork() {
        settings.update { it.copy(operatorEnabled = true, accessibilityOperatorEnabled = true, approvalTimeoutMinutes = 240) }
        val operator = agentNamed("Operator").profile.id
        val researcher = agentNamed("Researcher").profile.id

        folderAccess.pickable["content://tree/documents"] = "Documents"
        folderAccess.pickable["content://tree/camera"] = "Camera"
        val documents = (graph.ownerFolders.add("content://tree/documents") as OwnerResult.Done).value
        val camera = (graph.ownerFolders.add("content://tree/camera") as OwnerResult.Done).value
        graph.ownerFolders.setReadOnly(camera.id, true)
        applyAccess(graph.ownerGrants, operator, CapabilityConstraint.Folder(documents.id), FoldersViewModel.KIND, AccessLevel.CHANGE)
        applyAccess(graph.ownerGrants, researcher, CapabilityConstraint.Folder(documents.id), FoldersViewModel.KIND, AccessLevel.READ)

        installSampleApps()
        graph.ownerOperatorApps.authorise("org.example.notes", "Notes", "3f9a1c0be2d47a5581c6e0f2b9d3a4e7c1f08d2b6a5e9c3d7f1a0b8e2c4d6f81")
        applyAccess(graph.ownerGrants, operator, CapabilityConstraint.App("org.example.notes"), ScreenControlViewModel.KIND, AccessLevel.CHANGE)
        graph.ownerGrants.grant(operator, OperatorCapabilities.WORKSPACE_WRITE)

        val now = clock.current
        raise(sampleApproval(operator, id = "req_denied", createdAt = now - 90 * 60_000L, minutes = 240))
        graph.ownerRequests.deny(RequestId("req_denied"), "Not these files.")
        raise(samplePermission(researcher, id = "req_always", createdAt = now - 60 * 60_000L, minutes = 240, taskId = null, rootId = null, reason = "I compare reviews from several sites."))
        graph.ownerRequests.grant(RequestId("req_always"), GrantScope.ALWAYS)
        raise(samplePermission(researcher, id = "req_expired", createdAt = now - 5 * 60 * 60_000L, minutes = 30))
        raise(sampleApproval(operator, id = "req_waiting", createdAt = now, minutes = 240))

        val ws = graph.workspace
        ws.write(RelativePath.of("notes.md"), "# Trip to Lisbon\n\n- Train at 9:05 from platform 4\n- Check in after 15:00\n- Ask Writer for the packing list\n".toByteArray(), overwrite = false)
        ws.write(RelativePath.of("reports/2025/q1.csv"), "month,total\nJan,1200\nFeb,980\nMar,1430\n".toByteArray(), overwrite = false)
        ws.write(RelativePath.of("reports/summary.md"), "Q1 was up 8% on last year.".toByteArray(), overwrite = false)
        ws.write(RelativePath.of("packing-list.txt"), "Passport\nCharger\nRain jacket\n".toByteArray(), overwrite = false)
        ws.write(RelativePath.of("old draft.md"), "An early draft.".toByteArray(), overwrite = false)
        ws.trash(RelativePath.of("old draft.md"))
    }

    /** The five tabs and the three workspace screens, in that order, with [suffix] in each name. */
    private fun AppRobot.everyScreen(suffix: String) {
        openSectionViaMoreOrRail("Tools")
        scrollListTo("Files in the agents' own folder").tap("Workspace")
        shoot("p5_tools_1_catalog_$suffix")
        tap("Allowed")
        shoot("p5_tools_2_permissions_$suffix")
        tap("Folders")
        shoot("p5_tools_3_folders_$suffix")
        tap("Screen")
        shoot("p5_tools_4_screen_control_$suffix")
        tap("History")
        shoot("p5_tools_5_requests_$suffix")
        tap("All tools").scrollListTo("Open the workspace").tap("Open the workspace").waitUntilShown("packing-list.txt")
        shoot("p5_workspace_1_folder_$suffix")
        tapItemContaining("notes.md").waitUntilShown("Train at 9:05")
        shoot("p5_workspace_2_file_$suffix")
        back().waitUntilShown("packing-list.txt").tapIcon("Trash, 1 item").waitUntilShown("old draft.md")
        shoot("p5_workspace_3_trash_$suffix")
    }

    /** Phones reach Tools through More; wide screens have it on the rail. */
    private fun AppRobot.openSectionViaMoreOrRail(title: String) = apply {
        try {
            openSection(title)
        } catch (_: AssertionError) {
            openSectionViaMore(title)
        }
    }

    @Test
    fun phone() {
        app().everyScreen("phone")
    }

    @Test
    @Config(qualifiers = WIDE)
    fun wide() {
        app().everyScreen("wide")
    }

    @Test
    @Config(qualifiers = NARROW)
    fun narrow() {
        app().everyScreen("narrow")
    }

    @Test
    fun androidAccess() {
        val robot = app {
            atWork()
            systemScreens.access[DeviceAccess.MEDIA] = DeviceAccessState.SELECTED_ONLY
            systemScreens.access[DeviceAccess.TERMUX_RUN_COMMAND] = DeviceAccessState.NOT_ALLOWED
        }.openSectionViaMore("Tools")
        robot.scrollListTo("Photos and videos (Android permission)")
        shoot("p5_tools_10_android_access_media_phone")
        robot.scrollListTo("Run commands in Termux (Termux permission)")
        shoot("p5_tools_10_android_access_termux_phone")
    }

    @Test
    fun phoneLight() {
        app(dark = false).openSectionViaMore("Tools").tap("Folders")
        shoot("p5_tools_light_3_folders_phone")
    }

    @Test
    fun screenControlStates() {
        val robot = app {
            atWork()
            screen.setState(ScreenServiceState.RESTRICTED)
        }.openSectionViaMore("Tools").tap("Screen")
        shoot("p5_tools_6_screen_restricted_phone")
        robot.app.screen.setState(ScreenServiceState.READY)
        robot.waitUntilDescribed("Step 4, Turn on AI Control Center in Accessibility, Done").scrollListTo("Who may use it".uppercase())
        shoot("p5_tools_7_screen_app_phone")
        robot.scrollListTo("Authorise an app").tap("Authorise an app").waitUntilShown("Never operated")
        shootScreen("p5_tools_9_app_picker_phone")
        robot.tapInDialog("Terminal").waitUntilShown("Authorise Terminal?")
        shootScreen("p5_tools_9b_authorise_confirm_phone")
        robot.tapInDialog("Back").tapInDialog("Cancel").scrollListTo("Stop screen control").tap("Stop screen control").waitUntilShown("Resume screen control")
        shoot("p5_tools_8_screen_stopped_phone")
    }

    @Test
    @Config(qualifiers = NARROW)
    fun screenControlRestrictedNarrow() {
        app { atWork(); screen.setState(ScreenServiceState.NOT_ENABLED) }.openSectionViaMore("Tools").tap("Screen")
        shoot("p5_tools_6_screen_off_narrow")
    }

    // --- Screen control: "Set up phone control", with none, some and every step done ---

    /** Nothing set up: both switches off, no app, and the service blocked by restricted settings (installed from a file). */
    private suspend fun TestApp.noneDone() {
        screen.setState(ScreenServiceState.RESTRICTED)
    }

    /** The two switches on and Notes authorised; Android's side (steps 3 and 4) not done yet. */
    private suspend fun TestApp.someDone() {
        settings.update { it.copy(operatorEnabled = true, accessibilityOperatorEnabled = true) }
        installSampleApps()
        graph.ownerOperatorApps.authorise("org.example.notes", "Notes", "3f9a1c0be2d47a5581c6e0f2b9d3a4e7c1f08d2b6a5e9c3d7f1a0b8e2c4d6f81")
        screen.setState(ScreenServiceState.RESTRICTED)
    }

    /** Every step done: the phone operator in use, with the service on. */
    private suspend fun TestApp.allDone() {
        atWork()
        screen.setState(ScreenServiceState.READY)
    }

    /** Opens Tools › Screen on [world] and shoots the checklist as [name], waiting for its [summary] first. */
    private fun setUpPicture(name: String, summary: String, dark: Boolean = true, world: suspend TestApp.() -> Unit): AppRobot {
        val robot = app(dark) { world() }.openSectionViaMore("Tools").tap("Screen").waitUntilShown(summary)
        shoot(name)
        return robot
    }

    /** At 360 dp and font scale 2.0 the card is taller than the screen: steps 3, 4 and 5 each in view, then its end. */
    private fun AppRobot.restOfCard(name: String) {
        for (step in 3..5) {
            compose.onNode(hasContentDescription("Step $step, ", substring = true)).performScrollTo()
            compose.waitForIdle()
            shoot("${name}_${step - 1}")
        }
        // The card's end: Check again while the service is not on, else the Stop card below it.
        val checkAgain = compose.onAllNodes(hasText("Check again") and hasClickAction())
        if (checkAgain.fetchSemanticsNodes().isNotEmpty()) checkAgain.onFirst().performScrollTo() else scrollListTo("Stop screen control")
        compose.waitForIdle()
        shoot("${name}_5")
    }

    @Test
    fun setupNoneDone() {
        setUpPicture("p6_screen_setup_1_none_phone", "0 of 5 done") { noneDone() }
    }

    @Test
    fun setupSomeDone() {
        setUpPicture("p6_screen_setup_2_some_phone", "3 of 5 done") { someDone() }
    }

    @Test
    fun setupAllDone() {
        setUpPicture("p6_screen_setup_3_all_phone", SETUP_ALL_SET) { allDone() }
    }

    @Test
    fun setupSomeDoneLight() {
        setUpPicture("p6_screen_setup_2_some_phone_light", "3 of 5 done", dark = false) { someDone() }
    }

    @Test
    @Config(qualifiers = NARROW_SETUP, fontScale = 2.0f)
    fun setupNoneDoneLargestFont() {
        setUpPicture("p6_screen_setup_1_none_360dp_font200", "0 of 5 done") { noneDone() }.restOfCard("p6_screen_setup_1_none_360dp_font200")
    }

    @Test
    @Config(qualifiers = NARROW_SETUP, fontScale = 2.0f)
    fun setupSomeDoneLargestFont() {
        setUpPicture("p6_screen_setup_2_some_360dp_font200", "3 of 5 done") { someDone() }.restOfCard("p6_screen_setup_2_some_360dp_font200")
    }

    @Test
    @Config(qualifiers = NARROW_SETUP, fontScale = 2.0f)
    fun setupAllDoneLargestFont() {
        setUpPicture("p6_screen_setup_3_all_360dp_font200", SETUP_ALL_SET) { allDone() }.restOfCard("p6_screen_setup_3_all_360dp_font200")
    }

    @Test
    @Config(qualifiers = NARROW_SETUP, fontScale = 2.0f)
    fun setupSomeDoneLargestFontLight() {
        setUpPicture("p6_screen_setup_2_some_360dp_font200_light", "3 of 5 done", dark = false) { someDone() }
            .restOfCard("p6_screen_setup_2_some_360dp_font200_light")
    }

    /** Android 12 and earlier: step 3 is not needed on this phone (the tab alone, as the full app runs on API 35 here). */
    @Test
    fun setupNotNeededBelowAndroid13() {
        val apps = emptyList<com.aicontrolcenter.app.ui.tools.OperatorAppUi>()
        val service = serviceStatusOf(ScreenServiceState.NOT_ENABLED)
        val state = ScreenControlUiState(
            isLoading = false, service = service, operatorEnabled = true, screenControlEnabled = false, apps = apps,
            checklist = setupChecklistOf(operatorEnabled = true, screenControlEnabled = false, service = service, apps = apps, sdkInt = 32),
        )
        val actions = ScreenControlActions({}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, { _, _, _ -> })
        compose.setContent {
            AppTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) { ScreenControlTab(state, actions) }
            }
        }
        compose.waitForIdle()
        shoot("p6_screen_setup_4_not_needed_phone")
    }

    @Test
    fun foldersAndPermissionsDialogs() {
        val robot = app().openSectionViaMore("Tools").tap("Folders")
        robot.tapIcon("Remove Documents")
        shootScreen("p5_tools_10_remove_folder_phone")
        robot.tapInDialog("Cancel").tap("Allowed").tap("Remove all")
        shootScreen("p5_tools_11_remove_all_phone")
    }

    @Test
    fun settingsAndAgentEditor() {
        val robot = app().openSectionViaMore("Settings").scrollTo("Screen control")
        shoot("p5_settings_phone_operator_phone")
        robot.openSection("Agents").scrollListTo("Carries out approved actions").tapItemContaining("Carries out approved actions")
            .tapIcon("Edit agent").scrollTo("Switching a tool on makes it available").tap("Workspace").scrollTo("Make a workspace folder")
        shoot("p5_agent_editor_tool_families_phone")
    }

    @Test
    @Config(qualifiers = WIDE)
    fun settingsWide() {
        app().openSection("Settings").scrollTo("Screen control")
        shoot("p5_settings_phone_operator_wide")
    }

    @Test
    fun emptyTools() {
        val robot = app { }.openSectionViaMore("Tools").tap("Folders")
        shoot("p5_tools_12_folders_empty_phone")
        robot.tap("History")
        shoot("p5_tools_13_requests_empty_phone")
        robot.tap("All tools").scrollListTo("Open the workspace").tap("Open the workspace").waitUntilShown("The workspace is empty")
        shoot("p5_workspace_4_empty_phone")
    }
}

/** The 360 dp phone of the setup checklist's pictures (as wave 3's, rule 7). */
private const val NARROW_SETUP = "w360dp-h740dp-xhdpi"
