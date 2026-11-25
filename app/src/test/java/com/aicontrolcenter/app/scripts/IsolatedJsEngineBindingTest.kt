package com.aicontrolcenter.app.scripts

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.framework.operator.JsHost
import com.aicontrolcenter.framework.operator.JsOutcome
import com.aicontrolcenter.framework.operator.JsSandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 6 (WP6): the isolated engine over Android's binding of [ScriptProcessService], as Robolectric binds it
 * (`ShadowApplication.setComponentNameAndServiceForBindService`; the script process is the real runner in this
 * process). Robolectric has no isolated binding, so the Android 10+ call is passed in and binds with the same intent,
 * flag and executor; the instance names it was asked for are recorded.
 *
 * TEST SAFETY: as [IsolatedJsEngineTest]; a run held past its time is let go at the end of its test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class IsolatedJsEngineBindingTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val service = ComponentName(app, ScriptProcessService::class.java)
    private val pipes = TestPipes()
    private val ends = AtomicInteger()
    private val instances = CopyOnWriteArrayList<String>()
    private val release = CountDownLatch(1)
    private val runner = ScriptRunner(pipes, endProcess = { ends.incrementAndGet() }, pid = { 4_321 })

    @Before
    fun bindToTheRunner() = shadowOf(app).setComponentNameAndServiceForBindService(service, runner)

    @After
    fun letGo() = release.countDown()

    private fun launcher(sdkInt: Int = 35) = AndroidScriptLauncher(app, sdkInt) { context, intent, instance, executor, connection ->
        instances += instance
        context.bindService(intent, Context.BIND_AUTO_CREATE, executor, connection)
    }

    private fun engine(launcher: ScriptProcessLauncher = launcher()) = IsolatedJsEngine(jsLimits(), launcher, pipes, JsSandbox(jsLimits()), sharedProcess = false)

    private fun <T> blocking(block: suspend () -> T): T = runBlocking(Dispatchers.Default) { block() }

    private fun bound() = shadowOf(app).boundServiceConnections.size

    private fun unbound() = shadowOf(app).unboundServiceConnections.size

    private inner class DeafHost : JsHost {
        override val canWrite = false

        override fun read(path: String): String {
            val until = System.nanoTime() + 20_000_000_000L
            while (release.count > 0 && System.nanoTime() < until) {
                try {
                    release.await(50, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    // deaf
                }
            }
            return "x"
        }

        override fun list(path: String): List<Triple<String, Boolean, Long?>> = emptyList()

        override fun write(path: String, text: String): String = path
    }

    @Test(timeout = 60_000)
    fun `a script runs in the bound service, as an isolated instance named js0`() = blocking {
        val engine = engine()
        assertEquals("2", assertIs<JsOutcome.Finished>(engine.run("1 + 1", null, null, 5_000)).resultJson)
        assertEquals(listOf("js0"), instances.toList())
        assertEquals(1, bound())
        assertEquals(listOf(4_321), engine.processIds())
    }

    @Test(timeout = 60_000)
    fun `an overdue run unbinds its process, and the next run binds a fresh one`() = blocking {
        val engine = engine()
        assertIs<JsOutcome.Stopped>(engine.run("workspace.read('a')", null, DeafHost(), 300))
        assertEquals(1, unbound(), "let go of at once")
        assertEquals("2", assertIs<JsOutcome.Finished>(engine.run("1 + 1", null, null, 5_000)).resultJson)
        assertEquals(listOf("js0", "js0"), instances.toList())
        assertEquals(1, bound(), "one binding held: the fresh one")
    }

    @Test(timeout = 60_000)
    fun `a death mid-run is answered in plain words, and the next run binds afresh`() = blocking {
        val engine = engine()
        engine.run("1", null, null, 5_000)
        val outcome = coroutineScope {
            val running = async { engine.run("var t = Date.now(); while (Date.now() - t < 2000) {} 1", null, null, 5_000) }
            delay(500)
            // Android tells the connection the process went.
            shadowOf(app).boundServiceConnections.toList().forEach { it.onServiceDisconnected(service) }
            running.await()
        }
        assertEquals(IsolatedJsEngine.PROCESS_ENDED, assertIs<JsOutcome.Stopped>(outcome).reason)
        assertEquals(1, unbound())
        assertEquals("2", assertIs<JsOutcome.Finished>(engine.run("1 + 1", null, null, 5_000)).resultJson)
        assertEquals(listOf("js0", "js0"), instances.toList())
    }

    @Test(timeout = 60_000)
    fun `letting go of all processes unbinds every binding the engine holds, and the next run binds afresh`() = blocking {
        // For the device test (its finding 1): each test lets go of its engine, so the next one starts a cold process.
        val engine = engine()
        coroutineScope { List(2) { async { engine.run("var t = Date.now(); while (Date.now() - t < 1000) {} 5", null, null, 5_000) } }.awaitAll() }
        assertEquals(setOf("js0", "js1"), instances.toSet())
        assertEquals(2, bound(), "both processes are kept warm after their runs")
        val binders = engine.letGoOfAll()
        assertEquals(2, binders.size, "the binders of both processes, to wait for their end")
        assertTrue(binders.all { it === runner.asBinder() })
        assertEquals(0, bound(), "nothing is held any more")
        assertEquals(2, unbound())
        assertEquals(emptyList(), engine.processIds())
        assertEquals(emptyList(), engine.letGoOfAll(), "nothing is let go of twice")
        assertEquals(2, unbound())
        assertEquals("2", assertIs<JsOutcome.Finished>(engine.run("1 + 1", null, null, 5_000)).resultJson)
        assertEquals(1, bound(), "a fresh binding")
        assertEquals(3, instances.size)
    }

    @Test(timeout = 60_000)
    fun `the third of three runs at once waits for a free slot`() = blocking {
        val engine = engine()
        val start = System.nanoTime()
        val outcomes = coroutineScope { List(3) { async { engine.run("var t = Date.now(); while (Date.now() - t < 1000) {} 5", null, null, 5_000) } }.awaitAll() }
        val took = (System.nanoTime() - start) / 1_000_000
        outcomes.forEach { assertEquals("5", assertIs<JsOutcome.Finished>(it).resultJson) }
        assertTrue(took >= 1_900, "the third did not wait: $took ms")
        assertEquals(setOf("js0", "js1"), instances.toSet())
    }

    @Test(timeout = 60_000)
    fun `a workspace call after the run is refused`() = blocking {
        val hosts = CopyOnWriteArrayList<IScriptHost>()
        shadowOf(app).setComponentNameAndServiceForBindService(
            service,
            object : IScriptRunner.Stub() {
                override fun run(request: android.os.ParcelFileDescriptor?, host: IScriptHost?, result: IScriptResult?) {
                    host?.let { hosts += it }
                    runner.run(request, host, result)
                }

                override fun pid(): Int = runner.pid()
            },
        )
        val workspace = object : JsHost {
            val calls = AtomicInteger()
            override val canWrite = true

            override fun read(path: String): String = "r".also { calls.incrementAndGet() }

            override fun list(path: String): List<Triple<String, Boolean, Long?>> = emptyList<Triple<String, Boolean, Long?>>().also { calls.incrementAndGet() }

            override fun write(path: String, text: String): String = path.also { calls.incrementAndGet() }
        }
        engine().run("workspace.list('')", null, workspace, 5_000)
        assertEquals("!${RunHost.OVER}", hosts.single().list(""))
        assertEquals(1, workspace.calls.get())
    }

    @Test(timeout = 60_000)
    fun `on Android 9 the service is bound with bindService, and a refused bind falls back to the app's sandbox`() {
        val old = AndroidScriptLauncher(app, sdkInt = 28) { _, _, _, _, _ -> error("not on Android 9") }
        val events = CopyOnWriteArrayList<String>()
        val binding = old.bind(
            "js0",
            object : ScriptProcessLauncher.Events {
                override fun connected(binder: android.os.IBinder) {
                    events += "connected"
                }

                override fun ended() {
                    events += "ended"
                }
            },
        )
        assertTrue(binding != null)
        // Android 9's callbacks come on the main thread, which is this test's.
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf("connected"), events.toList())
        old.unbind(binding!!)
        assertEquals(1, unbound())
        assertEquals(emptyList(), instances.toList(), "no instance names before Android 10")

        shadowOf(app).declareComponentUnbindable(service)
        val refused = blocking { engine(launcher(sdkInt = 28)).run("1 + 1", null, null, 5_000) }
        assertEquals("2", assertIs<JsOutcome.Finished>(refused).resultJson)
        assertEquals(IsolatedJsEngine.FALLBACK_NOTE, refused.note)
    }
}
