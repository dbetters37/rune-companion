package com.aicontrolcenter.app.ui.models

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.models(app: TestApp) =
        ModelsViewModel(app.graph.registryDirectory, app.graph.aiAppRegistry).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `a first scan registers what it finds and says how each app was found`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        val viewModel = models(app)
        assertEquals(emptyList(), viewModel.uiState.value.apps)

        viewModel.scanDevice()
        val state = viewModel.uiState.value
        assertFalse(state.isScanning)
        assertEquals(ScanSummaryUi(added = listOf("AI Helper", "Example Chatbot", "Sample Assistant"), changed = 0, missing = emptyList()), state.lastScan)
        assertNull(state.message, "the summary stays on screen; a passing message would only repeat it")

        val chatbot = state.apps.first { it.label == "Example Chatbot" }
        assertEquals("com.example.chatbot", chatbot.packageName)
        assertEquals("Example Inc. · version 4.2.1", chatbot.vendorAndVersion)
        assertEquals("Installed", chatbot.status.text)
        assertEquals("Known AI app", chatbot.sourceLabel)
        assertFalse(chatbot.unverified)
        assertEquals(listOf("Can be opened", "Accepts shared text", "Processes selected text"), chatbot.capabilities)
        assertEquals(listOf("Open it", "Hand text to it"), chatbot.permitted, "a text round trip is not offered: nothing implements it")

        val assistant = state.apps.first { it.label == "Sample Assistant" }
        assertEquals(listOf("Open it"), assistant.permitted, "accepting shared images does not make text hand-off possible")

        val helper = state.apps.first { it.label == "AI Helper" }
        assertTrue(helper.unverified)
        assertEquals("Found by name", helper.sourceLabel)
        assertEquals("version 0.9", helper.vendorAndVersion)
    }

    @Test
    fun `later scans report what changed and what is gone`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        val viewModel = models(app)
        viewModel.scanDevice()

        viewModel.scanDevice()
        assertTrue(assertNotNull(viewModel.uiState.value.lastScan).nothingChanged)

        app.scanner.aiApps = listOf(TestApp.chatbot.copy(versionName = "5.0"), TestApp.lookalike)
        viewModel.scanDevice()
        assertEquals(ScanSummaryUi(added = emptyList(), changed = 1, missing = listOf("Sample Assistant")), viewModel.uiState.value.lastScan)

        val assistant = viewModel.uiState.value.apps.first { it.label == "Sample Assistant" }
        assertEquals("Not installed", assistant.status.text)
        assertEquals(StatusTone.UNAVAILABLE, assistant.status.tone)
        assertEquals(emptyList(), assistant.permitted, "nothing may be done with an app that is not installed")
        assertEquals("Example Inc. · version 5.0", viewModel.uiState.value.apps.first { it.label == "Example Chatbot" }.vendorAndVersion)
    }

    @Test
    fun `confirming an unverified app clears the mark and a stale id is reported`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        val viewModel = models(app)
        viewModel.scanDevice()
        val helper = viewModel.uiState.value.apps.first { it.unverified }

        viewModel.confirm(helper.id)
        assertFalse(viewModel.uiState.value.apps.first { it.id == helper.id }.unverified)
        assertTrue(app.appNamed("AI Helper").verifiedByUser)
        assertEquals("Confirmed as an AI application.", viewModel.uiState.value.message?.text)

        viewModel.confirm(AiAppId("app_gone"))
        assertEquals("That app is no longer registered.", viewModel.uiState.value.message?.text)
    }

    @Test
    fun `accessibility is only listed as permitted when both operator switches are on`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        val viewModel = models(app)
        viewModel.scanDevice()
        val chatbot = app.appNamed("Example Chatbot")
        app.graph.aiAppRegistry.setAccessibilityAuthorized(chatbot.id, true)

        fun permitted() = viewModel.uiState.value.apps.first { it.id == chatbot.id }.permitted
        assertFalse("Operate its screen" in permitted())

        app.settings.update { it.copy(operatorEnabled = true) }
        assertFalse("Operate its screen" in permitted())

        app.settings.update { it.copy(accessibilityOperatorEnabled = true) }
        assertTrue("Operate its screen" in permitted())
    }

    @Test
    fun `a removed app disappears and a scan does not bring it back`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        val viewModel = models(app)
        viewModel.scanDevice()
        app.graph.aiAppRegistry.remove(app.appNamed("Sample Assistant").id)
        assertEquals(listOf("AI Helper", "Example Chatbot"), viewModel.uiState.value.apps.map { it.label })

        viewModel.scanDevice()
        assertEquals(listOf("AI Helper", "Example Chatbot"), viewModel.uiState.value.apps.map { it.label })
        assertTrue(assertNotNull(viewModel.uiState.value.lastScan).nothingChanged)
    }

    @Test
    fun `providers and models are shown as the registry holds them`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val viewModel = models(app)
        assertEquals(emptyList(), viewModel.uiState.value.providers)
        assertEquals(emptyList(), viewModel.uiState.value.models)

        // Labelled "on device" in storage although its address is on the network.
        app.providers.upsert(Samples.provider(locality = Locality.ON_DEVICE))
        app.providers.upsert(Samples.provider(id = "prv_off", name = "Spare", enabled = false, createdAt = 2_000))
        app.models.upsert(Samples.model())
        app.models.upsert(Samples.model(id = "mdl_orphan", providerId = "prv_gone", createdAt = 2_000))
        app.graph.agentManager.assignModel(app.agentNamed("Writer").profile.id, ModelId("mdl_sample"))

        val state = viewModel.uiState.value
        val home = state.providers.first()
        assertEquals("Home server", home.name)
        assertEquals("OpenAI-compatible", home.kindLabel)
        assertEquals(Locality.LOCAL_NETWORK, home.locality, "the address decides, not the stored label")
        assertEquals("Online", home.status.text)
        assertEquals(1, home.modelCount)
        assertEquals("Disabled", state.providers[1].status.text)

        val gemma = state.models.first()
        assertEquals("Gemma 3 4B", gemma.name)
        assertEquals("gemma3:4b", gemma.wireName)
        assertEquals("Home server", gemma.providerName)
        assertEquals(Locality.LOCAL_NETWORK, gemma.locality)
        assertEquals(listOf("Tool calling", "Vision", "JSON output", "8k context"), gemma.traits)
        assertEquals(1, gemma.agentsUsing)

        val orphan = state.models[1]
        assertEquals("Unregistered provider", orphan.providerName)
        assertNull(orphan.locality)
    }

    @Test
    fun `a failing scan is reported and leaves the registry alone`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        val viewModel = models(app)
        viewModel.scanDevice()

        app.scanFailure = SecurityException("query refused")
        viewModel.scanDevice()
        val state = viewModel.uiState.value
        assertEquals("The scan failed: query refused", state.message?.text)
        assertFalse(state.isScanning)
        assertEquals(3, state.apps.size)
        assertEquals(512.toString() + " context", formatContextWindow(512))
    }
}
