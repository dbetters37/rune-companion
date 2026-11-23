package com.aicontrolcenter.app.ui.models

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderField
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.framework.llm.ProviderPresets
import com.aicontrolcenter.testkit.FakeProviderAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderFormViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val key = "sk-test-4f9a8b7c6d5e4f3a2b1c"

    private fun form(app: TestApp, presetId: String? = null, editing: ProviderId? = null, baseUrl: String? = null) =
        ProviderFormViewModel(app.graph.providerManager, app.graph.registryDirectory, presetId, editing, baseUrl)

    @Test
    fun `a preset fills the form and the manager's issues appear next to their fields`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = form(app, presetId = ProviderPresets.LM_STUDIO)
        val state = viewModel.uiState.value
        assertEquals("LM Studio", state.name)
        assertEquals(ProviderKind.OPENAI_COMPATIBLE, state.kind)
        assertEquals("http://192.168.1.100:1234/v1", state.baseUrl)
        assertEquals(Locality.LOCAL_NETWORK, state.locality)
        assertTrue(assertNotNull(state.setupHint).contains("Developer tab"))
        assertEquals(emptyMap(), state.issues)

        viewModel.setName(" ")
        viewModel.setBaseUrl("http://example.com/v1")
        viewModel.setHeadersText("X-Title: Phone\nnot a header")
        with(viewModel.uiState.value) {
            assertEquals("Give the provider a name.", issue(ProviderField.NAME))
            assertTrue(assertNotNull(issue(ProviderField.BASE_URL)).startsWith("Plain http is only allowed"))
            assertEquals("Line 2: write each header as Name: value.", issue(ProviderField.HEADERS))
            assertEquals(Locality.CLOUD, locality, "the address decides where prompts go")
        }

        viewModel.setHeadersText("Authorization: Bearer x")
        assertTrue(assertNotNull(viewModel.uiState.value.issue(ProviderField.HEADERS)).contains("carries a credential"))

        viewModel.save()
        assertNull(viewModel.uiState.value.setup, "nothing is saved while the form has problems")
        assertEquals(emptyList(), app.providers.getAll())
    }

    @Test
    fun `saving adds the provider, keeps the key out of the state, then checks it and fetches its models`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.adaptersByUrl["https://openrouter.ai/api/v1"] = FakeProviderAdapter(
            models = listOf(RemoteModel("vendor/alpha"), RemoteModel("vendor/beta", displayName = "Beta")),
        )
        val viewModel = form(app, presetId = ProviderPresets.OPENROUTER)
        viewModel.setApiKey(key)
        assertEquals(key, viewModel.uiState.value.apiKey, "typed into the masked field")

        viewModel.save()
        val state = viewModel.uiState.value
        assertEquals("", state.apiKey, "the key went to the manager and is gone from the UI")
        assertFalse(state.toString().contains(key))

        val provider = app.providers.getAll().single()
        assertEquals("OpenRouter", provider.name)
        assertEquals(key, app.vault.get(assertNotNull(provider.apiKeyAlias)), "stored in the vault, under the provider's alias only")
        assertFalse(provider.toString().contains(key))

        val setup = assertNotNull(state.setup)
        assertTrue(setup.finished)
        assertTrue(setup.succeeded)
        assertEquals(listOf("Saved", "Checking the connection", "Fetching the model list"), setup.steps.map { it.title })
        assertTrue(assertNotNull(setup.steps[0].detail).contains("encrypted storage"))
        assertTrue(assertNotNull(setup.steps[1].detail).startsWith("OpenRouter is online"))
        assertEquals("2 models registered. 2 added, 0 updated, 0 no longer listed.", setup.steps[2].detail)
        assertEquals(ConnectionStatus.ONLINE, app.providers.getAll().single().status)
        assertEquals(listOf("vendor/alpha", "vendor/beta"), app.models.getAll().map { it.modelName })
        assertFalse(app.activityLog.all.joinToString { "${it.title} ${it.detail} ${it.data}" }.contains(key), "never logged")
    }

    @Test
    fun `a failed connection check is reported and the model list is skipped`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = form(app, presetId = ProviderPresets.OLLAMA_ON_PHONE)
        assertEquals("http://127.0.0.1:11434", viewModel.uiState.value.baseUrl)
        viewModel.save()

        val setup = assertNotNull(viewModel.uiState.value.setup)
        assertTrue(setup.finished)
        assertFalse(setup.succeeded)
        assertEquals(SetupStepState.FAILED, setup.steps[1].state)
        assertEquals("Ollama on this phone is offline. Nothing is listening at that address.", setup.steps[1].detail)
        assertEquals(SetupStepState.SKIPPED, setup.steps[2].state)
        assertEquals(1, app.providers.getAll().size, "the provider is saved all the same")
    }

    @Test
    fun `a listing that fails after a passed check says why and how to go on`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.adaptersByUrl["http://127.0.0.1:8080/v1"] = FakeProviderAdapter(listError = LlmException.Unsupported("This server cannot list its models."))
        val viewModel = form(app, presetId = ProviderPresets.LLAMA_CPP)
        viewModel.save()
        val step = assertNotNull(viewModel.uiState.value.setup).steps[2]
        assertEquals(SetupStepState.FAILED, step.state)
        assertTrue(assertNotNull(step.detail).contains("This server cannot list its models."), step.detail)
        assertTrue(step.detail!!.endsWith("You can add a model by name on the provider's page."))
    }

    @Test
    fun `an address found on this phone wins over the preset's example, and names stay unique`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        form(app, presetId = ProviderPresets.LM_STUDIO).save()
        val viewModel = form(app, presetId = ProviderPresets.LM_STUDIO, baseUrl = "http://127.0.0.1:1234/v1")
        assertEquals("LM Studio 2", viewModel.uiState.value.name)
        assertEquals("http://127.0.0.1:1234/v1", viewModel.uiState.value.baseUrl)
        assertEquals(Locality.ON_DEVICE, viewModel.uiState.value.locality)
    }

    @Test
    fun `editing leaves the stored key alone unless a new one is typed or removal is asked for`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.adaptersByUrl["https://api.groq.com/openai/v1"] = FakeProviderAdapter()
        form(app, presetId = ProviderPresets.GROQ).apply {
            setApiKey(key)
            save()
        }
        val id = app.providers.getAll().single().id
        val alias = assertNotNull(app.providers.get(id)?.apiKeyAlias)

        val edit = form(app, editing = id)
        with(edit.uiState.value) {
            assertTrue(isEditing)
            assertTrue(keyStored)
            assertEquals("", apiKey, "a stored key is never shown again")
            assertFalse(isDirty)
        }
        edit.setName("Groq (work)")
        edit.save()
        assertEquals("Groq (work)", app.providers.get(id)?.name)
        assertEquals(key, app.vault.get(alias), "an empty key field means unchanged")
        assertEquals("Changes saved", edit.uiState.value.setup?.steps?.first()?.title)

        val replace = form(app, editing = id)
        replace.setApiKey("sk-test-replacement-000000000")
        replace.save()
        assertEquals("sk-test-replacement-000000000", app.vault.get(alias))

        val remove = form(app, editing = id)
        remove.setRemoveStoredKey(true)
        assertTrue(remove.uiState.value.isDirty)
        remove.save()
        assertNull(app.providers.get(id)?.apiKeyAlias)
        assertNull(app.vault.get(alias))
        assertTrue(assertNotNull(remove.uiState.value.setup).steps.first().detail!!.contains("deleted"))
    }

    @Test
    fun `a vendor service without a key is refused before anything is saved`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.adaptersByUrl[""] = FakeProviderAdapter(health = ProviderHealth(ConnectionStatus.AUTH_ERROR, "No key."))
        val viewModel = form(app, presetId = ProviderPresets.ANTHROPIC)
        assertTrue(viewModel.uiState.value.showBaseUrl, "Anthropic takes an optional proxy address")
        assertEquals("This provider needs an API key.", viewModel.uiState.value.issue(ProviderField.API_KEY))
        viewModel.save()
        assertEquals("Fix the marked fields, then save again.", viewModel.uiState.value.message?.text)
        assertEquals(emptyList(), app.providers.getAll())
    }

    @Test
    fun `a provider that no longer exists cannot be edited`() = runTest(mainRule.dispatcher) {
        val viewModel = form(TestApp(backgroundScope), editing = ProviderId("prv_gone"))
        assertTrue(viewModel.uiState.value.notFound)
    }
}
