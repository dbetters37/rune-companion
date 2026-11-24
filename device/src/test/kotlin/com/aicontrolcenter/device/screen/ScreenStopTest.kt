package com.aicontrolcenter.device.screen

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.testkit.InMemoryOperatorAppRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** Stop on a real DataStore file: it survives a restart, and the notification's Stop reaches it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScreenStopTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @After
    fun forgetProcessSwitch() = runBlocking { StopSwitch.resetProcess() }

    /** Every step of these tests has a time limit, so a write that can never finish fails the test instead of hanging the build. */
    private suspend fun <T> step(name: String, block: suspend () -> T): T = withTimeoutOrNull(STEP_MS) { block() } ?: fail("Hung at: $name")

    /** Opens the switch's file, runs [block], then closes it as the process ending would ([joinWrites]: after its writes). */
    private suspend fun <T> withSwitch(fileName: String, joinWrites: Boolean = true, block: suspend (StopSwitch) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var switch: StopSwitch? = null
        try {
            switch = StopSwitch(DataStoreStopStore.create(context, scope, fileName), scope)
            step("$fileName: read") { switch.awaitLoaded() }
            return step("$fileName: use") { block(switch) }
        } finally {
            if (joinWrites) switch?.let { step("$fileName: writes") { it.awaitWrites() } }
            step("$fileName: close") { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
        }
    }

    @Test
    fun `Stop and Resume survive a restart`() = runBlocking {
        val file = "stop-test-1"
        assertFalse(withSwitch(file) { it.stopped.value }, "nothing stored: not stopped")

        withSwitch(file) { it.stopAndSave().join() }
        assertTrue(withSwitch(file) { it.stopped.value }, "Stop holds after a restart")

        withSwitch(file) { it.resumeAndSave().join() }
        assertFalse(withSwitch(file) { it.stopped.value }, "so does Resume")

        withSwitch(file) { switch ->
            switch.stop()
            switch.resume()
            switch.stopAndSave().join()
        }
        assertTrue(withSwitch(file) { it.stopped.value }, "the last of several changes is what is stored")
    }

    @Test
    fun `closing a switch with writes still pending never hangs, and the last change is what is stored`() = runBlocking {
        // The review's hang: changes whose writes nobody joined, then the scope closed (A6).
        repeat(ROUNDS) { round ->
            val file = "stop-test-closing-$round"
            withSwitch(file, joinWrites = false) { it.stopAndSave().join() }
            withSwitch(file, joinWrites = false) { s ->
                s.stop()
                s.resume()
                s.stopAndSave().join()
            }
            assertTrue(withSwitch(file) { it.stopped.value }, "round $round: the last change was not what was stored")
        }
    }

    @Test
    fun `awaitWrites waits for every write started, not only the last one`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val open = CompletableDeferred<Unit>()
            val written = CopyOnWriteArrayList<Boolean>()
            val store = object : StopStore {
                override suspend fun read(): Boolean = false

                override suspend fun write(stopped: Boolean) {
                    open.await()
                    written += stopped
                }
            }
            val switch = StopSwitch(store, scope)
            switch.awaitLoaded()
            val writes = listOf(switch.stopAndSave(), switch.resumeAndSave(), switch.stopAndSave())
            val waiting = async { switch.awaitWrites() }
            delay(100)
            assertFalse(waiting.isCompleted, "three writes are still waiting to be stored")
            open.complete(Unit)
            step("awaitWrites") { waiting.await() }
            assertTrue(writes.all { it.isCompleted }, "every write had finished when awaitWrites returned")
            assertEquals(3, written.size)
            assertEquals(true, written.last(), "the last one stored is the current value")
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `the notification's Stop stops the process switch and saves it`() = runBlocking {
        val switch = StopSwitch.process(context)
        switch.awaitLoaded()
        switch.resume()
        assertFalse(switch.stopped.value)

        ScreenStopReceiver().onReceive(context, Intent(ScreenStopReceiver.ACTION_STOP))
        assertTrue(switch.stopped.value)

        ScreenStopReceiver().onReceive(context, Intent("something.else"))
        assertTrue(switch.stopped.value, "other intents change nothing; the receiver never resumes")

        val pending = ScreenOperatorLayer.stopPendingIntent(context)
        val sent = shadowOf(pending).savedIntent
        assertEquals(ScreenStopReceiver.ACTION_STOP, sent.action)
        assertEquals(ScreenStopReceiver::class.java.name, sent.component?.className)
        assertTrue(shadowOf(pending).isBroadcast)
        assertTrue(shadowOf(pending).isImmutable)

        StopSwitch.resetProcess()
        val restarted = StopSwitch.process(context)
        assertNotSame(switch, restarted)
        restarted.awaitLoaded()
        assertTrue(restarted.stopped.first(), "the receiver's Stop was saved")
    }

    @Test
    fun `the layer keeps the operator and the owner's control apart`() = runBlocking {
        val layer = ScreenOperatorLayer(context, InMemoryOperatorAppRepository(), ProtectedPackages(context.packageName))

        assertFalse(layer.operator is ScreenControl, "nothing holding the operator can stop or resume")
        layer.control.stop()
        assertTrue(layer.operator.stopped.value)
        layer.control.resume()
        assertFalse(layer.operator.stopped.value)
        assertEquals(OperatorAccessibilityService::class.java.name, ScreenOperatorLayer.serviceComponent(context).className)
        assertEquals(context.packageName, ScreenOperatorLayer.serviceComponent(context).packageName)
        assertEquals("package:${context.packageName}", ScreenOperatorLayer.appInfoIntent(context).dataString)
    }

    @Test
    fun `nothing the agent-facing operator holds can resume, or authorise or withdraw an app`() {
        val layer = ScreenOperatorLayer(context, InMemoryOperatorAppRepository(), ProtectedPackages(context.packageName))
        // Walks every object the operator reaches through its fields (lambdas and the app's own classes included). The owner's
        // Stop and the app list are reachable only through the read-only views made for it, which are not entered (R2).
        val forbidden = listOf(StopSwitch::class.java, ScreenControl::class.java, OperatorAppRepository::class.java)
        val views = setOf(StopSignal::class.java, AuthorisedAppView::class.java)
        val seen = IdentityHashMap<Any, Unit>()
        val queue = ArrayDeque<Pair<Any, String>>(listOf(layer.operator to "operator"))
        val found = ArrayList<String>()
        var objects = 0
        while (queue.isNotEmpty()) {
            val (value, path) = queue.removeFirst()
            if (seen.put(value, Unit) != null) continue
            objects++
            val type = value.javaClass
            if (forbidden.any { it.isAssignableFrom(type) }) {
                found += "$path: ${type.name}"
                continue
            }
            if (type in views || !(type.name.startsWith("com.aicontrolcenter.") || type.name.startsWith("kotlin.jvm.internal."))) continue
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                for (field in current.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                    if (forbidden.any { it.isAssignableFrom(field.type) }) found += "$path.${field.name}: declared ${field.type.simpleName}"
                    field.isAccessible = true
                    field.get(value)?.let { queue += it to "$path.${field.name}" }
                }
                current = current.superclass
            }
        }
        assertEquals(emptyList(), found)
        assertTrue(objects > 10, "the walk went into the operator ($objects objects)")
    }

    private companion object {
        const val STEP_MS = 10_000L
        const val ROUNDS = 20
    }
}
