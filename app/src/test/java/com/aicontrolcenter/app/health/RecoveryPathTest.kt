package com.aicontrolcenter.app.health

import android.app.Activity
import android.content.Intent
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.MainActivity
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.RecoveryResult
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeOwnerRecovery
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gate, Start over's restart, the crash notes and the scope's handler (Phase 6, R2, R5, G7; rules 9 and 30), and the
 * rule that the Recovery path never names the app's graph.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecoveryPathTest {
    @get:Rule val folder = TemporaryFolder()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T> eventually(read: () -> T, done: (T) -> Boolean): T {
        repeat(200) {
            idle()
            val value = read()
            if (done(value)) return value
            Thread.sleep(10)
        }
        return read()
    }

    @Test
    fun `the gate queues what needs the data until the data opened, then runs it in order on the main thread`() {
        val opened = CompletableDeferred<Unit>()
        val recovery = object : com.aicontrolcenter.core.health.OwnerRecovery {
            override suspend fun state(): DatabaseState {
                opened.await()
                return DatabaseState.OK
            }

            override suspend fun startOver(): RecoveryResult = RecoveryResult.Refused("no")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val gate = AppGate(recovery, scope)
            val ran = mutableListOf<String>()
            assertEquals(AppGate.State.Checking, gate.state.value)
            gate.whenOpen { ran += "start:${Looper.myLooper() == Looper.getMainLooper()}" }
            gate.whenOpen { ran += "objective" }
            idle()
            assertEquals(emptyList(), ran, "nothing touches the data before it opened")
            opened.complete(Unit)
            eventually({ gate.state.value }) { it == AppGate.State.Open }
            assertEquals(listOf("start:true", "objective"), ran)
            gate.whenOpen { ran += "later" }
            assertEquals("later", ran.last(), "once open, at once")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `data that does not open shows the Recovery screen and drops the queued work, and Try again looks again`() {
        val recovery = FakeOwnerRecovery(databaseState = DatabaseState.KEY_DAMAGED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val gate = AppGate(recovery, scope)
            var ran = false
            gate.whenOpen { ran = true }
            assertEquals(AppGate.State.NeedsRecovery(DatabaseState.KEY_DAMAGED), eventually({ gate.state.value }) { it is AppGate.State.NeedsRecovery })
            gate.whenOpen { ran = true }
            recovery.databaseState = DatabaseState.OK
            gate.recheck()
            eventually({ gate.state.value }) { it == AppGate.State.Open }
            assertFalse(ran, "work queued before the failure never runs")
        } finally {
            scope.cancel()
        }
        assertEquals(AppGate.State.Open, AppGate.open().state.value)
        assertEquals(AppGate.State.Open, AppGate(null, null).state.value)
    }

    @Test
    fun `a check that throws is a database that does not open`() {
        val recovery = object : com.aicontrolcenter.core.health.OwnerRecovery {
            override suspend fun state(): DatabaseState = throw IllegalStateException("broken")
            override suspend fun startOver(): RecoveryResult = RecoveryResult.Refused("no")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val gate = AppGate(recovery, scope)
            assertEquals(AppGate.State.NeedsRecovery(DatabaseState.OTHER), eventually({ gate.state.value }) { it is AppGate.State.NeedsRecovery })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Start over ends with a restart into the main activity as a new task, with no extra (rule 30)`() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var ended = 0
        val refused = FakeOwnerRecovery(result = RecoveryResult.Refused("Your data opens normally, so nothing was changed."))
        assertIs<RecoveryResult.Refused>(RecoveryActions.startOverAndRestart(activity, refused) { ended++ })
        assertEquals(0, ended)
        assertNull(shadowOf(activity).nextStartedActivity)

        val recovery = FakeOwnerRecovery(databaseState = DatabaseState.KEY_MISSING)
        assertIs<RecoveryResult.Done>(RecoveryActions.startOverAndRestart(activity, recovery) { ended++ })
        assertEquals(1, ended)
        val restart = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, restart.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK, restart.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNull(restart.extras, "no extra opens anything (rule 23)")
        assertNull(restart.action)
    }

    @Test
    fun `a crash note holds the exception's class and the app's own frames, never its message, and the old handler still runs`() {
        val notes = CrashNotes.inFolder(folder.root)
        var handedOn: Throwable? = null
        val handler = notes.handler(previous = { _, failure -> handedOn = failure }, now = { 42 })
        val failure = RuntimeException("sk-ant-secret and the owner's words", IllegalArgumentException("more secret words"))
        failure.stackTrace = arrayOf(
            StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 1),
            StackTraceElement("com.aicontrolcenter.framework.orchestration.DefaultOrchestrator", "resume", "DefaultOrchestrator.kt", 217),
        )
        handler.uncaughtException(Thread.currentThread(), failure)
        assertEquals(failure, handedOn)
        val note = notes.read().single()
        assertEquals(42, note.at)
        assertEquals("java.lang.RuntimeException", note.exceptionClass)
        assertEquals(listOf("java.lang.IllegalArgumentException"), note.causes)
        assertEquals("com.aicontrolcenter.framework.orchestration.DefaultOrchestrator.resume:217", note.frames.first())
        val stored = File(folder.root, CrashNotes.PATH).readText()
        assertFalse("secret" in stored || "owner" in stored || "ArrayList" in stored, stored)
        repeat(7) { notes.write(IllegalStateException("x"), 100L + it) }
        assertEquals(CrashNotes.MAX_NOTES, notes.read().size)
        assertEquals(listOf(105L, 106L), notes.since(104).map { it.at })
        assertTrue(notes.read().all { it.frames.size <= CrashNotes.MAX_FRAMES })
    }

    @Test
    fun `the scope's handler logs an exception without its message and keeps the process, and passes an error on`() = runBlocking {
        val log = InMemoryActivityLog()
        val recorder = ActivityRecorder(log, SequentialIdGenerator(), FakeClock())
        val handler = AppScopeExceptionHandler({ recorder }, logScope = this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
        try {
            val survivor = scope.launch { delay(200) }
            scope.launch { throw IllegalStateException("secret-marker from an agent") }.join()
            withTimeout(5_000) { while (log.all.isEmpty()) delay(10) }
            val event = log.all.single()
            assertEquals(AppScopeExceptionHandler.TITLE, event.title)
            assertEquals(ActivityType.ERROR, event.type)
            assertTrue("java.lang.IllegalStateException" in event.detail)
            assertFalse("secret-marker" in event.detail)
            assertTrue(survivor.isActive, "the other work of the scope goes on")
            assertFailsWith<OutOfMemoryError> { handler.handleException(scope.coroutineContext, OutOfMemoryError("too big")) }
            // Before the data opened there is nowhere to log: nothing happens.
            AppScopeExceptionHandler({ null }).handleException(scope.coroutineContext, IllegalStateException("early"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the Recovery path never names the app's graph (rule 30)`() {
        val sources = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val app = File(sources, "com/aicontrolcenter/app")
        val files: List<File> = (File(app, "health").listFiles().orEmpty().toList() + File(app, "ui/recovery").listFiles().orEmpty().toList())
            .filter { it.name.endsWith(".kt") }
        assertTrue(files.any { it.name == "AppGate.kt" } && files.any { it.name == "RecoveryScreen.kt" } && files.any { it.name == "RecoveryActions.kt" })
        val forbidden = listOf(Regex("\\bgraph\\b"), Regex("\\bAppGraph\\b"), Regex("\\bgraphIfBuilt\\b"), Regex("\\bLocalAppGraph\\b"))
        val found = files.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("/**") }
                .flatMap { (n, line) -> forbidden.filter { it.containsMatchIn(line) }.map { "${file.name}:${n + 1}: $line" } }
        }
        assertEquals(emptyList(), found)
    }
}
