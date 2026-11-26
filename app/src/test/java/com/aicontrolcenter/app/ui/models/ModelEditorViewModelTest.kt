package com.aicontrolcenter.app.ui.models

import com.aicontrolcenter.testkit.ScriptedLlmClient
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.app.testing.serveModel
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.populateWithProviders
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.UsageSource
import java.time.ZoneOffset
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContains

class ModelEditorViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun editor(app: TestApp, id: ModelId) = ModelEditorViewModel(id, app.graph.registryDirectory, app.graph.providerManager, app.graph.agentManager)

    /** The editor as the route builds it, with the owner's prices and the usage reports. */
    private fun pricedEditor(app: TestApp, id: ModelId) = ModelEditorViewModel(
        id, app.graph.registryDirectory, app.graph.providerManager, app.graph.agentManager,
        prices = app.graph.modelPrices, ownerPrices = app.graph.ownerModelPrices, usageReports = app.graph.usageReports,
        clock = app.clock, zone = { ZoneOffset.UTC },
    )

    private fun TextFieldState.type(text: String) {
        setTextAndPlaceCursorAtEnd(text)
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun `display name, capabilities and notes are saved, and the owner's description then stands`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.registerSampleModel()
        val id = ModelId("mdl_sample")
        val viewModel = editor(app, id)
        with(viewModel.uiState.value) {
            assertEquals("Gemma 3 4B", draft.displayName)
            assertEquals("gemma3:4b", wireName)
            assertEquals("Home server", providerName)
            assertEquals("8192", draft.contextWindowText)
            assertFalse(isDirty)
        }

        viewModel.setDisplayName("Gemma (fast)")
        viewModel.setNotes("Good enough for summaries.")
        viewModel.setReasoning(CapabilityTier.STRONG)
        viewModel.setCapabilities { it.copy(vision = false) }
        viewModel.setContextWindowText("32k")
        assertEquals("32", viewModel.uiState.value.draft.contextWindowText, "digits only")
        viewModel.setContextWindowText("32768")
        assertTrue(viewModel.uiState.value.isDirty)

        viewModel.save()
        val stored = assertNotNull(app.models.get(id))
        assertEquals("Gemma (fast)", stored.displayName)
        assertEquals("Good enough for summaries.", stored.notes)
        assertEquals(CapabilityTier.STRONG, stored.capabilities.reasoning)
        assertFalse(stored.capabilities.vision)
        assertEquals(32768, stored.capabilities.contextWindow)
        assertEquals(ModelSource.MANUAL, stored.source, "a refresh will no longer overwrite it")
        assertFalse(viewModel.uiState.value.isDirty)
        assertEquals("Saved.", viewModel.uiState.value.message?.text)
    }

    @Test
    fun `a blank name is refused, and switching off and removing work`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.registerSampleModel()
        app.graph.agentManager.ensureDefaults()
        app.graph.agentManager.assignModel(app.agentNamed("Writer").profile.id, ModelId("mdl_sample"))
        val viewModel = editor(app, ModelId("mdl_sample"))
        assertEquals(listOf("Writer"), viewModel.uiState.value.agentsUsing)

        viewModel.setDisplayName("  ")
        assertEquals("Give the model a display name.", viewModel.uiState.value.nameError)
        viewModel.save()
        assertEquals("Gemma 3 4B", app.models.get(ModelId("mdl_sample"))?.displayName)

        viewModel.setEnabled(false)
        assertFalse(assertNotNull(app.models.get(ModelId("mdl_sample"))).enabled)
        assertEquals("Disabled", viewModel.uiState.value.status.text)

        viewModel.remove()
        assertTrue(viewModel.uiState.value.removed)
        assertNull(app.models.get(ModelId("mdl_sample")))
        assertFalse(viewModel.uiState.value.notFound, "leaving after a removal is not a missing model")
    }

    @Test
    fun `the simulator cannot be described differently`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.providerManager.ensureBuiltIns()
        val viewModel = editor(app, Simulator.MODEL_ID)
        assertTrue(viewModel.uiState.value.isSimulator)
        viewModel.setDisplayName("A real model")
        assertFalse(viewModel.uiState.value.isDirty)
        assertEquals(Simulator.MODEL_DISPLAY_NAME, app.models.get(Simulator.MODEL_ID)?.displayName)
        assertEquals(Samples.model().displayName, "Gemma 3 4B")
    }

    // --- Use for agents ---

    @Test
    fun `use for agents offers every agent, the Director first and all chosen, and assigns the model to the chosen ones`() =
        runTest(mainRule.dispatcher) {
            val app = TestApp(backgroundScope)
            app.registerSampleModel()
            app.graph.providerManager.ensureBuiltIns()
            app.graph.agentManager.ensureDefaults()
            val sample = ModelId("mdl_sample")
            app.graph.agentManager.assignModel(app.agentNamed("Writer").profile.id, sample)
            app.graph.agentManager.assignModel(app.agentNamed("Researcher").profile.id, Simulator.MODEL_ID)
            val viewModel = editor(app, sample)
            assertEquals(6, viewModel.uiState.value.agentCount)

            viewModel.openUseForAgents()
            val open = assertNotNull(viewModel.uiState.value.useForAgents)
            assertEquals(
                listOf("Director", "Analyst", "Operator", "Researcher", "Reviewer", "Writer"),
                open.choices.map { it.name },
                "the Director first, then by name",
            )
            assertTrue(open.choices.first().isDirector)
            assertEquals(open.choices.map { it.id }.toSet(), open.selected, "every agent is chosen to begin with")
            val byName = open.choices.associateBy { it.name }
            assertEquals("Now: ${Simulator.MODEL_DISPLAY_NAME}", byName.getValue("Researcher").currentModel)
            assertEquals("Now: no model", byName.getValue("Analyst").currentModel)
            assertEquals("Already uses this model", byName.getValue("Writer").currentModel)
            assertEquals(5, open.changeCount)
            assertEquals("Leaves this device", open.warning?.title, "the same warning as the agent editor gives")
            assertFalse(assertNotNull(open.warning).blocksRunning)

            viewModel.toggleAgentChoice(byName.getValue("Analyst").id)
            assertEquals(4, viewModel.uiState.value.useForAgents?.changeCount)
            viewModel.useForChosenAgents()

            assertNull(viewModel.uiState.value.useForAgents, "the chooser closes")
            assertEquals(
                "Gemma 3 4B is now the model of Director, Operator, Researcher and Reviewer.",
                viewModel.uiState.value.message?.text,
                "names every agent that changed, and not the one that already used it",
            )
            for (name in listOf("Director", "Operator", "Researcher", "Reviewer", "Writer")) {
                assertEquals(sample, app.agentNamed(name).profile.modelId, name)
            }
            assertNull(app.agentNamed("Analyst").profile.modelId, "an agent taken off the list is left alone")
            assertEquals(5, viewModel.uiState.value.agentsUsing.size)
        }

    @Test
    fun `choosing agents that already use it changes nothing, and closing the chooser assigns nothing`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.registerSampleModel()
        app.graph.agentManager.ensureDefaults()
        val sample = ModelId("mdl_sample")
        val viewModel = editor(app, sample)

        viewModel.openUseForAgents()
        viewModel.dismissUseForAgents()
        assertNull(viewModel.uiState.value.useForAgents)
        assertTrue(app.snapshot().agents.all { it.profile.modelId == null })

        app.graph.agentManager.assignModel(app.agentNamed("Writer").profile.id, sample)
        viewModel.openUseForAgents()
        val open = assertNotNull(viewModel.uiState.value.useForAgents)
        open.choices.forEach { viewModel.toggleAgentChoice(it.id) }
        val writer = app.agentNamed("Writer").profile.id
        assertEquals(setOf(writer), viewModel.uiState.value.useForAgents?.selected, "an agent already on it cannot be unticked")
        assertEquals(0, viewModel.uiState.value.useForAgents?.changeCount)
        viewModel.useForChosenAgents()
        assertEquals("Every chosen agent already uses Gemma 3 4B.", viewModel.uiState.value.message?.text)
        assertEquals(1, app.snapshot().agents.count { it.profile.modelId == sample })
    }

    @Test
    fun `a model the agents cannot run on is still offered, as in the agent editor, and the reason is given`() =
        runTest(mainRule.dispatcher) {
            val app = TestApp(backgroundScope, FrameworkSettings(localOnlyMode = true))
            app.registerSampleModel()
            app.graph.agentManager.ensureDefaults()
            val sample = ModelId("mdl_sample")
            val viewModel = editor(app, sample)

            viewModel.openUseForAgents()
            val warning = assertNotNull(viewModel.uiState.value.useForAgents?.warning)
            assertEquals("Local-only mode is on", warning.title)
            assertTrue(warning.blocksRunning)
            assertContains(warning.text, "Home server would send data off this phone")

            viewModel.setEnabled(false)
            assertEquals("The model is disabled", viewModel.uiState.value.useForAgents?.warning?.title, "the open chooser follows the registry")

            val director = app.director().profile.id
            viewModel.uiState.value.useForAgents!!.choices.filter { it.id != director }.forEach { viewModel.toggleAgentChoice(it.id) }
            viewModel.useForChosenAgents()
            assertEquals(
                "Gemma 3 4B is now the model of Director. It cannot run on it yet (The model is disabled).",
                viewModel.uiState.value.message?.text,
            )
            assertEquals(sample, app.director().profile.modelId)
            assertFalse(app.director().availability.isReady)
        }

    // --- Phase 6: the owner's price, speed and reliability, the cheaper model for helpers ---

    @Test
    fun `the price is typed from the provider's page, saved apart from the description, shown again and removed`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.registerSampleModel()
        val id = ModelId("mdl_sample")
        val viewModel = pricedEditor(app, id)
        val sourceBefore = app.models.get(id)?.source
        with(viewModel.uiState.value.price) {
            assertTrue(available)
            assertNull(stored)
            assertFalse(isDirty)
        }
        assertEquals("US$", viewModel.priceFields.currency.text.toString(), "the usual currency to begin with")

        viewModel.priceFields.input.type("3")
        viewModel.priceFields.output.type("15,00")
        viewModel.priceFields.cachedInput.type("0.3")
        assertTrue(viewModel.uiState.value.price.isDirty)
        assertTrue(viewModel.uiState.value.hasUnsavedChanges, "leaving would ask first")
        viewModel.savePrice()

        val stored = assertNotNull(app.prices.get(id))
        assertEquals(ModelPrice(id, 3_000_000, 15_000_000, 300_000, "US$", stored.updatedAt), stored)
        assertEquals("Price saved. Costs with this model are now estimated from it.", viewModel.uiState.value.message?.text)
        assertFalse(viewModel.uiState.value.price.isDirty)
        assertEquals("15", viewModel.priceFields.output.text.toString(), "shown as stored")
        assertEquals(sourceBefore, app.models.get(id)?.source, "the description is not touched")
        assertFalse(viewModel.uiState.value.isDirty)

        // A second screen shows what is stored.
        val again = pricedEditor(app, id)
        assertEquals("3", again.priceFields.input.text.toString())
        assertEquals("0.3", again.priceFields.cachedInput.text.toString())
        assertEquals(stored, again.uiState.value.price.stored)

        viewModel.removePrice()
        assertNull(app.prices.get(id))
        assertEquals("", viewModel.priceFields.input.text.toString())
        assertEquals("Price removed. Costs with this model are no longer estimated.", viewModel.uiState.value.message?.text)
    }

    @Test
    fun `a price that cannot be one is refused with a sentence and nothing is stored`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.registerSampleModel()
        val id = ModelId("mdl_sample")
        val viewModel = pricedEditor(app, id)

        viewModel.priceFields.input.type("3.5.1")
        viewModel.savePrice()
        with(viewModel.uiState.value.price) {
            assertEquals(AMOUNT_HINT, inputError)
            assertEquals("Enter the output price.", outputError)
        }
        viewModel.priceFields.input.type("20000")
        viewModel.priceFields.output.type("1")
        viewModel.savePrice()
        assertEquals("A price can be at most 10,000 per million tokens.", viewModel.uiState.value.price.error)
        viewModel.priceFields.input.type("2")
        viewModel.priceFields.currency.type("  ")
        viewModel.savePrice()
        assertEquals("Say which currency the price is in, for example US$.", viewModel.uiState.value.price.error)
        assertNull(app.prices.get(id))
    }

    @Test
    fun `amounts are read as the owner types them`() {
        assertEquals(3_000_000, parseAmountMicros("3"))
        assertEquals(150_000, parseAmountMicros("0,15"))
        assertEquals(500_000, parseAmountMicros(".5"))
        assertEquals(2_500_000, parseAmountMicros(" 2.50 "))
        assertEquals(1, parseAmountMicros("0.000001"))
        assertNull(parseAmountMicros("0.0000001"), "smaller than a millionth")
        assertNull(parseAmountMicros("1e3"))
        assertNull(parseAmountMicros("US$3"))
        assertNull(parseAmountMicros(""))
        assertEquals("0.15", perMillionText(150_000))
        assertEquals("3", perMillionText(3_000_000))
        assertEquals("0.075", amountText(75_000))
        assertEquals("1234.50", amountText(1_234_500_000))
        assertEquals("US$3.00 in, US$15.00 out per million tokens, cached input US$0.30", priceSentence(ModelPrice(ModelId("m"), 3_000_000, 15_000_000, 300_000)))
    }

    @Test
    fun `speed and reliability say in words how the model did, with times only after five answers`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.registerSampleModel()
        val id = ModelId("mdl_sample")
        val viewModel = pricedEditor(app, id)
        assertEquals(listOf("No calls in the last 30 days."), viewModel.uiState.value.speed?.lines)

        fun sample(outcome: CallOutcome, error: ErrorClass? = null, answerMs: Long? = null, ttftMs: Long? = null, waits: Int = 0) = ModelCallSample(
            at = app.clock.current, modelId = id, modelLabel = "Gemma 3 4B", providerId = ProviderId("prv_sample"), providerLabel = "Home server",
            locality = Locality.LOCAL_NETWORK, source = UsageSource.CHAT, outcome = outcome, errorClass = error,
            inputTokens = if (outcome == CallOutcome.OK) 100 else null, outputTokens = if (outcome == CallOutcome.OK) 10 else null,
            answerMs = answerMs, ttftMs = ttftMs, rateLimitedWaits = waits,
        )
        repeat(4) { app.usage.record(sample(CallOutcome.OK, answerMs = 3_000, ttftMs = 800)) }
        assertTrue("Average times are shown once it has given 5 answers." in viewModel.uiState.value.speed!!.lines)
        app.usage.record(sample(CallOutcome.OK, answerMs = 4_000, ttftMs = 1_000, waits = 2))
        app.usage.record(sample(CallOutcome.FAILED, ErrorClass.NETWORK))
        app.usage.record(sample(CallOutcome.FAILED, ErrorClass.TIMEOUT))
        assertEquals(
            listOf(
                // "Answers" as the limits and Usage count them (D3A-9): every request the model received.
                "Last 30 days: 7 answers counted (5 came back, 2 failed).",
                "A whole answer took 3.2 seconds on average.",
                "The first words came after 0.8 seconds on average.",
                "Why calls failed: 1 took too long, 1 could not connect.",
                "The provider asked the app to slow down 2 times.",
            ),
            viewModel.uiState.value.speed?.lines,
        )
    }

    /** A cheap model off the phone (a cloud service) that can use tools, rated [reasoning]. */
    private suspend fun TestApp.cheapCloudModel(reasoning: CapabilityTier) = serveModel(
        ScriptedLlmClient.replies("Done."), name = "Budget Cloud", baseUrl = "https://api.budget.example/v1", apiKey = "test-key-not-real",
        modelName = "budget-1", displayName = "Budget 1", capabilities = ModelCapabilities(toolCalling = true, reasoning = reasoning),
    )

    @Test
    fun `the cheapest priced model that can use tools is offered to the helpers through Use for agents, the Director keeps its model`() =
        runTest(mainRule.dispatcher) {
            val app = TestApp(backgroundScope)
            app.populateWithProviders()
            app.graph.agentManager.ensureDefaults()
            val claude = app.models.getAll().single { it.modelName == "claude-sonnet-4-5" }
            val qwen = app.models.getAll().single { it.modelName == "qwen3:1.7b" }
            val budget = app.cheapCloudModel(CapabilityTier.STRONG)
            // Everyone on the dear model; the Analyst on a free model without a price stays where it is.
            for (agent in app.agents.getAll()) app.graph.agentManager.assignModel(agent.id, claude.id)
            app.graph.agentManager.assignModel(app.agentNamed("Analyst").profile.id, qwen.id)
            app.graph.ownerModelPrices.set(ModelPrice(claude.id, 3_000_000, 15_000_000))
            app.graph.ownerModelPrices.set(ModelPrice(budget.id, 100_000, 300_000))

            val cheap = pricedEditor(app, budget.id)
            val offer = assertNotNull(cheap.uiState.value.cheaper)
            assertTrue(offer.isThisModel)
            assertEquals("Cheaper model for helpers", offer.title)
            val helpers = listOf("Operator", "Researcher", "Reviewer", "Writer").map { app.agentNamed(it).profile.id }.toSet()
            assertEquals(helpers, offer.helpers, "every helper on the dear model, not the Director")
            assertTrue("Operator, Researcher, Reviewer and Writer" in offer.text, offer.text)
            assertTrue("The Director keeps its own model." in offer.text)
            assertNull(offer.tradeOff, "rated as well as the helpers' model: nothing traded away")

            cheap.openUseForHelpers()
            val open = assertNotNull(cheap.uiState.value.useForAgents)
            assertEquals(helpers, open.selected, "the helpers are ticked, the Director is not")
            cheap.useForChosenAgents()
            assertEquals(claude.id, app.director().profile.modelId)
            assertTrue(helpers.all { id -> app.snapshot().agents.single { it.profile.id == id }.profile.modelId == budget.id })
            assertNull(cheap.uiState.value.cheaper, "nothing left to suggest")

            val dear = pricedEditor(app, claude.id)
            val hint = assertNotNull(dear.uiState.value.cheaper)
            assertFalse(hint.isThisModel)
            assertEquals("A cheaper model can use tools", hint.title)
            assertTrue(hint.text.startsWith("Budget 1 costs about 40 times less"), hint.text)
        }

    @Test
    fun `a cheaper model rated lower at reasoning says what is traded away and ticks nobody (D3A-10)`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithProviders()
        app.graph.agentManager.ensureDefaults()
        val claude = app.models.getAll().single { it.modelName == "claude-sonnet-4-5" }
        val budget = app.cheapCloudModel(CapabilityTier.BASIC)
        for (agent in app.agents.getAll()) app.graph.agentManager.assignModel(agent.id, claude.id)
        app.graph.ownerModelPrices.set(ModelPrice(claude.id, 3_000_000, 15_000_000))
        app.graph.ownerModelPrices.set(ModelPrice(budget.id, 100_000, 300_000))

        val cheap = pricedEditor(app, budget.id)
        val offer = assertNotNull(cheap.uiState.value.cheaper)
        val tradeOff = "Budget 1 is rated Basic at reasoning; the helpers now use Claude Sonnet 4.5, rated Strong. " +
            "Cheaper models can give weaker results; try it on one helper first."
        assertEquals(tradeOff, offer.tradeOff)
        assertTrue(offer.text.endsWith(tradeOff), offer.text)
        assertEquals(emptySet(), offer.preselected)

        cheap.openUseForHelpers()
        val open = assertNotNull(cheap.uiState.value.useForAgents, "the chooser opens with nobody ticked")
        assertEquals(emptySet(), open.selected)
        assertEquals(tradeOff, open.tradeOff, "the chooser says it too")
    }

    @Test
    fun `a model on this phone is free, never priced and never offered as the cheapest (D3A-10)`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithProviders()
        app.graph.agentManager.ensureDefaults()
        val claude = app.models.getAll().single { it.modelName == "claude-sonnet-4-5" }
        val llama = app.models.getAll().single { it.modelName == "llama3.2:3b" }
        for (agent in app.agents.getAll()) app.graph.agentManager.assignModel(agent.id, claude.id)
        app.graph.ownerModelPrices.set(ModelPrice(claude.id, 3_000_000, 15_000_000))
        // A price entered before this release, for a model on the phone: ignored everywhere.
        app.graph.ownerModelPrices.set(ModelPrice(llama.id, 100_000, 300_000))

        val onPhone = pricedEditor(app, llama.id)
        assertEquals(Locality.ON_DEVICE, onPhone.uiState.value.locality, "the editor shows \"Free on this phone\" instead of price fields")
        assertNull(onPhone.uiState.value.cheaper)
        assertNull(pricedEditor(app, claude.id).uiState.value.cheaper, "nothing off the phone is cheaper than Claude here")
    }

    @Test
    fun `agent names are all listed`() {
        assertEquals("", listNames(emptyList()))
        assertEquals("Writer", listNames(listOf("Writer")))
        assertEquals("Analyst and Writer", listNames(listOf("Analyst", "Writer")))
        assertEquals("A, B, C, D and E", listNames(listOf("A", "B", "C", "D", "E")))
    }
}
