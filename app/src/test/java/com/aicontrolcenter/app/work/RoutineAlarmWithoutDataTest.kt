package com.aicontrolcenter.app.work

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.AiControlCenterApp
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.di.wiring.RoutinesPorts
import com.aicontrolcenter.app.di.wiring.RoutinesWiring
import com.aicontrolcenter.app.health.AppGate
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.health.RecoveryResult
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.data.security.DatabaseKeyException
import com.aicontrolcenter.data.security.KeyProblem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AND-W2B-2: a routine's alarm starts the work service while the app's data cannot be opened. The service builds the
 * graph only once the data opened (rule 9, R5), and a failure of the database under it never ends the process.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoutineAlarmWithoutDataTest {
    // As production's app scope: an exception there is logged and swallowed.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> })
    private val context: AiControlCenterApp get() = ApplicationProvider.getApplicationContext()
    private val broken = DatabaseKeyException("The database key could not be unsealed.", problem = KeyProblem.DAMAGED)

    @After
    fun close() {
        ObjectiveWorkService.runDue = { it.routinesAssembly.runDue() }
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class Recovery(private val answer: CompletableDeferred<DatabaseState>) : OwnerRecovery {
        override suspend fun state(): DatabaseState = answer.await()

        override suspend fun startOver(): RecoveryResult = throw UnsupportedOperationException()
    }

    /** Starts the service as the routine's alarm does; what reached the main thread's uncaught handler. */
    private fun alarm(): Pair<org.robolectric.android.controller.ServiceController<ObjectiveWorkService>, List<Throwable>> {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val thread = Thread.currentThread()
        val before = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val controller = Robolectric.buildService(ObjectiveWorkService::class.java).create()
                .withIntent(Intent(context, ObjectiveWorkService::class.java).setAction(RoutinesWiring.ACTION_RUN_DUE_ROUTINES))
                .startCommand(0, 1)
            idle()
            return controller to uncaught
        } finally {
            thread.uncaughtExceptionHandler = before
        }
    }

    @Test
    fun `while the data cannot be opened, the alarm builds no graph, runs nothing, and the service stops`() {
        var runs = 0
        ObjectiveWorkService.runDue = { runs++ }
        context.installGate(AppGate(Recovery(CompletableDeferred(DatabaseState.KEY_DAMAGED)), scope))
        val (controller, uncaught) = alarm()
        assertEquals(emptyList(), uncaught.toList())
        assertEquals(0, runs)
        assertNull(context.graphIfBuilt(), "the graph is never built while the data cannot be opened")
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `while the data is being opened the alarm waits, and runs the routines once it opened`() {
        var runs = 0
        ObjectiveWorkService.runDue = { runs++ }
        val answer = CompletableDeferred<DatabaseState>()
        context.installGate(AppGate(Recovery(answer), scope))
        val (controller, uncaught) = alarm()
        assertEquals(0, runs)
        assertNull(context.graphIfBuilt())
        // The data opens; the graph (a test's here) is built then.
        val app = TestApp(scope)
        val gate = context.gate
        context.installGraph(app.graph)
        context.installGate(gate)
        answer.complete(DatabaseState.OK)
        idle()
        assertEquals(emptyList(), uncaught.toList())
        assertEquals(1, runs)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf, "nothing runs: it stops")
    }

    @Test
    fun `a database that fails under the routines and the log never ends the process`() {
        val app = TestApp(scope, FrameworkSettings(routinesEnabled = true))
        val routines = object : RoutineRepository by app.routineStore {
            override suspend fun getAll(): List<Routine> = throw broken
        }
        val log = object : ActivityLog by app.activityLog {
            override suspend fun record(event: ActivityEvent) = throw broken
        }
        val graph = AppGraph(
            app.ports.copy(activityLog = log, routines = RoutinesPorts(routines = routines, pickUpRules = app.pickUpRuleStore)),
            scope,
            backgroundContext = EmptyCoroutineContext,
        )
        context.installGraph(graph)
        val (controller, uncaught) = alarm()
        assertEquals(emptyList(), uncaught.map { it.javaClass.simpleName })
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }
}
