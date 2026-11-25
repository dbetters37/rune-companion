package com.aicontrolcenter.app.operator

import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.framework.operator.AuthorisedApps
import com.aicontrolcenter.testkit.FakeControl
import com.aicontrolcenter.testkit.FakeScreen
import com.aicontrolcenter.testkit.FakeScreenOperator
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6, D4 (rule 12): the app's view of who operates which app keeps a session while one of
 * its screen actions waits for the owner, passes the hold on to the device's operator, and lets
 * the notification say "<agent> waits for your answer".
 */
class ScreenSessionHoldsTest {
    private val notes = FakeScreen("org.example.notes", "Notes", listOf(FakeControl(text = "New note")))
    private val device = FakeScreenOperator(screen = notes)
    private val apps = AuthorisedApps { pkg -> if (pkg == notes.packageName) OperatorApp(pkg, "Notes", 0) else null }

    @Test
    fun `a held session stays through another run's end and the idle time, and is passed on to the device`() = runTest {
        val sessions = ScreenSessions(device, apps, backgroundScope)
        runCurrent()
        sessions.read("Operator", notes.packageName)
        sessions.holdSession("req_1", "Operator", notes.packageName)
        assertEquals(FakeScreenOperator.Hold("Operator", notes.packageName), device.holds["req_1"])
        assertEquals(listOf(ScreenSession("Operator", notes.packageName, "Notes", waitingForOwner = true)), sessions.sessions.value)

        sessions.endSession("Operator")
        advanceTimeBy(5 * ScreenOperator.SESSION_IDLE_MS)
        runCurrent()
        assertEquals(1, sessions.sessions.value.size, "neither another run's end nor the idle time ends a held session")

        sessions.releaseSession("req_1")
        runCurrent()
        assertEquals(listOf("req_1"), device.releasedHolds.toList())
        assertTrue(sessions.sessions.value.isEmpty(), "its run had ended: the session ends with the hold")
    }

    @Test
    fun `a hold can start a session with the app's name, and the session ends after the idle time once released`() = runTest {
        val sessions = ScreenSessions(device, apps, backgroundScope)
        runCurrent()
        sessions.holdSession("req_1", "Operator", notes.packageName)
        runCurrent()
        assertEquals(listOf(ScreenSession("Operator", notes.packageName, "Notes", waitingForOwner = true)), sessions.sessions.value)
        sessions.releaseSession("req_1")
        assertEquals(listOf(ScreenSession("Operator", notes.packageName, "Notes", waitingForOwner = false)), sessions.sessions.value)
        advanceTimeBy(ScreenOperator.SESSION_IDLE_MS + 1)
        runCurrent()
        assertTrue(sessions.sessions.value.isEmpty())
    }

    @Test
    fun `Stop ends every hold`() = runTest {
        val sessions = ScreenSessions(device, apps, backgroundScope)
        runCurrent()
        sessions.read("Operator", notes.packageName)
        sessions.holdSession("req_1", "Operator", notes.packageName)
        device.stop()
        runCurrent()
        assertTrue(sessions.sessions.value.isEmpty())
        assertTrue(device.holds.isEmpty())
        sessions.holdSession("req_2", "Operator", notes.packageName)
        assertTrue(sessions.sessions.value.isEmpty(), "nothing is held while stopped")
    }
}
