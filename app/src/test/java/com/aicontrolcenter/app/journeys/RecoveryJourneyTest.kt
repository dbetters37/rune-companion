package com.aicontrolcenter.app.journeys

import android.app.Application
import android.os.Process
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.MainActivity
import com.aicontrolcenter.app.health.AppGate
import com.aicontrolcenter.app.health.GateHost
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.typeFast
import com.aicontrolcenter.app.ui.recovery.RecoveryHost
import com.aicontrolcenter.app.ui.recovery.RecoveryViewModel
import com.aicontrolcenter.app.ui.recovery.STILL_NOT_OPENING
import com.aicontrolcenter.app.ui.shell.AiControlCenterRoot
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.testkit.FakeOwnerRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowProcess
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R5 (rules 9, 27, 30): when the data does not open, the app's gate shows the Recovery screen instead of the app, for
 * each reason with its own words; Try again looks again and opens the app once the data opens; Start over, after the
 * typed word, ends with a restart into the main activity and nothing else. The window is secure throughout. As in the
 * main activity: `GateHost(gate, recovery = { RecoveryHost(it, recovery, gate::recheck) }) { AiControlCenterRoot(graph) }`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class RecoveryJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val checks = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app = TestApp(scope)
    private val recovery = FakeOwnerRecovery()
    private val gate = mutableStateOf(AppGate.open())

    @After
    fun stop() {
        scope.cancel()
        checks.cancel()
        ShadowProcess.clearKilledProcesses()
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            condition()
        }
        compose.waitForIdle()
    }

    private fun shown(text: String): Boolean = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** The gate as the application makes it: the check runs off the main thread, the answer lands on it. */
    private fun startWith(state: DatabaseState) {
        recovery.databaseState = state
        compose.runOnUiThread { gate.value = AppGate(recovery, checks) }
    }

    private fun show() {
        runBlocking { app.graph.start().join() }
        compose.setContent {
            val current = gate.value
            GateHost(current, recovery = { state -> RecoveryHost(state, recovery, onTryAgain = current::recheck) }) {
                AiControlCenterRoot(app.graph, darkTheme = true)
            }
        }
        compose.waitForIdle()
    }

    private val secure: Boolean get() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun `each reason the data does not open has its own words on a secure Recovery screen, never the app`() {
        show()
        for (state in DatabaseState.entries.filter { it.needsRecovery }) {
            startWith(state)
            waitFor { shown(state.ownerText) }
            assertTrue(shown(RecoveryViewModel.adviceFor(state)), "$state: what to try first")
            assertTrue(shown(RecoveryViewModel.TITLE))
            assertTrue(secure, "$state: FLAG_SECURE")
            assertTrue(!shown("agents ready to run"), "$state: the app is not shown")
        }
    }

    @Test
    fun `Try again looks again, still not opening says so, and once the data opens the app is there`() {
        show()
        startWith(DatabaseState.KEYSTORE_UNAVAILABLE)
        waitFor { shown(DatabaseState.KEYSTORE_UNAVAILABLE.ownerText) }
        compose.onAllNodes(hasText("Try again") and hasClickAction()).onFirst().performClick()
        waitFor { shown(STILL_NOT_OPENING) }

        recovery.databaseState = DatabaseState.OK
        compose.onAllNodes(hasText("Try again") and hasClickAction()).onFirst().performClick()
        waitFor { shown("agents ready to run") }
        assertTrue(!secure, "the app itself is not a secure window")
        assertEquals(0, recovery.startOvers.get(), "nothing was started over")
    }

    @Test
    fun `Start over after the typed word restarts into the main activity, with no extra`() {
        show()
        startWith(DatabaseState.KEY_DAMAGED)
        waitFor { shown(DatabaseState.KEY_DAMAGED.ownerText) }
        val word = compose.onNode(hasSetTextAction() and hasText("Type START OVER to confirm", substring = true))
        word.typeFast(compose, "START OVER")
        compose.onAllNodes(hasText("Start over") and hasClickAction()).onFirst().performScrollTo().performClick()
        waitFor { recovery.startOvers.get() == 1 }
        val restart = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity ?: error("no restart")
        assertEquals(MainActivity::class.java.name, restart.component?.className)
        assertNull(restart.extras, "no extra opens anything (rule 23)")
        assertTrue(ShadowProcess.wasKilled(Process.myPid()), "rule 30: the process ends")
    }
}
