package com.aicontrolcenter.app.ui.models

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.registry.DiscoverySource
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegisterAppViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test
    fun `the picker lists unregistered apps, hides system apps by default and searches label and package`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        val viewModel = RegisterAppViewModel(app.graph.aiAppRegistry)

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("Notes", "Terminal"), state.apps.map { it.label })
        assertEquals(3, state.totalCount)

        viewModel.setIncludeSystemApps(true)
        assertEquals(listOf("Notes", "Settings", "Terminal"), viewModel.uiState.value.apps.map { it.label })

        viewModel.setQuery("TERM")
        assertEquals(listOf("Terminal"), viewModel.uiState.value.apps.map { it.label })
        viewModel.setQuery("android.settings")
        assertEquals(listOf("Settings"), viewModel.uiState.value.apps.map { it.label })
        viewModel.setQuery("zzz")
        assertEquals(emptyList(), viewModel.uiState.value.apps)
        assertEquals(3, viewModel.uiState.value.totalCount)
    }

    @Test
    fun `registering adds the app as verified by the owner and takes it off the picker`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        val viewModel = RegisterAppViewModel(app.graph.aiAppRegistry)

        viewModel.register("org.example.terminal")
        val id = assertNotNull(viewModel.uiState.value.registeredId)
        val registered = assertNotNull(app.aiApps.get(id))
        assertEquals(DiscoverySource.MANUAL, registered.source)
        assertFalse(registered.isUnverified)
        assertEquals(listOf("Notes"), viewModel.uiState.value.apps.map { it.label })
        assertFalse(viewModel.uiState.value.isRegistering)

        viewModel.register("org.example.terminal")
        assertNull(viewModel.uiState.value.message, "a second tap on the same row is ignored, not reported as an error")

        viewModel.registeredHandled()
        assertNull(viewModel.uiState.value.registeredId)
    }

    @Test
    fun `a refusal from the registry is shown and the picker stays usable`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        val viewModel = RegisterAppViewModel(app.graph.aiAppRegistry)

        app.graph.aiAppRegistry.registerManually("org.example.notes")
        viewModel.register("org.example.notes")
        assertEquals("Notes is already registered.", viewModel.uiState.value.message?.text)

        viewModel.register("org.example.vanished")
        assertEquals("org.example.vanished is not installed on this device.", viewModel.uiState.value.message?.text)
        assertFalse(viewModel.uiState.value.isRegistering)
        assertNull(viewModel.uiState.value.registeredId)
    }

    @Test
    fun `a device that cannot be read gives an error with a retry that works`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        app.scanFailure = IllegalStateException("binder died")
        val viewModel = RegisterAppViewModel(app.graph.aiAppRegistry)
        assertTrue(assertNotNull(viewModel.uiState.value.loadError).contains("binder died"))

        app.scanFailure = null
        viewModel.load()
        assertNull(viewModel.uiState.value.loadError)
        assertEquals(2, viewModel.uiState.value.apps.size)
    }
}
