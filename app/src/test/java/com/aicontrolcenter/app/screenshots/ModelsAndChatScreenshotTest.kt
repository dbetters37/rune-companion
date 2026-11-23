package com.aicontrolcenter.app.screenshots

import com.aicontrolcenter.app.testing.populatePhaseOne
import com.aicontrolcenter.framework.llm.Simulator
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.GatedLlmClient
import com.aicontrolcenter.app.testing.SampleUrls
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.populateWithProviders
import com.aicontrolcenter.app.testing.sampleResearcherClient
import com.aicontrolcenter.app.testing.serveModel
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the Phase 2 screens and states: providers, models, the agent changes, chat and
 * the dashboard's live state. Recorded with `:app:recordRoborazziDebug` into
 * `build/outputs/roborazzi`, for reviewing the design.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ModelsAndChatScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    private fun app(dark: Boolean = true, populate: suspend TestApp.() -> Unit = { populateWithProviders() }): AppRobot =
        AppRobot(compose).given(populate).launch(dark).also { robot = it }

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    private fun shootScreen(name: String) = captureScreenRoboImage("build/outputs/roborazzi/$name.png")

    /** Starts a turn that runs on in the background, the way one does after the owner sends a message. */
    private fun TestApp.sendInBackground(conversation: ConversationId, text: String) {
        CoroutineScope(Dispatchers.Unconfined).launch { graph.agentChat.send(conversation, text) }
    }

    // --- Providers ---

    @Test
    fun providers() {
        val robot = app().openSectionViaMore("Models")
        shoot("p2_providers_1_list")
        robot.scrollListTo("OpenRouter")
        shoot("p2_providers_2_list_end")
    }

    @Test
    fun providersLight() {
        app(dark = false).openSectionViaMore("Models")
        shoot("p2_providers_light")
    }

    @Test
    fun localServers() {
        val robot = app {
            populateWithProviders()
            adaptersByUrl["http://127.0.0.1:8080/v1"] = FakeProviderAdapter(models = listOf(RemoteModel("gemma-3-4b-it-q4")))
        }.openSectionViaMore("Models")
        robot.tap("Find servers on this phone")
        shootScreen("p2_providers_3_local_servers")
    }

    @Test
    fun presetGallery() {
        val robot = app().openSectionViaMore("Models").tap("Add provider")
        shoot("p2_presets_1_top")
        robot.scrollListTo("Custom OpenAI-compatible server")
        shoot("p2_presets_2_bottom")
    }

    @Test
    fun providerFormAdd() {
        val robot = app().openSectionViaMore("Models").tap("Add provider").scrollListTo("GPT models").tapItemContaining("GPT models")
        shoot("p2_provider_form_1_openai")
        robot.type("Name", "Anthropic").type("Base URL", "http://api.openai.com/v1").type("Extra headers", "Authorization: Bearer x")
        robot.scrollTo("Extra headers")
        shoot("p2_provider_form_2_issues")
    }

    @Test
    fun providerSetup() {
        val robot = app {
            populateWithProviders()
            adaptersByUrl["http://127.0.0.1:8080/v1"] = FakeProviderAdapter(models = listOf(RemoteModel("gemma-3-4b-it-q4"), RemoteModel("phi-4-mini")))
        }.openSectionViaMore("Models").tap("Add provider").tapItemContaining("A llama.cpp server")
        robot.scrollTo("Add and connect").tap("Add and connect")
        shoot("p2_provider_setup_1_success")
        robot.tap("Open provider").back().tap("Add provider").scrollListTo("Ollama on the local network").tapItemContaining("Ollama on a computer")
        robot.scrollTo("Add and connect").tap("Add and connect")
        shoot("p2_provider_setup_2_offline")
    }

    @Test
    fun providerFormEdit() {
        app().openSectionViaMore("Models").tapItemContaining("Anthropic · 1 model").tapIcon("Edit provider")
        shoot("p2_provider_form_3_edit")
    }

    @Test
    fun providerDetail() {
        val robot = app().openSectionViaMore("Models").tapItemContaining(SampleUrls.PHONE)
        shoot("p2_provider_detail_1_top")
        robot.tap("Check now").tap("Refresh models").scrollTo("Model list refreshed")
        shoot("p2_provider_detail_2_after_refresh")
        robot.scrollTo("Remove provider").tap("Remove provider")
        shootScreen("p2_provider_detail_3_remove")
    }

    @Test
    fun providerDetailProblems() {
        val robot = app().openSectionViaMore("Models").tapItemContaining(SampleUrls.DESK)
        shoot("p2_provider_detail_4_offline")
        robot.back().tapItemContaining("Simulator: not a real model ·")
        shoot("p2_provider_detail_5_simulator")
    }

    // --- Models ---

    @Test
    fun modelsTab() {
        val robot = app().openSectionViaMore("Models").tap("Models · 5")
        shoot("p2_models_1_grouped")
        robot.scrollListTo("Qwen2.5 Coder 14B")
        shoot("p2_models_2_more")
        robot.scrollListTo("ANTHROPIC").tap("Add model").scrollListTo("Add a model to")
        shoot("p2_models_3_add_by_name")
    }

    @Test
    fun modelEditor() {
        val robot = app().openSectionViaMore("Models").tap("Models · 5").tapItemContaining("Llama 3.2 3B")
        shoot("p2_model_editor_1_top")
        robot.scrollTo("Longest reply (tokens)")
        shoot("p2_model_editor_2_capabilities")
        robot.back().scrollListTo("simulator-1").tapItemContaining("simulator-1")
        shoot("p2_model_editor_3_simulator")
    }

    // --- Agents ---

    @Test
    fun agentsWithoutModels() {
        val robot = app { graph.start().join() }.openSection("Agents")
        shoot("p2_agents_1_without_model")
        robot.tap("Assign a model to agents without one")
        shootScreen("p2_agents_2_pick_model")
        robot.tapItemContaining("Simulator (not a real model)")
        shootScreen("p2_agents_3_confirm")
    }

    @Test
    fun agentEditor() {
        val robot = app().openSection("Agents").tapItemContaining("Turns objectives into plans").tapIcon("Edit agent")
            .scrollTo("Leaves this device")
        shoot("p2_agent_editor_1_cloud_warning")
        robot.scrollTo("Team members")
        shoot("p2_agent_editor_2_tools")
        robot.back().back().scrollListTo("Organises material").tapItemContaining("Organises material").tapIcon("Edit agent").scrollTo("Replies are scripted")
        shoot("p2_agent_editor_3_simulator")
    }

    @Test
    fun agentDetailAtWork() {
        val gated = GatedLlmClient(listOf("Here is what I found so far"))
        val robot = app {
            populateWithProviders(phoneClient = gated)
            val researcher = agentNamed("Researcher").profile
            val conversation = graph.agentChat.startConversation(researcher.id)
            sendInBackground(conversation.id, "Summarise the latest notes")
        }.openSection("Agents").tapItemContaining("Investigates subjects")
        shoot("p2_agent_detail_working")
        robot.tap("Chat")
        shoot("p2_conversations_running")
    }

    // --- Chat ---

    @Test
    fun conversationsEmpty() {
        val robot = app().openSection("Agents").tapItemContaining("Investigates subjects").tap("Chat")
        shoot("p2_conversations_empty")
        robot.tap("New conversation")
        shoot("p2_chat_1_new")
    }

    @Test
    @Config(qualifiers = NARROW)
    fun chatOnANarrowPhone() {
        app {
            populatePhaseOne()
            graph.providerManager.ensureBuiltIns()
            graph.agentManager.assignModel(agentNamed("Researcher").profile.id, Simulator.MODEL_ID)
        }.openSection("Agents").tapItemContaining("Investigates subjects").tap("Chat").tap("New conversation")
        shoot("p2_chat_narrow_simulator")
    }

    private fun chatTranscript(dark: Boolean): AppRobot = app(dark) {
        populateWithProviders(phoneClient = sampleResearcherClient())
        val conversation = graph.agentChat.startConversation(agentNamed("Researcher").profile.id)
        graph.agentChat.send(conversation.id, "What time is it in Berlin, and who is on the team?")
    }.openSection("Agents").tapItemContaining("Investigates subjects").tap("Chat").tapItemContaining("What time is it in Berlin")

    @Test
    fun chat() {
        val robot = chatTranscript(dark = true)
        shoot("p2_chat_2_transcript")
        robot.tapItemContaining("Team members")
        shoot("p2_chat_3_tool_expanded")
        robot.tapIcon("Conversation options")
        shootScreen("p2_chat_4_menu")
        robot.tap("Rename")
        shoot("p2_chat_4b_rename")
    }

    @Test
    fun chatLight() {
        chatTranscript(dark = false)
        shoot("p2_chat_light")
    }

    @Test
    fun chatStreaming() {
        val gated = GatedLlmClient(
            listOf("The notes cover three topics: the budget, the hiring plan and"),
            reasoning = "The owner wants a short summary. The notes have three sections; I will list them in order.",
            streamReasoning = true,
        )
        app {
            populateWithProviders(phoneClient = gated)
            val conversation = graph.agentChat.startConversation(agentNamed("Researcher").profile.id)
            sendInBackground(conversation.id, "Summarise the latest meeting notes in three bullet points.")
        }.openSection("Agents").tapItemContaining("Investigates subjects").tap("Chat").tapItemContaining("Summarise the latest")
        shoot("p2_chat_5_streaming")
    }

    @Test
    fun chatFailures() {
        val robot = app {
            populateWithProviders()
            val rejected = serveModel(
                ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Authentication("The server rejected the API key (401)."))),
                name = "Office gateway", baseUrl = "http://192.168.1.50:4000/v1", modelName = "office-large", displayName = "Office Large",
                apiKey = "sk-office-0000000000000000",
            )
            val reviewer = agentNamed("Reviewer").profile
            graph.agentManager.assignModel(reviewer.id, rejected.id)
            val conversation = graph.agentChat.startConversation(reviewer.id)
            graph.agentChat.send(conversation.id, "Review the draft I sent you.")
        }.openSection("Agents").scrollListTo("Checks results against").tapItemContaining("Checks results against").tap("Chat")
            .tapItemContaining("Review the draft")
        shoot("p2_chat_6_failed_credentials")
        robot.tap("Check Office gateway")
        shoot("p2_chat_7_after_check")
    }

    @Test
    fun chatCutOff() {
        app {
            populateWithProviders()
            val model = serveModel(CutOffClient, name = "Laptop", baseUrl = "http://192.168.1.60:11434", modelName = "mistral:7b", displayName = "Mistral 7B")
            val writer = agentNamed("Writer").profile
            graph.agentManager.assignModel(writer.id, model.id)
            val conversation = graph.agentChat.startConversation(writer.id)
            graph.agentChat.send(conversation.id, "Draft the announcement for the new office.")
        }.openSection("Agents").scrollListTo("Organises material").tapItemContaining("Organises material").tap("Chat")
            .tapItemContaining("Draft the announcement")
        shoot("p2_chat_8_cut_off")
    }

    @Test
    fun chatBlocked() {
        val robot = app {
            populateWithProviders()
            graph.agentChat.startConversation(director().profile.id, "Quarterly planning")
            settings.update { it.copy(localOnlyMode = true) }
        }.openSection("Agents").tapItemContaining("Turns objectives into plans").tap("Chat").tapItemContaining("Quarterly planning")
        shoot("p2_chat_9_local_only")
    }

    @Test
    fun chatProviderOffline() {
        app().openSection("Agents").tapItemContaining("Compares options").tap("Chat").tap("New conversation")
        shoot("p2_chat_10_provider_offline")
    }

    // --- Dashboard and settings ---

    @Test
    fun dashboardQuickStart() {
        val robot = app { graph.start().join() }
        shoot("p2_dashboard_1_quick_start")
        robot.tap("Try with the simulator")
        shootScreen("p2_dashboard_2_simulator_confirm")
    }

    @Test
    fun dashboardLive() {
        val gated = GatedLlmClient(listOf("Working on it"))
        val robot = app {
            populateWithProviders(phoneClient = gated)
            val conversation = graph.agentChat.startConversation(agentNamed("Researcher").profile.id)
            sendInBackground(conversation.id, "Look into the supplier list")
        }
        shoot("p2_dashboard_3_top")
        robot.scrollTo("WORKING NOW")
        shoot("p2_dashboard_4_working")
        robot.scrollTo("A ringed agent is working")
        shoot("p2_dashboard_5_network")
    }

    @Test
    @Config(qualifiers = WIDE)
    fun dashboardWide() {
        app()
        shoot("p2_dashboard_wide")
    }

    @Test
    fun settingsLocalOnly() {
        val robot = app().openSectionViaMore("Settings")
        shoot("p2_settings_1_local_only_off")
        robot.tapItemContaining("Local-only mode")
        shoot("p2_settings_2_local_only_on")
    }
}

/** A model whose every reply reaches its output limit. */
private object CutOffClient : LlmClient {
    override suspend fun chat(request: ChatRequest): ChatResponse = ChatResponse(
        ChatMessage.assistant(
            "We are delighted to announce that from the first of next month our team moves into the new office on Harbour " +
                "Street. The building offers more room for collaboration, a quiet floor for focused work, and",
        ),
        FinishReason.LENGTH,
        TokenUsage(412, 48),
        modelName = "mistral:7b",
    )
}
