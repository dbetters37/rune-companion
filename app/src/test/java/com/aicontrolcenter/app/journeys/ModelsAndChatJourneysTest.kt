package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.testkit.FakeProviderAdapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Phase 2 journeys: connecting a provider, talking to an agent, and losing a provider. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ModelsAndChatJourneysTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var robot: AppRobot
    private val app: TestApp get() = robot.app

    @Before
    fun startApp() {
        robot = AppRobot(compose).given { graph.start().join() }
    }

    @After
    fun stopApp() = robot.close()

    @Test
    fun `add a provider from a preset and see its models`() {
        app.adaptersByUrl["http://127.0.0.1:11434"] = FakeProviderAdapter(
            models = listOf(RemoteModel("llama3.2:3b", displayName = "Llama 3.2 3B"), RemoteModel("qwen3:1.7b")),
        )
        robot.launch()
            .openSectionViaMore("Models")
            .tap("Add provider")
            .assertShown("ON THIS PHONE")
            .tapItemContaining("Models served by Ollama running on this phone")
            .assertShown("Base URL")
            .assertShown("http://127.0.0.1:11434")
            .assertShown("On this device")
            .scrollTo("Add and connect")
            .tap("Add and connect")
            .assertShown("Connecting Ollama on this phone")
            .assertShown("Ollama on this phone is online")
            .assertShown("2 models registered. 2 added, 0 updated, 0 no longer listed.")
            .tap("Open provider")
            .assertShown("MODELS · 2")
            .assertShown("Llama 3.2 3B")
            .assertShown("qwen3:1.7b")

        // Back leaves the adding flow behind and lands on the list, where the models are grouped by provider.
        robot.back().assertShown("Providers · 2").tap("Models · 3")
            .assertShown("OLLAMA ON THIS PHONE")
            .assertShown("Llama 3.2 3B")
        val provider = runBlocking { app.providers.getAll() }.first { it.name == "Ollama on this phone" }
        assertEquals(listOf("llama3.2:3b", "qwen3:1.7b"), runBlocking { app.models.getByProvider(provider.id) }.map { it.modelName })
    }

    @Test
    fun `try the simulator and chat with an agent, tool call included`() {
        robot.launch()
            .tap("Try with the simulator")
            .assertShown("It is not a real model")
            .tapInDialog("Use the simulator")
        assertTrue(runBlocking { app.agents.getAll() }.all { it.modelId == Simulator.MODEL_ID })

        robot.openSection("Agents")
            .tapItemContaining("Investigates subjects")
            .tap("Chat")
            .assertShown("No conversations yet")
            .tap("New conversation")
            .assertShown("Talk to Researcher")
            .assertShown("Stays on this device")
            .assertShown("Simulator: not a real model")
            .type("Message Researcher", "What time is it?")
            .tapIcon("Send")
            .assertShown("What time is it?")
            .assertShown("Current time")
            .assertShown("Simulated reply")
            .assertShown("Answered by simulator-1")

        // Leaving and coming back shows the same conversation, named after its first message.
        robot.back().assertShown("What time is it?").assertShown("CONVERSATIONS · 1")
        val conversation = runBlocking { app.conversations.observeConversations(null).first() }.single()
        val entries = runBlocking { app.conversations.getEntries(conversation.id) }
        assertTrue(entries.any { it.message.toolCalls.any { call -> call.name == "clock_now" } }, "the simulator asked for the clock")
    }

    @Test
    fun `remove a provider and see the agent that used it flagged`() {
        robot.given {
            registerSampleModel()
            graph.agentManager.assignModel(agentNamed("Researcher").profile.id, ModelId("mdl_sample"))
        }.launch()
            .openSectionViaMore("Models")
            .tapItemContaining("http://192.168.1.20:8080/v1")
            .scrollTo("Remove provider")
            .tap("Remove provider")
            .assertShown("Remove Home server?")
            .assertShown("Researcher would be left without a model")
            .tapInDialog("Remove")
            .assertShown("Providers · 1")

        val availability = runBlocking { app.agentNamed("Researcher") }.availability
        assertEquals(UnavailableReason.MODEL_NOT_REGISTERED, assertIs<AgentAvailability.Unavailable>(availability).reason)

        robot.openSection("Dashboard")
            .assertShown("Researcher's model was removed")
            .tapItemContaining("Researcher's model was removed")
            .assertShown("Model not registered")
    }
}
