package com.aicontrolcenter.app.ui.models

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.serveModel
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.agents.toDraft
import com.aicontrolcenter.core.llm.ProviderDraft
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderDetailViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.detail(app: TestApp, id: ProviderId) =
        ProviderDetailViewModel(id, app.graph.registryDirectory, app.graph.providerManager, app.clock, ZoneOffset.UTC)
            .also { it.uiState.activeIn(backgroundScope) }

    private val url = "http://192.168.1.30:11434"

    @Test
    fun `check now and refresh report what they found`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val adapter = FakeProviderAdapter(models = listOf(RemoteModel("llama3.2:3b"), RemoteModel("qwen3:1.7b")))
        val model = app.serveModel(ScriptedLlmClient.replies("ok"), name = "Desk PC", baseUrl = url, modelName = "llama3.2:3b", displayName = "Llama")
        app.adaptersByUrl[url] = adapter
        val viewModel = detail(app, model.providerId)

        val before = assertNotNull(viewModel.uiState.value.provider)
        assertEquals("Desk PC", before.name)
        assertEquals("Not checked", before.status.text)
        assertEquals("Never checked", before.row.lastChecked)
        assertEquals("No API key (local servers rarely need one)", before.row.keyNote)

        viewModel.checkNow()
        assertEquals("Desk PC is online (answered in 12 ms).", viewModel.uiState.value.lastCheck)
        assertEquals("Online", viewModel.uiState.value.provider?.status?.text)
        assertTrue(assertNotNull(viewModel.uiState.value.provider).row.lastChecked.startsWith("Checked "))

        viewModel.refreshModels()
        val sync = assertNotNull(viewModel.uiState.value.lastSync)
        assertTrue("qwen3:1.7b" in sync.added, sync.toString())
        assertEquals(2, viewModel.uiState.value.provider?.models?.size, "the hand-added model is not listed twice")

        adapter.models = listOf(RemoteModel("qwen3:1.7b"))
        viewModel.refreshModels()
        assertEquals(emptyList(), viewModel.uiState.value.lastSync?.missing, "a model added by hand is never marked missing")

        adapter.health = ProviderHealth(ConnectionStatus.OFFLINE, "Connection refused.")
        viewModel.checkNow()
        assertEquals("Desk PC is offline. Connection refused.", viewModel.uiState.value.lastCheck)
        assertEquals("Connection refused.", viewModel.uiState.value.provider?.row?.statusMessage)
    }

    @Test
    fun `models no longer listed stay, marked unavailable`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val adapter = FakeProviderAdapter(models = listOf(RemoteModel("a"), RemoteModel("b")))
        app.adaptersByUrl[url] = adapter
        val provider = app.graph.providerManager.addProvider(ProviderDraft("Desk PC", ProviderKind.OLLAMA, url))
        val viewModel = detail(app, provider.id)
        viewModel.refreshModels()
        assertEquals(listOf("a", "b"), viewModel.uiState.value.lastSync?.added)

        adapter.models = listOf(RemoteModel("a"))
        viewModel.refreshModels()
        assertEquals(SyncReportUi(added = emptyList(), updated = emptyList(), missing = listOf("b")), viewModel.uiState.value.lastSync)
        assertEquals(ModelStatus.UNAVAILABLE, app.models.getAll().first { it.modelName == "b" }.status)
        assertEquals("Unavailable", viewModel.uiState.value.provider?.models?.first { it.wireName == "b" }?.status?.text)
    }

    @Test
    fun `a refresh that fails is said in a sentence`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val provider = app.graph.providerManager.addProvider(ProviderDraft("Nowhere", ProviderKind.OLLAMA, url))
        val viewModel = detail(app, provider.id)
        viewModel.refreshModels()
        assertEquals("Could not reach the provider. Could not reach the server.", viewModel.uiState.value.message?.text)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `disabling, adding a model and removing name the agents concerned`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val model = app.serveModel(ScriptedLlmClient.replies("ok"), name = "Desk PC", baseUrl = url)
        val elsewhere = app.serveModel(ScriptedLlmClient.replies("ok"), name = "Other", baseUrl = "http://127.0.0.1:9200/v1")
        val manager = app.graph.agentManager
        manager.ensureDefaults()
        manager.assignModel(app.agentNamed("Researcher").profile.id, model.id)
        val analyst = app.agentNamed("Analyst").profile
        manager.update(analyst.id, analyst.toDraft().copy(modelId = model.id, fallbackModelIds = listOf(elsewhere.id)))
        val viewModel = detail(app, model.providerId)

        val dependents = assertNotNull(viewModel.uiState.value.provider).dependents
        assertEquals(listOf("Researcher"), dependents.leftWithoutModel)
        assertEquals(listOf("Analyst"), dependents.fallingBack)
        val message = removalMessage(viewModel.uiState.value.provider!!)
        assertTrue("Researcher would be left without a model" in message, message)
        assertTrue("Analyst would switch to a fallback model" in message, message)

        viewModel.setEnabled(false)
        assertEquals("Disabled", viewModel.uiState.value.provider?.status?.text)
        assertEquals(UnavailableReason.PROVIDER_DISABLED, (app.agentNamed("Researcher").availability as AgentAvailability.Unavailable).reason)
        viewModel.setEnabled(true)

        viewModel.addModel("mistral-small")
        assertEquals("Added mistral-small.", viewModel.uiState.value.message?.text)
        assertEquals(2, viewModel.uiState.value.provider?.models?.size)

        viewModel.remove()
        assertTrue(viewModel.uiState.value.removed)
        assertNull(app.providers.get(model.providerId))
        assertEquals(emptyList(), app.models.getByProvider(model.providerId))
        assertEquals(UnavailableReason.MODEL_NOT_REGISTERED, (app.agentNamed("Researcher").availability as AgentAvailability.Unavailable).reason)
        assertTrue(app.agentNamed("Analyst").availability.isReady, "the fallback on the other provider takes over")
    }

    @Test
    fun `the simulator is labelled and a cloud provider without a key is marked`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.providerManager.ensureBuiltIns()
        val simulator = assertNotNull(detail(app, Simulator.PROVIDER_ID).uiState.value.provider)
        assertTrue(simulator.row.isSimulator)
        assertEquals("Simulator: not a real model", simulator.row.kindLabel)
        assertNull(simulator.row.keyNote, "the simulator has no key to speak of")
        assertTrue(simulator.models.single().isSimulator)
        assertTrue(simulator.models.single().chips.none { it.estimated }, "its capabilities are declared by the app")

        app.adaptersByUrl["https://api.example.com/v1"] = FakeProviderAdapter()
        val cloud = app.graph.providerManager.addProvider(
            ProviderDraft("Hosted", ProviderKind.OPENAI_COMPATIBLE, "https://api.example.com/v1"),
        )
        val row = assertNotNull(detail(app, cloud.id).uiState.value.provider).row
        assertEquals("No API key stored", row.keyNote)
        assertTrue(row.keyMissing)

        app.settings.update { it.copy(localOnlyMode = true) }
        val blocked = detail(app, cloud.id)
        assertTrue(assertNotNull(blocked.uiState.value.provider).blockedByLocalOnly)
        blocked.checkNow()
        assertEquals("Local-only mode is on, so Hosted was not contacted.", blocked.uiState.value.lastCheck)
    }
}
