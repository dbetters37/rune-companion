package com.aicontrolcenter.device.screen

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.testkit.InMemoryOperatorAppRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
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
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    /**
     * Runs what is dispatched to it only when told to, the newest first: the order two threads of
     * the IO pool can give two writes, where the write started last reaches the store first.
     */
    private class NewestFirst : CoroutineDispatcher() {
        private val waiting = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(waiting) { waiting.addLast(block) }
        }

        fun runAll() {
            while (true) (synchronized(waiting) { waiting.removeLastOrNull() } ?: return).run()
        }
    }

    @Test
    fun `awaitWrites waits for every write started, also when the one started last finishes first`() = runBlocking {
        val threads = NewestFirst()
        val scope = CoroutineScope(SupervisorJob() + threads)
        try {
            val turns = Channel<Unit>(Channel.UNLIMITED)
            val written = CopyOnWriteArrayList<Boolean>()
            val store = object : StopStore {
                override suspend fun read(): Boolean = false

                override suspend fun write(stopped: Boolean) {
                    turns.receive()
                    written += stopped
                }
            }
            val switch = StopSwitch(store, scope)
            threads.runAll()
            switch.awaitLoaded()
            val first = switch.stopAndSave()
            val second = switch.resumeAndSave()
            val last = switch.stopAndSave()
            // The write started last gets to the store first; the two before it queue behind it.
            threads.runAll()
            turns.send(Unit)
            threads.runAll()
            assertTrue(last.isCompleted, "the write started last has finished")
            assertFalse(first.isCompleted || second.isCompleted, "the two started before it have not")

            val waiting = async(Dispatchers.Unconfined) { switch.awaitWrites() }
            assertFalse(waiting.isCompleted, "joining the last write alone is not enough: two are still to be stored (A6)")
            repeat(2) {
                turns.send(Unit)
                threads.runAll()
            }
            step("awaitWrites") { waiting.await() }
            assertTrue(first.isCompleted && second.isCompleted, "every write had finished when awaitWrites returned")
            assertEquals(listOf(true, true, true), written.toList(), "each write stores the value current when it runs")
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            threads.runAll()
        }
    }

    /**
     * S7: what failed "the notification's Stop stops the process switch and saves it" once in a while. That test presses
     * Stop and at once closes the switch as a restart would, which waits for the writes; the wait listed the writes in
     * flight with a call that, for a single write, asks how many there are and then for the first, and a write finishing
     * on its own thread in between left none (NoSuchElementException).
     */
    @Test
    fun `waiting for the writes never fails while a write is finishing on another thread`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val switch = StopSwitch(InMemoryStopStore(), scope)
            step("read") { switch.awaitLoaded() }
            repeat(WAIT_ROUNDS) { round ->
                if (round % 2 == 0) switch.stop() else switch.resume()
                // One write was just started and finishes about now.
                step("round $round") { switch.awaitWrites() }
            }
            assertFalse(switch.stopped.value)
        } finally {
            scope.cancel()
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
    fun `the layer reads Android's settings again in the background, never on the caller's thread`() {
        val background = NewestFirst()
        val layer = ScreenOperatorLayer(context, InMemoryOperatorAppRepository(), { ProtectedPackages(context.packageName) }, io = background)
        // The first read is started by the layer itself, so no screen has to do it on the main thread.
        background.runAll()
        val before = layer.operator.state.value
        assertNotEquals(ScreenServiceState.STARTING, before)

        // The owner switches the service on in Android's settings and comes back to Screen control, on the main thread.
        val me = ScreenOperatorLayer.serviceComponent(context)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, me.flattenToString())
        layer.refreshStateInBackground()
        assertEquals(before, layer.operator.state.value, "nothing is read on the caller's thread (A7)")
        background.runAll()
        assertEquals(ScreenServiceState.STARTING, layer.operator.state.value, "the state follows once the background read is done")
    }

    /** S6: a screen that looks at the state before the layer's first read gets "not known yet"; it reads nothing itself. */
    @Test
    fun `the state is not known until the layer's first background read, whoever looks first`() {
        val me = ScreenOperatorLayer.serviceComponent(context)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, me.flattenToString())
        val background = NewestFirst()
        val layer = ScreenOperatorLayer(context, InMemoryOperatorAppRepository(), { ProtectedPackages(context.packageName) }, io = background)

        // The Screen control screen opens at once, on the main thread, before the background read ran.
        assertEquals(ScreenServiceState.CHECKING, layer.operator.state.value, "the caller's thread read Android's settings")
        assertEquals(ScreenServiceState.CHECKING, layer.operator.state.value)
        background.runAll()
        assertEquals(ScreenServiceState.STARTING, layer.operator.state.value, "the state follows once the background read is done")
    }

    /**
     * S7: the layer's own scope (the default thread pool in the app) follows the service connecting and going away. It
     * used to read Android's settings right there, at a moment of its own choosing, which made the test above fail
     * about once in 60 runs. Every read now runs where the layer says Android is read.
     */
    @Test
    fun `the layer's own scope never reads Android's settings, only its background dispatcher does`() {
        val own = NewestFirst()
        val background = NewestFirst()
        val scope = CoroutineScope(SupervisorJob() + own)
        try {
            val layer = ScreenOperatorLayer(context, InMemoryOperatorAppRepository(), { ProtectedPackages(context.packageName) }, scope = scope, io = background)
            background.runAll()
            val before = layer.operator.state.value
            assertNotEquals(ScreenServiceState.CHECKING, before)
            assertNotEquals(ScreenServiceState.STARTING, before)

            // The owner switches the service on; then the layer's scope gets to run what it has queued (its watch of the service link).
            val me = ScreenOperatorLayer.serviceComponent(context)
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, me.flattenToString())
            own.runAll()
            assertEquals(before, layer.operator.state.value, "the layer's own scope read Android's settings")
            background.runAll()
            own.runAll()
            assertEquals(ScreenServiceState.STARTING, layer.operator.state.value, "the read it asked for ran on the background dispatcher")
        } finally {
            scope.cancel()
            own.runAll()
            background.runAll()
        }
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

        /** Many times the rounds the old wait survived: it failed within the first few hundred. */
        const val WAIT_ROUNDS = 10_000
    }
}
