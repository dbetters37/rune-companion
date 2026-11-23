package com.aicontrolcenter.app.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.populatePhaseOne
import com.aicontrolcenter.app.testing.populateWithModels
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
 * Pictures of every screen and major state, taken from the real app over realistic registry
 * contents. Recorded with `:app:recordRoborazziDebug` into `build/outputs/roborazzi`; they are
 * the material for reviewing the design, not golden files that fail a build.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    private fun app(dark: Boolean = true, populate: suspend TestApp.() -> Unit = { populatePhaseOne() }): AppRobot =
        AppRobot(compose).given(populate).launch(dark).also { robot = it }

    @After
    fun stopApp() {
        robot?.close()
    }

    /** One window: the screen itself. */
    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    /** Everything on the display, for states with a dialog or sheet (a second window) on top. */
    private fun shootScreen(name: String) = captureScreenRoboImage("build/outputs/roborazzi/$name.png")

    // --- Dashboard ---

    @Test
    fun dashboard() {
        val robot = app()
        shoot("dashboard_1_top")
        robot.scrollTo("Tap an agent to see its role and status.")
        robot.tapIcon("Researcher, Specialist, No model yet")
        shoot("dashboard_2_network")
        robot.scrollToEnd()
        shoot("dashboard_3_bottom")
    }

    @Test
    fun dashboardLight() {
        app(dark = false)
        shoot("dashboard_light")
    }

    @Test
    @Config(qualifiers = WIDE)
    fun dashboardWide() {
        app()
        shoot("dashboard_wide")
    }

    @Test
    fun dashboardWithModels() {
        val robot = app { populateWithModels() }
        shoot("dashboard_models_1_top")
        robot.scrollTo("Tap an agent to see its role and status.")
        shoot("dashboard_models_2_network")
    }

    @Test
    fun dashboardEmpty() {
        app { }
        shoot("dashboard_empty")
    }

    // --- Agents ---

    @Test
    fun agents() {
        val robot = app().openSection("Agents")
        shoot("agents_list")
        robot.type("Search agents", "nobody")
        shoot("agents_no_match")
    }

    @Test
    fun agentsLight() {
        app(dark = false).openSection("Agents")
        shoot("agents_list_light")
    }

    @Test
    fun agentsWithModels() {
        app { populateWithModels() }.openSection("Agents")
        shoot("agents_list_models")
    }

    @Test
    @Config(qualifiers = WIDE)
    fun agentsWide() {
        app { populateWithModels() }.openSection("Agents")
        shoot("agents_list_wide")
    }

    @Test
    fun agentCreation() {
        val robot = app().openSection("Agents").tapIcon("New agent")
        shoot("agent_templates")
        robot.tapItemContaining("Researcher")
        shoot("agent_editor_1_top")
        robot.type("Name", "Analyst")
        shoot("agent_editor_2_name_taken")
        robot.scrollTo("No model assigned. The agent cannot run without one.")
        shoot("agent_editor_3_model_none")
        robot.scrollTo("Create agent")
        shoot("agent_editor_4_bottom")
        robot.back()
        shootScreen("agent_editor_5_discard")
    }

    @Test
    fun agentEditorWithModels() {
        val robot = app { populateWithModels() }.openSection("Agents")
            .tapItemContaining("Investigates subjects").tapIcon("Edit agent")
            .scrollTo("Tried in order when the primary model is unavailable.")
        shoot("agent_editor_models")
        robot.tap("Change model")
        shootScreen("agent_editor_model_picker")
    }

    @Test
    fun agentDetail() {
        val robot = app().openSection("Agents").tapItemContaining("Turns objectives into plans")
        shoot("agent_detail_1_top")
        robot.scrollTo("No model yet")
        robot.scrollTo("Maximum output tokens")
        shoot("agent_detail_2_middle")
        robot.scrollTo("Delete agent")
        shoot("agent_detail_3_bottom")
        robot.tap("Delete agent")
        shootScreen("agent_detail_4_director_delete")
    }

    @Test
    fun agentDetailWithHistory() {
        val robot = app { populateWithModels() }.openSection("Agents").tapItemContaining("Investigates subjects")
        shoot("agent_detail_models_1_top")
        robot.scrollTo("Primary model")
        shoot("agent_detail_models_2_model")
        robot.scrollTo("Tokens in / out")
        shoot("agent_detail_models_3_track_record")
    }

    // --- Models ---

    @Test
    fun modelsEmpty() {
        val robot = app { graph.start().join() }.openSectionViaMore("Models")
        shoot("models_providers_empty")
        robot.tap("Models · 1")
        shoot("models_models_empty")
        robot.tap("AI apps · 0")
        shoot("models_apps_empty")
    }

    @Test
    fun modelsPopulated() {
        val robot = app { populateWithModels() }.openSectionViaMore("Models")
        shoot("models_providers")
        robot.tap("Models · 4")
        shoot("models_models")
    }

    @Test
    fun aiApps() {
        val robot = app().openSectionViaMore("Models").tap("AI apps · 4")
        shoot("models_apps_1_list")
        robot.given { installSampleApps() }.tap("Scan device")
        shoot("models_apps_2_after_scan")
        robot.scrollListTo("Confirm it is an AI app")
        shoot("models_apps_3_unverified")
    }

    @Test
    fun aiAppDetail() {
        val robot = app().openSectionViaMore("Models").tap("AI apps · 4")
            .scrollListTo("com.example.chatbot").tapItemContaining("com.example.chatbot")
        shoot("app_detail_1_top")
        robot.tap("Send text to app…")
        robot.type("Text to hand over", "Draft a polite reply declining the meeting on Thursday.")
        robot.scrollTo("Text to hand over")
        shoot("app_detail_2_send_text")
        robot.tap("Cancel").scrollTo("Remove from registry")
        shoot("app_detail_3_bottom")
        robot.tap("Remove from registry")
        shootScreen("app_detail_4_remove")
        robot.tapInDialog("Cancel").tapItemContaining("Allow accessibility operation")
        shootScreen("app_detail_5_accessibility_warning")
        robot.tapInDialog("Authorise (test)").scrollTo("Operate its screen: authorised, not active")
        shoot("app_detail_6_authorised_inactive")
    }

    @Test
    fun aiAppDetailProblemStates() {
        val robot = app().openSectionViaMore("Models").tap("AI apps · 4")
            .scrollListTo("io.unknown.aihelper").tapItemContaining("io.unknown.aihelper")
        shoot("app_detail_unverified")
        robot.back().scrollListTo("com.sample.assistant").tapItemContaining("com.sample.assistant")
        shoot("app_detail_not_installed")
    }

    @Test
    fun registerAppAndConcepts() {
        val robot = app().openSectionViaMore("Models").tap("AI apps · 4").tap("Register manually")
        shoot("register_app")
        robot.back().tapIcon("AI application, model or agent: what is the difference?")
        shoot("concepts")
    }

    // --- Activity ---

    @Test
    fun activity() {
        val robot = app().openSection("Activity")
        shoot("activity_1_log")
        robot.tapItemContaining("Text handed to another app")
        shootScreen("activity_2_event")
    }

    @Test
    fun activityFiltered() {
        val robot = app().openSection("Activity").tap("Left the device")
        shoot("activity_3_left_device")
        robot.tap("Errors only")
        shoot("activity_4_no_match")
    }

    // --- Settings ---

    @Test
    fun settings() {
        val robot = app().openSectionViaMore("Settings")
        shoot("settings_1_privacy")
        robot.scrollTo("Objective time-out")
        shoot("settings_2_limits")
        robot.scrollTo("Everything is stored on this device")
        shoot("settings_3_device_about")
        robot.scrollTo("Clear activity log").tap("Clear activity log")
        shootScreen("settings_4_clear_log")
    }

    // --- The section that is not built yet, and the shell ---

    @Test
    fun plannedSections() {
        app().openSectionViaMore("Tools (test)")
        shoot("planned_tools")
    }

    @Test
    fun moreSheet() {
        app().openMore()
        shootScreen("more_sheet")
    }

    @Test
    @Config(qualifiers = WIDE)
    fun settingsWide() {
        app().openSection("Settings")
        shoot("settings_wide")
    }
}

internal const val PHONE = "w411dp-h891dp-xhdpi"

/** A small tablet or an unfolded phone: wide enough for the navigation rail and two panes. */
internal const val WIDE = "w840dp-h900dp-xhdpi"

/** The narrowest common phone width (many Samsung models). */
internal const val NARROW = "w360dp-h780dp-xhdpi"
