package com.aicontrolcenter.app.scripts

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicontrolcenter.framework.operator.JsHost
import com.aicontrolcenter.framework.operator.JsLimits
import com.aicontrolcenter.framework.operator.JsOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 6 (WP6, D1-D3, rules 11 and 31): scripts in their own isolated process, on a device (spike Q1-Q10 and its
 * design changes). Method names are camelCase: names with spaces cannot be dexed below API 30.
 *
 * Bounds for the software emulator (spike Q5, Q9): starting a process takes up to 8.7 s and getting Rhino ready up to
 * 29 s there, so the engine gets 60 s to bind and 90 s to get ready; the scripts' own limits are the tool's.
 *
 * TEST SAFETY: no script here is endless. The "stuck" script walks an iterator whose `next` is a built-in, a loop the
 * sandbox cannot reach (docs/LIMITATIONS.md), over a bounded array: it ends by itself after far longer than the
 * script's limit and grace on the emulator, and is run only after a first run proved that scripts run in the isolated
 * process (never in this one). The memory hogs are bounded loops that run out of memory first.
 *
 * Each test starts with no script process (device gate of wave 2, finding 1): engines in one app process share the
 * `js0`/`js1` processes, so an engine a test left bound kept the next tests in the first test's process. After each
 * test its engines let go of their processes, and the test waits until each has ended.
 */
@RunWith(AndroidJUnit4::class)
class ScriptProcessDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val limits = JsLimits(bindTimeoutMs = 60_000, engineStartMs = 90_000)
    private val appPid = Process.myPid()
    private val engines = mutableListOf<IsolatedJsEngine>()

    /** A new engine for this test; its processes are let go of after the test ([letGoOfTheScriptProcesses]). */
    private fun engine(): IsolatedJsEngine = IsolatedJsEngine(context, limits).also { engines += it }

    @After
    fun letGoOfTheScriptProcesses() {
        val binders = engines.flatMap { it.letGoOfAll() }
        engines.clear()
        val ended = CountDownLatch(binders.size)
        for (binder in binders) {
            try {
                binder.linkToDeath({ ended.countDown() }, 0)
            } catch (e: RemoteException) {
                ended.countDown() // already ended
            }
        }
        val start = SystemClock.elapsedRealtime()
        val allEnded = ended.await(PROCESS_END_WAIT_S, TimeUnit.SECONDS)
        log("after the test: ${binders.size} script process binding(s) let go of; ended: $allEnded, in ${SystemClock.elapsedRealtime() - start} ms")
        assertTrue(allEnded, "a script process did not end within $PROCESS_END_WAIT_S s of being let go of, so the next test would not start cold")
    }

    private fun log(text: String) {
        Log.i(TAG, text)
    }

    private fun timed(name: String, block: () -> JsOutcome): JsOutcome {
        val start = SystemClock.elapsedRealtime()
        val outcome = block()
        log("$name: ${SystemClock.elapsedRealtime() - start} ms -> $outcome")
        return outcome
    }

    private fun IsolatedJsEngine.runBlockingJs(code: String, timeoutMs: Long, host: JsHost? = null, input: String? = null): JsOutcome =
        runBlocking { run(code, input, host, timeoutMs) }

    /** A first run, in a fresh process; fails the test at once if the script ran in this process instead (fallback). */
    private fun ready(engine: IsolatedJsEngine) {
        val outcome = timed("cold bind and first run of 1 + 1") { engine.runBlockingJs("1 + 1", 60_000) }
        assertEquals("2", assertIs<JsOutcome.Finished>(outcome).resultJson)
        assertNull(outcome.note, "the script ran in the app's own process: ${outcome.note}")
        assertEquals(appPid, Process.myPid())
        val ids = engine.processIds()
        assertTrue(ids.isNotEmpty() && appPid !in ids, "script processes $ids, app $appPid")
    }

    @Test
    fun onePlusOneRunsInAnIsolatedProcessAndAWarmRunKeepsTheDefaultLimit() {
        val engine = engine()
        ready(engine)
        // D2: a warm run's setup (3.7-5.2 s on the emulator) is not taken from the script's 5 s.
        val warm = timed("warm 1 + 1 with the default 5 s") { engine.runBlockingJs("1 + 1", JsLimits().timeoutMs) }
        assertEquals("2", assertIs<JsOutcome.Finished>(warm).resultJson)
        val regex = timed("regular expressions") { engine.runBlockingJs("[/a+/.test('caa'), 'b c'.replace(/\\s/g, '-')]", 30_000) }
        assertEquals("[true,\"b-c\"]", assertIs<JsOutcome.Finished>(regex).resultJson)
        assertEquals(appPid, Process.myPid())
    }

    @Test
    fun stuckScriptThreeTimesThenOnePlusOneWithinTheRestartBackOff() {
        val engine = engine()
        ready(engine)
        // `new Set(o)` walks an iterator whose `next` is the built-in `pop`, bound to an array: no function of the script is
        // entered, so the sandbox's budget and clock never reach the loop (docs/LIMITATIONS.md). It is bounded (one million
        // steps, seconds on the emulator, so far past the 1 s limit and the grace) and ends by itself if nothing ended it.
        val stuck = "var r = {done: false, value: 1}; var a = new Array(1000000).fill(r); a[0] = {done: true}; " +
            "var o = {}; o[Symbol.iterator] = function () { return { next: a.pop.bind(a) }; }; new Set(o).size"
        val timeoutMs = 1_000L
        repeat(3) { n ->
            val start = SystemClock.elapsedRealtime()
            val outcome = timed("stuck script, run ${n + 1}") { engine.runBlockingJs(stuck, timeoutMs) }
            val took = SystemClock.elapsedRealtime() - start
            assertIs<JsOutcome.Stopped>(outcome)
            assertTrue(took < 60_000 + timeoutMs + IsolatedJsEngine.PAST_BOUND_MS, "answered after $took ms")
            assertEquals(appPid, Process.myPid(), "the app's process stays")
        }
        // Unbound at once and bound afresh: no restart back-off (1 s, 4 s, 16 s; spike Q6).
        val after = timed("1 + 1 after three stuck scripts") { engine.runBlockingJs("1 + 1", 60_000) }
        assertEquals("2", assertIs<JsOutcome.Finished>(after).resultJson)
        assertNull(after.note)
        assertTrue(appPid !in engine.processIds())
    }

    @Test
    fun aFourMegabyteReadAndALargeRoundTrip() {
        val engine = engine()
        ready(engine)
        val big = "é".repeat(4 * 1024 * 1024)
        val host = object : JsHost {
            override val canWrite = false

            override fun read(path: String): String = big

            override fun list(path: String): List<Triple<String, Boolean, Long?>> = emptyList()

            override fun write(path: String, text: String): String = path
        }
        val read = timed("a 4 MB read") { engine.runBlockingJs("workspace.read('big.txt').length", 60_000, host) }
        assertEquals("${big.length}", assertIs<JsOutcome.Finished>(read).resultJson)
        // Spike Q7: 200,000 characters in, 100,000 out (slow on the emulator: 60 s).
        val input = "\"" + "i".repeat(199_990) + "\""
        val round = timed("200,000 in, 100,000 out") { engine.runBlockingJs("input.length + ':' + 'r'.repeat(99_990)", 60_000, input = input) }
        val result = assertIs<JsOutcome.Finished>(round)
        assertFalse(result.resultTruncated)
        assertEquals(99_999, result.resultJson!!.length)
    }

    @Test
    fun aScriptMemoryHogEndsOnlyTheScriptAndAddsNoExitOfTheApp() {
        val engine = engine()
        ready(engine)
        val exitsBefore = mainProcessExits()
        val hog = timed("a script that fills the memory") {
            engine.runBlockingJs("var a = []; for (var i = 0; i < 400; i++) a.push('x'.repeat(1000000) + i); a.length", 60_000)
        }
        assertTrue(hog is JsOutcome.Stopped, "$hog")
        assertEquals(appPid, Process.myPid())
        assertEquals("2", assertIs<JsOutcome.Finished>(engine.runBlockingJs("1 + 1", 60_000)).resultJson)
        assertEquals(exitsBefore, mainProcessExits(), "no exit of the app's own process was added")
    }

    @Test
    fun theScriptProcessIsIsolatedAndCannotReachTheAppsData() {
        withProbe("probe0") { probe ->
            assertTrue(probe.isolated())
            assertTrue(probe.pid() != appPid)
            assertTrue(probe.uid() != Process.myUid(), "uid ${probe.uid()}")
            assertTrue(probe.processName().startsWith(context.packageName + ":scripts"), probe.processName())
            val data = context.dataDir
            val secret = java.io.File(context.filesDir, "wp6-probe.txt").apply { writeText("main-secret") }
            try {
                val tried = probe.tryAppData(data.path, secret.path)
                log("app data from the script process: $tried")
                assertTrue("list failed" in tried || "listed: null" in tried, tried)
                assertTrue("read failed" in tried, tried)
            } finally {
                secret.delete()
            }
            val network = probe.tryNetwork("10.0.2.2", 5037)
            log("network from the script process: $network")
            assertTrue(network.startsWith("failed"), network)
        }
    }

    @Test
    fun anOutOfMemoryErrorEndsTheScriptProcessQuietly() {
        assumeTrue("exit reasons need Android 11", Build.VERSION.SDK_INT >= 30)
        val exitsBefore = mainProcessExits()
        val died = CountDownLatch(1)
        withProbe("probe1", onDeath = { died.countDown() }) { probe ->
            val pid = probe.pid()
            probe.exhaustMemory()
            assertTrue(died.await(60, TimeUnit.SECONDS), "the probe process $pid did not end")
        }
        assertEquals(appPid, Process.myPid())
        assertEquals(exitsBefore, mainProcessExits(), "no exit of the app's own process was added")
    }

    /** Exit records of the app's own process (rule 31: script processes never count); empty before Android 11. */
    private fun mainProcessExits(): List<Long> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val manager = context.getSystemService(ActivityManager::class.java)
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.processName == context.applicationInfo.processName }
            .map { it.timestamp }
    }

    private fun withProbe(instance: String, onDeath: () -> Unit = {}, block: (IScriptProbe) -> Unit) {
        val bound = AtomicReference<IBinder?>()
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                bound.set(service)
                service?.linkToDeath({ onDeath() }, 0)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = onDeath()
        }
        val intent = Intent().setClassName(context, "com.aicontrolcenter.app.scripts.ScriptProbeService")
        val ok = if (Build.VERSION.SDK_INT >= 29) {
            context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, instance, Executors.newSingleThreadExecutor(), connection)
        } else {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        assertTrue(ok, "the probe could not be bound")
        try {
            assertTrue(connected.await(60, TimeUnit.SECONDS), "the probe did not start")
            block(IScriptProbe.Stub.asInterface(assertNotNull(bound.get())))
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private companion object {
        const val TAG = "ScriptProcessDeviceTest"

        /** Android ends an isolated process nothing holds within a second (spike Q2); the software emulator is slower. */
        const val PROCESS_END_WAIT_S = 30L
    }
}
