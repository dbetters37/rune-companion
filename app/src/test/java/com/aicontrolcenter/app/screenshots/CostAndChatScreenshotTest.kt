package com.aicontrolcenter.app.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.populateWithProviders
import com.aicontrolcenter.app.testing.serveModel
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.agents.AgentDraft
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.framework.tools.ClockTool
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of WP11b's Phase 6 screens (wave 3): the chat's cut-off notice with "Send again", its cost line and today's
 * limit, a long chat's "Show earlier"; the model editor's price, "Speed and reliability" and the cheaper model for
 * helpers; the agent editor's model tiers and tool token line; Tools › Screen when Android switched it off (D7) and
 * Tools › Folders with another app's folder and "Pick up new files"; the presets' cost lines. Each in dark (phone),
 * light, wide, 360 dp and, for the busiest, font scale 2.0. Recorded with `:app:recordRoborazziDebug` into
 * `build/outputs/roborazzi`, for review by eye, not compared.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class CostAndChatScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/p6_wp11b_$name.png")

    private fun app(dark: Boolean = true, populate: suspend TestApp.() -> Unit): AppRobot =
        AppRobot(compose).given(populate).launch(dark).also { robot = it }

    // --- Chat: the cut-off notice and "Send again" (R3) ---

    private suspend fun TestApp.cutOffChat() {
        val model = serveModel(ScriptedLlmClient.replies("High tide is at 14:20."))
        val agent = graph.agentManager.create(AgentDraft(name = "Tidewatch", kind = AgentKind.SPECIALIST, modelId = model.id, description = "Knows the tides."))
        val conversation = graph.agentChat.startConversation(agent.id, "Tides")
        conversations.appendEntry(ConversationEntry(conversation.id, -1, ChatMessage.user("When is high tide today, and how high?"), clock.now()))
        conversations.markTurnStarted(conversation.id, clock.now())
        graph.start().join()
    }

    private fun AppRobot.openCutOff() =
        openSection("Agents").tapItemContaining("Knows the tides.").tap("Chat").tapItemContaining("Tides").waitUntilShown("The app stopped")

    @Test
    fun chatCutOff() {
        app { cutOffChat() }.openCutOff()
        shoot("chat_cut_off")
    }

    @Test
    fun chatCutOffLight() {
        app(dark = false) { cutOffChat() }.openCutOff()
        shoot("chat_cut_off_light")
    }

    @Test
    @Config(qualifiers = WIDE_WAVE3)
    fun chatCutOffWide() {
        app { cutOffChat() }.openCutOff()
        shoot("chat_cut_off_wide")
    }

    @Test
    @Config(qualifiers = NARROW_WAVE3)
    fun chatCutOffNarrow() {
        app { cutOffChat() }.openCutOff()
        shoot("chat_cut_off_360")
    }

    @Test
    @Config(qualifiers = NARROW_WAVE3, fontScale = 2.0f)
    fun chatCutOffLargeFont() {
        app { cutOffChat() }.openCutOff()
        shoot("chat_cut_off_360_font2")
    }

    // --- Chat: the cost line, and today's limit reached ---

    private suspend fun TestApp.pricedChatAtTheLimit(limit: Int? = null): ModelId {
        if (limit != null) settings.update { it.copy(maxModelCallsPerDay = limit) }
        val model = serveModel(
            ScriptedLlmClient.script(ScriptStep.Reply("There are 14 apples in the basket.", TokenUsage(100_000, 20_000))),
            name = "Hosted", baseUrl = "https://api.example.com/v1", apiKey = "sk-test-0123456789abcdef", displayName = "Hosted model",
        )
        graph.ownerModelPrices.set(ModelPrice(model.id, 3_000_000, 15_000_000))
        graph.agentManager.create(AgentDraft(name = "Counter", kind = AgentKind.SPECIALIST, modelId = model.id, description = "Counts things."))
        graph.start().join()
        return model.id
    }

    private fun AppRobot.chatOnce() = openSection("Agents").tapItemContaining("Counts things.").tap("Chat").tap("New conversation")
        .type("Message Counter", "How many apples are in the basket?").tapIcon("Send")
        .waitUntilShown("about US$0.60")

    @Test
    fun chatCostAndLimit() {
        app(populate = { pricedChatAtTheLimit(limit = 1) }).chatOnce().waitUntilShown("Today's limit is reached")
        shoot("chat_cost_limit")
    }

    @Test
    fun chatCostLight() {
        app(dark = false, populate = { pricedChatAtTheLimit() }).chatOnce()
        shoot("chat_cost_light")
    }

    @Test
    @Config(qualifiers = NARROW_WAVE3)
    fun chatCostNarrow() {
        app(populate = { pricedChatAtTheLimit(limit = 1) }).chatOnce().waitUntilShown("Today's limit is reached")
        shoot("chat_cost_limit_360")
    }

    // --- Chat: a long conversation, newest 50 first (P5) ---

    @Test
    fun chatShowEarlier() {
        val robot = app {
            val model = serveModel(ScriptedLlmClient.replies("never"))
            val agent = graph.agentManager.create(AgentDraft(name = "Diarist", kind = AgentKind.SPECIALIST, modelId = model.id, description = "Keeps the diary."))
            val conversation = graph.agentChat.startConversation(agent.id, "The diary")
            repeat(60) { day ->
                conversations.appendEntry(ConversationEntry(conversation.id, -1, ChatMessage.user("What happened on day ${day + 1}?"), clock.now()))
                conversations.appendEntry(ConversationEntry(conversation.id, -1, ChatMessage.assistant("On day ${day + 1} the seeds were watered."), clock.now(), modelName = "scripted-1"))
            }
            graph.start().join()
        }.openSection("Agents").tapItemContaining("Keeps the diary.").tap("Chat").tapItemContaining("The diary")
            .waitUntilShown("day 60")
        robot.scrollListTo("Show earlier messages")
        shoot("chat_show_earlier")
    }

    // --- The model editor: price, speed and reliability, the cheaper model for helpers ---

    private suspend fun TestApp.pricedModels() {
        populateWithProviders()
        graph.agentManager.ensureDefaults()
        val claude = models.getAll().single { it.modelName == "claude-sonnet-4-5" }
        val llama = models.getAll().single { it.modelName == "llama3.2:3b" }
        for (agent in agents.getAll()) graph.agentManager.assignModel(agent.id, claude.id)
        graph.ownerModelPrices.set(ModelPrice(claude.id, 3_000_000, 15_000_000, 300_000))
        graph.ownerModelPrices.set(ModelPrice(llama.id, 100_000, 300_000))
        fun sample(outcome: CallOutcome, error: ErrorClass? = null, answerMs: Long? = null, ttftMs: Long? = null, waits: Int = 0) = ModelCallSample(
            at = clock.current, modelId = llama.id, modelLabel = llama.displayName, providerId = llama.providerId, providerLabel = "Ollama on this phone",
            locality = Locality.ON_DEVICE, source = UsageSource.CHAT, outcome = outcome, errorClass = error,
            inputTokens = if (outcome == CallOutcome.OK) 1_200 else null, outputTokens = if (outcome == CallOutcome.OK) 180 else null,
            answerMs = answerMs, ttftMs = ttftMs, rateLimitedWaits = waits,
        )
        repeat(6) { usage.record(sample(CallOutcome.OK, answerMs = 3_200, ttftMs = 700)) }
        usage.record(sample(CallOutcome.FAILED, ErrorClass.TIMEOUT))
        usage.record(sample(CallOutcome.OK, answerMs = 2_900, ttftMs = 650, waits = 1))
    }

    private fun AppRobot.openLlama(wide: Boolean = false) = (if (wide) openSection("Models") else openSectionViaMore("Models"))
        .tap("Models · 5").scrollListTo("Llama 3.2 3B").tapItemContaining("Llama 3.2 3B").waitUntilShown("Cheaper model for helpers")

    @Test
    fun modelEditorCost() {
        val robot = app { pricedModels() }.openLlama()
        shoot("model_editor_1_cheaper_price")
        robot.scrollTo("See usage and cost")
        shoot("model_editor_2_speed")
        robot.scrollTo("Coding")
        shoot("model_editor_3_tiers")
    }

    @Test
    fun modelEditorCostLight() {
        app(dark = false) { pricedModels() }.openLlama()
        shoot("model_editor_cost_light")
    }

    @Test
    @Config(qualifiers = WIDE_WAVE3)
    fun modelEditorCostWide() {
        app { pricedModels() }.openLlama(wide = true)
        shoot("model_editor_cost_wide")
    }

    @Test
    @Config(qualifiers = NARROW_WAVE3)
    fun modelEditorCostNarrow() {
        val robot = app { pricedModels() }.openLlama()
        shoot("model_editor_cost_360")
        robot.scrollTo("Save price")
        shoot("model_editor_price_360")
    }

    @Test
    @Config(qualifiers = NARROW_WAVE3, fontScale = 2.0f)
    fun modelEditorLargeFont() {
        val robot = app { pricedModels() }.openLlama()
        robot.scrollTo("Input, per million tokens")
        shoot("model_editor_price_360_font2")
    }

    @Test
    fun useForHelpers() {
        app { pricedModels() }.openLlama().tap("Use for helpers…").waitUntilShown("Use Llama 3.2 3B for…")
        shoot("model_editor_use_for_helpers")
    }

    @Test
    fun presetCostLines() {
        app { populateWithProviders() }.openSectionViaMore("Models").tap("Add provider").scrollListTo("xAI")
        shoot("presets_cost_lines")
    }

    // --- The agent editor: tiers in the model picker, the tools' token line ---

    @Test
    fun agentEditorTiersAndTokens() {
        val robot = app {
            populateWithProviders()
            graph.agentManager.ensureDefaults()
            graph.start().join()
        }.openSection("Agents").scrollListTo("Carries out approved actions").tapItemContaining("Carries out approved actions").tapIcon("Edit agent")
            .waitUntilShown("Instructions")
        robot.scrollTo("These tools")
        shoot("agent_editor_tool_tokens")
        robot.openModelPicker()
        shoot("agent_editor_model_tiers")
    }

    @Test
    @Config(qualifiers = NARROW_WAVE3)
    fun agentEditorTiersNarrow() {
        app {
            populateWithProviders()
            graph.agentManager.ensureDefaults()
            graph.start().join()
        }.openSection("Agents").scrollListTo("Carries out approved actions").tapItemContaining("Carries out approved actions").tapIcon("Edit agent")
            .openModelPicker()
        shoot("agent_editor_model_tiers_360")
    }

    /** "Change model" when the agent has one, else "Choose model"; the picker lists the models with their tiers. */
    private fun AppRobot.openModelPicker(): AppRobot {
        val label = if (compose.onAllNodes(androidx.compose.ui.test.hasText("Change model")).fetchSemanticsNodes().isNotEmpty()) "Change model" else "Choose model"
        return scrollTo(label).tap(label).waitUntilShown("Reasoning: ")
    }

    // --- Tools: Screen switched off by Android (D7), Folders from another app, pick-up ---

    private suspend fun TestApp.toolsWorld() {
        graph.start().join()
        screen.switchedOffByAndroid.value = true
        val cloud = "content://com.example.cloud.documents/tree/root%3AWork"
        val inbox = "content://com.android.externalstorage.documents/tree/primary%3AInbox"
        folderAccess.pickable[cloud] = "Work"
        folderAccess.pickable[inbox] = "Inbox"
        systemScreens.storageApps[cloud] = "Cloud Drive"
        graph.ownerFolders.add(cloud)
        val added = graph.ownerFolders.add(inbox) as com.aicontrolcenter.core.operator.OwnerResult.Done
        graph.ownerFolders.setReadOnly(added.value.id, true)
    }

    @Test
    fun toolsScreenSwitchedOff() {
        app { toolsWorld() }.openSectionViaMore("Tools").tap("Screen").waitUntilShown("Screen control was switched off")
        shoot("tools_screen_switched_off")
    }

    @Test
    fun toolsFolders() {
        app { toolsWorld() }.openSectionViaMore("Tools").tap("Folders").waitUntilShown("This folder comes from Cloud Drive.")
        shoot("tools_folders_other_app")
    }

    @Test
    fun toolsFoldersLight() {
        val robot = app(dark = false) { toolsWorld() }.openSectionViaMore("Tools").tap("Folders").waitUntilShown("This folder comes from Cloud Drive.")
        robot.scrollListTo("Pick up new files")
        shoot("tools_folders_pick_up_light")
    }

    @Test
    @Config(qualifiers = WIDE_WAVE3)
    fun toolsFoldersWide() {
        app { toolsWorld() }.openSection("Tools").tap("Folders").waitUntilShown("This folder comes from Cloud Drive.")
        shoot("tools_folders_wide")
    }

    @Test
    @Config(qualifiers = NARROW_WAVE3)
    fun toolsScreenNarrow() {
        app { toolsWorld() }.openSectionViaMore("Tools").tap("Screen").waitUntilShown("Screen control was switched off")
        shoot("tools_screen_switched_off_360")
    }
}

/** Wave 3's wide picture: a phone in landscape or a small tablet. */
internal const val WIDE_WAVE3 = "w900dp-h600dp-xhdpi"

/** Wave 3's narrow picture: the narrowest common phone. */
internal const val NARROW_WAVE3 = "w360dp-h740dp-xhdpi"
