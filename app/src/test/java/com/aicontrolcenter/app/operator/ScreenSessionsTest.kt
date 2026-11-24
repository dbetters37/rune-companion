package com.aicontrolcenter.app.operator

import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenTarget
import com.aicontrolcenter.framework.operator.AuthorisedApps
import com.aicontrolcenter.testkit.FakeControl
import com.aicontrolcenter.testkit.FakeScreen
import com.aicontrolcenter.testkit.FakeScreenOperator
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Who is operating which app, as the "is operating" notification shows it. */
class ScreenSessionsTest {
    private val notes = FakeScreen("org.example.notes", "Notes", listOf(FakeControl(text = "New note")))
    private val device = FakeScreenOperator(screen = notes)
    private val apps = AuthorisedApps { pkg -> if (pkg == notes.packageName) OperatorApp(pkg, "Notes", 0) else null }

    @Test
    fun `a call past the checks starts a session, which ends at endSession, after the idle time, or at Stop`() = runTest {
        val sessions = ScreenSessions(device, apps, backgroundScope)
        runCurrent()

        assertIs<ScreenResult.Ok<*>>(sessions.read("Operator", notes.packageName))
        assertEquals(listOf(ScreenSession("Operator", notes.packageName, "Notes")), sessions.sessions.value)

        sessions.endSession("Operator")
        assertTrue(sessions.sessions.value.isEmpty())
        assertEquals(listOf("Operator"), device.endedSessions, "the device is told too")

        sessions.read("Operator", notes.packageName)
        advanceTimeBy(ScreenOperator.SESSION_IDLE_MS - 1)
        runCurrent()
        assertEquals(1, sessions.sessions.value.size, "still within the idle time")
        val element = (sessions.find(notes.packageName, ScreenTarget.ExactText("New note")) as ScreenResult.Ok).value
        sessions.tap("Operator", element)
        advanceTimeBy(ScreenOperator.SESSION_IDLE_MS - 1)
        runCurrent()
        assertEquals(1, sessions.sessions.value.size, "each call starts the idle time again")
        advanceTimeBy(2)
        runCurrent()
        assertTrue(sessions.sessions.value.isEmpty(), "ended after the idle time")

        sessions.read("Operator", notes.packageName)
        device.stop()
        runCurrent()
        assertTrue(sessions.sessions.value.isEmpty(), "Stop ends every session")
    }

    @Test
    fun `a refused call starts nothing, and the view cannot press Stop or Resume`() = runTest {
        val sessions = ScreenSessions(device, apps, backgroundScope)
        assertIs<ScreenResult.NotAuthorised>(sessions.read("Operator", "org.example.bank"))
        assertTrue(sessions.sessions.value.isEmpty())
        assertFalse((sessions as Any) is ScreenControl, "the agent-side view is never the owner's Stop and Resume")
    }
}
