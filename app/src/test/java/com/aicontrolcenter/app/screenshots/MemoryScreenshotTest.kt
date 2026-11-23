package com.aicontrolcenter.app.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.SampleMemories
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.populateWithMemories
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
 * Pictures of every memory screen over a realistic memory: agents' private memories saved in
 * chats, the owner's own notes, two shared spaces, a shared copy, a contradiction and task
 * memory. Recorded with `:app:recordRoborazziDebug` into `build/outputs/roborazzi`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class MemoryScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null
    private lateinit var sample: SampleMemories

    private fun app(dark: Boolean = true, populate: suspend TestApp.() -> Unit = { sample = populateWithMemories() }): AppRobot =
        AppRobot(compose).given(populate).launch(dark).also { robot = it }

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    private fun shootScreen(name: String) = captureScreenRoboImage("build/outputs/roborazzi/$name.png")

    // --- Explorer ---

    @Test
    fun explorer() {
        val robot = app().openSectionViaMore("Memory")
        shoot("memory_explorer_1_list")
        robot.type("Search memories", "lisbon trains").waitUntilShown("% match")
        shoot("memory_explorer_2_search")
        robot.tapIcon("Clear search").tap("Research notes")
        shoot("memory_explorer_3_space")
    }

    @Test
    fun explorerLight() {
        app(dark = false).openSectionViaMore("Memory")
        shoot("memory_explorer_light")
    }

    @Test
    @Config(qualifiers = NARROW)
    fun explorerNarrow() {
        app().openSectionViaMore("Memory")
        shoot("memory_explorer_narrow")
    }

    @Test
    fun explorerEmpty() {
        app { graph.start().join() }.openSectionViaMore("Memory")
        shoot("memory_explorer_empty")
    }

    // --- One memory ---

    @Test
    fun memoryDetail() {
        val robot = app().openSectionViaMore("Memory").tapItemContaining("Prefers summaries as short bullet points")
        shoot("memory_detail_1_top")
        robot.scrollTo("Recorded")
        shoot("memory_detail_2_provenance")
        robot.scrollTo("Add link").tap("Add link")
        robot.scrollTo("Find a memory by title")
        shoot("memory_detail_3_add_link")
        robot.scrollTo("Delete memory")
        shoot("memory_detail_4_bottom")
        robot.tap("Delete memory")
        shootScreen("memory_detail_5_delete")
    }

    @Test
    fun memoryDetailShare() {
        val robot = app().openSectionViaMore("Memory").scrollListTo("Grocery spending rose 12% in Q3").tapItemContaining("Grocery spending rose 12% in Q3")
        robot.scrollTo("Contradicted by")
        shoot("memory_detail_contradiction")
        robot.scrollTo("Share to space").tap("Share to space")
        shootScreen("memory_detail_share")
    }

    @Test
    fun memoryEditor() {
        val robot = app().openSectionViaMore("Memory").tapIcon("New memory")
        shoot("memory_editor_1_new")
        robot.tap("Save memory")
        robot.scrollTo("Choose where this memory goes.")
        shoot("memory_editor_2_errors")
    }

    @Test
    fun memoryEditorEdit() {
        val robot = app().openSectionViaMore("Memory").scrollListTo("Lisbon trip, 12 to 16 March").tapItemContaining("Lisbon trip, 12 to 16 March").tapIcon("Edit memory")
        shoot("memory_editor_3_edit")
        robot.scrollTo("Short-term")
        shoot("memory_editor_4_details")
    }

    // --- Spaces ---

    @Test
    fun spaces() {
        val robot = app().openSectionViaMore("Memory").tap("Spaces · 2")
        shoot("memory_spaces_1_list")
        robot.tapIcon("New space").type("Space name", "Household")
        shoot("memory_spaces_2_new")
    }

    @Test
    fun spaceDetail() {
        val robot = app().openSectionViaMore("Memory").tap("Spaces · 2").tapItemContaining("Research notes")
        shoot("memory_space_1_top")
        robot.scrollTo("Delete space")
        shoot("memory_space_2_access")
        robot.tap("Delete space")
        shootScreen("memory_space_3_delete")
        robot.tapInDialog("Cancel").scrollTo("Rename").tap("Rename")
        shoot("memory_space_4_rename")
    }

    // --- Graph ---

    @Test
    fun graph() {
        val robot = app().openSectionViaMore("Memory").tap("Graph").waitForGraph()
        shoot("memory_graph_1")
        robot.tapGraphMemory(sample.lisbonTrip)
        shoot("memory_graph_2_selected")
        robot.tapIcon("Zoom in").tapIcon("Zoom in")
        shoot("memory_graph_3_zoomed")
    }

    @Test
    fun graphLight() {
        val robot = app(dark = false).openSectionViaMore("Memory").tap("Graph").waitForGraph()
        robot.tapGraphMemory(sample.groceriesUp)
        shoot("memory_graph_light")
    }

    @Test
    @Config(qualifiers = NARROW)
    fun graphNarrow() {
        app().openSectionViaMore("Memory").tap("Graph").waitForGraph()
        shoot("memory_graph_narrow")
    }

    @Test
    fun graphFilteredAndEmpty() {
        val robot = app().openSectionViaMore("Memory").tap("Graph").waitForGraph().tap("Analyst")
        robot.waitUntilShown("2 memories")
        shoot("memory_graph_4_filtered")
        robot.tap("Analyst").tap("Reviewer").waitUntilShown("Nothing to draw")
        shoot("memory_graph_5_nothing")
    }

    // --- Elsewhere in the app ---

    @Test
    fun agentMemory() {
        val robot = app().openSection("Agents").tapItemContaining("Investigates subjects")
        robot.scrollTo("Open its memories")
        shoot("memory_agent_detail")
        robot.tapIcon("Edit agent").scrollTo("Recall automatically")
        robot.scrollTo("Shared spaces")
        shoot("memory_agent_editor")
    }

    @Test
    fun dashboardAndSettings() {
        val robot = app()
        shoot("memory_dashboard_tile")
        robot.openSectionViaMore("Settings").scrollTo("Short-term memory")
        shoot("memory_settings_card")
    }
}
