package com.aicontrolcenter.app.journeys

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.SampleMemories
import com.aicontrolcenter.app.testing.populateWithMemories
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.memory.MemoryGraphJson
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The memory journeys, driven through the real shell, navigation, view models and memory engine. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class MemoryJourneysTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun start(populate: suspend com.aicontrolcenter.app.testing.TestApp.() -> Unit): AppRobot =
        AppRobot(compose).given(populate).launch().also { robot = it }

    @Test
    fun `create a space, let an agent read it, save a memory into it and find it by search`() {
        val robot = start { graph.start().join() }
        val app = robot.app

        robot.openSectionViaMore("Memory")
            .assertShown("No memories yet")
            .tap("Spaces · 1")
            .tapIcon("New space")
            .type("Space name", "Household")
            .tap("Create space")
            // Creating opens the space, so access can be given straight away.
            .assertShown("No description")
            .tapIcon("Writer may read")

        val writer = runBlocking { app.agentNamed("Writer").profile }
        val household = runBlocking { app.graph.memoryService.observeSpaces().first().single { it.name == "Household" } }
        assertTrue(household.id in writer.memory.readableSpaces)
        assertTrue(household.id !in writer.memory.writableSpaces, "reading does not grant writing")

        robot.tap("Its memories")
            .assertShown("No memories yet")
            .tapIcon("New memory")
            .assertShown("1 agent may read it.")
            .type("Title", "Boiler service is due in May")
            .type("What to remember", "The gas boiler was last serviced in May; the engineer's number is on the fridge.")
            .tap("Save memory")
            .assertShown("Written by you")
            .assertShown("The gas boiler was last serviced in May")
            .back()
            .tap("All memory")
            .type("Search memories", "boiler service")
            .waitUntilShown("% match")
            .assertShown("Boiler service is due in May")

        val saved = runBlocking { app.memories.list() }.single()
        assertEquals(MemoryScope.Shared(household.id), saved.scope)
        // The grant is real: the Writer itself now recalls it.
        val recalled = runBlocking {
            app.graph.memoryService.recall(MemoryActor.Agent(app.agentNamed("Writer").profile), RecallQuery("when is the boiler service"))
        }
        assertEquals(listOf(saved.id), recalled.map { it.record.id })
    }

    @Test
    fun `open the graph, tap a memory and open it`() {
        lateinit var sample: SampleMemories
        val robot = start { sample = populateWithMemories() }
        val app = robot.app

        robot.openSectionViaMore("Memory").tap("Graph").waitForGraph()
        robot.assertShown("pinch to zoom, tap a memory")
        robot.tapGraphMemory(sample.vegetarian)

        robot.assertShown("The owner is vegetarian").assertShown("Not linked to other memories in this view.")
            .tap("Open")
            .assertShown("No meat or fish")
            .assertShown("Written by you")
    }

    @Test
    fun `export the graph through the share sheet as aicc memory-graph JSON`() {
        val robot = start { populateWithMemories() }
        val app = robot.app

        robot.openSectionViaMore("Memory").tap("Graph").waitForGraph()
        robot.tap("Export graph")

        val application = ApplicationProvider.getApplicationContext<Application>()
        var chooser: Intent? = null
        robot.waitUntil { shadowOf(application).nextStartedActivity?.also { chooser = it } != null }
        val started = assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val send = assertNotNull(started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(MemoryGraphJson.MIME_TYPE, send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val uri = assertNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("${application.packageName}.exports", uri.authority)

        val file = File(File(application.cacheDir, "exports"), uri.lastPathSegment!!)
        val exported = MemoryGraphJson.decode(file.readText())
        assertEquals(runBlocking { app.memories.list() }.size, exported.nodes.size)
        robot.waitUntil { runBlocking { app.activityLog.recent(ActivityFilter(egressOnly = true)) }.any { it.title == "Memory graph exported" } }
    }

    @Test
    fun `an agent's page opens the explorer narrowed to its private memory`() {
        val robot = start { populateWithMemories() }

        robot.openSection("Agents")
            .tapItemContaining("Investigates subjects")
            .scrollTo("Open its memories")
            .assertShown("On, 3 memories")
            .tap("Open its memories")
            .assertShown("Lisbon trip, 12 to 16 March")
            .assertNotShown("The owner is vegetarian")
    }
}
