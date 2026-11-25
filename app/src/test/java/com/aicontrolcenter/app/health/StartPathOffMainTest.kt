package com.aicontrolcenter.app.health

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.DebugStrictMode
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.testkit.FakeOwnerRecovery
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D45 (wave 3, WP8): what the main thread no longer does at a cold start. The gate builds the graph off the main thread
 * once the data opened, before it opens (so the main thread finds it built); work queued for an activity that is gone by
 * then never runs; the crash notes' and start ledger's folders are asked for only when first used, never when installed;
 * and StrictMode reports main-thread disk work in debug builds only, never under Robolectric or in a release build.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StartPathOffMainTest {
    @get:Rule val folder = TemporaryFolder()

    private class Owner(state: Lifecycle.State) : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = state }
    }

    /** A gate over [recovery] whose check runs on a scope named "check" and whose queued work runs on "main". */
    private fun TestScope.gate(recovery: FakeOwnerRecovery, prepare: suspend () -> Unit): AppGate {
        val main = StandardTestDispatcher(testScheduler)
        val check = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineName("check"))
        return AppGate(recovery, check, main = main, prepare = prepare)
    }

    @Test
    fun `the graph is prepared on the check's scope before the gate opens, and queued work then finds it`() = runTest {
        val order = ArrayList<String>()
        var preparedOn: String? = null
        val gate = gate(FakeOwnerRecovery(DatabaseState.OK)) {
            preparedOn = currentCoroutineContext()[CoroutineName]?.name
            order += "prepared"
        }
        gate.whenOpen { order += "queued work" }
        testScheduler.advanceUntilIdle()
        assertEquals(AppGate.State.Open, gate.state.value)
        assertEquals(listOf("prepared", "queued work"), order, "the main thread's work comes after the graph is built")
        assertEquals("check", preparedOn, "built off the main thread, on the check's scope")
    }

    @Test
    fun `nothing is prepared when the data does not open, and a failed preparation still opens the gate`() = runTest {
        var prepared = 0
        val closed = gate(FakeOwnerRecovery(DatabaseState.KEY_DAMAGED)) { prepared++ }
        testScheduler.advanceUntilIdle()
        assertEquals(AppGate.State.NeedsRecovery(DatabaseState.KEY_DAMAGED), closed.state.value)
        assertEquals(0, prepared, "no graph for data that does not open (rule 9)")
        val failing = gate(FakeOwnerRecovery(DatabaseState.OK)) { error("a service failed to build") }
        var ran = false
        failing.whenOpen { ran = true }
        testScheduler.advanceUntilIdle()
        assertEquals(AppGate.State.Open, failing.state.value, "the graph is then built on first use, as before")
        assertTrue(ran)
    }

    @Test
    fun `work queued for an activity that is gone by the time the data opened never runs`() = runTest {
        val gate = gate(FakeOwnerRecovery(DatabaseState.OK)) {}
        val gone = Owner(Lifecycle.State.CREATED)
        val shown = Owner(Lifecycle.State.CREATED)
        val ran = ArrayList<String>()
        gate.whenOpen(gone) { ran += "gone" }
        gate.whenOpen(shown) { ran += "shown" }
        gone.lifecycle.currentState = Lifecycle.State.DESTROYED
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("shown"), ran)
        // Once open, work for a live activity runs at once.
        gate.whenOpen(shown) { ran += "again" }
        assertEquals(listOf("shown", "again"), ran)
    }

    @Test
    fun `the crash notes and the start ledger ask for their folder only when first used`() {
        var asked = 0
        val notes = CrashNotes.inFolder { asked++; folder.root }
        val ledger = StartLedger.inFolder { asked++; folder.root }
        assertEquals(0, asked, "making them touches no folder")
        assertEquals(emptyList(), notes.read())
        assertEquals(StartLedger.Entry.EMPTY, ledger.read())
        assertEquals(2, asked, "each asks once, at first use")
        notes.write(IllegalStateException("never written down"), at = 5)
        assertEquals(listOf("java.lang.IllegalStateException"), notes.read().map { it.exceptionClass })
        assertTrue(File(folder.root, CrashNotes.PATH).isFile)
        assertEquals(2, asked, "and never again")
    }

    @Test
    fun `StrictMode logs main-thread work in debug builds on a phone only`() {
        assertTrue(DebugStrictMode.shouldInstall(debug = true, fingerprint = "motorola/razr/razr:16/…"))
        assertFalse(DebugStrictMode.shouldInstall(debug = false, fingerprint = "motorola/razr/razr:16/…"), "never in a release build")
        assertFalse(DebugStrictMode.shouldInstall(debug = true, fingerprint = "robolectric"), "never under Robolectric")
        // Both policies build on this API level and on the oldest the app runs on.
        DebugStrictMode.threadPolicy()
        DebugStrictMode.vmPolicy(sdkInt = 35)
        DebugStrictMode.vmPolicy(sdkInt = 28)
    }
}
