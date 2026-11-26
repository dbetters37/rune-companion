package com.aicontrolcenter.app.ui.settings

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.TestBackgroundScreens
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.StandbyBucket
import com.aicontrolcenter.device.health.BackgroundSettingsIntents
import com.aicontrolcenter.testkit.FakeBackgroundHealth
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * WP10b's Settings view models: Questions from agents (Q3: on by default, 20 minutes, 5..240), Appearance: Calm (rule 17),
 * and Working in the background (A3): what each Android state reads as, the buttons, a missing screen, the two switches.
 */
class WP10bSettingsViewModelsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.questions(app: TestApp) = QuestionSettingsViewModel(app.graph.settings).also { it.uiState.activeIn(backgroundScope) }

    private fun TestScope.motion(app: TestApp) = MotionSettingsViewModel(app.graph.settings).also { it.uiState.activeIn(backgroundScope) }

    private fun TestScope.background(app: TestApp, health: FakeBackgroundHealth = app.backgroundHealth, screens: TestBackgroundScreens = TestBackgroundScreens()) =
        BackgroundSectionViewModel(health, screens, app.graph.settings).also { it.uiState.activeIn(backgroundScope) }

    // --- Questions from agents ---

    @Test
    fun `questions are on by default with 20 minutes to answer, and the choices run from 5 minutes to 4 hours`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val state = questions(app).uiState.value
        assertTrue(state.questionsOn)
        assertEquals(20, state.timeoutMinutes)
        assertEquals(
            listOf("5 minutes", "10 minutes", "20 minutes (usual)", "30 minutes", "1 hour", "2 hours", "4 hours"),
            state.choices.map { it.label },
        )
    }

    @Test
    fun `switching questions off and choosing a time store them, and a time outside 5 to 240 minutes is ignored`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = questions(app)
        viewModel.setQuestionsOn(false)
        assertFalse(app.settings.get().agentQuestionsEnabled)
        assertFalse(viewModel.uiState.value.questionsOn)
        viewModel.setTimeoutMinutes(60)
        assertEquals(60, app.settings.get().ownerQuestionTimeoutMinutes)
        assertEquals(60, viewModel.uiState.value.timeoutMinutes)
        viewModel.setTimeoutMinutes(4)
        viewModel.setTimeoutMinutes(241)
        assertEquals(60, app.settings.get().ownerQuestionTimeoutMinutes)
    }

    @Test
    fun `a stored time that is not one of the choices is shown in its place`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.settings.update { it.copy(ownerQuestionTimeoutMinutes = 45) }
        val state = questions(app).uiState.value
        assertEquals(45, state.timeoutMinutes)
        assertEquals(listOf(5, 10, 20, 30, 45, 60, 120, 240), state.choices.map { it.minutes })
        assertEquals("45 minutes", state.choices.single { it.minutes == 45 }.label)
    }

    // --- Calm ---

    @Test
    fun `Calm is off by default and the switch stores it`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = motion(app)
        assertFalse(viewModel.uiState.value.calm)
        viewModel.setCalm(true)
        assertTrue(app.settings.get().calmMotion)
        assertTrue(viewModel.uiState.value.calm)
        viewModel.setCalm(false)
        assertFalse(app.settings.get().calmMotion)
    }

    // --- Working in the background ---

    @Test
    fun `everything allowed reads as all clear, with an icon and words for each item`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.backgroundHealth.current.value = BackgroundState(notificationsAllowed = true, batteryOptimised = false, backgroundRestricted = false, standbyBucket = StandbyBucket.ACTIVE)
        val state = background(app).uiState.value
        assertTrue(state.allClear)
        assertEquals(BackgroundSectionViewModel.ALL_CLEAR, state.summary)
        assertEquals(listOf("Allowed", "Does not hold the app back", "Not restricted", "Often enough"), state.items.map { it.status })
        assertTrue(state.items.all { it.ok == true && it.tone == StatusTone.READY && it.fix == null })
        assertTrue(state.keepAwake && state.notifyWhenWorkEnds, "both switches are on from the start")
    }

    @Test
    fun `each thing Android holds back says so in the Dashboard's words, with the button that fixes it`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.backgroundHealth.current.value = BackgroundState(notificationsAllowed = false, batteryOptimised = true, backgroundRestricted = true, standbyBucket = StandbyBucket.RARE)
        val state = background(app).uiState.value
        assertFalse(state.allClear)
        assertEquals(BackgroundSectionViewModel.NOT_CLEAR, state.summary)
        val byKey = state.items.associateBy { it.key }
        assertEquals("Off", byKey.getValue("notifications").status)
        assertEquals(AndroidScreen.APP_PAGE, byKey.getValue("notifications").fix)
        assertEquals("Battery saving applies to the app", byKey.getValue("battery").status)
        assertEquals(AndroidScreen.BATTERY, byKey.getValue("battery").fix)
        assertEquals("Let the app run in the background", byKey.getValue("battery").fixLabel)
        assertEquals("Background use is restricted", byKey.getValue("restricted").status)
        assertEquals(AndroidScreen.APP_PAGE, byKey.getValue("restricted").fix)
        assertEquals("Android rarely lets it run", byKey.getValue("standby").status)
        assertTrue(state.items.all { it.ok == false && it.tone == StatusTone.ATTENTION })
        assertTrue(byKey.getValue("battery").spoken.startsWith("Battery saving: Battery saving applies to the app."), "read as one phrase")
    }

    @Test
    fun `what Android does not say is shown as not known, never as a problem`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val state = background(app).uiState.value
        assertTrue(state.allClear, "nothing known, nothing to warn about")
        val unknown = state.items.filter { it.ok == null }
        assertEquals(listOf("battery", "restricted", "standby"), unknown.map { it.key })
        assertTrue(unknown.all { it.status == BackgroundSectionViewModel.NOT_KNOWN && it.tone == StatusTone.INFO && it.fix == null })
    }

    @Test
    fun `the buttons hand out Android's screens, and a missing screen says so and offers the app's page`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val screens = TestBackgroundScreens()
        val viewModel = background(app, screens = screens)
        assertSame(screens.battery, viewModel.intentFor(AndroidScreen.BATTERY))
        assertSame(screens.appPage, viewModel.intentFor(AndroidScreen.APP_PAGE))

        viewModel.screenMissing(AndroidScreen.BATTERY)
        assertEquals(BackgroundSettingsIntents.NO_SUCH_SCREEN, viewModel.uiState.value.message)
        assertEquals(AndroidScreen.APP_PAGE, viewModel.uiState.value.offer)
        viewModel.screenMissing(AndroidScreen.APP_PAGE)
        assertEquals(BackgroundSectionViewModel.NO_APP_PAGE, viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.offer)
        viewModel.screenOpened()
        assertNull(viewModel.uiState.value.message)

        screens.battery = null
        assertNull(viewModel.intentFor(AndroidScreen.BATTERY), "no intent: the screen says it is missing")
    }

    @Test
    fun `Check again asks Android again, and a change there shows at once`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.backgroundHealth.current.value = BackgroundState(notificationsAllowed = true, batteryOptimised = true, backgroundRestricted = false, standbyBucket = StandbyBucket.ACTIVE)
        val viewModel = background(app)
        assertFalse(viewModel.uiState.value.allClear)
        viewModel.refresh()
        assertEquals(1, app.backgroundHealth.refreshes)
        assertFalse(viewModel.uiState.value.checking)
        app.backgroundHealth.current.value = app.backgroundHealth.current.value.copy(batteryOptimised = false)
        assertTrue(viewModel.uiState.value.allClear)
    }

    @Test
    fun `the two switches store keep awake and tell me when work ends`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = background(app)
        viewModel.setKeepAwake(false)
        viewModel.setNotifyWhenWorkEnds(false)
        assertFalse(app.settings.get().keepAwakeWhileAgentsWork)
        assertFalse(app.settings.get().notifyWhenWorkEnds)
        assertFalse(viewModel.uiState.value.keepAwake)
        assertFalse(viewModel.uiState.value.notifyWhenWorkEnds)
        viewModel.setKeepAwake(true)
        assertTrue(app.settings.get().keepAwakeWhileAgentsWork)
    }
}
