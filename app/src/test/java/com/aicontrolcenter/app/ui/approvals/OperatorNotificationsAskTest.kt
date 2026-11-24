package com.aicontrolcenter.app.ui.approvals

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.work.InMemoryNotificationAccess
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review 5, A2: on Android 13 and later the phone operator's notifications ("Approval needed",
 * "is operating · Stop") need the owner's permission, which is asked for with the phone operator,
 * and the Approvals list says so while notifications are off.
 */
class OperatorNotificationsAskTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test
    fun `turning the phone operator on explains and asks once, apart from the objectives' ask`() = runTest(mainRule.dispatcher) {
        val settings = InMemorySettingsRepository(FrameworkSettings())
        // The owner already said no for objectives' progress; that is no answer about approvals.
        val notifications = InMemoryNotificationAccess(enabled = false, askable = false).apply { askableForOperator = true }
        val vm = OperatorNotificationsViewModel(settings, notifications)
        val state = vm.uiState.activeIn(backgroundScope)
        runCurrent()
        assertFalse(state.value.explain, "nothing is asked while the phone operator is off")

        settings.update { it.copy(operatorEnabled = true) }
        runCurrent()
        assertTrue(state.value.explain, "turning it on explains why, then Android asks")

        vm.explained()
        runCurrent()
        assertFalse(state.value.explain, "the explanation goes while Android asks")
        vm.answered()
        runCurrent()
        assertEquals(1, notifications.timesAskedForOperator)
        assertFalse(notifications.shouldAskForOperator(), "asked once: never again from here")

        // Off and on again asks nothing more; the warning banners stay instead.
        settings.update { it.copy(operatorEnabled = false) }
        settings.update { it.copy(operatorEnabled = true) }
        runCurrent()
        assertFalse(OperatorNotificationsViewModel(settings, notifications).uiState.activeIn(backgroundScope).also { runCurrent() }.value.explain)
    }

    @Test
    fun `the first start with the phone operator already on asks too, and not when notifications are allowed`() = runTest(mainRule.dispatcher) {
        val on = InMemorySettingsRepository(FrameworkSettings(operatorEnabled = true))
        val notAsked = InMemoryNotificationAccess(enabled = false).apply { askableForOperator = true }
        val first = OperatorNotificationsViewModel(on, notAsked).uiState.activeIn(backgroundScope)
        runCurrent()
        assertTrue(first.value.explain, "an upgrade from 0.4 with the operator on is asked at the first start")

        val allowed = InMemoryNotificationAccess(enabled = true).apply { askableForOperator = true }
        val none = OperatorNotificationsViewModel(on, allowed).uiState.activeIn(backgroundScope)
        runCurrent()
        assertFalse(none.value.explain, "nothing to ask when notifications are allowed")

        // "Not now" is an answer too.
        val dismissing = OperatorNotificationsViewModel(on, notAsked)
        dismissing.answered()
        assertFalse(notAsked.shouldAskForOperator())
    }

    @Test
    fun `the Approvals list warns while notifications are off`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope, FrameworkSettings(operatorEnabled = true))
        app.graph.start().join()
        app.raise(sampleApproval(AgentId("agt_operator"), createdAt = app.clock.current, minutes = 240))
        val enabled = MutableStateFlow(false)
        val list = ApprovalsViewModel(
            app.graph.ownerRequests, app.graph.registryDirectory, app.graph.capabilities::info, app.clock, ZoneOffset.UTC, flowOf(Unit), enabled,
        ).uiState.activeIn(backgroundScope)
        runCurrent()
        assertTrue(list.value.notificationsOff)
        assertEquals(1, list.value.requests.size, "the request still waits here")

        enabled.value = true
        runCurrent()
        assertFalse(list.value.notificationsOff)
    }
}
