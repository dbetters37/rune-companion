package com.aicontrolcenter.device.screen

import android.content.Context
import android.os.PowerManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.ActiveWindow
import com.aicontrolcenter.core.operator.FrontState
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.core.operator.ScreenTarget
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.NOTES
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 6 on the device (Robolectric at SDK 35): holds per request (D4, S22, rule 12), a window
 * Android does not let the app read (D6), screen actions with the screen off (S15, rule 12) and
 * Android switching screen control off after the app was stopped (D7).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScreenPhase6Test {
    private fun notes() = TestWindow(NOTES, title = "Inbox") { button("Send", name = "send") }

    private suspend fun OperatorHarness.readOk(agent: String = "Scout"): ScreenSnapshot =
        assertIs<ScreenResult.Ok<ScreenSnapshot>>(operator.read(agent, NOTES)).value

    // ------------------------------------------------------------------ D4 and S22: holds per request

    @Test
    fun `a held session stays with its own banner while another run of the agent ends, and ends with the hold`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(notes())
        val banner = h.host.indicator
        h.readOk()
        h.operator.holdSession("req_1", "Scout", NOTES)
        runCurrent()
        assertEquals("Scout waits for your answer to act in Notes", banner.text)
        // Run 2 of the same agent ends while run 1's tap waits for the owner: the banner stays.
        h.operator.endSession("Scout")
        runCurrent()
        assertEquals("Scout waits for your answer to act in Notes", banner.text)
        // The idle time-out does not end it either: the owner may take minutes.
        advanceTimeBy(10 * ScreenOperator.SESSION_IDLE_MS)
        runCurrent()
        assertEquals("Scout waits for your answer to act in Notes", banner.text)
        // The answer came, the call ended: the session ends with the hold, since its run had ended.
        h.operator.releaseSession("req_1")
        runCurrent()
        assertNull(banner.text)
    }

    @Test
    fun `holds are counted per request id, and the same id is the same hold`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(notes())
        val banner = h.host.indicator
        h.readOk()
        h.operator.holdSession("req_1", "Scout", NOTES)
        h.operator.holdSession("req_1", "Scout", NOTES)
        h.operator.holdSession("req_2", "Scout", NOTES)
        h.operator.releaseSession("req_1")
        runCurrent()
        assertEquals("Scout waits for your answer to act in Notes", banner.text, "req_2 still holds it")
        h.operator.releaseSession("req_2")
        h.operator.releaseSession("unknown")
        runCurrent()
        assertEquals("Scout is operating Notes", banner.text)
        advanceTimeBy(ScreenOperator.SESSION_IDLE_MS + 1)
        runCurrent()
        assertNull(banner.text, "then it ends as usual, after the idle time")
    }

    @Test
    fun `a hold can start a session, named as the owner authorised the app`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        // The owner's stored Stop has been read (a screen tool's assessment always reads the screen first).
        runCurrent()
        h.operator.holdSession("req_1", "Scout", NOTES)
        runCurrent()
        assertEquals("Scout waits for your answer to act in Notes", h.host.indicator.text)
    }

    @Test
    fun `Stop ends every hold, and a hold asked for while stopped is not taken`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(notes())
        h.readOk()
        h.operator.holdSession("req_1", "Scout", NOTES)
        runCurrent()
        h.control.stop()
        runCurrent()
        assertNull(h.host.indicator.text)
        h.operator.holdSession("req_2", "Scout", NOTES)
        h.operator.releaseSession("req_1")
        runCurrent()
        assertNull(h.host.indicator.text)
    }

    // ------------------------------------------------------------------ D6

    @Test
    fun `a window in front whose content Android does not let the app read is Hidden, and nothing of it is read`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val secure = AccessibilityWindowInfo().also {
            val shadow = shadowOf(it)
            shadow.setId(9)
            shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            shadow.setActive(true)
            shadow.setFocused(true)
        }
        h.host.windows = listOf(secure)
        assertEquals(FrontState.Hidden, h.operator.frontState())
        assertNull(h.operator.activeWindow())
        h.show(notes())
        assertEquals(FrontState.App(ActiveWindow(NOTES, "Inbox")), h.operator.frontState())
        h.host.windows = emptyList()
        assertEquals(FrontState.None, h.operator.frontState())
    }

    // ------------------------------------------------------------------ S15, rule 12

    @Test
    fun `with the screen off every screen call is refused and nothing of the window is read`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(notes())
        h.facts.screenIsOn = false
        val refusal = ScreenResult.NotSupported(AccessibilityScreenOperator.SCREEN_OFF)
        assertEquals(refusal, h.operator.read("Scout", NOTES))
        assertEquals(refusal, h.operator.inspect(NOTES))
        assertEquals(refusal, h.operator.find(NOTES, ScreenTarget.ExactText("Send")))
        runCurrent()
        assertNull(h.host.indicator.text, "no session started")
        assertEquals("The phone's screen is off. Screen actions wait until you use the phone.", AccessibilityScreenOperator.SCREEN_OFF)
        h.facts.screenIsOn = true
        assertIs<ScreenResult.Ok<ScreenSnapshot>>(h.operator.read("Scout", NOTES))
    }

    @Test
    fun `an action whose screen went off between reading and pressing is not taken`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = notes()
        h.show(window)
        val send = h.readOk().elements.single { it.text == "Send" }
        h.facts.screenIsOn = false
        assertEquals(ScreenResult.NotSupported(AccessibilityScreenOperator.SCREEN_OFF), h.operator.tap("Scout", send))
        assertTrue(window.log.isEmpty(), "nothing was pressed")
    }

    @Test
    fun `Android's own facts say the screen is off when PowerManager is not interactive`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val facts = AndroidDeviceFacts(context)
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsInteractive(false)
        assertFalse(facts.screenOn())
        power.setIsInteractive(true)
        assertTrue(facts.screenOn())
    }

    // ------------------------------------------------------------------ D7

    private class MemoryFlag(initial: Boolean = false) : ReadyFlagStore {
        @Volatile var stored: Boolean = initial

        override fun wasReady() = stored

        override fun setReady(ready: Boolean) {
            stored = ready
        }
    }

    @Test
    fun `a service that was ready and is off at the next start was switched off by Android`() {
        val flag = MemoryFlag()
        val first = SwitchedOffWatch(flag, 34) { emptyList() }
        first.onState(ScreenServiceState.CHECKING)
        first.onState(ScreenServiceState.NOT_ENABLED)
        assertFalse(first.switchedOff.value, "never ready before: nothing to explain")
        first.onState(ScreenServiceState.READY)
        assertTrue(flag.stored)

        // The app is stopped; at the next start Android has removed the service (a sideloaded app reads RESTRICTED).
        val second = SwitchedOffWatch(flag, 34) { listOf(StartRecord(wasForceStopped = true)) }
        second.onState(ScreenServiceState.CHECKING)
        second.onState(ScreenServiceState.RESTRICTED)
        assertTrue(second.switchedOff.value)
        assertEquals(SwitchedOffWatch.STOPPED, second.sentence.value, "before Android 15 a force stop cannot be told from a swipe")
        // It stays until the owner turns it on again.
        second.onState(ScreenServiceState.NOT_ENABLED)
        assertTrue(second.switchedOff.value)
        second.onState(ScreenServiceState.STARTING)
        second.onState(ScreenServiceState.READY)
        assertFalse(second.switchedOff.value)
        assertNull(second.sentence.value)
    }

    @Test
    fun `on Android 15 and later the notice says force-stopped only when Android says so`() {
        val forced = SwitchedOffWatch(MemoryFlag(initial = true), 35) { listOf(StartRecord(wasForceStopped = true)) }
        forced.onState(ScreenServiceState.NOT_ENABLED)
        assertEquals(SwitchedOffWatch.FORCE_STOPPED, forced.sentence.value)
        val swiped = SwitchedOffWatch(MemoryFlag(initial = true), 35) { listOf(StartRecord(wasForceStopped = false)) }
        swiped.onState(ScreenServiceState.NOT_ENABLED)
        assertEquals(SwitchedOffWatch.STOPPED, swiped.sentence.value)
        val unreadable = SwitchedOffWatch(MemoryFlag(initial = true), 36) { error("no records") }
        unreadable.onState(ScreenServiceState.NOT_ENABLED)
        assertEquals(SwitchedOffWatch.STOPPED, unreadable.sentence.value)
    }

    @Test
    fun `switching it off while the app runs is the owner's choice and says nothing, now or after a restart`() {
        val flag = MemoryFlag()
        val running = SwitchedOffWatch(flag, 35) { listOf(StartRecord(true)) }
        running.onState(ScreenServiceState.READY)
        running.onState(ScreenServiceState.NOT_ENABLED)
        assertFalse(running.switchedOff.value)
        assertFalse(flag.stored)
        val next = SwitchedOffWatch(flag, 35) { listOf(StartRecord(true)) }
        next.onState(ScreenServiceState.NOT_ENABLED)
        assertFalse(next.switchedOff.value)
        // Advanced Protection switching it off is Android's own, explained elsewhere.
        val protectedStart = SwitchedOffWatch(MemoryFlag(initial = true), 37) { emptyList() }
        protectedStart.onState(ScreenServiceState.ADVANCED_PROTECTION)
        protectedStart.onState(ScreenServiceState.NOT_ENABLED)
        assertFalse(protectedStart.switchedOff.value)
    }

    @Test
    fun `the ready flag is kept in the app's own preferences across restarts`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferencesReadyFlagStore(context).setReady(true)
        assertTrue(PreferencesReadyFlagStore(context).wasReady())
        PreferencesReadyFlagStore(context).setReady(false)
        assertFalse(PreferencesReadyFlagStore(context).wasReady())
    }

    @Test
    fun `the owner's control reports what the watch decided`() {
        val watch = SwitchedOffWatch(MemoryFlag(initial = true), 35) { emptyList() }
        val switch = StopSwitch(InMemoryStopStore(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val control = SwitchScreenControl(switch, watch.switchedOff)
        assertFalse(control.switchedOffByAndroid.value)
        watch.onState(ScreenServiceState.NOT_ENABLED)
        assertTrue(control.switchedOffByAndroid.value)
    }
}
