package com.aicontrolcenter.app.work

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.AiControlCenterApp
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.health.TimedWaits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rule 5: the phone is kept awake only while agents work or are about to try again, with one alarm for a longer wait. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WakeHoldTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val now = 1_000_000_000L
    private val minute = 60_000L

    @Before
    fun clean() = ShadowPowerManager.clearWakeLocks()

    private fun input(allowed: Boolean = true, working: Boolean = false, answering: Boolean = false, due: Long? = null) =
        WakeDecision.Input(allowed, working, answering, due)

    @Test
    fun `the rule as a table`() {
        data class Row(val name: String, val input: WakeDecision.Input, val hold: Boolean, val alarmAt: Long?)
        val rows = listOf(
            Row("nothing at all", input(), hold = false, alarmAt = null),
            Row("an agent works", input(working = true), hold = true, alarmAt = null),
            Row("a chat answer is being written", input(answering = true), hold = true, alarmAt = null),
            Row("a wait due in a minute", input(due = now + minute), hold = true, alarmAt = null),
            Row("a wait due in exactly two minutes", input(due = now + TimedWaits.KEEP_AWAKE_WITHIN_MS), hold = true, alarmAt = null),
            Row("a wait already due", input(due = now - 5), hold = true, alarmAt = null),
            Row("a wait due in ten minutes: an alarm two minutes before", input(due = now + 10 * minute), hold = false, alarmAt = now + 8 * minute),
            Row("working, and a long wait as well", input(working = true, due = now + 10 * minute), hold = true, alarmAt = now + 8 * minute),
            Row("the owner switched it off: nothing, not even an alarm", input(allowed = false, working = true, due = now + 10 * minute), hold = false, alarmAt = null),
            Row("switched off, a wait due now", input(allowed = false, due = now), hold = false, alarmAt = null),
        )
        for (row in rows) {
            val decision = WakeDecision.of(row.input, now)
            assertEquals(row.hold, decision.hold, row.name)
            assertEquals(row.alarmAt, decision.alarmAt, row.name)
            assertEquals(row.alarmAt, decision.recheckAt, row.name + ": decide again when the alarm would come")
        }
        assertEquals(now, WakeDecision.earliest(null, now + 5, now))
        assertNull(WakeDecision.earliest(null, null))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the wake lock is one partial lock, not counted, taken for ten minutes and renewed every five, and released`() {
        val scope = TestScope(StandardTestDispatcher())
        val hold = WakeHold(context, scope)
        hold.set(true)
        val lock = assertNotNull(ShadowPowerManager.getLatestWakeLock())
        val shadow = shadowOf(lock)
        assertEquals(WakeHold.TAG, shadow.tag)
        assertFalse(shadow.isReferenceCounted)
        assertTrue(lock.isHeld)
        assertEquals(1, shadow.timesHeld)
        hold.set(true)
        assertEquals(1, shadow.timesHeld, "holding twice is holding once")
        scope.advanceTimeBy(WakeHold.RENEW_MS + 1)
        scope.runCurrent()
        assertEquals(2, shadow.timesHeld, "renewed after five minutes")
        hold.set(false)
        assertFalse(lock.isHeld)
        scope.advanceTimeBy(WakeHold.RENEW_MS * 3)
        assertEquals(2, shadow.timesHeld, "no renewal after the release")
        hold.release()
        scope.cancel()
    }

    @Test
    fun `the wake alarm is one alarm, allowed while idle, on the elapsed clock, to the app's own receiver`() {
        val alarms = WakeAlarms(context, elapsedNow = { 5_000L })
        val shadow = shadowOf(context.getSystemService(AlarmManager::class.java))
        alarms.set(at = now + 8 * minute, now = now)
        alarms.set(at = now + 9 * minute, now = now)
        val alarm = shadow.scheduledAlarms.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertEquals(5_000L + 9 * minute, alarm.triggerAtTime)
        assertTrue(alarm.allowWhileIdle)
        val target = shadowOf(alarm.operation).savedIntent
        assertEquals(WakeReceiver::class.java.name, target.component?.className)
        assertEquals(WakeReceiver.ACTION_WAKE, target.action)
        alarms.cancel()
        assertTrue(shadow.scheduledAlarms.isEmpty())
    }

    @Test
    fun `the receiver only wakes a graph that exists, and holds the phone awake briefly`() {
        val app = context as AiControlCenterApp
        // No graph yet: nothing is built, nothing is held.
        WakeReceiver().onReceive(context, Intent(context, WakeReceiver::class.java).setAction(WakeReceiver.ACTION_WAKE))
        assertNull(app.graphIfBuilt())
        assertNull(ShadowPowerManager.getLatestWakeLock())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val testApp = TestApp(scope)
            app.installGraph(testApp.graph)
            val signals = WakeSignals.signals.value
            WakeReceiver().onReceive(context, Intent(context, WakeReceiver::class.java).setAction("something.else"))
            assertEquals(signals, WakeSignals.signals.value, "only its own action")
            WakeReceiver().onReceive(context, Intent(context, WakeReceiver::class.java).setAction(WakeReceiver.ACTION_WAKE))
            val lock = assertNotNull(ShadowPowerManager.getLatestWakeLock())
            assertEquals(WakeReceiver.TAG, shadowOf(lock).tag)
            assertTrue(lock.isHeld)
            assertEquals(signals + 1, WakeSignals.signals.value, "the service decides again")
            val wakes = context.getSystemService(PowerManager::class.java)
            assertNotNull(wakes)
        } finally {
            scope.cancel()
        }
    }

    @After
    fun releaseAll() = ShadowPowerManager.clearWakeLocks()
}
