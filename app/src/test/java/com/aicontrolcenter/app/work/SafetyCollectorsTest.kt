package com.aicontrolcenter.app.work

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.operator.ScreenSession
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rule 26 (N10): the safety collectors fail stop, not silent. An exception injected into each collector's flow restarts
 * it with a growing wait and a WARNING; three failures within five minutes run its fail-stop action.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SafetyCollectorsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val log = InMemoryActivityLog()
    private val clock = FakeClock(1_000_000)
    private val recorder = ActivityRecorder(log, SequentialIdGenerator(), clock)

    /** A flow that fails at once, as a broken store or a closed database would. */
    private fun <T> failing(): Flow<T> = flow { throw IllegalStateException("injected failure") }

    @Test
    fun `a failing collector is restarted after 1, 2, 4 seconds and so on up to a minute, and each failure is logged`() = runTest {
        val waits = mutableListOf<Long>()
        var runs = 0
        val safety = SafetyCollectors(backgroundScope, recorder, clock, pause = { waits += it; clock.current += it; if (waits.size >= 9) kotlinx.coroutines.awaitCancellation() })
        val notifier = ApprovalNotifier(context, failing<List<OwnerRequest>>(), clock, backgroundScope, leftover = { emptySet() })
        safety.supervise(GraphServices.APPROVALS) { runs++; notifier.run() }
        runCurrent()
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000), waits)
        assertEquals(9, runs)
        val warnings = log.all.filter { it.severity == Severity.WARNING }
        assertEquals(9, warnings.size)
        assertTrue(warnings.first().title.startsWith(GraphServices.APPROVALS))
        assertTrue("IllegalStateException" in warnings.first().detail)
        assertTrue(warnings.none { "injected failure" in it.detail }, "the class only, never the message")
    }

    @Test
    fun `the operating notification failing three times within five minutes stops screen control`() = runTest {
        var stops = 0
        var pauses = 0
        val safety = SafetyCollectors(backgroundScope, recorder, clock, pause = { clock.current += it; pauses++; if (pauses >= 3) kotlinx.coroutines.awaitCancellation() })
        val stop = PendingIntent.getBroadcast(context, 7, Intent("test.STOP").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val notifier = ScreenSessionNotifier(context, failing<List<ScreenSession>>(), { stop }, backgroundScope)
        safety.supervise(GraphServices.SCREEN, onRepeatedFailure = { stops++ }) { notifier.run() }
        runCurrent()
        assertEquals(1, stops, "the third failure stops screen control")
        assertTrue(log.all.last().detail.contains("stopped what depends on it"))
    }

    @Test
    fun `failures spread over more than five minutes stop nothing`() = runTest {
        var stops = 0
        var pauses = 0
        val safety = SafetyCollectors(backgroundScope, recorder, clock, pause = {
            clock.current += 4 * 60_000L
            pauses++
            if (pauses >= 6) kotlinx.coroutines.awaitCancellation()
        })
        safety.supervise(GraphServices.SCREEN, onRepeatedFailure = { stops++ }) { throw IllegalStateException("again") }
        runCurrent()
        assertEquals(0, stops)
    }

    @Test
    fun `the controller failing three times pauses the running objectives`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val app = TestApp(scope)
            var paused = 0
            var pauses = 0
            val safety = SafetyCollectors(backgroundScope, recorder, clock, pause = { clock.current += it; pauses++; if (pauses >= 3) kotlinx.coroutines.awaitCancellation() })
            val controller = ObjectiveWorkController(context, app.graph, scope, health = failing<BackgroundState>())
            safety.supervise(GraphServices.CONTROLLER, onRepeatedFailure = { paused++ }) { controller.run() }
            runCurrent()
            assertEquals(1, paused)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the result notifier's failures are restarted too`() = runTest {
        var runs = 0
        val safety = SafetyCollectors(backgroundScope, recorder, clock, pause = { clock.current += it; if (runs >= 2) kotlinx.coroutines.awaitCancellation() })
        val notifier = ResultNotifier(context, failing(), kotlinx.coroutines.flow.MutableStateFlow(false), com.aicontrolcenter.testkit.InMemorySettingsRepository(), { null }, post = {})
        safety.supervise(GraphServices.RESULTS) { runs++; notifier.run() }
        runCurrent()
        assertEquals(2, runs)
    }
}
