package com.aicontrolcenter.app.ui.models

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.registry.ControlChannel
import com.aicontrolcenter.core.registry.DiscoverySource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiAppDetailViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private suspend fun TestScope.scanned(): TestApp = TestApp(backgroundScope).also {
        it.installSampleApps()
        it.graph.aiAppRegistry.discover()
    }

    private fun TestScope.detail(app: TestApp, id: AiAppId) =
        AiAppDetailViewModel(id, app.graph.aiAppRegistry, app.graph.aiAppLauncher, app.graph.settings)
            .also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `opening an app goes through the launcher and is logged without any egress`() = runTest(mainRule.dispatcher) {
        val app = scanned()
        val viewModel = detail(app, app.appNamed("Example Chatbot").id)
        assertTrue(viewModel.uiState.value.canOpen)

        viewModel.openApp()
        assertEquals(listOf("com.example.chatbot"), app.launcher.launched)
        val event = app.activityLog.all.last()
        assertEquals("Opened app", event.title)
        assertEquals(ActivityType.USER_ACTION, event.type)
        assertNull(event.egress)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `handing text over is recorded as information leaving this app's control`() = runTest(mainRule.dispatcher) {
        val app = scanned()
        val viewModel = detail(app, app.appNamed("Example Chatbot").id)
        assertTrue(viewModel.uiState.value.canHandOffText)

        viewModel.handOffText("Summarise this paragraph.")
        assertEquals(listOf("com.example.chatbot" to "Summarise this paragraph."), app.launcher.handedOff)
        assertEquals("Handed to Example Chatbot. Its reply stays in that app.", viewModel.uiState.value.message?.text)

        val event = app.activityLog.all.last()
        assertEquals(ActivityType.DATA_EGRESS, event.type)
        assertEquals("app:com.example.chatbot", event.egress?.destinationHost)
        assertFalse("Summarise" in event.detail + event.title, "the text itself is never written to the log")
        assertEquals(1, app.activityLog.all.count { it.type == ActivityType.DATA_EGRESS }, "one hand-off, one entry")
    }

    @Test
    fun `nothing is sent when the text is blank or the app does not accept text`() = runTest(mainRule.dispatcher) {
        val app = scanned()
        val chatbot = detail(app, app.appNamed("Example Chatbot").id)
        chatbot.handOffText("   ")
        assertEquals("Enter some text to send.", chatbot.uiState.value.message?.text)

        val assistant = detail(app, app.appNamed("Sample Assistant").id)
        assertFalse(assistant.uiState.value.canHandOffText)
        assistant.handOffText("hello")
        assertEquals("Sample Assistant does not accept shared text.", assistant.uiState.value.message?.text)
        assertEquals(emptyList(), app.launcher.handedOff)
    }

    @Test
    fun `authorising accessibility records the decision but permits nothing while the operator is off`() = runTest(mainRule.dispatcher) {
        val app = scanned()
        val viewModel = detail(app, app.appNamed("Example Chatbot").id)

        viewModel.setAccessibilityAuthorized(true)
        val state = viewModel.uiState.value
        assertTrue(assertNotNull(state.app).accessibilityAuthorized)
        assertFalse(ControlChannel.ACCESSIBILITY in state.channels)
        assertTrue(state.accessibilityAuthorisedButInactive)
        val event = app.activityLog.all.last()
        assertEquals(ActivityType.PERMISSION, event.type)
        assertEquals(Severity.WARNING, event.severity)

        app.settings.update { it.copy(operatorEnabled = true, accessibilityOperatorEnabled = true) }
        assertTrue(ControlChannel.ACCESSIBILITY in viewModel.uiState.value.channels)
        assertFalse(viewModel.uiState.value.accessibilityAuthorisedButInactive)

        viewModel.setAccessibilityAuthorized(false)
        assertFalse(assertNotNull(viewModel.uiState.value.app).accessibilityAuthorized)
        assertEquals("Accessibility authorisation revoked.", viewModel.uiState.value.message?.text)
    }

    @Test
    fun `an app that is not installed cannot be authorised and the registry's reason is shown`() = runTest(mainRule.dispatcher) {
        val app = scanned()
        val chatbot = app.appNamed("Example Chatbot")
        app.scanner.aiApps = listOf(TestApp.assistant, TestApp.lookalike)
        app.graph.aiAppRegistry.refreshStatuses()
        val viewModel = detail(app, chatbot.id)
        assertEquals(emptySet(), viewModel.uiState.value.channels)

        viewModel.setAccessibilityAuthorized(true)
        assertEquals(
            "Example Chatbot is not installed, so it cannot be authorised for accessibility operation.",
            viewModel.uiState.value.message?.text,
        )
        assertFalse(assertNotNull(viewModel.uiState.value.app).accessibilityAuthorized)

        viewModel.openApp()
        assertEquals("Example Chatbot cannot be opened.", viewModel.uiState.value.message?.text)
    }

    @Test
    fun `confirming, disabling and notes change only this registry entry`() = runTest(mainRule.dispatcher) {
        val app = scanned()
        val viewModel = detail(app, app.appNamed("AI Helper").id)
        assertTrue(assertNotNull(viewModel.uiState.value.app).isUnverified)

        viewModel.confirm()
        assertFalse(assertNotNull(viewModel.uiState.value.app).isUnverified)
        assertEquals(DiscoverySource.HEURISTIC, viewModel.uiState.value.app?.source, "how it was found does not change")

        viewModel.setEnabled(false)
        assertEquals(emptySet(), viewModel.uiState.value.channels)
        assertFalse(viewModel.uiState.value.canOpen)
        viewModel.setEnabled(true)
        assertTrue(viewModel.uiState.value.canOpen)

        viewModel.saveNotes("  Tried once, \"fine\".\nKeep.  ")
        assertEquals("Tried once, \"fine\".\nKeep.", viewModel.uiState.value.app?.notes)
        assertEquals("Notes saved.", viewModel.uiState.value.message?.text)
    }

    @Test
    fun `removing hides the app for good until it is registered again by hand`() = runTest(mainRule.dispatcher) {
        val app = scanned()
        val helper = app.appNamed("AI Helper")
        val viewModel = detail(app, helper.id)

        viewModel.remove()
        assertTrue(viewModel.uiState.value.removed)
        assertNull(viewModel.uiState.value.app)

        app.graph.aiAppRegistry.discover()
        assertFalse("AI Helper" in app.snapshot().apps.map { it.label }, "a scan must not bring a removed app back")

        val returned = app.graph.aiAppRegistry.registerManually("io.unknown.aihelper")
        assertEquals(helper.id, returned.id)
        assertEquals("AI Helper", viewModel.uiState.value.app?.label)
    }

    @Test
    fun `a stale id shows nothing and every action is refused with a message`() = runTest(mainRule.dispatcher) {
        val app = scanned()
        val viewModel = detail(app, AiAppId("app_gone"))
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.app)

        viewModel.setEnabled(false)
        assertEquals("That app is no longer registered.", viewModel.uiState.value.message?.text)
        viewModel.confirm()
        viewModel.setAccessibilityAuthorized(true)
        viewModel.saveNotes("x")
        viewModel.openApp()
        assertEquals("This app is no longer in the registry.", viewModel.uiState.value.message?.text)
        viewModel.handOffText("x")
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(emptyList(), app.launcher.launched)
    }
}
