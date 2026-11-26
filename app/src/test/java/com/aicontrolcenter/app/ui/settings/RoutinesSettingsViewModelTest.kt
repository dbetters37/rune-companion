package com.aicontrolcenter.app.ui.settings

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.makePickUpRule
import com.aicontrolcenter.app.testing.makeRoutine
import com.aicontrolcenter.app.testing.makeSpace
import com.aicontrolcenter.app.testing.shareFolder
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings › Routines and folder pick-up (WP10c; Q1, Appendix A): both master switches are off after installing; switching
 * one on asks once more (work then runs while nobody watches) and switching off is at once; the counts; a build without
 * the ports shows nothing and can switch nothing.
 */
class RoutinesSettingsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.section(app: TestApp) =
        RoutinesSettingsViewModel(app.graph.settings, app.graph.ownerRoutines, app.graph.ownerPickUpRules).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `both switches are off after installing, and the counts say nothing exists yet`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val state = section(app).uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.routinesAvailable)
        assertTrue(state.pickUpAvailable)
        assertFalse(state.routinesOn)
        assertFalse(state.pickUpOn)
        assertEquals("No routines yet.", state.routinesLine)
        assertEquals("No folder pick-up rules yet.", state.pickUpLine)
    }

    @Test
    fun `switching routines on asks once more, only the confirmation switches them on, and off is at once`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = section(app)
        viewModel.request(MasterSwitch.ROUTINES, true)
        assertEquals(MasterSwitch.ROUTINES, viewModel.uiState.value.confirming)
        assertFalse(app.settings.get().routinesEnabled, "nothing is switched on before the owner confirms")
        viewModel.cancel()
        assertNull(viewModel.uiState.value.confirming)
        assertFalse(app.settings.get().routinesEnabled)

        viewModel.request(MasterSwitch.ROUTINES, true)
        viewModel.confirm()
        assertTrue(app.settings.get().routinesEnabled)
        assertTrue(viewModel.uiState.value.routinesOn)
        assertFalse(app.settings.get().folderPickUpEnabled, "the other switch is left alone")

        viewModel.request(MasterSwitch.ROUTINES, false)
        assertNull(viewModel.uiState.value.confirming, "switching off is not asked")
        assertFalse(app.settings.get().routinesEnabled)
    }

    @Test
    fun `switching folder pick-up on asks once more too`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = section(app)
        viewModel.request(MasterSwitch.PICK_UP, true)
        assertEquals(MasterSwitch.PICK_UP, viewModel.uiState.value.confirming)
        assertFalse(app.settings.get().folderPickUpEnabled)
        viewModel.confirm()
        assertTrue(app.settings.get().folderPickUpEnabled)
        assertFalse(app.settings.get().routinesEnabled)
        viewModel.request(MasterSwitch.PICK_UP, false)
        assertFalse(app.settings.get().folderPickUpEnabled)
    }

    @Test
    fun `the counts follow the routines and rules`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = section(app)
        val first = app.makeRoutine("Morning news")
        assertEquals("1 routine, switched on. None runs while routines are off.", viewModel.uiState.value.routinesLine)
        app.settings.update { it.copy(routinesEnabled = true, folderPickUpEnabled = true) }
        assertEquals("1 routine, switched on.", viewModel.uiState.value.routinesLine)
        app.makeRoutine("Evening digest")
        app.makeRoutine("Weekly review")
        app.graph.ownerRoutines!!.setEnabled(first.id, false)
        assertEquals("3 routines, 2 of them switched on.", viewModel.uiState.value.routinesLine)
        val rule = app.makePickUpRule(app.shareFolder("Inbox", readOnly = true), app.makeSpace())
        assertEquals("1 rule, switched on.", viewModel.uiState.value.pickUpLine)
        app.graph.ownerPickUpRules!!.setEnabled(rule.id, false)
        assertEquals("1 rule, switched off.", viewModel.uiState.value.pickUpLine)
        assertEquals("2 rules, all switched on.", RoutinesSettingsViewModel.countLine(2, 2, "rule", ""))
        assertEquals("2 rules, all switched off.", RoutinesSettingsViewModel.countLine(2, 0, "rule", ""))
    }

    @Test
    fun `a build without routines or pick-up shows nothing and switches nothing`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = RoutinesSettingsViewModel(app.graph.settings, null, null).also { it.uiState.activeIn(backgroundScope) }
        val state = viewModel.uiState.value
        assertFalse(state.routinesAvailable)
        assertFalse(state.pickUpAvailable)
        viewModel.request(MasterSwitch.ROUTINES, true)
        viewModel.confirm()
        viewModel.request(MasterSwitch.PICK_UP, true)
        viewModel.confirm()
        assertFalse(app.settings.get().routinesEnabled)
        assertFalse(app.settings.get().folderPickUpEnabled)
    }
}
