package com.aicontrolcenter.device.operator.commands

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.ExactText
import com.aicontrolcenter.core.operator.TermuxAvailability
import com.aicontrolcenter.device.testing.FakeDevice
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TermuxCommandRunnerTest {
    private val device = FakeDevice()
    private val app = device.application
    private val runner = TermuxCommandRunner(app)
    private val limits = CommandLimits(timeoutMs = 30_000, maxOutputChars = 100)

    private fun ready() {
        device.install(TermuxCommandRunner.PACKAGE)
        shadowOf(app).grantPermissions(TermuxCommandRunner.PERMISSION)
    }

    private fun result(build: Bundle.() -> Unit) = Intent().putExtra(TermuxCommandRunner.RESULT_BUNDLE, Bundle().apply(build))

    /** Starts [command], returns the intent Termux received and the running call. */
    private fun TestScope.started(command: String, limits: CommandLimits = this@TermuxCommandRunnerTest.limits) =
        async { runner.run(command, limits) }.let { call ->
            runCurrent()
            assertNotNull(shadowOf(app).nextStartedService, "Termux's service must have been started") to call
        }

    private fun TestScope.answer(request: Intent, answer: Intent) {
        IntentCompat.getParcelableExtra(request, TermuxCommandRunner.EXTRA_PENDING_INTENT, PendingIntent::class.java)!!.send(app, 0, answer)
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    @Test
    fun `availability says what is missing`() = runTest {
        assertEquals(TermuxAvailability.NOT_INSTALLED, runner.availability())
        device.install(TermuxCommandRunner.PACKAGE)
        assertEquals(TermuxAvailability.PERMISSION_MISSING, runner.availability())
        shadowOf(app).grantPermissions(TermuxCommandRunner.PERMISSION)
        assertEquals(TermuxAvailability.READY, runner.availability())
    }

    @Test
    fun `nothing is sent when Termux is missing or not permitted`() = runTest {
        val missing = assertIs<CommandOutcome.Unavailable>(runner.run("ls", limits))
        assertEquals(DeviceAccess.TERMUX_RUN_COMMAND, missing.access)
        assertTrue("not installed" in missing.reason)

        device.install(TermuxCommandRunner.PACKAGE)
        val denied = assertIs<CommandOutcome.Unavailable>(runner.run("ls", limits))
        assertEquals(DeviceAccess.TERMUX_RUN_COMMAND, denied.access)
        assertTrue("Run commands in Termux environment" in denied.reason)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `the command goes to bash on standard input, unsplit, in the background`() = runTest {
        ready()
        val command = "cd ~/project && git log --oneline | head -n 3; echo \"it's \$HOME\"\nexit 0"

        val (request, call) = started(command)

        assertEquals(ComponentName(TermuxCommandRunner.PACKAGE, TermuxCommandRunner.SERVICE), request.component)
        assertEquals("com.termux.RUN_COMMAND", request.action)
        assertEquals("/data/data/com.termux/files/usr/bin/bash", request.getStringExtra(TermuxCommandRunner.EXTRA_PATH))
        assertEquals(listOf("-s"), request.getStringArrayExtra(TermuxCommandRunner.EXTRA_ARGUMENTS)!!.toList())
        assertEquals(command, request.getStringExtra(TermuxCommandRunner.EXTRA_STDIN))
        assertEquals("/data/data/com.termux/files/home", request.getStringExtra(TermuxCommandRunner.EXTRA_WORKDIR))
        assertTrue(request.getBooleanExtra(TermuxCommandRunner.EXTRA_BACKGROUND, false))
        val pending = IntentCompat.getParcelableExtra(request, TermuxCommandRunner.EXTRA_PENDING_INTENT, PendingIntent::class.java)!!
        assertTrue(shadowOf(pending).isBroadcast)
        assertEquals(app.packageName, shadowOf(pending).savedIntent.`package`, "the result PendingIntent is explicit")
        call.cancel()
    }

    @Test
    fun `Termux's result is returned with exit code and both streams`() = runTest {
        ready()
        val (request, call) = started("echo hi; echo oops 1>&2; exit 4")

        answer(request, result {
            putString("stdout", "hi\n")
            putString("stderr", "oops\n")
            putInt("exitCode", 4)
            putInt("err", Activity.RESULT_OK)
        })

        val done = assertIs<CommandOutcome.Finished>(call.await())
        assertEquals(CommandOutcome.Finished(4, "hi\n", "oops\n", truncated = false, durationMs = done.durationMs), done)
    }

    @Test
    fun `output is cut to the limit, and output Termux already cut is reported`() = runTest {
        ready()
        val (first, call) = started("yes | head -n 1000")
        answer(first, result {
            putString("stdout", "y\n".repeat(500))
            putInt("exitCode", 0)
        })
        val cut = assertIs<CommandOutcome.Finished>(call.await())
        assertEquals(100, cut.stdout.length)
        assertTrue(cut.truncated)

        val (second, again) = started("big")
        answer(second, result {
            putString("stdout", "tail")
            putString("stdout_original_length", "250000")
            putInt("exitCode", 0)
        })
        assertTrue(assertIs<CommandOutcome.Finished>(again.await()).truncated)
    }

    @Test
    fun `Termux not configured for other apps is reported with what to change`() = runTest {
        ready()
        val (request, call) = started("ls")

        answer(request, result {
            putInt("err", 2)
            putString("errmsg", "RUN_COMMAND requires `allow-external-apps` property to be set to `true` in `~/.termux/termux.properties` file.")
        })

        val unavailable = assertIs<CommandOutcome.Unavailable>(call.await())
        assertEquals(DeviceAccess.TERMUX_RUN_COMMAND, unavailable.access)
        assertTrue("allow-external-apps=true" in unavailable.reason)
    }

    @Test
    fun `other Termux errors are failures with Termux's message on one line`() = runTest {
        ready()
        val (request, call) = started("ls")

        answer(request, result {
            putInt("err", 1)
            putString("errmsg", "Executable not found\nat path")
        })

        assertEquals(CommandOutcome.Failed("Termux could not run the command: Executable not found at path"), call.await())
    }

    @Test
    fun `no answer within the limit is a time-out while Termux may still be running it`() = runTest {
        ready()
        val (_, call) = started("sleep 999", CommandLimits(timeoutMs = 5_000, maxOutputChars = 100))

        advanceTimeBy(5_001)

        assertEquals(CommandOutcome.TimedOut("", "", (call.await() as CommandOutcome.TimedOut).durationMs), call.await())
        assertTrue(shadowOf(app).registeredReceivers.none { it.intentFilter.actionsIterator().asSequence().any { a -> ".operator.termux." in a } })
    }

    @Test
    fun `an empty command is refused before Termux is asked`() = runTest {
        ready()

        assertIs<CommandOutcome.Failed>(runner.run("  ", limits))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `a command that would not arrive in Termux as the same text is not sent`() = runTest {
        ready()

        for (broken in listOf("rm keep\uD800", "rm keep\uDC00 x", "echo \uDC00\uD83D", "ls\u0000 -la")) {
            val outcome = assertIs<CommandOutcome.Failed>(runner.run(broken, limits), "[$broken]")

            // The words of the one rule (ExactText), the character named by its code.
            assertTrue(ExactText.problem(broken)!! in outcome.reason && "U+" in outcome.reason && "was not run" in outcome.reason, outcome.reason)
            assertNull(shadowOf(app).nextStartedService, "Termux was not asked")
        }

        // A whole pair is an ordinary character.
        val (request, call) = started("echo '\uD83D\uDE00'")
        assertEquals("echo '\uD83D\uDE00'", request.getStringExtra(TermuxCommandRunner.EXTRA_STDIN))
        answer(request, result { putString("stdout", "ok"); putInt("exitCode", 0) })
        assertIs<CommandOutcome.Finished>(call.await())
    }
}
